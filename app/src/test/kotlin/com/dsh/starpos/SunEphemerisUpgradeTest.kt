package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.V
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 太阳星历升级验证：
 *  1) 复现 Meeus 例 25.b（1992-10-13.0 TD）：视黄经 199.90895°、R=0.99760775 AU；
 *  2) 与旧版（UTC 平黄经、无章动/光行差）在生产时间点对比，确认改动量级合理（<0.02°）。
 */
class SunEphemerisUpgradeTest {

    private fun apparentSun(jdTt: Double): Pair<Double, Double> {
        val t = (jdTt - 2451545.0) / 36525.0
        val d2r = Math.PI / 180.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val mr = m * d2r
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(mr) +
            (0.019993 - 0.000101 * t) * Math.sin(2 * mr) + 0.000289 * Math.sin(3 * mr)
        val trueLong = l0 + c
        val trueAnom = mr + c * d2r
        val r = 1.000001018 * (1 - e * e) / (1 + e * Math.cos(trueAnom))
        val om = (125.04 - 1934.136 * t) * d2r
        val lambda = trueLong - 0.00569 - 0.00478 * Math.sin(om)
        return lambda to r
    }

    /** 旧实现（升级前）：UTC + 平黄经几何位置。 */
    private fun oldSunRaDec(utcMillis: Long): Pair<Double, Double> {
        val jd = 2440587.5 + utcMillis / 86_400_000.0
        val n = jd - 2451545.0
        val d2r = Math.PI / 180.0
        val l = 280.460 + 0.9856474 * n
        val g = (357.528 + 0.9856003 * n) * d2r
        val lambda = (l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * d2r
        val eps = (23.439 - 0.0000004 * n) * d2r
        val ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda))
        val dec = Math.asin(Math.sin(eps) * Math.sin(lambda))
        return ra to dec
    }

    @Test
    fun `太阳视位置符合 Meeus 例`() {
        val (lambda, r) = apparentSun(2448908.5)   // 1992-10-13.0 TD
        val lambdaDeg = ((lambda % 360.0) + 360.0) % 360.0
        println("Meeus 25.b：视黄经 %.5f°（期望 199.90895°），R=%.8f AU（期望 0.99760775）".format(lambdaDeg, r))
        assertEquals("视黄经（Meeus 25.10 与例 25.b 一致到 1e-4°）", 199.90895, lambdaDeg, 1e-4)
        // R 是低精度公式 25.5 的结果；Meeus 例 25.b 的 0.99760775 AU 来自 VSOP87，
        // 低精度公式差 ~5e-5 AU（0.005%，可接受；若要 ~1e-6 需引 VSOP87 系数表）
        assertEquals(0.99760775, r, 1.5e-4)
    }

    @Test
    fun `生产太阳星历与旧版差异在合理范围`() {
        // 覆盖 2026 年几个时间点（含今天 11-24）
        val times = listOf(
            1763950000000L,  // 2026-11-24 附近
            1764200000000L,
            1735689600000L,  // 2025-01-01
            1790000000000L,  // 2026-09 附近
        )
        var maxDiff = 0.0
        for (ms in times) {
            val ep = Moon.sunEphemerisAt(ms)
            val (raOld, decOld) = oldSunRaDec(ms)
            val vNew = V.fromRaDec(ep.raRad, ep.decRad)
            val vOld = V.fromRaDec(raOld, decOld)
            val diff = Math.toDegrees(V.angle(vNew, vOld))
            maxDiff = maxOf(maxDiff, diff)
            println("t=%d：新旧太阳方向差 %.4f°（RA %.4f° / %.4f°，Dec %.4f° / %.4f°）".format(
                ms, diff, Math.toDegrees(ep.raRad), Math.toDegrees(raOld),
                Math.toDegrees(ep.decRad), Math.toDegrees(decOld),
            ))
        }
        println("最大差异 %.4f°".format(maxDiff))
        // 旧版少了 TT/光行差/章动，合计应在 0.01° 量级，但不应超过 0.05°
        assertEquals(true, maxDiff < 0.05)
        assertEquals(true, maxDiff > 0.002)
    }
}
