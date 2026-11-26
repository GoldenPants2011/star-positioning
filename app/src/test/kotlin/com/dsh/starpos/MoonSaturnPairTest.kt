// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import org.junit.Test

/**
 * 新数据（19:00 两组）是否同时拍到月亮和土星：
 * 1) 圆面拟合找月亮；2) 点源检测找候选星点；3) 用图像内角距（不依赖姿态）找与月亮相距 ≈ 月-土角距的点；
 * 4) 两体（月亮+土星）联合定位。
 */
class MoonSaturnPairTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun angSep(cam: com.dsh.starpos.astro.CameraIntrinsics, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val a = V.unit(cam.unproject(x1, y1))
        val b = V.unit(cam.unproject(x2, y2))
        return Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `月亮土星同框检测`() {
        val root = projectRoot()
        val dir = File(root, "field-inbox")
        val zips = dir.listFiles { f -> f.name.startsWith("session-20261121-19") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics
                if (truth == null || intr == null) { println("${zip.name}: 缺真值/内参"); return@use }
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                println("=".repeat(70))
                println(zip.name + " truth=${truth.lat},${truth.lon}")
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val gast = SkyContext.at(meta.wallClockUtcMs).gast
                    val moon = Moon.ephemerisAt(meta.wallClockUtcMs)
                    val saturn = Planets.geocentric(Planets.Body.SATURN, meta.wallClockUtcMs)
                    val moonAlt = Math.toDegrees(Moon.altitudeRad(site, moon, meta.wallClockUtcMs, gast))
                    val satAlt = Math.toDegrees(Moon.altitudeRad(site, saturn, meta.wallClockUtcMs, gast))
                    val sepEphem = Math.toDegrees(
                        V.angle(V.fromRaDec(moon.raRad, moon.decRad), V.fromRaDec(saturn.raRad, saturn.decRad)),
                    )
                    // 月亮中心：优先圆面拟合；失败则用最亮像素
                    val moonFit = Moon.fitDisk(
                        f.luminance, f.width, f.height, minAreaPx = 32, minRadiusPx = 2.5,
                    )
                    val (mcx, mcy) = if (moonFit != null) moonFit.cx to moonFit.cy else {
                        var bi = 0; var bv = -1f
                        for (i in f.luminance.indices) if (f.luminance[i] > bv) { bv = f.luminance[i]; bi = i }
                        (bi % f.width).toDouble() to (bi / f.width).toDouble()
                    }
                    val sortedLum = f.luminance.sorted()
                    val med = sortedLum[sortedLum.size / 2]
                    // 像素噪声（用中位绝对偏差近似）
                    val devs = FloatArray(sortedLum.size) { Math.abs(sortedLum[it] - med) }
                    devs.sort()
                    val pixSigma = devs[devs.size / 2] * 1.4826f
                    // 候选点源：5x5 局部极大，且亮度 > 中位 + 0.02
                    val cands = ArrayList<Triple<Double, Double, Float>>()
                    for (y in 3 until f.height - 3) for (x in 3 until f.width - 3) {
                        val v = f.luminance[y * f.width + x]
                        if (v < med + Math.max(0.004f, 6f * pixSigma)) continue
                        var isMax = true
                        for (dy in -2..2) for (dx in -2..2) {
                            if (dx == 0 && dy == 0) continue
                            if (f.luminance[(y + dy) * f.width + (x + dx)] > v) isMax = false
                        }
                        if (!isMax) continue
                        if (Math.hypot(x - mcx, y - mcy) < 80.0) continue
                        cands.add(Triple(x.toDouble(), y.toDouble(), v))
                    }
                    cands.sortByDescending { it.third }
                    println("  帧%d 星历：月亮 %.2f° 土星 %.2f° 角距 %.2f° | 月心=(%.1f,%.1f) 候选 %d".format(
                        meta.index, moonAlt, satAlt, sepEphem, mcx, mcy, cands.size,
                    ))
                    val g0 = FieldAnalyzer.gravityAt(
                        imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    val up0 = V.unit(g0.upCam)
                    fun altOf0(x: Double, y: Double): Double {
                        val r = cam.unproject(x, y)
                        val cz = (r[0] * up0[0] + r[1] * up0[1] + r[2] * up0[2]).coerceIn(-1.0, 1.0)
                        val app = Math.PI / 2 - acos(cz)
                        return app - Moon.refractionRad(app)
                    }
                    val moonAltMeas = Math.toDegrees(altOf0(mcx, mcy))
                    println("    测得月心高度 %.2f°（星历 %.2f°）".format(moonAltMeas, moonAlt))
                    // 候选按"与土星星历高度差"和"角距偏差"联合筛选
                    val rated = cands.map { c ->
                        val sep = angSep(cam, mcx, mcy, c.first, c.second)
                        val alt = Math.toDegrees(altOf0(c.first, c.second))
                        Triple(c, sep, alt)
                    }.filter { it.second < 45.0 }
                        .sortedBy { Math.abs(it.second - sepEphem) + Math.abs(it.third - satAlt) / 3.0 }
                    println("    候选（按角距+高度综合排序）：")
                    rated.take(10).forEach { (c, sep, alt) ->
                        println("      (x=%.0f,y=%.0f) 角距 %.2f°（差 %.2f°） 测得高度 %.1f°（土星 %.1f°，差 %.1f°） 峰值 %.4f".format(
                            c.first, c.second, sep, sep - sepEphem, alt, satAlt, alt - satAlt, c.third,
                        ))
                    }
                    // ==== 用"重力+角距"预测土星的两个镜像候选位置，看哪个位置真的有亮斑 ====
                    run {
                        val moonDir = V.unit(cam.unproject(mcx, mcy))
                        val upv = up0
                        val moonDotUp = moonDir[0] * upv[0] + moonDir[1] * upv[1] + moonDir[2] * upv[2]
                        // 用月亮的测量偏置修正土星高度（同一帧姿态误差近似相同）
                        val satAltMeas = Math.toRadians(satAlt) + Math.toRadians(moonAltMeas - moonAlt)
                        val sinA = Math.sin(satAltMeas)
                        // e1 = up 在垂直于 moonDir 平面上的分量
                        var e1 = doubleArrayOf(upv[0] - moonDotUp * moonDir[0], upv[1] - moonDotUp * moonDir[1], upv[2] - moonDotUp * moonDir[2])
                        val n1 = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2])
                        if (n1 > 1e-9) {
                            e1 = doubleArrayOf(e1[0] / n1, e1[1] / n1, e1[2] / n1)
                            val e2 = doubleArrayOf(
                                moonDir[1] * e1[2] - moonDir[2] * e1[1],
                                moonDir[2] * e1[0] - moonDir[0] * e1[2],
                                moonDir[0] * e1[1] - moonDir[1] * e1[0],
                            )
                            val cs = Math.toRadians(sepEphem)
                            val e1dotup = e1[0] * upv[0] + e1[1] * upv[1] + e1[2] * upv[2]
                            val cosPhi = (sinA - Math.cos(cs) * moonDotUp) / (Math.sin(cs) * e1dotup)
                            if (Math.abs(cosPhi) <= 1.0) {
                                val phi0 = Math.acos(cosPhi)
                                for (sgn in doubleArrayOf(1.0, -1.0)) {
                                    val phi = sgn * phi0
                                    val u = doubleArrayOf(
                                        Math.cos(cs) * moonDir[0] + Math.sin(cs) * (Math.cos(phi) * e1[0] + Math.sin(phi) * e2[0]),
                                        Math.cos(cs) * moonDir[1] + Math.sin(cs) * (Math.cos(phi) * e1[1] + Math.sin(phi) * e2[1]),
                                        Math.cos(cs) * moonDir[2] + Math.sin(cs) * (Math.cos(phi) * e1[2] + Math.sin(phi) * e2[2]),
                                    )
                                    if (u[2] <= 0.01) continue
                                    val px = cam.fx * u[0] / u[2] + cam.cx
                                    val py = cam.fy * u[1] / u[2] + cam.cy
                                    if (px < 3 || py < 3 || px > f.width - 4 || py > f.height - 4) {
                                        println("    预测土星位置 (%.0f,%.0f) 在画面外".format(px, py))
                                        continue
                                    }
                                    var peak = 0f
                                    var pxI = 0
                                    var pyI = 0
                                    for (dy in -3..3) for (dx in -3..3) {
                                        val v = f.luminance[((py.toInt() + dy) * f.width + (px.toInt() + dx))]
                                        if (v > peak) { peak = v; pxI = px.toInt() + dx; pyI = py.toInt() + dy }
                                    }
                                    val snr = (peak - med) / (if (pixSigma > 0f) pixSigma else 1f)
                                    println("    预测土星位置 (%.0f,%.0f) 局部峰值 %.4f（SNR %.1fσ）".format(px, py, peak, snr))
                                }
                            }
                        }
                    }
                    val best = rated.firstOrNull()
                    if (best == null) { println("    无候选"); continue }
                    // 用该候选当土星做两体解算
                    val sights = listOf(
                        Moon.Sight(
                            meta.wallClockUtcMs, gast, altOf0(mcx, mcy),
                            Math.max(0.15, g0.sigmaDeg),
                            moon.raRad, moon.decRad, moon.distanceKm,
                        ),
                        Moon.Sight(
                            meta.wallClockUtcMs, gast, altOf0(best.first.first, best.first.second),
                            0.25, saturn.raRad, saturn.decRad, saturn.distanceKm,
                        ),
                    )
                    val fix = Moon.solveOrbit(sights)
                    val errKm = fix?.let {
                        com.dsh.starpos.field.FieldAnalyzer.haversineKm(it.latDeg, it.lonDeg, truth.lat, truth.lon)
                    }
                    println("    两体解算：%s | 误差 %s".format(
                        fix?.let { "%.4f,%.4f rms %.2f°".format(it.latDeg, it.lonDeg, it.rmsDeg) } ?: "失败",
                        errKm?.let { "%.1f km".format(it) } ?: "—",
                    ))
                }
            }
        }
    }
}
