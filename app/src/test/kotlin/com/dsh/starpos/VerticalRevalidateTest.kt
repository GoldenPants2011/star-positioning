package com.dsh.starpos

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.Precession
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.mul
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarSolveParams
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.FrameMeta
import com.dsh.starpos.field.ImuRow
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

/**
 * 把"设备系垂直修正 dt"合成进外参矩阵（不改 App 配置），离线复验：
 *   M' = M_ext · M_dev · R(dt) · M_dev⁻¹
 * 对比 旧外参 / 新外参 在旧星光会话上的定位误差，并做留一会话交叉验证。
 *
 * 只输出数据，不写配置文件、不生成 APK。
 */
class VerticalRevalidateTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun matVec(m: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    )
    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val c = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i][k] * b[k][j]
            c[i][j] = s
        }
        return c
    }
    private fun matT(a: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }
    private fun inv3(a: Array<DoubleArray>): Array<DoubleArray> {
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val inv = Array(3) { DoubleArray(3) }
        inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det
        inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det
        inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det
        inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det
        inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det
        inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det
        inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det
        inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det
        inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det
        return inv
    }

    private fun extMatrix(ext: ExtrinsicConfig): Array<DoubleArray> {
        val mm = ext.m
        if (mm != null) return arrayOf(
            doubleArrayOf(mm[0], mm[1], mm[2]),
            doubleArrayOf(mm[3], mm[4], mm[5]),
            doubleArrayOf(mm[6], mm[7], mm[8]),
        )
        return arrayOf(
            doubleArrayOf(1.0, -ext.rz, ext.ry),
            doubleArrayOf(ext.rz, 1.0, -ext.rx),
            doubleArrayOf(-ext.ry, ext.rx, 1.0),
        )
    }

    private fun rotSmall(dt: DoubleArray): Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, -dt[2], dt[1]),
        doubleArrayOf(dt[2], 1.0, -dt[0]),
        doubleArrayOf(-dt[1], dt[0], 1.0),
    )

    /** M' = M_ext · M_dev · R(dt) · M_dev⁻¹，转成 9 元行优先。 */
    private fun composeFlat(ext: ExtrinsicConfig, orientation: Int, dt: DoubleArray): DoubleArray {
        val mext = extMatrix(ext)
        val mdev = DeviceFrame.cameraFromDevice(orientation)
        val mp = matMul(matMul(matMul(mext, mdev), rotSmall(dt)), matT(mdev))
        return doubleArrayOf(
            mp[0][0], mp[0][1], mp[0][2],
            mp[1][0], mp[1][1], mp[1][2],
            mp[2][0], mp[2][1], mp[2][2],
        )
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * Math.asin(sqrt(h).coerceIn(0.0, 1.0))
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            for (k in col until 4) m[col][k] /= d
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }

    private class FrameRec(
        val session: String,
        val meta: FrameMeta,
        val imu: List<ImuRow>,
        val orientation: Int,
        val rot: Array<DoubleArray>,
        val ctx: SkyContext,
        val gravitySigma: Double,
        val attitudeSigma: Double,
        val truthLat: Double,
        val truthLon: Double,
        val baselineUp: DoubleArray,
    )

    private fun err(fr: FrameRec, up: DoubleArray): Pair<Double, Double>? {
        val pos = PositionSolver.solve(
            fr.rot, up, fr.ctx,
            gravitySigmaDeg = fr.gravitySigma, attitudeSigmaDeg = fr.attitudeSigma,
            timeUncertaintySec = 0.2,
        ).fix ?: return null
        val dLat = pos.latDeg - fr.truthLat
        val dLon = pos.lonDeg - fr.truthLon
        return (dLon * 111.32 * cos(Math.toRadians(fr.truthLat))) to (dLat * 111.32)
    }

    private fun km(e: Pair<Double, Double>?) = e?.let { sqrt(it.first * it.first + it.second * it.second) }

    /** 拟合 dt：用旧外参的 upBase，最小化位置误差平方和。 */
    private fun fitDt(frames: List<FrameRec>, ext: ExtrinsicConfig): DoubleArray {
        // 预计算 invE=(M_ext·M_dev)⁻¹，E=M_ext·M_dev
        val invE = frames.associate { fr ->
            val e = matMul(extMatrix(ext), DeviceFrame.cameraFromDevice(fr.orientation))
            fr to inv3(e)
        }
        fun upCorr(fr: FrameRec, d: DoubleArray): DoubleArray {
            val ie = invE[fr]!!
            val e = inv3(ie)
            val ud = V.unit(matVec(ie, fr.baselineUp))
            val u = V.unit(
                doubleArrayOf(
                    ud[0] + d[1] * ud[2] - d[2] * ud[1],
                    ud[1] + d[2] * ud[0] - d[0] * ud[2],
                    ud[2] + d[0] * ud[1] - d[1] * ud[0],
                ),
            )
            return V.unit(matVec(e, u))
        }
        fun res(d: DoubleArray): DoubleArray {
            val out = ArrayList<Double>()
            for (fr in frames) {
                val e = err(fr, upCorr(fr, d)) ?: continue
                out.add(e.first); out.add(e.second)
            }
            return out.toDoubleArray()
        }
        var dt = doubleArrayOf(0.0, 0.0, 0.0)
        var r = res(dt)
        var cost = r.sumOf { it * it }
        repeat(10) {
            val n = r.size
            val j = Array(n) { DoubleArray(3) }
            val h = 1e-4
            for (k in 0 until 3) {
                val dp = dt.copyOf(); dp[k] += h
                val rp = res(dp)
                for (i in 0 until n) j[i][k] = (rp[i] - r[i]) / h
            }
            val jtj = Array(3) { DoubleArray(3) }
            val jtr = DoubleArray(3)
            for (i in 0 until n) for (a in 0 until 3) {
                jtr[a] += j[i][a] * r[i]
                for (b in 0 until 3) jtj[a][b] += j[i][a] * j[i][b]
            }
            for (a in 0 until 3) jtj[a][a] += 1e-8
            val delta = solve3(jtj, jtr.map { -it }.toDoubleArray())
            val step = sqrt(dot(delta, delta))
            val scale = if (step > 0.05) 0.05 / step else 1.0
            val cand = doubleArrayOf(dt[0] + delta[0] * scale, dt[1] + delta[1] * scale, dt[2] + delta[2] * scale)
            val rc = res(cand)
            val cc = rc.sumOf { it * it }
            if (cc < cost) {
                dt = cand; r = rc; cost = cc
            }
        }
        return dt
    }

    private fun evalAll(frames: List<FrameRec>, newExt: ExtrinsicConfig?, orientation: Int): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        for (fr in frames) {
            val up = if (newExt == null) fr.baselineUp else {
                val g = FieldAnalyzer.gravityAt(fr.imu, fr.meta, fr.orientation, extrinsic = newExt)
                if (!g.valid) fr.baselineUp else g.upCam
            }
            val e = km(err(fr, up)) ?: continue
            out.add(fr.session to e)
        }
        return out
    }

    private fun summary(tag: String, errs: List<Pair<String, Double>>) {
        if (errs.isEmpty()) { println("$tag：无可评估帧"); return }
        val s = errs.map { it.second }.sorted()
        println("%s：%d 帧，中位 %.1f km，平均 %.1f km，最好 %.1f km，最差 %.1f km".format(
            tag, errs.size, s[s.size / 2], errs.map { it.second }.average(), s.first(), s.last(),
        ))
        val bySession = errs.groupBy { it.first }
        for ((sess, list) in bySession) {
            val v = list.map { it.second }.sorted()
            println("    %-32s %2d 帧：中位 %.1f km，平均 %.1f km".format(
                sess.removeSuffix(".zip"), v.size, v[v.size / 2], v.average(),
            ))
        }
    }

    @Test
    fun `垂直修正合成后复验`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val oldExt = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: run {
            println("无外参配置"); return
        }
        // 离线分析要跟 App 一致：先扣六位置零偏 (§90)。否则重力天顶系统性偏 ~1.4°。
        com.dsh.starpos.field.FieldAnalyzer.accelBias = doubleArrayOf(
            26.3e-3 * 9.80665, -20.0e-3 * 9.80665, 1.4e-3 * 9.80665,
        )
        val catalog = Fixtures.catalog
        val index = SkyIndex(catalog)

        val sessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261121-002708.zip",
            "session-20261121-193428.zip",
            "session-20261122-000444.zip",
            "session-20261122-190714.zip",
        )
        val frames = ArrayList<FrameRec>()
        for (name in sessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) { println("缺文件 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                val gnss = reader.readGnss()
                if (gnss.isEmpty()) { println("$name 无 GNSS"); return@use }
                val truth = gnss[gnss.size / 2]
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = oldExt)
                    if (!g.valid) continue
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0, medianPrefilter = false)
                    val starLike = det.detections.filter { StarSolveParams.accept(it, cam) }
                    if (starLike.size < 6) continue
                    val solver = PlateSolver(
                        index, cam,
                        triangleMagLimit = StarSolveParams.triangleMag(cam),
                        maxResidualArcsec = maxOf(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val plate = runCatching {
                        solver.solve(
                            starLike,
                            maxImageStars = StarSolveParams.maxImageStars(cam),
                            matchToleranceDeg = StarSolveParams.matchToleranceDeg(cam),
                            upCam = g.upCam,
                        )
                    }.getOrNull() ?: continue
                    if (plate.matchedStars < 4) continue
                    val ctx0 = SkyContext.at(meta.wallClockUtcMs)
                    // 星解 R: camera→ICRS(J2000)；PositionSolver 需要 TOD → 左乘岁差+章动矩阵
                    val rotTod = mul(Precession.icrsToTod(ctx0.jdTt), plate.rotation)
                    frames.add(
                        FrameRec(
                            name, meta, imu, orientation, rotTod,
                            ctx0, g.sigmaDeg, plate.attitudeSigmaDeg,
                            truth.lat, truth.lon, g.upCam,
                        ),
                    )
                }
            }
        }
        println("可用帧：${frames.size}（${frames.map { it.session }.distinct().size} 组会话）")

        // 基线
        val baseErrs = ArrayList<Pair<String, Double>>()
        for (fr in frames) km(err(fr, fr.baselineUp))?.let { baseErrs.add(fr.session to it) }
        summary("基线（旧外参）", baseErrs)

        // 全量拟合 → 合成新外参 → 复验
        val dt = fitDt(frames, oldExt)
        val dtDeg = Math.toDegrees(sqrt(dot(dt, dt)))
        println("拟合 dt = (%.3f, %.3f, %.3f)°，|dt| = %.2f°".format(
            Math.toDegrees(dt[0]), Math.toDegrees(dt[1]), Math.toDegrees(dt[2]), dtDeg,
        ))
        val flat = composeFlat(oldExt, 90, dt)
        println("合成后 m' = [%s]".format(flat.joinToString(",") { "%.10f".format(it) }))
        val newExt = ExtrinsicConfig(rx = 0.0, ry = 0.0, rz = 0.0, note = "vertical-dt", source = "offline", m = flat)
        println("新外参角度（trace）：%.3f°（旧 %.3f°）".format(newExt.angleDeg, oldExt.angleDeg))
        // 新旧 up 差异
        val ups = frames.mapNotNull { fr ->
            val g = FieldAnalyzer.gravityAt(fr.imu, fr.meta, fr.orientation, extrinsic = newExt)
            if (!g.valid) null else Math.toDegrees(acos(dot(fr.baselineUp, g.upCam).coerceIn(-1.0, 1.0)))
        }
        if (ups.isNotEmpty()) {
            println("新外参对天顶的平均改动：%.2f°（最大 %.2f°）".format(ups.average(), ups.max()))
        }
        // 一致性检查：composeFlat 得到的新外参 vs 直接设备系旋转，两者应等价
        for (fr in frames.take(4)) {
            val E = matMul(extMatrix(oldExt), DeviceFrame.cameraFromDevice(fr.orientation))
            val invE = inv3(E)
            val ud = V.unit(matVec(invE, fr.baselineUp))
            val udr = V.unit(
                doubleArrayOf(
                    ud[0] + dt[1] * ud[2] - dt[2] * ud[1],
                    ud[1] + dt[2] * ud[0] - dt[0] * ud[2],
                    ud[2] + dt[0] * ud[1] - dt[1] * ud[0],
                ),
            )
            val upCeil = V.unit(matVec(E, udr))
            val gNew = FieldAnalyzer.gravityAt(fr.imu, fr.meta, fr.orientation, extrinsic = newExt)
            val ang = if (gNew.valid) Math.toDegrees(acos(dot(upCeil, gNew.upCam).coerceIn(-1.0, 1.0))) else -1.0
            println("  一致性 帧=${fr.meta.index}: 模型 vs gravityAt(新外参) 差 %.4f° (newValid=${gNew.valid})".format(ang))
        }
        summary("全量拟合后复验（新外参）", evalAll(frames, newExt, 90))

        // 留一会话交叉验证：训练拟合 dt → 合成 → 在留出会话上评估
        val allErrs = ArrayList<Pair<String, Double>>()
        for (held in frames.map { it.session }.distinct()) {
            val train = frames.filter { it.session != held }
            val test = frames.filter { it.session == held }
            if (train.size < 6 || test.isEmpty()) continue
            val d = fitDt(train, oldExt)
            val f = composeFlat(oldExt, 90, d)
            val ext = ExtrinsicConfig(rx = 0.0, ry = 0.0, rz = 0.0, note = "cv", source = "offline", m = f)
            var baseMed = Double.NaN
            var newMed = Double.NaN
            val b = test.mapNotNull { km(err(it, it.baselineUp)) }.sorted()
            if (b.isNotEmpty()) baseMed = b[b.size / 2]
            val n = ArrayList<Double>()
            for (fr in test) {
                val g = FieldAnalyzer.gravityAt(fr.imu, fr.meta, fr.orientation, extrinsic = ext)
                if (!g.valid) continue
                val e = km(err(fr, g.upCam)) ?: continue
                n.add(e)
                allErrs.add(fr.session to e)
            }
            val ns = n.sorted()
            if (ns.isNotEmpty()) newMed = ns[ns.size / 2]
            println("留出 %-32s 基线中位 %6.1f km → 新外参中位 %6.1f km".format(
                held.removeSuffix(".zip"), baseMed, newMed,
            ))
        }
        if (allErrs.isNotEmpty()) {
            val s = allErrs.map { it.second }.sorted()
            println("交叉验证（新外参）：%d 帧，中位 %.1f km，平均 %.1f km".format(
                allErrs.size, s[s.size / 2], allErrs.map { it.second }.average(),
            ))
        }
    }
}
