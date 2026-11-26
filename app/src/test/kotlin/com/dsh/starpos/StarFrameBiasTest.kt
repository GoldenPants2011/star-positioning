package com.dsh.starpos

import com.dsh.starpos.astro.Aberration
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Geo
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.Precession
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarSolveParams
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

/**
 * 星光分支"固定姿态偏差"来源诊断：
 * 星图解出的 R 是 camera→ICRS(J2000)，但 PositionSolver 把它当 TOD 用（lon = RA − GAST）。
 * 本测试对比四种处理下的定位误差：
 *   A 现状（R 当 TOD）
 *   B z_icrs 经 IAU2006 岁差+章动 → z_tod
 *   C B + 年周光行差
 *   D 只看"z_tod 与真值天顶"的夹角（不含位置解算）
 */
class StarFrameBiasTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normPi(x: Double): Double {
        var v = x
        while (v > Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }

    private fun posFromZ(zIn: DoubleArray, gast: Double): Pair<Double, Double> {
        val z = V.unit(zIn)
        val decGeo = asin(z[2].coerceIn(-1.0, 1.0))
        val ra = atan2(z[1], z[0])
        val lon = normPi(ra - gast)
        val lat = Geo.geodeticFromGeocentric(decGeo)
        return Math.toDegrees(lat) to Math.toDegrees(lon)
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

    private class Rec(
        val session: String,
        val rot: Array<DoubleArray>,   // camera→ICRS(J2000)
        val upCam: DoubleArray,
        val ctx: SkyContext,
        val truthLat: Double,
        val truthLon: Double,
    )

    @Test
    fun `星光固定姿态偏差来源`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: run {
            println("无外参"); return
        }
        // 关键实验：离线分析默认没有加载加速度计零偏（App 内由 AccelBiasStore 加载）。
        // 按 §90 记录的六位置零偏 (26.3, −20.0, 1.4) mg 补上，看看是不是这个 1.5° 偏差的来源。
        com.dsh.starpos.field.FieldAnalyzer.accelBias = doubleArrayOf(
            26.3e-3 * 9.80665, -20.0e-3 * 9.80665, 1.4e-3 * 9.80665,
        )
        val index = SkyIndex(Fixtures.catalog)
        val sessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261122-000444.zip",
        )
        val recs = ArrayList<Rec>()
        for (name in sessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                val gnss = reader.readGnss()
                if (gnss.isEmpty()) return@use
                val truth = gnss[gnss.size / 2]
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
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
                    recs.add(Rec(name, plate.rotation, g.upCam, SkyContext.at(meta.wallClockUtcMs), truth.lat, truth.lon))
                }
            }
        }
        println("可用帧：${recs.size}")

        var nA = 0
        var nB = 0
        var nC = 0
        val errA = ArrayList<Double>()
        val errB = ArrayList<Double>()
        val errC = ArrayList<Double>()
        val dzAng = ArrayList<Double>()
        // ICRS 与 TOD 的差别（真值天顶两坐标系的夹角 = 岁差+章动造成的位置偏差）
        val icrsTodAng = ArrayList<Double>()
        // 相机系里的偏差向量（若固定，说明是相机系内固定旋转偏差）
        val dc = ArrayList<DoubleArray>()
        val eastList = ArrayList<Double>()
        val northList = ArrayList<Double>()
        val dtList = ArrayList<Double>()
        for (r in recs) {
            val zIcrs = V.unit(apply(r.rot, r.upCam))
            // 真值天顶（TOD）
            val lst = r.ctx.gast + Math.toRadians(r.truthLon)
            val zTrueTod = V.fromRaDec(lst, Math.toRadians(r.truthLat))
            // 用真值反推的观测天顶（ICRS）→ 经 icrsToTod 后应与 zTrueTod 一致
            val m = Precession.icrsToTod(r.ctx.jdTt)
            val zTod = V.unit(apply(m, zIcrs))
            val zTodAb = V.unit(apply(m, Aberration.apply(zIcrs, r.ctx.jdTt)))
            val pA = posFromZ(zIcrs, r.ctx.gast)
            val pB = posFromZ(zTod, r.ctx.gast)
            val pC = posFromZ(zTodAb, r.ctx.gast)
            errA.add(haversineKm(r.truthLat, r.truthLon, pA.first, pA.second))
            errB.add(haversineKm(r.truthLat, r.truthLon, pB.first, pB.second))
            errC.add(haversineKm(r.truthLat, r.truthLon, pC.first, pC.second))
            if (errA.last() < 50.0) nA++
            // 东/北分量 + 等效时间偏差（纯经度差 → 钟差）
            val dEast = normPi(Math.toRadians(pA.second - r.truthLon)) * Math.toDegrees(1.0).let {
                Math.toDegrees(normPi(Math.toRadians(pA.second - r.truthLon))) * 111.32 * cos(Math.toRadians(r.truthLat))
            }
            val dNorth = (pA.first - r.truthLat) * 111.32
            eastList.add(dEast); northList.add(dNorth)
            // 1° lon ≈ 15.041°/h 时间（赤道旋转），δt(秒) = dLonDeg / 15.041 * 3600
            val dtSec = Math.toDegrees(normPi(Math.toRadians(pA.second - r.truthLon))) / 15.041069 * 3600.0
            dtList.add(dtSec)
            if (errB.last() < 50.0) nB++
            if (errC.last() < 50.0) nC++
            dzAng.add(Math.toDegrees(acos(dot(zTod, zTrueTod).coerceIn(-1.0, 1.0))))
            icrsTodAng.add(Math.toDegrees(acos(dot(zIcrs, zTrueTod).coerceIn(-1.0, 1.0))))
            // 相机系偏差：d_cam = Rᵀ·(z_meas_icrs − z_true_icrs)
            val zTrueIcrs = V.unit(apply(transpose(m), zTrueTod))
            val d = doubleArrayOf(zIcrs[0] - zTrueIcrs[0], zIcrs[1] - zTrueIcrs[1], zIcrs[2] - zTrueIcrs[2])
            val rT = transpose(r.rot)
            dc.add(V.unit(apply(rT, d)))
        }
        fun med(v: ArrayList<Double>): Double {
            val s = v.sorted()
            return s[s.size / 2]
        }
        println("A 现状（R 当 TOD）：中位 %.1f km（<50km %d/%d）".format(med(errA), nA, recs.size))
        println("B 岁差+章动到 TOD：中位 %.1f km（<50km %d/%d）".format(med(errB), nB, recs.size))
        println("C B+光行差：中位 %.1f km（<50km %d/%d）".format(med(errC), nC, recs.size))
        println("z_icrs 与真 TOD 天顶夹角：中位 %.3f°".format(med(icrsTodAng)))
        println("z_tod 与真 TOD 天顶夹角（残差）：中位 %.3f°".format(med(dzAng)))
        fun avg(v: List<Double>) = v.average()
        println("位置误差分量：|东| 平均 %.1f km（范围 %.0f~%.0f），|北| 平均 %.1f km（范围 %.0f~%.0f）".format(
            avg(eastList.map { Math.abs(it) }), eastList.minOf { Math.abs(it) }, eastList.maxOf { Math.abs(it) },
            avg(northList.map { Math.abs(it) }), northList.minOf { Math.abs(it) }, northList.maxOf { Math.abs(it) },
        ))
        println("等效钟差 δt：平均 %+.1f s（中位 %+.1f s），逐帧 %s".format(
            avg(dtList),
            med(ArrayList(dtList)),
            dtList.joinToString(", ") { "%.0f".format(it) },
        ))
        // 相机系偏差一致性
        var sum = 0.0
        var cnt = 0
        val mean = doubleArrayOf(0.0, 0.0, 0.0)
        for (v in dc) { mean[0] += v[0]; mean[1] += v[1]; mean[2] += v[2]; cnt++ }
        val meanU = V.unit(mean)
        for (v in dc) {
            sum += Math.toDegrees(acos(dot(meanU, v).coerceIn(-1.0, 1.0)))
        }
        println("相机系偏差方向一致性：各帧与均值夹角 平均 %.2f°（0=完全固定方向，大=方向随姿态变）".format(sum / cnt))
        println("相机系偏差均值方向 = (%.3f, %.3f, %.3f)".format(meanU[0], meanU[1], meanU[2]))
        // 分组看
        for (sess in recs.map { it.session }.distinct()) {
            val es = recs.indices.filter { recs[it].session == sess }
            if (es.isEmpty()) continue
            val sA = es.map { errA[it] }.sorted()
            val sB = es.map { errB[it] }.sorted()
            println("  %-32s 帧%2d：A 中位 %6.1f km → B 中位 %6.1f km".format(
                sess.removeSuffix(".zip"), es.size, med(ArrayList(sA)), med(ArrayList(sB)),
            ))
        }
    }
}
