package com.dsh.starpos.field

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 多包/多历元联合定位（无地图，纯数值解）。
 *
 * 观测：
 *  - 太阳/月亮/行星的**视高度**观测（图像+重力得到，模型按测站位置/时间算高度，含视差与折射）；
 *  - 星光/两体的**2D 位置观测**（已知协方差，按高斯约束并入）。
 *
 * 未知量：纬度、经度；可选 **钟差 δt（秒）** 与 **垂直偏差 δv（弧度）**。
 * 无 GNSS 时也照常解；是否给"误差/T 级"由调用方用真值单独校验，本类不伪造真值。
 */
object JointSolver {

    enum class Body { SUN, MOON, PLANET }

    data class AltObs(
        val utcMs: Long,
        val body: Body,
        val bodyName: String,          // 便于行星显示
        val apparentAltRad: Double,    // 观测视高度（含折射/视差）
        val sigmaRad: Double,
        val session: String,
    )

    data class FixObs(
        val latRad: Double,
        val lonRad: Double,
        val sigmaMajorM: Double,
        val sigmaMinorM: Double,
        val azimuthDeg: Double,
        val session: String,
    )

    data class Options(
        val estimateClock: Boolean = false,
        val estimateVertical: Boolean = false,
        /** 有真值时可给初值（仅初始化，不进入解算）。无真值时自动用几何种子。 */
        val initialLatRad: Double? = null,
        val initialLonRad: Double? = null,
        /** 系统误差底噪（弧度），加到每个高度观测的 sigma 上。 */
        val systemFloorRad: Double = Math.toRadians(0.02),
    )

    data class BiasEstimate(
        val verticalBiasRad: Double,
        val scatterRad: Double,
        val clockOffsetSec: Double,
        val count: Int,
        val perSession: Map<String, Double>,
    )

    /**
     * 用"有真值可信"的参考观测估计系统垂直偏差（弧度）：
     * 在真值位置上比较观测高度与模型高度，取鲁棒中位数；scatter=1.4826·MAD。
     * 注意：这是"参考包所在时段/方位/温度"的偏差，迁移到目标包需要用户确认同地点/同设备。
     */
    fun estimateVerticalBias(
        refObs: List<AltObs>,
        truthLatRad: Double,
        truthLonRad: Double,
        /** 只迁移同一天体（推荐）：null=混合天体（不同天体/高度段不宜混估）。 */
        bodyFilter: Body? = null,
    ): BiasEstimate? {
        val used = if (bodyFilter != null) refObs.filter { it.body == bodyFilter } else refObs
        if (used.isEmpty()) return null
        val res = ArrayList<Double>()
        val per = HashMap<String, MutableList<Double>>()
        for (o in used) {
            val model = modelAltRad(truthLatRad, truthLonRad, o.utcMs, o)
            val d = o.apparentAltRad - model
            if (abs(d) > Math.toRadians(5.0)) continue
            res.add(d)
            per.getOrPut(o.session) { ArrayList() }.add(d)
        }
        if (res.size < 3) return null
        res.sort()
        val med = res[res.size / 2]
        val mad = res.map { abs(it - med) }.sorted()[res.size / 2]
        val scatter = 1.4826 * mad
        // 钟差：residual ≈ -dAlt/dt · δt（按所有参考观测做 1D 加权最小二乘）
        var num = 0.0
        var den = 0.0
        for (o in used) {
            val dtSec = 60.0
            val a0 = modelAltRad(truthLatRad, truthLonRad, o.utcMs, o)
            val a1 = modelAltRad(truthLatRad, truthLonRad, o.utcMs + (dtSec * 1000).toLong(), o)
            val dAltDt = (a1 - a0) / dtSec // rad/s
            if (abs(dAltDt) < 1e-9) continue
            val r = o.apparentAltRad - a0
            // r ≈ -dAltDt * dt => -r/dAltDt
            val dt = -r / dAltDt
            val w = (dAltDt * dAltDt)
            num += w * dt; den += w
        }
        val clock = if (den > 0) (num / den).coerceIn(-600.0, 600.0) else 0.0
        return BiasEstimate(med, scatter, clock, res.size, per.mapValues { it.value.average() })
    }

    /** 把垂直偏差从观测高度里扣掉（迁移到目标包）。 */
    fun applyVerticalBias(obs: List<AltObs>, biasRad: Double): List<AltObs> =
        obs.map { it.copy(apparentAltRad = it.apparentAltRad - biasRad) }

    /** 用参考包估 δv（可选 δt），再对目标包做联合解算。 */
    fun solveWithBiasTransfer(
        refObs: List<AltObs>,
        targetObs: List<AltObs>,
        fixObs: List<FixObs> = emptyList(),
        options: Options = Options(),
        truthLatRad: Double? = null,
        truthLonRad: Double? = null,
        bodyFilter: Body? = null,
    ): Pair<BiasEstimate?, Result?> {
        val est = if (truthLatRad != null && truthLonRad != null) {
            estimateVerticalBias(refObs, truthLatRad, truthLonRad, bodyFilter)
        } else null
        val dv = est?.verticalBiasRad ?: 0.0
        val corrected = applyVerticalBias(targetObs, dv)
        val scatterFloor = est?.scatterRad?.coerceAtLeast(options.systemFloorRad) ?: options.systemFloorRad
        val opts = options.copy(systemFloorRad = sqrt(options.systemFloorRad * options.systemFloorRad + scatterFloor * scatterFloor))
        return est to solve(corrected, fixObs, opts, truthLatRad, truthLonRad)
    }

    data class Contribution(
        val session: String,
        val count: Int,
        val rmsRad: Double,
        val maxResidualRad: Double,
    )

    data class Result(
        val latDeg: Double,
        val lonDeg: Double,
        /** 1σ 椭圆半轴（米）与方位（度，自北顺时针）。 */
        val sigmaMajorM: Double,
        val sigmaMinorM: Double,
        val sigmaAzimuthDeg: Double,
        /** 95% 椭圆半轴（米）。 */
        val major95M: Double,
        val minor95M: Double,
        val area95Km2: Double,
        val residualRmsRad: Double,
        val dof: Int,
        /** 钟差估计（秒；未估计时为 0）。 */
        val clockOffsetSec: Double,
        /** 垂直偏差估计（弧度；未估计时为 0）。 */
        val verticalBiasRad: Double,
        val gdop: Double,
        val obsCount: Int,
        val sessionCount: Int,
        val contributions: List<Contribution>,
        val warnings: List<String>,
        /** 若调用方传入真值：与真值的地面距离（km）；无真值 = null。 */
        val truthErrorKm: Double?,
        val tier: String?,
    )

    private const val R_EARTH_M = 6_371_008.8

    /** 单条高度观测的模型值（视高度）。 */
    fun modelAltRad(latRad: Double, lonRad: Double, tMs: Long, obs: AltObs): Double {
        val ctx = SkyContext.at(tMs)
        val site = Moon.Site(latRad, lonRad)
        val ep = when (obs.body) {
            Body.SUN -> Moon.sunEphemerisAt(tMs)
            Body.MOON -> Moon.ephemerisAt(tMs)
            Body.PLANET -> runCatching {
                val b = Planets.Body.entries.firstOrNull { it.name == obs.bodyName || it.chinese == obs.bodyName }
                if (b != null) Planets.geocentric(b, tMs) else Moon.sunEphemerisAt(tMs)
            }.getOrElse { Moon.sunEphemerisAt(tMs) }
        }
        return Moon.altitudeRad(site, ep, tMs, ctx.gast)
    }

    private fun latLonToEnu(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val east = (lon - lon0).let { d -> (d + Math.PI) % (2 * Math.PI) - Math.PI } * R_EARTH_M * cos(lat0)
        val north = (lat - lat0) * R_EARTH_M
        return east to north
    }

    /**
     * 联合解算。观测不足（<2 条高度且无 2D 定位）时返回 null。
     */
    fun solve(
        altObs: List<AltObs>,
        fixObs: List<FixObs> = emptyList(),
        options: Options = Options(),
        /** 仅用于解后校验，不参与解算；null = 无真值。 */
        truthLatRad: Double? = null,
        truthLonRad: Double? = null,
    ): Result? {
        if (altObs.isEmpty() && fixObs.isEmpty()) return null
        if (altObs.size + fixObs.size < 2 && fixObs.isEmpty()) return null

        val p = 2 + (if (options.estimateClock) 1 else 0) + (if (options.estimateVertical) 1 else 0)

        // ---- 初值：显式给出优先；否则用几何种子 ----
        val seeds = ArrayList<Pair<Double, Double>>()
        if (options.initialLatRad != null && options.initialLonRad != null) {
            seeds.add(options.initialLatRad to options.initialLonRad)
        } else {
            val first = altObs.firstOrNull()
            if (first != null) {
                val ctx = SkyContext.at(first.utcMs)
                val ep = when (first.body) {
                    Body.SUN -> Moon.sunEphemerisAt(first.utcMs)
                    Body.MOON -> Moon.ephemerisAt(first.utcMs)
                    Body.PLANET -> Planets.geocentric(
                        Planets.Body.entries.firstOrNull { it.name == first.bodyName || it.chinese == first.bodyName } ?: Planets.Body.VENUS,
                        first.utcMs,
                    )
                }
                val dec = ep.decRad
                val subLon = ep.raRad - ctx.gast
                val z = Math.PI / 2 - first.apparentAltRad
                for (latSign in listOf(1.0, -1.0)) {
                    val lat = (dec + latSign * z).coerceIn(-Math.toRadians(89.0), Math.toRadians(89.0))
                    for (lonOff in listOf(0.0, Math.PI / 2, Math.PI, 3 * Math.PI / 2)) {
                        seeds.add(lat to normPi(subLon + lonOff))
                    }
                }
            } else {
                val f = fixObs.first()
                seeds.add(f.latRad to f.lonRad)
            }
        }

        var best: LocalSolve? = null
        for ((lat0, lon0) in seeds) {
            val s = runSolve(lat0, lon0, altObs, fixObs, options, p) ?: continue
            if (best == null || s.cost < best!!.cost) best = s
        }
        val s = best ?: return null
        if (!s.converged) return null

        // ---- 协方差：Gauss-Newton 的 (JᵀJ)⁻¹ × 残差方差 ----
        val dof = (altObs.size * 1 + fixObs.size * 2) - p
        val unitVar = if (dof > 0) s.cost / dof else 1.0
        val covLL = invert(s.jtj) ?: return null
        for (i in 0 until 2) for (j in 0 until 2) covLL[i][j] *= unitVar

        val lat = s.x[0]
        val lon = s.x[1]
        val g = arrayOf(
            doubleArrayOf(0.0, R_EARTH_M * cos(lat)),   // east  wrt (lat,lon)
            doubleArrayOf(R_EARTH_M, 0.0),              // north
        )
        val covEnu = Array(2) { DoubleArray(2) }
        for (i in 0 until 2) for (j in 0 until 2) {
            var sum = 0.0
            for (k in 0 until 2) for (l in 0 until 2) sum += g[i][k] * covLL[k][l] * g[j][l]
            covEnu[i][j] = sum
        }
        val eig = eigen2(covEnu)
        val major = sqrt(eig.first.coerceAtLeast(1e-12))
        val minor = sqrt(eig.second.coerceAtLeast(1e-12))
        val azDeg = Math.toDegrees(eig.third)
        val k95 = 2.4477
        val major95 = major * k95
        val minor95 = minor * k95
        val area95 = Math.PI * major95 * minor95 / 1e6
        val jtjInv = invert(s.jtj)
        val gdop = if (jtjInv != null) sqrt(trace(jtjInv)) else Double.NaN

        val contributions = ArrayList<Contribution>()
        for ((sess, list) in altObs.groupBy { it.session }) {
            val res = ArrayList<Double>()
            for (o in list) {
                val t = o.utcMs + (if (options.estimateClock) s.x[2] * 1000.0 else 0.0).toLong()
                val model = modelAltRad(lat, lon, t, o)
                val obs = o.apparentAltRad - (if (options.estimateVertical) s.x[p - 1] else 0.0)
                res.add(abs(obs - model))
            }
            contributions.add(Contribution(sess, list.size, res.average(), res.maxOrNull() ?: 0.0))
        }
        for ((sess, list) in fixObs.groupBy { it.session }) {
            contributions.add(Contribution(sess, list.size, 0.0, 0.0))
        }

        val warnings = ArrayList<String>()
        if (dof <= 0) warnings.add("观测数不足，协方差为几何下界（无残差方差可估）")
        if (major95 > 50_000) warnings.add("95% 半长轴 >50 km：未达 T1，只能作大致位置")
        if (options.estimateClock) warnings.add("钟差 δt 已作为未知量估计：依赖多历元几何，仅作参考")
        if (options.estimateVertical) warnings.add("垂直偏差 δv 已作为未知量估计：与位置强相关，建议多方位/多天体观测")
        if (truthLatRad == null) warnings.add("无 GNSS 真值：以上为内部估计，未经验证，不给出 T 级")

        val truthErr = if (truthLatRad != null && truthLonRad != null) {
            haversineKm(truthLatRad, truthLonRad, lat, lon)
        } else null
        val tier = truthErr?.let { e -> when {
            e <= 1.0 -> "T0"
            e <= 50.0 -> "T1"
            else -> "T2"
        } }

        return Result(
            latDeg = Math.toDegrees(lat),
            lonDeg = Math.toDegrees(normPi(lon)),
            sigmaMajorM = major,
            sigmaMinorM = minor,
            sigmaAzimuthDeg = azDeg,
            major95M = major95,
            minor95M = minor95,
            area95Km2 = area95,
            residualRmsRad = run {
                var sumSq = 0.0
                var n = 0
                for (o in altObs) {
                    val t = o.utcMs + (if (options.estimateClock) s.x[2] * 1000.0 else 0.0).toLong()
                    val dv = if (options.estimateVertical) s.x[p - 1] else 0.0
                    val r = o.apparentAltRad - dv - modelAltRad(lat, lon, t, o)
                    sumSq += r * r; n++
                }
                sqrt(sumSq / n.coerceAtLeast(1))
            },
            dof = dof,
            clockOffsetSec = if (options.estimateClock) s.x[2] else 0.0,
            verticalBiasRad = if (options.estimateVertical) s.x.getOrElse(2 + (if (options.estimateClock) 1 else 0)) { 0.0 } else 0.0,
            gdop = gdop,
            obsCount = altObs.size + fixObs.size,
            sessionCount = (altObs.map { it.session } + fixObs.map { it.session }).distinct().size,
            contributions = contributions,
            warnings = warnings,
            truthErrorKm = truthErr,
            tier = tier,
        )
    }


    private class LocalSolve(
        val x: DoubleArray,
        val cost: Double,
        val jtj: Array<DoubleArray>,
        val converged: Boolean,
    )

    private fun runSolve(
        lat0: Double,
        lon0: Double,
        altObs: List<AltObs>,
        fixObs: List<FixObs>,
        options: Options,
        p: Int,
    ): LocalSolve? {
        val x = doubleArrayOf(lat0, lon0) + DoubleArray(p - 2)
        val n = altObs.size + fixObs.size * 2
        var cost = 0.0
        var jtj = Array(p) { DoubleArray(p) }
        repeat(40) { iter ->
            val r = DoubleArray(n)
            val j = Array(n) { DoubleArray(p) }
            var idx = 0
            // 高度观测
            for (o in altObs) {
                val sigma = sqrt(o.sigmaRad * o.sigmaRad + options.systemFloorRad * options.systemFloorRad)
                val t = o.utcMs + (if (options.estimateClock) x[2] * 1000.0 else 0.0).toLong()
                val model = modelAltRad(x[0], x[1], t, o)
                val dv = if (options.estimateVertical) x[p - 1] else 0.0
                r[idx] = (o.apparentAltRad - dv - model) / sigma
                // 数值雅可比
                val h = 1e-7
                for (k in 0 until p) {
                    val xp = x.copyOf(); xp[k] += h
                    val tp = o.utcMs + (if (options.estimateClock) xp[2] * 1000.0 else 0.0).toLong()
                    val dvp = if (options.estimateVertical) xp[p - 1] else 0.0
                    val mp = if (options.estimateVertical && k == p - 1) model else modelAltRad(xp[0], xp[1], tp, o)
                    val rp = (o.apparentAltRad - dvp - mp) / sigma
                    j[idx][k] = (rp - r[idx]) / h
                }
                // 对 dv 的解析项更稳
                if (options.estimateVertical) j[idx][p - 1] = -1.0 / sigma
                idx++
            }
            // 2D 位置观测：按椭圆协方差白化（各向异性，方位自北顺时针）
            for (f in fixObs) {
                val a = f.sigmaMajorM.coerceAtLeast(1.0)
                val b = f.sigmaMinorM.coerceAtLeast(1.0)
                val az = Math.toRadians(f.azimuthDeg)
                // 局部椭圆坐标：major 方向 (sinAz, cosAz)（east,north），minor 方向 (cosAz, -sinAz)
                val de = ((f.lonRad - x[1]) + Math.PI) % (2 * Math.PI) - Math.PI
                val dn = f.latRad - x[0]
                val east = de * cos(f.latRad) * R_EARTH_M
                val north = dn * R_EARTH_M
                val u = (east * sin(az) + north * cos(az)) / a
                val v = (east * cos(az) - north * sin(az)) / b
                r[idx] = u
                // north=(f.lat-x0)*R, east=(f.lon-x1)*cos(lat)*R ⇒ 对未知量导数为负
                j[idx][0] = (-R_EARTH_M * cos(az)) / a
                j[idx][1] = (-cos(f.latRad) * R_EARTH_M * sin(az)) / a
                idx++
                r[idx] = v
                j[idx][0] = (R_EARTH_M * sin(az)) / b
                j[idx][1] = (-cos(f.latRad) * R_EARTH_M * cos(az)) / b
                idx++
            }
            cost = r.sumOf { it * it }
            jtj = Array(p) { DoubleArray(p) }
            val jtr = DoubleArray(p)
            for (i in 0 until n) for (a in 0 until p) {
                jtr[a] += j[i][a] * r[i]
                for (b in 0 until p) jtj[a][b] += j[i][a] * j[i][b]
            }
            // LM 阻尼
            for (a in 0 until p) jtj[a][a] += 1e-6
            val delta = solveLinear(jtj, jtr.map { -it }.toDoubleArray()) ?: return null
            // 限步
            val latStep = delta[0].coerceIn(-0.05, 0.05)
            val lonStep = delta[1].coerceIn(-0.05, 0.05)
            x[0] = (x[0] + latStep).coerceIn(-Math.PI / 2, Math.PI / 2)
            x[1] = normPi(x[1] + lonStep)
            if (p > 2) x[2] = (x[2] + delta.getOrElse(2) { 0.0 }).coerceIn(-3600.0, 3600.0)
            if (options.estimateVertical) x[p - 1] = (x[p - 1] + delta.getOrElse(p - 1) { 0.0 }).coerceIn(-0.2, 0.2)
            if (abs(latStep) < 1e-10 && abs(lonStep) < 1e-10) {
                return LocalSolve(x.copyOf(), cost, jtj, true)
            }
        }
        return LocalSolve(x.copyOf(), cost, jtj, true)
    }

    private fun normPi(x: Double): Double {
        var v = x
        while (v > Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }

    private fun trace(m: Array<DoubleArray>): Double = (0 until m.size).sumOf { m[it][it] }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = m.size
        val a = Array(n) { i -> DoubleArray(2 * n) }
        for (i in 0 until n) {
            for (j in 0 until n) a[i][j] = m[i][j]
            a[i][n + i] = 1.0
        }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-18) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val d = a[col][col]
            for (k in 0 until 2 * n) a[col][k] /= d
            for (r in 0 until n) if (r != col) {
                val f = a[r][col]
                for (k in 0 until 2 * n) a[r][k] -= f * a[col][k]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][n + j] } }
    }

    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = a.size
        val m = Array(n) { i -> doubleArrayOf(*a[i], b[i]) }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-18) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            for (k in col until n + 1) m[col][k] /= d
            for (r in 0 until n) if (r != col) {
                val f = m[r][col]
                for (k in col until n + 1) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    /** 2×2 对称矩阵特征值/主方向：返回 (major, minor, azimuthRad)。 */
    private fun eigen2(m: Array<DoubleArray>): Triple<Double, Double, Double> {
        val a = m[0][0]
        val b = (m[0][1] + m[1][0]) / 2
        val d = m[1][1]
        val tr = a + d
        val det = a * d - b * b
        val disc = sqrt((tr * tr / 4 - det).coerceAtLeast(0.0))
        val major = tr / 2 + disc
        val minor = tr / 2 - disc
        val thetaFromEast = 0.5 * atan2(2 * b, a - d)
        // 方位角自北顺时针：east/north 坐标下 az = 90° − θ(east 轴)
        val az = Math.PI / 2 - thetaFromEast
        return Triple(major, minor, az)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dp = lat2 - lat1
        val dl = lon2 - lon1
        val h = sin(dp / 2) * sin(dp / 2) + cos(lat1) * cos(lat2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R_EARTH_M / 1000.0 * asin(sqrt(h).coerceIn(0.0, 1.0))
    }
}
