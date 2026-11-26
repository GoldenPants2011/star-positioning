// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 行星地心位置（Meeus 33 章 J2000 平均轨道根数 + 开普勒方程）。
 *
 * 用途：
 *  1. 黄昏/黎明"月亮 + 亮行星同框"两体定位（见 Moon.Sight 的多天体支持）；
 *  2. 预览里给识别到的亮天体注明名字（金星/木星/火星/土星）。
 *
 * 精度：无摄动项，内行星约 1–3′、外行星约 5–20′。**标注够用**，
 * 做定位时会把这份误差计入观测 σ；要更准需上 VSOP87 截断。
 */
object Planets {

    enum class Body(val label: String, val chinese: String) {
        MERCURY("Mercury", "水星"),
        VENUS("Venus", "金星"),
        MARS("Mars", "火星"),
        JUPITER("Jupiter", "木星"),
        SATURN("Saturn", "土星"),
    }

    /** J2000 平均根数（a[AU], e, i[deg], L[deg], peri[deg], node[deg]）与每儒略世纪变率。 */
    private class Elements(
        val a: Double, val aDot: Double,
        val e: Double, val eDot: Double,
        val i: Double, val iDot: Double,
        val l: Double, val lDot: Double,
        val w: Double, val wDot: Double,
        val n: Double, val nDot: Double,
    )

    private val elements = mapOf(
        Body.MERCURY to Elements(0.38709831, 0.0, 0.20563175, 0.000020406, 7.00498625, -0.00594749,
            252.25032350, 149472.67411175, 77.45611904, 0.15886423, 48.33076593, -0.12534081),
        Body.VENUS to Elements(0.72333566, 0.00000390, 0.00677192, -0.000047765, 3.39466189, -0.00097141,
            181.97909950, 58517.81538729, 131.56370300, 0.00140274, 76.67984255, -0.27769418),
        Body.MARS to Elements(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84972648, -0.00814723,
            355.44656743, 19140.30268499, 336.04084, 0.44390176, 49.55809321, -0.2949846),
        Body.JUPITER to Elements(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30327024, -0.00198725,
            34.33479152, 3034.90371757, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
        Body.SATURN to Elements(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48887875, 0.00193609,
            50.07571329, 1222.11494724, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
    )

    /** 地球日心位置（AU，J2000 黄道坐标），由太阳地心星历反推。 */
    private fun earthHelio(utcMillis: Long): DoubleArray {
        val sun = Moon.sunEphemerisAt(utcMillis)
        val d2r = Math.PI / 180.0
        // 太阳地心赤道坐标 → 黄道坐标
        val eps = 23.4392911 * d2r
        val x = sun.distanceKm * cos(sun.decRad) * cos(sun.raRad)
        val y = sun.distanceKm * (cos(sun.decRad) * sin(sun.raRad) * cos(eps) + sin(sun.decRad) * sin(eps))
        val z = sun.distanceKm * (sin(sun.decRad) * cos(eps) - cos(sun.decRad) * sin(sun.raRad) * sin(eps))
        // 地球 = -太阳地心（转成 AU）
        val au = 149_597_870.7
        return doubleArrayOf(-x / au, -y / au, -z / au)
    }

    /** 行星地心位置（赤道坐标，J2000）。 */
    fun geocentric(body: Body, utcMillis: Long): Moon.Ephemeris {
        val el = elements[body] ?: error("未实现：$body")
        val jd = 2440587.5 + utcMillis / 86_400_000.0
        val t = (jd - 2451545.0) / 36525.0
        val d2r = Math.PI / 180.0

        val a = el.a + el.aDot * t
        val e = el.e + el.eDot * t
        val inc = (el.i + el.iDot * t) * d2r
        val l = (el.l + el.lDot * t) * d2r
        val w = (el.w + el.wDot * t) * d2r
        val node = (el.n + el.nDot * t) * d2r
        val meanAnom = l - w
        // 开普勒方程
        var ea = meanAnom
        repeat(12) { ea -= (ea - e * sin(ea) - meanAnom) / (1 - e * cos(ea)) }
        val r = a * (1 - e * cos(ea))
        val nu = atan2(sqrt(1 - e * e) * sin(ea), cos(ea) - e)
        val u = nu + w - node
        // 轨道面 → 黄道
        val cosU = cos(u); val sinU = sin(u)
        val cosO = cos(node); val sinO = sin(node)
        val cosI = cos(inc); val sinI = sin(inc)
        val xh = r * (cosO * cosU - sinO * sinU * cosI)
        val yh = r * (sinO * cosU + cosO * sinU * cosI)
        val zh = r * (sinU * sinI)
        val earth = earthHelio(utcMillis)
        val gx = xh - earth[0]
        val gy = yh - earth[1]
        val gz = zh - earth[2]
        val distAu = sqrt(gx * gx + gy * gy + gz * gz)
        val lon = atan2(gy, gx)
        val lat = asin(gz / distAu)
        // 黄道 → 赤道（平黄赤交角）
        val eps = 23.4392911 * d2r
        val dec = asin(sin(lat) * cos(eps) + cos(lat) * sin(eps) * sin(lon))
        val ra = atan2(sin(lon) * cos(eps) - lat.let { sin(it) } / cos(lat) * sin(eps), cos(lon))
        return Moon.Ephemeris(
            ((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI),
            dec, distAu * 149_597_870.7, 0.0,
        )
    }

    /** 所有亮行星在当前时刻的地心位置。 */
    fun all(utcMillis: Long): List<Pair<Body, Moon.Ephemeris>> =
        Body.values().map { it to geocentric(it, utcMillis) }
}
