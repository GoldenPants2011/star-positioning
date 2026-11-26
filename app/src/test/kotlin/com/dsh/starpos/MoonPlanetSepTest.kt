package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.V
import org.junit.Test

/** 诊断：今晚三次月亮观测时，行星与月亮的角距（判断是否有"第二体"可同帧利用）。 */
class MoonPlanetSepTest {
    @Test
    fun 月亮与行星角距() {
        val times = listOf(
            "session-20261120-202658" to 1795187225000L,
            "session-20261120-214304" to 1795191789000L,
            "session-20261120-223707" to 1795195031000L,
        )
        for ((name, t) in times) {
            val m = Moon.ephemerisAt(t)
            val mv = V.fromRaDec(m.raRad, m.decRad)
            val parts = Planets.all(t).mapNotNull { (body, e) ->
                val sep = Math.toDegrees(V.angle(mv, V.fromRaDec(e.raRad, e.decRad)))
                if (sep < 60.0) "%s %.1f°".format(body.chinese, sep) else null
            }
            println("%s：月亮赤经/赤纬 %.1f°/%.1f°，同天区天体：%s".format(
                name, Math.toDegrees(m.raRad), Math.toDegrees(m.decRad), parts.joinToString("，").ifEmpty { "无" }))
        }
    }
}
