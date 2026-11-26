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
 * 联合定位（含"每会话一个垂直偏置"的 profile-likelihood 拟合）。
 *
 * 动机：三次观测的垂直方向系统误差不同（-0.07° / +0.38° / +0.60°），
 * 直接拿 Moon.solveOrbit 会被这些偏置拉偏。这里把"每会话一个常数偏置"一起拟合掉，
 * 看位置本身能不能定出来。
 */
class MoonJointFixTest {

    private data class Sighting(val utc: Long, val gast: Double, val altRad: Double, val ra: Double, val dec: Double, val distKm: Double, val session: Int)

    @Test
    fun `三组月光联合定位_带会话偏置`() {
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val names = listOf(
            "session-20261120-202658.zip",
            "session-20261120-214304.zip",
            "session-20261120-223707.zip",
        )
        val sightings = ArrayList<Sighting>()
        var truth: Pair<Double, Double>? = null
        names.forEachIndexed { si, name ->
            val zip = File("../field-inbox/" + name)
            if (!zip.isFile) return@forEachIndexed
            BundleReader.open(zip).use { reader ->
                val t = reader.readGnss().lastOrNull() ?: return@use
                if (truth == null) truth = t.lat to t.lon
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                for (m in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(
                        imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    if (!g.valid) continue
                    val ephem = Moon.ephemerisAt(m.wallClockUtcMs)
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8)
                    val c = if (fit != null && fit.radiusPx > 1.0) {
                        fit.cx to fit.cy
                    } else {
                        peakCentroid(f.luminance, f.width, f.height, 12)
                    } ?: continue
                    val dir = cam.unproject(c.first, c.second)
                    val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                        .coerceIn(-1.0, 1.0)
                    val altApp = Math.PI / 2 - acos(cosZ)
                    val altTrue = altApp - Moon.refractionRad(altApp)
                    sightings.add(
                        Sighting(
                            m.wallClockUtcMs, SkyContext.at(m.wallClockUtcMs).gast, altTrue,
                            ephem.raRad, ephem.decRad, ephem.distanceKm, si,
                        ),
                    )
                }
            }
        }
        println("观测 ${sightings.size} 次，会话数 ${sightings.map { it.session }.distinct().size}")
        // 全局粗网格（2°），每个格点内解析消去"每会话偏置"
        var bestLat = 0.0
        var bestLon = 0.0
        var bestSse = Double.MAX_VALUE
        var latg = -84.0
        while (latg <= 84.0) {
            var long = -180.0
            while (long < 180.0) {
                val sse = profileSse(latg, long, sightings)
                if (sse < bestSse) { bestSse = sse; bestLat = latg; bestLon = long }
                long += 2.0
            }
            latg += 2.0
        }
        // 局部细化（0.05° 步长）
        var step = 1.0
        while (step > 0.004) {
            var improved = true
            while (improved) {
                improved = false
                for (dLat in listOf(-step, 0.0, step)) for (dLon in listOf(-step, 0.0, step)) {
                    val la = bestLat + dLat
                    val lo = bestLon + dLon
                    val sse = profileSse(la, lo, sightings)
                    if (sse < bestSse) { bestSse = sse; bestLat = la; bestLon = lo; improved = true }
                }
            }
            step /= 2.0
        }
        val offs = sessionOffsets(bestLat, bestLon, sightings)
        val rmsDeg = Math.toDegrees(Math.sqrt(bestSse / sightings.size))
        println("拟合位置：%.4f, %.4f（残差 rms %.3f°）".format(bestLat, bestLon, rmsDeg))
        offs.forEachIndexed { i, o -> println("  会话 $i 垂直偏置：%+.3f°".format(Math.toDegrees(o))) }
        truth?.let {
            println("与真值差（每会话偏置）：**%.1f km**".format(haversineKm(bestLat, bestLon, it.first, it.second)))
        }

        // 对照：全夜共用一个垂直偏置（假设手机指向基本不变）
        var bLat = 0.0; var bLon = 0.0; var bSse = Double.MAX_VALUE
        var la = -84.0
        while (la <= 84.0) {
            var lo = -180.0
            while (lo < 180.0) {
                val sse = commonOffsetSse(la, lo, sightings)
                if (sse < bSse) { bSse = sse; bLat = la; bLon = lo }
                lo += 2.0
            }
            la += 2.0
        }
        var st = 1.0
        while (st > 0.004) {
            var imp = true
            while (imp) {
                imp = false
                for (dLat in listOf(-st, 0.0, st)) for (dLon in listOf(-st, 0.0, st)) {
                    val sse = commonOffsetSse(bLat + dLat, bLon + dLon, sightings)
                    if (sse < bSse) { bSse = sse; bLat += dLat; bLon += dLon; imp = true }
                }
            }
            st /= 2.0
        }
        println("单一公共偏置拟合：%.4f, %.4f（rms %.3f°）".format(bLat, bLon,
            Math.toDegrees(Math.sqrt(bSse / sightings.size))))
        truth?.let {
            println("与真值差（公共偏置）：**%.1f km**".format(haversineKm(bLat, bLon, it.first, it.second)))
        }
    }

    /** 全夜共用 1 个垂直偏置（解析消去）。 */
    private fun commonOffsetSse(latDeg: Double, lonDeg: Double, sightings: List<Sighting>): Double {
        val site = Moon.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
        val resid = DoubleArray(sightings.size)
        var sum = 0.0
        sightings.forEachIndexed { i, s ->
            val e = Moon.Ephemeris(s.ra, s.dec, s.distKm, 0.0)
            resid[i] = s.altRad - Moon.altitudeRad(site, e, s.utc, s.gast)
            sum += resid[i]
        }
        val off = sum / sightings.size
        var sse = 0.0
        for (r in resid) { val d = r - off; sse += d * d }
        return sse
    }

    /** 给定位置，消除每会话偏置后的残差平方和。 */
    private fun profileSse(latDeg: Double, lonDeg: Double, sightings: List<Sighting>): Double {
        val site = Moon.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
        val bySession = sightings.groupBy { it.session }
        var sse = 0.0
        for ((_, list) in bySession) {
            var sum = 0.0
            val resid = DoubleArray(list.size)
            list.forEachIndexed { i, s ->
                val e = Moon.Ephemeris(s.ra, s.dec, s.distKm, 0.0)
                val pred = Moon.altitudeRad(site, e, s.utc, s.gast)
                resid[i] = s.altRad - pred
                sum += resid[i]
            }
            val off = sum / list.size
            for (r in resid) {
                val d = r - off
                sse += d * d
            }
        }
        return sse
    }

    private fun sessionOffsets(latDeg: Double, lonDeg: Double, sightings: List<Sighting>): List<Double> {
        val site = Moon.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
        return sightings.groupBy { it.session }.toSortedMap().values.map { list ->
            list.map { s ->
                val e = Moon.Ephemeris(s.ra, s.dec, s.distKm, 0.0)
                s.altRad - Moon.altitudeRad(site, e, s.utc, s.gast)
            }.average()
        }
    }

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
