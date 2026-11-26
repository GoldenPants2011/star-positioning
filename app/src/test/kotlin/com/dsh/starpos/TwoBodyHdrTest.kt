package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.TwoBody
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import org.junit.Test

/**
 * HDR 批次的两体解算：
 * - 短曝光帧（2 ms）取**月亮**中心（饱和膨胀小、中心准）；
 * - 长曝光帧（1 s）取**土星**（2 ms 拍不到，1 s 能看到；月亮的饱和光斑在长曝光里偏心，不用它定心）；
 * - 同一三脚架 → 相机姿态不变，可以把"短帧月亮"和"长帧土星"配对成一次两体观测；
 * - 多组（1 小时内隔 5 分钟）合并 → 两体姿态定位。
 */
class TwoBodyHdrTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `HDR 两体定位`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name } ?: return
        if (zips.isEmpty()) { println("没有 20:xx HDR 包，跳过"); return }
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val obs = ArrayList<TwoBody.Obs>()
        var truthLat = Double.NaN
        var truthLon = Double.NaN
        var pairsInSessions = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                truthLat = truth.lat; truthLon = truth.lon
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                data class FrameInfo(val meta: com.dsh.starpos.field.FrameMeta, val cam: com.dsh.starpos.astro.CameraIntrinsics, val f: com.dsh.starpos.field.LoadedFrame, val gast: Double)
                val infos = reader.frameMetas().mapNotNull { m ->
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: return@mapNotNull null
                    FrameInfo(m, intr.toCameraIntrinsics(f.width, f.height), f, SkyContext.at(m.wallClockUtcMs).gast)
                }
                // 1) 短曝光帧定月亮
                var moonObs: TwoBody.Obs? = null
                for (fi in infos.filter { it.meta.exposureNs < 100_000_000L }) {
                    val fit = Moon.fitDisk(fi.f.luminance, fi.f.width, fi.f.height, minAreaPx = 32, minRadiusPx = 2.5)
                        ?: continue
                    val moon = Moon.ephemerisAt(fi.meta.wallClockUtcMs)
                    val expectedPx = Math.tan(moon.angularRadiusRad) * fi.cam.fx
                    if (fit.radiusPx < 0.3 * expectedPx || fit.radiusPx > 40.0 * expectedPx) continue
                    val dir = V.unit(fi.cam.unproject(fit.cx, fit.cy))
                    moonObs = TwoBody.Obs(fi.meta.wallClockUtcMs, fi.gast, moon.raRad, moon.decRad, moon.distanceKm, dir, 0.15)
                    println("  ${zip.name} 月亮：帧${fi.meta.index} r=%.1f 中心(%.0f,%.0f)".format(fit.radiusPx, fit.cx, fit.cy))
                    break
                }
                if (moonObs == null) { println("  ${zip.name}: 未找到月亮"); return@use }
                val moonEp = Moon.ephemerisAt(moonObs.utcMillis)
                // 2) 长曝光帧找土星：点源 + 与月亮角距/高度都接近星历
                var satObs: TwoBody.Obs? = null
                for (fi in infos.filter { it.meta.exposureNs >= 500_000_000L }) {
                    val sat = Planets.geocentric(Planets.Body.SATURN, fi.meta.wallClockUtcMs)
                    val sepEphem = Math.toDegrees(
                        V.angle(V.fromRaDec(moonEp.raRad, moonEp.decRad), V.fromRaDec(sat.raRad, sat.decRad)),
                    )
                    val satAlt = Math.toDegrees(Moon.altitudeRad(site, sat, fi.meta.wallClockUtcMs, fi.gast))
                    val g = FieldAnalyzer.gravityAt(
                        imu, fi.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    if (g.sigmaDeg > 5.0) continue
                    val up = V.unit(g.upCam)
                    val det = FrameAnalyzer.analyze(
                        fi.f.luminance, fi.f.width, fi.f.height, thresholdSigma = 6.0, maxComponents = 4000,
                    )
                    var best: Pair<com.dsh.starpos.astro.Detection, Double>? = null
                    // 注：上面只是占位，真正用月亮方向由 camDir 计算
                    for (d in det.detections) {
                        if (d.peak < 0.5f) continue
                        val dir = V.unit(fi.cam.unproject(d.x.toDouble(), d.y.toDouble()))
                        val camMoon = moonObs.camDir
                        val sepMeas = Math.toDegrees(V.angle(V.unit(camMoon), dir))
                        val altMeas = Math.toDegrees(
                            Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)),
                        )
                        val score = Math.abs(sepMeas - sepEphem) + Math.abs(altMeas - satAlt)
                        if (score > 2.5) continue
                        if (best == null || score < best.second) best = d to score
                    }
                    if (best != null) {
                        val d = best.first
                        val dir = V.unit(fi.cam.unproject(d.x.toDouble(), d.y.toDouble()))
                        satObs = TwoBody.Obs(fi.meta.wallClockUtcMs, fi.gast, sat.raRad, sat.decRad, sat.distanceKm, dir, 0.3)
                        println("  ${zip.name} 土星：帧${fi.meta.index} (%.0f,%.0f) 峰值 %.2f 角距差 %.2f°".format(
                            d.x, d.y, d.peak, best.second,
                        ))
                        break
                    }
                }
                if (satObs == null) { println("  ${zip.name}: 长曝光帧未找到土星"); return@use }
                obs.add(moonObs); obs.add(satObs)
                pairsInSessions++
                val sep = Math.toDegrees(V.angle(V.unit(moonObs.camDir), V.unit(satObs.camDir)))
                val sepEphem = Math.toDegrees(
                    V.angle(
                        V.fromRaDec(moonEp.raRad, moonEp.decRad),
                        V.fromRaDec(Planets.geocentric(Planets.Body.SATURN, satObs.utcMillis).raRad,
                            Planets.geocentric(Planets.Body.SATURN, satObs.utcMillis).decRad),
                    ),
                )
                println("    配对：%s 测得角距 %.2f°，星历 %.2f°（差 %+.2f°）".format(zip.name, sep, sepEphem, sep - sepEphem))
            }
        }
        println("=".repeat(60))
        println("会话 ${zips.size}，完成配对 $pairsInSessions，观测 ${obs.size}")
        if (obs.isEmpty()) return
        val spanMin = (obs.maxOf { it.utcMillis } - obs.minOf { it.utcMillis }) / 60_000.0
        println("时间跨度 %.1f min".format(spanMin))
        val fix = TwoBody.solve(obs)
        if (fix == null) {
            println("两体定位：几何不足")
        } else {
            val err = FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truthLat, truthLon)
            println("两体定位：%.4f, %.4f rms %.3f° 观测 %d，与真值差 %.1f km".format(
                fix.latDeg, fix.lonDeg, fix.rmsDeg, fix.obs, err,
            ))
            fix.warnings.forEach { println("  警告：$it") }
        }
    }
}
