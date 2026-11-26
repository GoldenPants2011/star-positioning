// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * 今晚三组月光数据的联合解算：
 * 202658（1 s）、214304（250 ms）、223707（100 ms/ISO400）。
 * 同地点、跨 2.2 h → 首次具备"单天体多历元定位"的时间跨度。
 */
class MoonTripleSightTest {

    @Test
    fun `三组月光联合解算`() {
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val names = listOf(
            "session-20261120-202658.zip",
            "session-20261120-214304.zip",
            "session-20261120-223707.zip",
        )
        val allSights = ArrayList<Moon.Sight>()
        var refTruth: Pair<Double, Double>? = null
        for (name in names) {
            val zip = File("../field-inbox/" + name)
            if (!zip.isFile) { println("缺少 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                    ?: return@use
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                println("=== $name 真值=%.5f,%.5f ===".format(truth.lat, truth.lon))
                if (refTruth == null) refTruth = truth.lat to truth.lon
                else {
                    val d = haversineKm(refTruth!!.first, refTruth.second, truth.lat, truth.lon)
                    println("  与第一组的位置差：%.2f km".format(d))
                }
                for (m in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(
                        imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    if (!g.valid) { println("  帧 ${m.index}: IMU 无效"); continue }
                    val ephem = Moon.ephemerisAt(m.wallClockUtcMs)
                    val gast = SkyContext.at(m.wallClockUtcMs).gast
                    val expAlt = Moon.altitudeRad(
                        Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)), ephem, m.wallClockUtcMs, gast,
                    )
                    // 月心：优先圆拟合（放宽小月面），失败用"峰值窗口加权质心"
                    var cx: Double? = null
                    var cy: Double? = null
                    val fit = Moon.fitDisk(
                        f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8,
                    )
                    val expectedPx = Math.tan(ephem.angularRadiusRad) * cam.fx
                    if (fit != null && fit.radiusPx in (0.3 * expectedPx)..(40.0 * expectedPx)) {
                        cx = fit.cx; cy = fit.cy
                    } else {
                        val c = peakCentroid(f.luminance, f.width, f.height, 12)
                        if (c != null) { cx = c.first; cy = c.second }
                    }
                    if (cx == null || cy == null) { println("  帧 ${m.index}: 找不到月面"); continue }
                    val dir = cam.unproject(cx!!, cy!!)
                    val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                        .coerceIn(-1.0, 1.0)
                    val altApp = Math.PI / 2 - acos(cosZ)
                    val altTrue = altApp - Moon.refractionRad(altApp)
                    val rd = Math.toDegrees(altTrue - expAlt)
                    println(
                        "  帧 %d: 月心=(%.1f,%.1f) 高度=%.3f° 期望=%.3f° 残差=%+.3f° 时间=%s".format(
                            m.index, cx, cy, Math.toDegrees(altTrue), Math.toDegrees(expAlt), rd,
                            java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(m.wallClockUtcMs)),
                        ),
                    )
                    allSights.add(
                        Moon.Sight(
                            utcMillis = m.wallClockUtcMs, gast = gast, altRad = altTrue,
                            sigmaDeg = max(0.2, g.sigmaDeg),
                            raRad = ephem.raRad, decRad = ephem.decRad, distanceKm = ephem.distanceKm,
                        ),
                    )
                }
            }
        }
        if (allSights.size < 3) { println("观测不足，无法联合解算"); return }
        val spanH = (allSights.maxOf { it.utcMillis } - allSights.minOf { it.utcMillis }) / 3_600_000.0
        println("联合观测 ${allSights.size} 次，跨度 %.2f h".format(spanH))
        val fix = Moon.solveOrbit(allSights)
        if (fix == null) {
            println("解算失败")
            return
        }
        println("解算结果：%.5f, %.5f（rms %.3f°，%d 次观测）".format(fix.latDeg, fix.lonDeg, fix.rmsDeg, fix.sights))
        if (refTruth != null) {
            val d = haversineKm(fix.latDeg, fix.lonDeg, refTruth!!.first, refTruth.second)
            println("与真值差：**%.1f km**".format(d))
        }
        fix.warnings.forEach { println("警告：$it") }
    }

    /** 峰值窗口加权质心（小月面兜底）。 */
    private fun peakCentroid(lum: FloatArray, w: Int, h: Int, r: Int): Pair<Double, Double>? {
        var best = -1f
        var bx = 0
        var by = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (lum[row + x] > best) { best = lum[row + x]; bx = x; by = y }
        }
        if (best < 0.05f) return null
        val x0 = max(0, bx - r); val x1 = min(w - 1, bx + r)
        val y0 = max(0, by - r); val y1 = min(h - 1, by + r)
        val samples = ArrayList<Float>()
        for (y in y0..y1) for (x in x0..x1) samples.add(lum[y * w + x])
        samples.sort()
        val bg = samples[(samples.size * 0.2).toInt().coerceIn(0, samples.size - 1)]
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (y in y0..y1) for (x in x0..x1) {
            val dv = lum[y * w + x] - bg
            if (dv <= 0f) continue
            val wt = (dv * dv).toDouble()
            sx += x * wt; sy += y * wt; sw += wt
        }
        if (sw <= 0.0) return null
        return (sx / sw) to (sy / sw)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(min(1.0, Math.sqrt(a)))
    }
}
