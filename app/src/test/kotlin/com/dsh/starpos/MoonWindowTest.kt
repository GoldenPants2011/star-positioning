// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/** 今夜月亮高度表：找"直射+反射同框"（月亮高度 < 27°）的水镜窗口。 */
class MoonWindowTest {
    @Test
    fun `今夜月亮高度窗口`() {
        val lat = 30.0
        val lon = 120.0
        val site = Moon.Site(Math.toRadians(lat), Math.toRadians(lon))
        val zone = ZoneId.of("Asia/Shanghai")
        println("时间   高度   方位   光照")
        for (minute in 0..(8 * 60) step 30) {
            val t = LocalDateTime.of(2026, 11, 22, 18, 0).plusMinutes(minute.toLong())
            val ms = t.atZone(zone).toInstant().toEpochMilli()
            val sc = SkyContext.at(ms)
            val m = Moon.ephemerisAt(ms)
            val alt = Math.toDegrees(Moon.altitudeRad(site, m, ms, sc.gast))
            val az = Math.toDegrees(Moon.azimuthRad(site, m, sc.gast))
            val illum = (1 + Math.cos(m.phaseAngleRad)) / 2 * 100
            val mark = when {
                alt < 0 -> "（地平线下）"
                alt < 27 -> "★ 同框窗口"
                alt < 40 -> "超广角可同框"
                else -> "只能拍反射"
            }
            println("%02d:%02d  %+6.1f°  %5.0f°  %3.0f%%  %s".format(
                t.hour, t.minute, alt, az, illum, mark,
            ))
        }
    }
}
