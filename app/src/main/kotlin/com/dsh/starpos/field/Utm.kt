package com.dsh.starpos.field

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.pow

/**
 * WGS84 经纬度 ↔ UTM（高斯-克吕格）投影。
 * 结果页用来显示"平面坐标"，纯本地计算，不需要地图/网络。
 */
object Utm {

    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val K0 = 0.9996

    data class Point(
        val zone: Int,
        val north: Boolean,
        /** 东伪坐标（含 500 km 假东）。 */
        val easting: Double,
        /** 北伪坐标（南半球含 10,000 km 假北）。 */
        val northing: Double,
        val latitudeBand: Char,
    )

    fun fromLatLon(latDeg: Double, lonDeg: Double): Point {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        var zone = floor((lonDeg + 180.0) / 6.0).toInt() + 1
        if (zone < 1) zone = 1
        if (zone > 60) zone = 60
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val n = A / sqrt(1 - E2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = E2 / (1 - E2) * cos(lat).pow(2)
        val aa = cos(lat) * (lon - lon0)
        val m = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * lat -
                (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * lat) +
                (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * lat) -
                (35 * E2 * E2 * E2 / 3072) * sin(6 * lat)
            )
        val easting = K0 * n * (
            aa + (1 - t + c) * aa.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * (E2 / (1 - E2))) * aa.pow(5) / 120
            ) + 500_000.0
        var northing = K0 * (
            m + n * tan(lat) * (
                aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * (E2 / (1 - E2))) * aa.pow(6) / 720
                )
            )
        val north = latDeg >= 0
        if (!north) northing += 10_000_000.0
        return Point(zone, north, easting, northing, latitudeBand(latDeg))
    }

    /** UTM 纬度带字母（MGRS 标准，I/O 不用）。 */
    fun latitudeBand(latDeg: Double): Char {
        val bands = "CDEFGHJKLMNPQRSTUVWX"
        val idx = ((latDeg + 80.0) / 8.0).toInt().coerceIn(0, bands.length - 1)
        return bands[idx]
    }

    fun format(p: Point): String =
        "%d%c %.0f E %.0f N".format(p.zone, if (p.north) 'N' else 'S', p.easting, p.northing)
}
