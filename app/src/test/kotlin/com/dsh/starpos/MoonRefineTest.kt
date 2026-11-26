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
 * 月亮多历元定位（**限定在近似位置附近搜索**）。
 *
 * 现实架构：先用星图解算给出 T1 粗位置（≤50 km），再拿月亮多历元高度把位置精化。
 * 天文定位本来就是"从推算位置附近定位"，全地球盲搜会落到镜像解/伪解上（已实测：2993/6296 km）。
 */
class MoonRefineTest {

    private data class Sight(val utc: Long, val gast: Double, val alt: Double, val ra: Double, val dec: Double, val dist: Double, val s: Int)

    @Test
    fun `月亮多历元_在近似位置附近精化`() {
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val names = listOf(
            "session-20261120-202658.zip",
            "session-20261120-214304.zip",
            "session-20261120-223707.zip",
        )
        val sights = ArrayList<Sight>()
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
                    val e = Moon.ephemerisAt(m.wallClockUtcMs)
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8)
                    val c = if (fit != null && fit.radiusPx > 1.0) fit.cx to fit.cy
                    else peakCentroid(f.luminance, f.width, f.height, 12) ?: continue
                    val dir = cam.unproject(c.first, c.second)
                    val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                        .coerceIn(-1.0, 1.0)
                    val altApp = Math.PI / 2 - acos(cosZ)
                    val altTrue = altApp - Moon.refractionRad(altApp)
                    sights.add(Sight(m.wallClockUtcMs, SkyContext.at(m.wallClockUtcMs).gast, altTrue, e.raRad, e.decRad, e.distanceKm, si))
                }
            }
        }
        val tr = truth ?: return
        println("真值：%.5f,%.5f，观测 %d 次".format(tr.first, tr.second, sights.size))

        // 诊断用：如果"垂直误差已被校正"（用真值把每会话偏置扣掉），位置能到多少
        run {
            val site = Moon.Site(Math.toRadians(tr.first), Math.toRadians(tr.second))
            val bias = HashMap<Int, Double>()
            for ((si, list) in sights.groupBy { it.s }) {
                bias[si] = list.map { s ->
                    s.alt - Moon.altitudeRad(site, Moon.Ephemeris(s.ra, s.dec, s.dist, 0.0), s.utc, s.gast)
                }.average()
            }
            val corrected = sights.map { it.copy(alt = it.alt - bias[it.s]!!) }
            var bLa = tr.first; var bLo = tr.second; var bSse = Double.MAX_VALUE
            var la = tr.first - 2.0
            while (la <= tr.first + 2.0) {
                var lo = tr.second - 2.5
                while (lo <= tr.second + 2.5) {
                    val sse = corrected.sumOf { s ->
                        val d = s.alt - Moon.altitudeRad(Moon.Site(Math.toRadians(la), Math.toRadians(lo)), Moon.Ephemeris(s.ra, s.dec, s.dist, 0.0), s.utc, s.gast)
                        d * d
                    }
                    if (sse < bSse) { bSse = sse; bLa = la; bLo = lo }
                    lo += 0.5
                }
                la += 0.5
            }
            println("（诊断）扣除各会话真值偏置后：%.5f,%.5f → 误差 **%.1f km**（rms %.3f°）".format(
                bLa, bLo, haversineKm(bLa, bLo, tr.first, tr.second),
                Math.toDegrees(Math.sqrt(bSse / corrected.size))))
        }

        for (mode in listOf("公共偏置", "每会话偏置")) {
            // 以真值 ±200 km 作为"近似位置"搜索窗（模拟 T1 粗位置）
            var bestLat = tr.first
            var bestLon = tr.second
            var bestSse = Double.MAX_VALUE
            val spanLat = 2.0        // 约 ±220 km
            val spanLon = 2.5
            var la = tr.first - spanLat
            while (la <= tr.first + spanLat) {
                var lo = tr.second - spanLon
                while (lo <= tr.second + spanLon) {
                    val sse = sse(la, lo, sights, mode)
                    if (sse < bestSse) { bestSse = sse; bestLat = la; bestLon = lo }
                    lo += 0.5
                }
                la += 0.5
            }
            var st = 0.25
            while (st > 0.002) {
                var imp = true
                while (imp) {
                    imp = false
                    for (dLat in listOf(-st, 0.0, st)) for (dLon in listOf(-st, 0.0, st)) {
                        val sse = sse(bestLat + dLat, bestLon + dLon, sights, mode)
                        if (sse < bestSse) { bestSse = sse; bestLat += dLat; bestLon += dLon; imp = true }
                    }
                }
                st /= 2.0
            }
            println(
                "%s：%.5f,%.5f（rms %.3f°）→ 误差 **%.1f km**".format(
                    mode, bestLat, bestLon, Math.toDegrees(Math.sqrt(bestSse / sights.size)),
                    haversineKm(bestLat, bestLon, tr.first, tr.second),
                ),
            )
        }
    }

    private fun sse(lat: Double, lon: Double, sights: List<Sight>, mode: String): Double {
        val site = Moon.Site(Math.toRadians(lat), Math.toRadians(lon))
        fun pred(s: Sight) = Moon.altitudeRad(site, Moon.Ephemeris(s.ra, s.dec, s.dist, 0.0), s.utc, s.gast)
        if (mode == "公共偏置") {
            var sum = 0.0
            for (s in sights) sum += s.alt - pred(s)
            val off = sum / sights.size
            var sse = 0.0
            for (s in sights) { val d = (s.alt - pred(s)) - off; sse += d * d }
            return sse
        }
        var sse = 0.0
        for ((_, list) in sights.groupBy { it.s }) {
            var sum = 0.0
            for (s in list) sum += s.alt - pred(s)
            val off = sum / list.size
            for (s in list) { val d = (s.alt - pred(s)) - off; sse += d * d }
        }
        return sse
    }

    private fun peakCentroid(lum: FloatArray, w: Int, h: Int, r: Int): Pair<Double, Double>? {
        var best = -1f; var bx = 0; var by = 0
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
