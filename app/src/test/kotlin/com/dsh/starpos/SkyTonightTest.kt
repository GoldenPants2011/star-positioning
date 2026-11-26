package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import org.junit.Test

/** 今晚（2026-11-21）天空概况：月亮高度变化 + 月亮与行星的角距，用于选"低空月亮水面反射/双天体"时段。 */
class SkyTonightTest {

    private fun localMs(h: Int, m: Int): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        cal.set(2026, java.util.Calendar.NOVEMBER, 21, h, m, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `今晚月亮与行星`() {
        val site = Moon.Site(Math.toRadians(30.0), Math.toRadians(120.0))
        val bodies = listOf(
            Planets.Body.VENUS, Planets.Body.JUPITER, Planets.Body.MARS, Planets.Body.SATURN,
        )
        println("时间(本地)  月亮高度/方位 | 行星高度/方位（与月亮角距）")
        var t = localMs(17, 30)
        while (t <= localMs(23, 31)) {
            val now = t
            val gast = SkyContext.at(now).gast
            val moon = Moon.ephemerisAt(now)
            val moonAlt = Math.toDegrees(Moon.altitudeRad(site, moon, now, gast))
            val moonAz = Math.toDegrees(Moon.azimuthRad(site, moon, gast))
            val moonVec = V.fromRaDec(moon.raRad, moon.decRad)
            val sb = StringBuilder()
            for (b in bodies) {
                val ep = Planets.geocentric(b, now)
                val alt = Math.toDegrees(Moon.altitudeRad(site, ep, now, gast))
                val az = Math.toDegrees(Moon.azimuthRad(site, ep, gast))
                val sep = Math.toDegrees(V.angle(moonVec, V.fromRaDec(ep.raRad, ep.decRad)))
                if (alt > -2.0) sb.append(" %s %.0f°/%.0f°(距月 %.1f°)".format(b.chinese, alt, az, sep))
            }
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = now }
            println("%02d:%02d  月 %+.1f°/%.0f° |%s".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE),
                moonAlt, moonAz, sb.toString(),
            ))
            t += 20 * 60_000L
        }
    }

    @Test
    fun `明天下午太阳高度`() {
        val site = Moon.Site(Math.toRadians(30.0), Math.toRadians(120.0))
        var t = localMs(9, 0)
        while (t <= localMs(17, 1)) {
            val now = t
            val gast = SkyContext.at(now).gast
            val sun = Moon.sunEphemerisAt(now)
            val alt = Math.toDegrees(Moon.altitudeRad(site, sun, now, gast))
            val az = Math.toDegrees(Moon.azimuthRad(site, sun, gast))
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply { timeInMillis = now }
            println("%02d:%02d 太阳 %+.1f° / 方位 %.0f°".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), alt, az,
            ))
            t += 60 * 60_000L
        }
    }
}
