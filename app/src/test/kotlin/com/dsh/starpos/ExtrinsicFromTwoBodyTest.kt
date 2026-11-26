package com.dsh.starpos

import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import org.junit.Test

/**
 * 用"月亮+土星"两体姿态得到**独立的天顶方向**（不需要重力），
 * 与 IMU 重力估计的天顶比较 → 拟合相机↔IMU 外参修正量。
 */
class ExtrinsicFromTwoBodyTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun enu(alt: Double, az: Double): DoubleArray {
        val ca = Math.cos(alt)
        return doubleArrayOf(ca * Math.sin(az), ca * Math.cos(az), Math.sin(alt))
    }

    @Test
    fun `两体天顶 vs IMU 天顶`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name } ?: return
        if (zips.isEmpty()) { println("没有 20:xx HDR 包，跳过"); return }
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        println("当前外参：${extrinsic?.angleDeg}°（${extrinsic?.note?.take(50)}）")
        val upTrueList = ArrayList<DoubleArray>()
        val upImuList = ArrayList<DoubleArray>()
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                data class F(val meta: com.dsh.starpos.field.FrameMeta, val cam: com.dsh.starpos.astro.CameraIntrinsics, val f: com.dsh.starpos.field.LoadedFrame, val gast: Double)
                val infos = reader.frameMetas().mapNotNull { m ->
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: return@mapNotNull null
                    F(m, intr.toCameraIntrinsics(f.width, f.height), f, SkyContext.at(m.wallClockUtcMs).gast)
                }
                // 月亮（短曝光）
                var moonF: F? = null; var moonDir: DoubleArray? = null; var moonEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs < 100_000_000L }) {
                    val fit = Moon.fitDisk(fi.f.luminance, fi.f.width, fi.f.height, minAreaPx = 32, minRadiusPx = 2.5) ?: continue
                    val ep = Moon.ephemerisAt(fi.meta.wallClockUtcMs)
                    val expPx = Math.tan(ep.angularRadiusRad) * fi.cam.fx
                    if (fit.radiusPx < 0.3 * expPx || fit.radiusPx > 40.0 * expPx) continue
                    moonF = fi; moonDir = V.unit(fi.cam.unproject(fit.cx, fit.cy)); moonEp = ep
                    break
                }
                if (moonF == null || moonDir == null || moonEp == null) { println("${zip.name}: 未找到月亮"); return@use }
                // 土星（长曝光，点源匹配角距/高度）
                var satF: F? = null; var satDir: DoubleArray? = null; var satEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs >= 500_000_000L }) {
                    val sat = Planets.geocentric(Planets.Body.SATURN, fi.meta.wallClockUtcMs)
                    val sepEphem = Math.toDegrees(V.angle(V.fromRaDec(moonEp.raRad, moonEp.decRad), V.fromRaDec(sat.raRad, sat.decRad)))
                    val satAlt = Math.toDegrees(Moon.altitudeRad(site, sat, fi.meta.wallClockUtcMs, fi.gast))
                    val g = FieldAnalyzer.gravityAt(imu, fi.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (g.sigmaDeg > 5.0) continue
                    val up = V.unit(g.upCam)
                    val det = FrameAnalyzer.analyze(fi.f.luminance, fi.f.width, fi.f.height, thresholdSigma = 6.0, maxComponents = 4000)
                    var best: Pair<DoubleArray, Double>? = null
                    for (d in det.detections) {
                        if (d.peak < 0.5f) continue
                        val dir = V.unit(fi.cam.unproject(d.x.toDouble(), d.y.toDouble()))
                        val sep = Math.toDegrees(V.angle(V.unit(moonDir), dir))
                        val alt = Math.toDegrees(Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)))
                        val score = Math.abs(sep - sepEphem) + Math.abs(alt - satAlt)
                        if (score > 2.5) continue
                        if (best == null || score < best.second) best = dir to score
                    }
                    if (best != null) { satF = fi; satDir = best.first; satEp = sat; break }
                }
                if (satF == null || satDir == null || satEp == null) { println("${zip.name}: 未找到土星"); return@use }
                // ENU 方向（按各自观测时刻）
                val mAlt = Moon.altitudeRad(site, moonEp, moonF.meta.wallClockUtcMs, moonF.gast)
                val mAz = Moon.azimuthRad(site, moonEp, moonF.gast)
                val sAlt = Moon.altitudeRad(site, satEp, satF.meta.wallClockUtcMs, satF.gast)
                val sAz = Moon.azimuthRad(site, satEp, satF.gast)
                val enuM = enu(mAlt, mAz)
                val enuS = enu(sAlt, sAz)
                // Wahba：camDir ≈ R · enu
                val r = runCatching {
                    Attitude.wahba(listOf(enuM, enuS), listOf(moonDir, satDir))
                }.getOrNull() ?: return@use
                val upTrue = V.unit(apply(r, doubleArrayOf(0.0, 0.0, 1.0)))
                // IMU 天顶（用月亮帧的重力）
                val g = FieldAnalyzer.gravityAt(imu, moonF.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                if (g.sigmaDeg > 5.0) return@use
                val upImu = V.unit(g.upCam)
                val ang = Math.toDegrees(acos((upTrue[0] * upImu[0] + upTrue[1] * upImu[1] + upTrue[2] * upImu[2]).coerceIn(-1.0, 1.0)))
                println("${zip.name}: 两体天顶 vs IMU 天顶 = %.2f°".format(ang))
                upTrueList.add(upTrue)
                upImuList.add(upImu)
            }
        }
        println("=".repeat(30))
        if (upTrueList.size < 3) { println("样本不足"); return }
        // 拟合 Δ：upTrue ≈ Δ · upImu（Δ 是相机系里需要施加在"IMU 天顶"上的修正）
        val delta = runCatching { Attitude.wahba(upImuList, upTrueList) }.getOrNull() ?: return
        // 残差
        var sum = 0.0
        for (i in upTrueList.indices) {
            val pred = V.unit(apply(delta, upImuList[i]))
            val a = Math.toDegrees(acos((pred[0] * upTrueList[i][0] + pred[1] * upTrueList[i][1] + pred[2] * upTrueList[i][2]).coerceIn(-1.0, 1.0)))
            sum += a * a
        }
        val rms = Math.sqrt(sum / upTrueList.size)
        // Δ 的旋转角/轴
        val trace = delta[0][0] + delta[1][1] + delta[2][2]
        val angDeg = Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
        println("拟合修正 Δ：旋转 %.2f°（轴 ≈ %.2f, %.2f, %.2f），修正后天顶残差 RMS %.2f°".format(
            angDeg,
            delta[2][1] - delta[1][2], delta[0][2] - delta[2][0], delta[1][0] - delta[0][1], rms,
        ))
        println("（当前外参 %.2f°）".format(extrinsic?.angleDeg ?: Double.NaN))
    }
}
