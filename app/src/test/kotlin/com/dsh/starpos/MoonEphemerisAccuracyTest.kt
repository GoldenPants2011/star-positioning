package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月亮星历精度验证：
 * 1) Meeus《Astronomical Algorithms》第 47 章官方算例（1992-04-12 0h TD）；
 * 2) 旧截断版 vs 新完整版在本次观测时刻的差异。
 */
class MoonEphemerisAccuracyTest {

    /** JD(UTC) → 毫秒时间戳（Unix epoch 起点 JD=2440587.5）。 */
    private fun millis(jdUtc: Double): Long = ((jdUtc - 2440587.5) * 86_400_000.0).toLong()

    @Test
    fun `Meeus 第47章官方算例`() {
        // 1992-04-12 0h TD：JD_TT = 2448724.5；ΔT(1992) ≈ 58.6 s
        val jdTt = 2448724.5
        val utc = jdTt - 58.6 / 86_400.0
        val e = Moon.ephemerisAt(millis(utc))
        // 直接与官方黄经/黄纬/距离对比需要黄道坐标；这里用赤道坐标对照官方换算值
        // 官方：λ=133.162655°, β=-3.229126°, Δ=368409.7 km
        // 官方算例（PyMeeus/Meeus）：λ=133.162655°, β=-3.229126°, Δ=368409.7 km
        // 其等价的视赤道坐标：ra=134.688469°, dec=13.768367°
        val raOfficial = Math.toRadians(134.688469)
        val decOfficial = Math.toRadians(13.768367)
        val distExpected = 368409.7
        val dRaArcsec = Math.toDegrees(angDiff(e.raRad, raOfficial)) * 3600.0
        val dDecArcsec = Math.toDegrees(e.decRad - decOfficial) * 3600.0
        val dDistKm = e.distanceKm - distExpected
        println("Meeus 算例：ΔRA=%.1f″，ΔDec=%.1f″，Δ距离=%.1f km".format(dRaArcsec, dDecArcsec, dDistKm))
        println("  RA=%.6f°（期望 134.688469°） Dec=%.6f°（期望 13.768367°） 距离=%.1f km".format(
            Math.toDegrees(e.raRad), Math.toDegrees(e.decRad), e.distanceKm))
        assertTrue("赤经误差应 < 10″（含 ΔT 波动）", abs(dRaArcsec) < 10.0)
        assertTrue("赤纬误差应 < 30″", abs(dDecArcsec) < 30.0)
        assertTrue("距离误差应 < 5 km", abs(dDistKm) < 5.0)
    }

    @Test
    fun `新旧实现差异`() {
        val times = longArrayOf(
            1795187225000L, // 2026-11-20 20:27
            1795191789000L, // 21:43
            1795195031000L, // 22:37
        )
        for (t in times) {
            val n = Moon.ephemerisAt(t)
            val o = Moon.ephemerisAtLegacy(t)
            val dAng = Math.toDegrees(angDiff(n.raRad, o.raRad) * Math.cos(n.decRad))
            val dDec = Math.toDegrees(n.decRad - o.decRad)
            println("新-旧：ΔRA(含cosδ)=%.1f″，ΔDec=%.1f″，Δ距离=%.1f km".format(
                dAng * 3600.0, dDec * 3600.0, n.distanceKm - o.distanceKm))
        }
    }

    private fun angDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        return d
    }
}
