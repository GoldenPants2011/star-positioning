package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * 月亮 + 土星 双体定位（今晚土星距月亮只有 6.2–6.5°，同帧可见）。
 *
 * 双体的价值：两者的"高度角随位置变化"不同（月亮有 ~0.95° 的地平视差，土星没有），
 * 因此即使有共同的垂直偏置，也能把位置和偏置分开。
 */
class MoonSaturnTest {

    private data class Sight(val utc: Long, val gast: Double, val alt: Double, val ra: Double, val dec: Double, val dist: Double, val s: Int)

    @Test
    fun `月亮加土星双体定位`() {
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
                    val utc = m.wallClockUtcMs
                    val gast = SkyContext.at(utc).gast
                    val moonE = Moon.ephemerisAt(utc)
                    val satE = Planets.geocentric(Planets.Body.SATURN, utc)
                    // 月心
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8)
                    val mc = if (fit != null && fit.radiusPx > 1.0) fit.cx to fit.cy
                    else peakCentroid(f.luminance, f.width, f.height, 12) ?: continue
                    val mDir = cam.unproject(mc.first, mc.second)
                    val moonAlt = altOf(mDir, g.upCam)
                    // 预期月-土角距（地心）
                    val expectedSep = Math.toDegrees(
                        V.angle(V.fromRaDec(moonE.raRad, moonE.decRad), V.fromRaDec(satE.raRad, satE.decRad)),
                    )
                    // 找候选亮点：与月亮角距接近 expectedSep 的亮斑
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 8.0)
                    var bestScore = Double.MAX_VALUE
                    var bestSat: Pair<Double, Double>? = null
                    for (d in det.detections) {
                        if (d.saturated) continue
                        if (d.area > 400) continue
                        val dir = cam.unproject(d.x, d.y)
                        val sep = Math.toDegrees(acos(
                            (dir[0] * mDir[0] + dir[1] * mDir[1] + dir[2] * mDir[2]).coerceIn(-1.0, 1.0),
                        ))
                        if (sep < 3.0) continue          // 月面本身/光晕
                        val err = Math.abs(sep - expectedSep)
                        if (err < 0.6 && d.peak > 0.2 && err < bestScore) {
                            bestScore = err
                            bestSat = d.x to d.y
                        }
                    }
                    println(
                        "会话 %d 帧 %d: 月高度 %.2f°，月-土预期 %.2f°，找到土星=%s".format(
                            si, m.index, Math.toDegrees(moonAlt), expectedSep,
                            if (bestSat == null) "否" else "是(%.2f° 误差)".format(bestScore),
                        ),
                    )
                    sights.add(Sight(utc, gast, moonAlt, moonE.raRad, moonE.decRad, moonE.distanceKm, si))
                    if (bestSat != null) {
                        val sDir = cam.unproject(bestSat.first, bestSat.second)
                        sights.add(Sight(utc, gast, altOf(sDir, g.upCam), satE.raRad, satE.decRad, 1.0e9, si))
                    }
                }
            }
        }
        val tr = truth ?: return
        println("总数：月亮 %d 次，土星 %d 次".format(
            sights.count { it.dist < 1.0e8 }, sights.count { it.dist >= 1.0e8 }))
        if (sights.none { it.dist >= 1.0e8 }) { println("没有土星观测，双体不成立"); return }
        // 近似位置 ±200 km 内找最优（含每会话公共偏置）
        var bLa = tr.first
        var bLo = tr.second
        var bSse = Double.MAX_VALUE
        var la = tr.first - 2.0
        while (la <= tr.first + 2.0) {
            var lo = tr.second - 2.5
            while (lo <= tr.second + 2.5) {
                val s = sse(la, lo, sights)
                if (s < bSse) { bSse = s; bLa = la; bLo = lo }
                lo += 0.4
            }
            la += 0.4
        }
        var st = 0.2
        while (st > 0.002) {
            var imp = true
            while (imp) {
                imp = false
                for (dLat in listOf(-st, 0.0, st)) for (dLon in listOf(-st, 0.0, st)) {
                    val s = sse(bLa + dLat, bLo + dLon, sights)
                    if (s < bSse) { bSse = s; bLa += dLat; bLo += dLon; imp = true }
                }
            }
            st /= 2.0
        }
        println("双体解算：%.5f,%.5f（rms %.3f°）→ 误差 **%.1f km**".format(
            bLa, bLo, Math.toDegrees(Math.sqrt(bSse / sights.size)),
            haversineKm(bLa, bLo, tr.first, tr.second)))
    }

    private fun altOf(dir: DoubleArray, up: DoubleArray): Double {
        val cosZ = (dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)
        val app = Math.PI / 2 - acos(cosZ)
        return app - Moon.refractionRad(app)
    }

    /** 每会话一个公共垂直偏置（解析消去）后的残差平方和。 */
    private fun sse(lat: Double, lon: Double, sights: List<Sight>): Double {
        val site = Moon.Site(Math.toRadians(lat), Math.toRadians(lon))
        var sse = 0.0
        for ((_, list) in sights.groupBy { it.s }) {
            var sum = 0.0
            val res = list.map { s ->
                val e = Moon.Ephemeris(s.ra, s.dec, s.dist, 0.0)
                val pred = Moon.altitudeRad(site, e, s.utc, s.gast)
                (s.alt - pred).also { sum += it }
            }
            val off = sum / list.size
            for (r in res) { val d = r - off; sse += d * d }
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
