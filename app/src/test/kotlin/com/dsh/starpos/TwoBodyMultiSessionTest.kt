// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.TwoBody
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/**
 * 跨会话的两体姿态定位：把同一晚（同一三脚架）多组采集包的"月亮+行星"观测合并，
 * 做 Wahba 姿态 + 定位（两体单次观测退化，需要 ≥5 分钟时间跨度）。
 */
class TwoBodyMultiSessionTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `跨会话两体定位`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        // 只合并"同一固定三脚架"的连续会话：19:30 以后（HDR 批次）
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name }?.take(2) ?: return
        if (zips.isEmpty()) { println("没有 19:xx 的采集包，跳过"); return }
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val obsList = ArrayList<TwoBody.Obs>()
        var truthLat = Double.NaN
        var truthLon = Double.NaN
        var sessionsUsed = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                truthLat = truth.lat; truthLon = truth.lon
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val usedBefore = obsList.size
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val gast = SkyContext.at(meta.wallClockUtcMs).gast
                    val moon = Moon.ephemerisAt(meta.wallClockUtcMs)
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 32, minRadiusPx = 2.5)
                        ?: continue
                    val expectedPx = Math.tan(moon.angularRadiusRad) * cam.fx
                    if (fit.radiusPx < 0.3 * expectedPx || fit.radiusPx > 40.0 * expectedPx) {
                        println("  ${zip.name} 帧${meta.index} 月面拟合被门限拒绝 r=%.1f 期望 %.1f".format(fit.radiusPx, expectedPx))
                        continue
                    }
                    val g = FieldAnalyzer.gravityAt(imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (g.sigmaDeg > 5.0) continue
                    println("  ${zip.name} 帧${meta.index} exp=${meta.exposureNs/1_000_000}ms 月面 r=%.1f cx=%.0f cy=%.0f" .format(fit.radiusPx, fit.cx, fit.cy))
                    if (meta.exposureNs >= 500_000_000L) {
                        val det = com.dsh.starpos.astro.FrameAnalyzer.analyze(
                            f.luminance, f.width, f.height, thresholdSigma = 6.0, maxComponents = 4000,
                        )
                        println("    点源 ${det.detections.size} 个（前 8，含与月亮角距/高度）：")
                        det.detections.sortedByDescending { it.peak }.take(8).forEach { d ->
                            val sp = Math.toDegrees(
                                com.dsh.starpos.astro.V.angle(
                                    com.dsh.starpos.astro.V.unit(cam.unproject(fit.cx, fit.cy)),
                                    com.dsh.starpos.astro.V.unit(cam.unproject(d.x.toDouble(), d.y.toDouble())),
                                ),
                            )
                            println("      (%.0f,%.0f) 角距 %.2f° 峰值 %.4f 面积 %d".format(d.x, d.y, sp, d.peak, d.area))
                        }
                    }
                    val up = V.unit(g.upCam)
                    val moonDir = V.unit(cam.unproject(fit.cx, fit.cy))
                    val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                    // 画面噪声
                    val sorted = f.luminance.copyOf(); sorted.sort()
                    val med = sorted[sorted.size / 2]
                    var dev = 0.0
                    for (v in sorted) dev += Math.abs(v - med)
                    val pixSigma = (dev / sorted.size * 1.4826).toFloat()
                    for (b in listOf(Planets.Body.SATURN, Planets.Body.JUPITER, Planets.Body.VENUS)) {
                        val ep = runCatching { Planets.geocentric(b, meta.wallClockUtcMs) }.getOrNull() ?: continue
                        val sep = Math.toDegrees(
                            V.angle(V.fromRaDec(moon.raRad, moon.decRad), V.fromRaDec(ep.raRad, ep.decRad)),
                        )
                        if (sep > 38.0) continue
                        val planetAlt = Math.toDegrees(Moon.altitudeRad(site, ep, meta.wallClockUtcMs, gast))
                        if (planetAlt < 3.0) continue
                        val moonAlt = Math.toDegrees(
                            Math.asin((moonDir[0] * up[0] + moonDir[1] * up[1] + moonDir[2] * up[2]).coerceIn(-1.0, 1.0)),
                        )
                        val sinA = Math.sin(Math.toRadians(planetAlt))
                        val cs = Math.toRadians(sep)
                        val moonDotUp = moonDir[0] * up[0] + moonDir[1] * up[1] + moonDir[2] * up[2]
                        var e1 = doubleArrayOf(
                            up[0] - moonDotUp * moonDir[0], up[1] - moonDotUp * moonDir[1], up[2] - moonDotUp * moonDir[2],
                        )
                        val n1 = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2])
                        if (n1 < 1e-9) continue
                        e1 = doubleArrayOf(e1[0] / n1, e1[1] / n1, e1[2] / n1)
                        val e2 = doubleArrayOf(
                            moonDir[1] * e1[2] - moonDir[2] * e1[1],
                            moonDir[2] * e1[0] - moonDir[0] * e1[2],
                            moonDir[0] * e1[1] - moonDir[1] * e1[0],
                        )
                        val e1dotup = e1[0] * up[0] + e1[1] * up[1] + e1[2] * up[2]
                        val cosPhi = (sinA - Math.cos(cs) * moonDotUp) / (Math.sin(cs) * e1dotup)
                        if (Math.abs(cosPhi) > 1.0) continue
                        val phi0 = Math.acos(cosPhi)
                        var bestSnr = 0.0
                        var bestDir: DoubleArray? = null
                        for (sgn in doubleArrayOf(1.0, -1.0)) {
                            val phi = sgn * phi0
                            val u = V.unit(
                                doubleArrayOf(
                                    Math.cos(cs) * moonDir[0] + Math.sin(cs) * (Math.cos(phi) * e1[0] + Math.sin(phi) * e2[0]),
                                    Math.cos(cs) * moonDir[1] + Math.sin(cs) * (Math.cos(phi) * e1[1] + Math.sin(phi) * e2[1]),
                                    Math.cos(cs) * moonDir[2] + Math.sin(cs) * (Math.cos(phi) * e1[2] + Math.sin(phi) * e2[2]),
                                ),
                            )
                            if (u[2] <= 0.05) continue
                            val px = (cam.fx * u[0] / u[2] + cam.cx).toInt()
                            val py = (cam.fy * u[1] / u[2] + cam.cy).toInt()
                            if (px < 4 || py < 4 || px > f.width - 5 || py > f.height - 5) continue
                            var peak = 0f
                            for (dy in -3..3) for (dx in -3..3) {
                                val v = f.luminance[(py + dy) * f.width + (px + dx)]
                                if (v > peak) peak = v
                            }
                            val snr = if (pixSigma > 0f) ((peak - med) / pixSigma).toDouble() else 0.0
                            if (snr > bestSnr) { bestSnr = snr; bestDir = u }
                        }
                        println("    行星 %s sep=%.2f° 预测峰 SNR %.1fσ".format(b.chinese, sep, bestSnr))
                        if (bestSnr > 12.0 && bestDir != null) {
                            obsList.add(
                                TwoBody.Obs(meta.wallClockUtcMs, gast, moon.raRad, moon.decRad, moon.distanceKm, moonDir, 0.15),
                            )
                            obsList.add(
                                TwoBody.Obs(meta.wallClockUtcMs, gast, ep.raRad, ep.decRad, ep.distanceKm, bestDir, 0.3),
                            )
                        }
                    }
                }
                if (obsList.size > usedBefore) sessionsUsed++
            }
        }
        println("会话数 ${zips.size}，参与两体观测的会话 $sessionsUsed，观测 ${obsList.size} 个")
        if (obsList.isEmpty()) { println("没有识别到任何 月亮+行星 同框观测"); return }
        val spanMin = (obsList.maxOf { it.utcMillis } - obsList.minOf { it.utcMillis }) / 60_000.0
        println("时间跨度 %.1f min" .format(spanMin))
        val fix = TwoBody.solve(obsList)
        if (fix == null) {
            println("两体定位：几何不足（两体单次观测退化；需固定相机隔 ≥5 分钟多组）")
        } else {
            val errKm = FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truthLat, truthLon)
            println("两体定位：%.4f, %.4f rms %.2f° 观测 %d，与真值差 %.1f km".format(
                fix.latDeg, fix.lonDeg, fix.rmsDeg, fix.obs, errKm,
            ))
            fix.warnings.forEach { println("  警告：$it") }
        }
    }
}
