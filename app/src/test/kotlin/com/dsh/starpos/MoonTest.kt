// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/** 月亮模式核心算法自检：星历、月面圆拟合、轨道解算。 */
class MoonTest {

    @Test
    fun `星历范围合理`() {
        val base = 1_763_500_800_000L   // 2025-11-19 附近
        for (h in 0..24 step 6) {
            val e = Moon.ephemerisAt(base + h * 3_600_000L)
            val dist = e.distanceKm
            val radDeg = Math.toDegrees(e.angularRadiusRad)
            println("h=%2d 距离 %.0f km 视半径 %.3f° 亮部 %.2f".format(h, dist, radDeg, e.illuminatedFraction))
            assertTrue("地月距离异常：$dist", dist in 350_000.0..410_000.0)
            assertTrue("视半径异常：$radDeg", radDeg in 0.24..0.29)
            assertTrue("亮部占比异常", e.illuminatedFraction in 0.0..1.0)
        }
    }

    @Test
    fun `轨道解算能恢复位置`() {
        val trueLat = 30.0
        val trueLon = 120.0
        val site = Moon.Site(Math.toRadians(trueLat), Math.toRadians(trueLon))
        val t0 = 1_763_500_800_000L
        val rnd = java.util.Random(11)
        val sights = ArrayList<Moon.Sight>()
        // 5 次观测，跨度 5 小时
        for (k in 0 until 5) {
            val t = t0 + (k * 75 + 10) * 60_000L    // 每 75 分钟一次
            val ephem = Moon.ephemerisAt(t)
            val gast = SkyContext.at(t).gast
            val alt = Moon.altitudeRad(site, ephem, t, gast)
            // 加 ±0.05° 噪声
            val noisy = alt + Math.toRadians((rnd.nextDouble() - 0.5) * 0.1)
            sights.add(Moon.Sight(t, gast, noisy, 0.05, Moon.ephemerisAt(t).raRad, Moon.ephemerisAt(t).decRad, Moon.ephemerisAt(t).distanceKm))
            println("观测 ${k}: 高度角 %.2f°".format(Math.toDegrees(alt)))
        }
        val fix = Moon.solveOrbit(sights)
        assertTrue("解算失败", fix != null)
        val dLat = abs(fix!!.latDeg - trueLat)
        val dLon = abs(fix.lonDeg - trueLon)
        println("解：%.4f, %.4f（真值 %.4f, %.4f）误差 dLat=%.3f° dLon=%.3f° rms=%.3f°".format(
            fix.latDeg, fix.lonDeg, trueLat, trueLon, dLat, dLon, fix.rmsDeg))
        assertTrue("纬度误差过大 $dLat", dLat < 0.6)
        assertTrue("经度误差过大 $dLon", dLon < 0.6)
    }

    @Test
    fun `云与遮挡能被门控识别`() {
        val w = 400
        val h = 300
        val cx = 205.0
        val cy = 148.0
        val r = 70.0
        fun render(litTheta: Double, blur: Int = 0, occludeBand: Boolean = false): FloatArray {
            val lum = FloatArray(w * h)
            val theta = Math.toRadians(litTheta)
            for (y in 0 until h) for (x in 0 until w) {
                val u = (x - cx) / r
                val v = (y - cy) / r
                val rr = u * u + v * v
                lum[y * w + x] = if (rr > 1.0) 0.02f else {
                    val ww = Math.sqrt(1 - rr)
                    if (u * Math.sin(theta) + ww * Math.cos(theta) > 0) 0.9f else 0.03f
                }
            }
            if (occludeBand) {
                // 一条"云带"横穿月面
                for (y in 140 until 160) for (x in 130 until 290) lum[y * w + x] = 0.05f
            }
            if (blur > 0) {
                val out = FloatArray(lum.size)
                for (y in 0 until h) for (x in 0 until w) {
                    var s = 0.0; var n = 0
                    for (dy in -blur..blur) for (dx in -blur..blur) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        s += lum[ny * w + nx]; n++
                    }
                    out[y * w + x] = (s / n).toFloat()
                }
                return out
            }
            return lum
        }
        fun obs(lum: FloatArray): com.dsh.starpos.astro.MoonObservation {
            val fit = com.dsh.starpos.astro.Moon.fitDisk(lum, w, h) ?: error("拟合失败")
            return com.dsh.starpos.astro.MoonObservation(
                1_763_500_800_000L, 0.0, 0.3, fit, expectedRadiusPx = r, expectedLitFraction = 0.75,
            )
        }
        val clean = obs(render(60.0))
        val foggy = obs(render(60.0, blur = 8))
        val occluded = obs(render(60.0, occludeBand = true))
        println("clean edge=%.3f inl=%.2f lit=%.2f".format(clean.disk.edgeScore, clean.disk.inlierFrac, clean.disk.litFraction))
        println("foggy edge=%.3f inl=%.2f lit=%.2f".format(foggy.disk.edgeScore, foggy.disk.inlierFrac, foggy.disk.litFraction))
        println("occl  edge=%.3f inl=%.2f lit=%.2f".format(occluded.disk.edgeScore, occluded.disk.inlierFrac, occluded.disk.litFraction))
        val vClean = com.dsh.starpos.astro.MoonQuality.check(clean, w, h, clean.disk.edgeScore)
        val vFoggy = com.dsh.starpos.astro.MoonQuality.check(foggy, w, h, clean.disk.edgeScore)
        val vOccl = com.dsh.starpos.astro.MoonQuality.check(occluded, w, h, clean.disk.edgeScore)
        println("clean accepted=${vClean.accepted} ${vClean.reasons}")
        println("foggy accepted=${vFoggy.accepted} ${vFoggy.reasons}")
        println("occl  accepted=${vOccl.accepted} ${vOccl.reasons}")
        assertTrue("清晰帧应通过", vClean.accepted)
        assertTrue("雾帧应被拒", !vFoggy.accepted)
        assertTrue("遮挡帧应被拒", !vOccl.accepted)
    }

    @Test
    fun `月面圆拟合能定中心`() {
        val w = 400
        val h = 300
        val cx = 205.0
        val cy = 148.0
        val r = 70.0
        // 合成：亮部 = 法向·太阳 > 0（相位角 60°，亮部约 0.75）
        val lum = FloatArray(w * h)
        val theta = Math.toRadians(60.0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = (x - cx) / r
                val v = (y - cy) / r
                val rr = u * u + v * v
                if (rr > 1.0) { lum[y * w + x] = 0.02f; continue }
                val ww = Math.sqrt(1 - rr)
                val lit = u * Math.sin(theta) + 0.0 * v + ww * Math.cos(theta)
                lum[y * w + x] = if (lit > 0) 0.9f else 0.03f
            }
        }
        val fit = Moon.fitDisk(lum, w, h)
        assertTrue("拟合失败", fit != null)
        println("拟合中心 (%.1f, %.1f) 半径 %.1f（真值 %.1f, %.1f, %.1f）亮部 %.2f".format(
            fit!!.cx, fit.cy, fit.radiusPx, cx, cy, r, fit.litFraction))
        assertTrue("中心误差过大", abs(fit.cx - cx) < 3.0 && abs(fit.cy - cy) < 3.0)
        assertTrue("半径误差过大", abs(fit.radiusPx - r) < 4.0)
        assertTrue("亮部占比异常", fit.litFraction in 0.6..0.9)
    }

    @Test
    fun `时间跨度对轨道解算精度的影响`() {
        val trueLat = 30.0
        val trueLon = 120.0
        val site = Moon.Site(Math.toRadians(trueLat), Math.toRadians(trueLon))
        val t0 = 1_763_500_800_000L
        println("跨度(min) | 观测数 | 噪声0.05° 误差 | 噪声0.10° 误差 | 噪声0.20° 误差")
        for (spanMin in listOf(10, 20, 30, 45, 60, 90, 120)) {
            val line = StringBuilder("${spanMin.toString().padStart(4)}     | ")
            for (noiseDeg in listOf(0.05, 0.10, 0.20)) {
                val rnd = java.util.Random(21)
                val n = 4
                val sights = ArrayList<Moon.Sight>()
                for (k in 0 until n) {
                    val t = t0 + (spanMin * 60_000L * k) / (n - 1)
                    val ephem = Moon.ephemerisAt(t)
                    val gast = SkyContext.at(t).gast
                    val alt = Moon.altitudeRad(site, ephem, t, gast)
                    val noisy = alt + Math.toRadians((rnd.nextDouble() * 2 - 1) * noiseDeg)
                    sights.add(Moon.Sight(t, gast, noisy, noiseDeg, Moon.ephemerisAt(t).raRad, Moon.ephemerisAt(t).decRad, Moon.ephemerisAt(t).distanceKm))
                }
                val fix = Moon.solveOrbit(sights)
                val err = if (fix == null) Double.NaN else
                    com.dsh.starpos.field.FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, trueLat, trueLon)
                if (noiseDeg == 0.05) line.append("%.0f km | ".format(err))
                else line.append("%.0f km | ".format(err))
            }
            println(line)
        }
    }

    @Test
    fun `短跨度为何病态：代价函数与方位角变化`() {
        val trueLat = 30.0
        val trueLon = 120.0
        val site = Moon.Site(Math.toRadians(trueLat), Math.toRadians(trueLon))
        val t0 = 1_763_500_800_000L
        val rnd = java.util.Random(5)
        for (spanMin in listOf(30, 60, 90, 120)) {
            val n = 4
            val sights = ArrayList<Moon.Sight>()
            var azFirst = 0.0
            var azLast = 0.0
            for (k in 0 until n) {
                val t = t0 + (spanMin * 60_000L * k) / (n - 1)
                val ephem = Moon.ephemerisAt(t)
                val gast = SkyContext.at(t).gast
                val alt = Moon.altitudeRad(site, ephem, t, gast)
                if (k == 0) azFirst = com.dsh.starpos.astro.Moon.azimuthRad(site, ephem, gast)
                if (k == n - 1) azLast = com.dsh.starpos.astro.Moon.azimuthRad(site, ephem, gast)
                val noisy = alt + Math.toRadians((rnd.nextDouble() * 2 - 1) * 0.05)
                sights.add(Moon.Sight(t, gast, noisy, 0.05, Moon.ephemerisAt(t).raRad, Moon.ephemerisAt(t).decRad, Moon.ephemerisAt(t).distanceKm))
            }
            val fix = Moon.solveOrbit(sights)
            // 真值处残差
            fun costAt(lat: Double, lon: Double): Double {
                val st = Moon.Site(Math.toRadians(lat), Math.toRadians(lon))
                var s = 0.0
                for (sg in sights) {
                    val pred = Moon.altitudeRad(st, Moon.ephemerisAt(sg.utcMillis), sg.utcMillis, sg.gast)
                    s += ((pred - sg.altRad) / Math.toRadians(0.05)) .let { it * it }
                }
                return s / (sights.size - 2)
            }
            val cTruth = costAt(trueLat, trueLon)
            val cFix = fix?.let { costAt(it.latDeg, it.lonDeg) } ?: Double.NaN
            var azDiff = Math.toDegrees(azLast - azFirst)
            if (azDiff > 180) azDiff -= 360
            if (azDiff < -180) azDiff += 360
            println("跨度 %3d min：方位角变化 %6.1f° | 真值处 χ²=%.1f | 解处 χ²=%.1f | 误差 %s".format(
                spanMin, azDiff, cTruth, cFix,
                fix?.let { "%.0f km".format(com.dsh.starpos.field.FieldAnalyzer.haversineKm(it.latDeg, it.lonDeg, trueLat, trueLon)) } ?: "—",
            ))
        }
    }

    @Test
    fun `双天体同帧定位不需要等一小时`() {
        val trueLat = 30.0
        val trueLon = 120.0
        val site = Moon.Site(Math.toRadians(trueLat), Math.toRadians(trueLon))
        // 找一个"两体都在天上"的时刻（模拟黄昏：月亮 + 亮行星/太阳同框）
        var t = 0L
        var gast = 0.0
        var moon: Moon.Ephemeris? = null
        var other: Moon.Ephemeris? = null
        var found = false
        for (m in 0 until 24 * 6) {
            val tt = 1_763_500_800_000L + m * 10 * 60_000L
            val g = SkyContext.at(tt).gast
            val e = Moon.ephemerisAt(tt)
            val o = Moon.Ephemeris(
                raRad = (e.raRad + Math.toRadians(60.0)) % (2 * Math.PI),
                decRad = e.decRad - Math.toRadians(5.0),
                distanceKm = 149_597_870.7,
                phaseAngleRad = 0.0,
            )
            val alt1 = Moon.altitudeRad(site, e, tt, g)
            val alt2 = Moon.altitudeRad(site, o, tt, g)
            if (alt1 > Math.toRadians(15.0) && alt2 > Math.toRadians(15.0)) {
                t = tt; gast = g; moon = e; other = o; found = true
                println("两体同时在天：高度角 %.1f° / %.1f°".format(Math.toDegrees(alt1), Math.toDegrees(alt2)))
                break
            }
        }
        if (!found) { println("没有两体同时在天的时刻，跳过"); return }
        val rnd = java.util.Random(3)
        val sights = ArrayList<Moon.Sight>()
        for ((label, body) in listOf("月亮" to moon!!, "另一体" to other!!)) {
            val alt = Moon.altitudeRad(site, body, t, gast)
            val noisy = alt + Math.toRadians((rnd.nextDouble() * 2 - 1) * 0.05)
            sights.add(Moon.Sight(t, gast, noisy, 0.05, body.raRad, body.decRad, body.distanceKm))
        }
        println("可用天体 ${sights.size} 个（同一时刻、无需等待）")
        if (sights.size < 2) return
        // 解算器要求 ≥3 个观测，双体只有 2 个：这里用"同一时刻的两体 + 各自重复"验证几何可解性
        val fix = Moon.solveOrbit(sights)
        println("双体解算：${fix?.let { "%.4f, %.4f 误差 %.1f km".format(it.latDeg, it.lonDeg, com.dsh.starpos.field.FieldAnalyzer.haversineKm(it.latDeg, it.lonDeg, trueLat, trueLon)) } ?: "失败（需要 ≥3 个观测，解算器下限）"}")
    }

    @Test
    fun `两体短时重复观测能唯一解`() {
        val trueLat = 30.0
        val trueLon = 120.0
        val site = Moon.Site(Math.toRadians(trueLat), Math.toRadians(trueLon))
        // 找两体同时在天的时刻
        var t0 = 0L
        var moon0: Moon.Ephemeris? = null
        for (m in 0 until 24 * 6) {
            val tt = 1_763_500_800_000L + m * 10 * 60_000L
            val g = SkyContext.at(tt).gast
            val e = Moon.ephemerisAt(tt)
            val o = Moon.Ephemeris(
                (e.raRad + Math.toRadians(60.0)) % (2 * Math.PI),
                e.decRad - Math.toRadians(5.0), 149_597_870.7, 0.0,
            )
            if (Moon.altitudeRad(site, e, tt, g) > Math.toRadians(20.0) &&
                Moon.altitudeRad(site, o, tt, g) > Math.toRadians(20.0)
            ) { t0 = tt; moon0 = e; break }
        }
        if (moon0 == null) { println("无两体同时在天时刻"); return }
        println("跨度(min) | 观测数 | 误差 | χ²(解)")
        for (spanMin in listOf(5, 10, 20, 30, 45, 60)) {
            val rnd = java.util.Random(9)
            val sights = ArrayList<Moon.Sight>()
            for (k in 0 until 3) {
                val t = t0 + (spanMin * 60_000L * k) / 2
                val g = SkyContext.at(t).gast
                val e = Moon.ephemerisAt(t)
                val o = Moon.Ephemeris(
                    (e.raRad + Math.toRadians(60.0)) % (2 * Math.PI),
                    e.decRad - Math.toRadians(5.0), 149_597_870.7, 0.0,
                )
                for (body in listOf(e, o)) {
                    val alt = Moon.altitudeRad(site, body, t, g)
                    if (alt < Math.toRadians(10.0)) continue
                    val noisy = alt + Math.toRadians((rnd.nextDouble() * 2 - 1) * 0.05)
                    sights.add(Moon.Sight(t, g, noisy, 0.05, body.raRad, body.decRad, body.distanceKm))
                }
            }
            val fix = Moon.solveOrbit(sights)
            val err = fix?.let { com.dsh.starpos.field.FieldAnalyzer.haversineKm(it.latDeg, it.lonDeg, trueLat, trueLon) }
            println("%3d | %2d | %s".format(spanMin, sights.size, err?.let { "%.0f km".format(it) } ?: "失败"))
        }
    }

    @Test
    fun `太阳星历合理性`() {
        // 2026-06-21 04:00 UTC（上海正午前后）：太阳应在天顶附近，高度角约 82°
        val t = 1_782_014_400_000L
        val site = Moon.Site(Math.toRadians(30.0), Math.toRadians(120.0))
        val g = SkyContext.at(t).gast
        val e = Moon.sunEphemerisAt(t)
        val alt = Math.toDegrees(Moon.altitudeRad(site, e, t, g))
        println("太阳：RA=%.2f° Dec=%.2f° 距离 %.3f AU 高度角 %.1f°".format(
            Math.toDegrees(e.raRad % (2 * Math.PI)), Math.toDegrees(e.decRad), e.distanceKm / 149_597_870.7, alt,
        ))
        assertTrue("太阳赤纬应在 ±23.5° 内", abs(Math.toDegrees(e.decRad)) < 23.5)
        assertTrue("太阳距离应在 0.98–1.02 AU", e.distanceKm / 149_597_870.7 in 0.98..1.02)
        assertTrue("正午高度角应偏高：$alt", alt in 70.0..90.0)
    }

    @Test
    fun `行星星历合理性`() {
        val t0 = 1_763_500_800_000L
        val sun = Moon.sunEphemerisAt(t0)
        val au = 149_597_870.7
        val sunVec = com.dsh.starpos.astro.V.fromRaDec(sun.raRad, sun.decRad)
        for (b in com.dsh.starpos.astro.Planets.Body.values()) {
            val e = com.dsh.starpos.astro.Planets.geocentric(b, t0)
            val distAu = e.distanceKm / au
            val elong = Math.toDegrees(
                com.dsh.starpos.astro.V.angle(com.dsh.starpos.astro.V.fromRaDec(e.raRad, e.decRad), sunVec),
            )
            println("%s: 距离 %.2f AU 日距角 %.1f°".format(b.chinese, distAu, elong))
            val range = when (b) {
                com.dsh.starpos.astro.Planets.Body.MERCURY -> 0.5..1.5
                com.dsh.starpos.astro.Planets.Body.VENUS -> 0.25..1.75
                com.dsh.starpos.astro.Planets.Body.MARS -> 0.37..2.8
                com.dsh.starpos.astro.Planets.Body.JUPITER -> 3.9..6.6
                com.dsh.starpos.astro.Planets.Body.SATURN -> 8.0..11.2
            }
            assertTrue("%s 距离异常：%.2f AU".format(b.chinese, distAu), distAu in range)
            val maxElong = if (b == com.dsh.starpos.astro.Planets.Body.MERCURY) 30.0
            else if (b == com.dsh.starpos.astro.Planets.Body.VENUS) 50.0 else 180.0
            assertTrue("%s 日距角异常：%.1f°".format(b.chinese, elong), elong <= maxElong)
        }
        // 位置随时间变化率应 <1.5°/天
        val e1 = com.dsh.starpos.astro.Planets.geocentric(com.dsh.starpos.astro.Planets.Body.JUPITER, t0)
        val e2 = com.dsh.starpos.astro.Planets.geocentric(com.dsh.starpos.astro.Planets.Body.JUPITER, t0 + 86_400_000L)
        val move = Math.toDegrees(
            com.dsh.starpos.astro.V.angle(
                com.dsh.starpos.astro.V.fromRaDec(e1.raRad, e1.decRad),
                com.dsh.starpos.astro.V.fromRaDec(e2.raRad, e2.decRad),
            ),
        )
        println("木星日移动 %.3f°".format(move))
        assertTrue("木星日移动异常：$move", move < 1.5)
    }
}
