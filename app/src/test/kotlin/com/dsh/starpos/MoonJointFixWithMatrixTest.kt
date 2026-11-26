// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * 用全部有真值的数据拟合"垂直方向的 3×3 线性映射"，再看今晚三组月光定位能改善多少。
 *
 * M: up_corrected ∝ M · up_raw（raw = 设备方向，不含外参小旋转）。
 * 拟合样本：星图可解帧的 (up_raw, 真值天顶在相机系中的方向)。
 * 应用：月亮帧的 up 用 M 修正后再算高度角。
 */
class MoonJointFixWithMatrixTest {

    private data class S(val u: DoubleArray, val v: DoubleArray, val session: String)
    private data class Sighting(val utc: Long, val gast: Double, val alt: Double, val ra: Double, val dec: Double, val dist: Double, val s: Int)

    @Test
    fun `3x3映射改善月光定位`() {
        val dir = File("../field-inbox")
        // A. 拟合样本（有星可解 + 有真值的会话）
        val samples = ArrayList<S>()
        val starSessions = dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: emptyList()
        for (zip in starSessions) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val first = runCatching { reader.loadFrame(reader.frameMetas().first().index) }.getOrNull() ?: return@use
                val cam = intr.toCameraIntrinsics(first.width, first.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val latRad = Math.toRadians(truth.lat)
                val lonRad = Math.toRadians(truth.lon)
                val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                for (m in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: continue
                    val ctx = SkyContext.at(m.wallClockUtcMs)
                    val ra = ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)
                    val zenith = V.fromRaDec(ra, geoC)
                    val v = V.unit(apply(transpose(sol.rotation), zenith))
                    // raw up（不加外参）
                    val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = null)
                    if (!g.valid) continue
                    samples.add(S(V.unit(g.upCam), v, zip.name))
                }
            }
        }
        println("拟合样本 ${samples.size} 帧，来自 ${samples.map { it.session }.distinct().size} 个会话")
        val mA = fitMatrix(samples, 0.0)
        val mR = fitMatrix(samples, 1.0)
        println("矩阵对角：λ=0 %.2f/%.2f/%.2f；λ=1 %.2f/%.2f/%.2f".format(
            mA[0][0], mA[1][1], mA[2][2], mR[0][0], mR[1][1], mR[2][2]))

        // B. 月光三组
        val names = listOf(
            "session-20261120-202658.zip",
            "session-20261120-214304.zip",
            "session-20261120-223707.zip",
        )
        for ((label, mat) in listOf("原始(无矩阵)" to null, "3×3(λ=1)" to mR, "3×3(λ=0)" to mA)) {
            val sights = ArrayList<Sighting>()
            var truth: Pair<Double, Double>? = null
            names.forEachIndexed { si, name ->
                val zip = File(dir, name)
                if (!zip.isFile) return@forEachIndexed
                BundleReader.open(zip).use { reader ->
                    val t = reader.readGnss().lastOrNull() ?: return@use
                    if (truth == null) truth = t.lat to t.lon
                    val intr = reader.manifest.intrinsics ?: return@use
                    val imu = reader.readImu()
                    for (m in reader.frameMetas()) {
                        val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                        val cam = intr.toCameraIntrinsics(f.width, f.height)
                        val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = null)
                        if (!g.valid) continue
                        val up = if (mat != null) V.unit(apply(mat, g.upCam)) else V.unit(g.upCam)
                        val e = Moon.ephemerisAt(m.wallClockUtcMs)
                        val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8)
                        val c = if (fit != null && fit.radiusPx > 1.0) fit.cx to fit.cy
                        else peakCentroid(f.luminance, f.width, f.height, 12) ?: continue
                        val d = cam.unproject(c.first, c.second)
                        val cosZ = (d[0] * up[0] + d[1] * up[1] + d[2] * up[2]).coerceIn(-1.0, 1.0)
                        val app = Math.PI / 2 - acos(cosZ)
                        sights.add(Sighting(m.wallClockUtcMs, SkyContext.at(m.wallClockUtcMs).gast,
                            app - Moon.refractionRad(app), e.raRad, e.decRad, e.distanceKm, si))
                    }
                }
            }
            val tr = truth ?: continue
            // ±200 km 窗口 + 每会话公共偏置
            var bLa = tr.first; var bLo = tr.second; var bSse = Double.MAX_VALUE
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
            println("%s：%.5f,%.5f（rms %.3f°）→ 误差 **%.1f km**".format(
                label, bLa, bLo, Math.toDegrees(Math.sqrt(bSse / sights.size)),
                haversineKm(bLa, bLo, tr.first, tr.second)))
        }
    }

    private fun fitMatrix(list: List<S>, lambda: Double): Array<DoubleArray> {
        val a = Array(3) { DoubleArray(3) }
        val s = Array(3) { DoubleArray(3) }
        for (sm in list) for (i in 0..2) for (j in 0..2) {
            a[i][j] += sm.v[i] * sm.u[j]
            s[i][j] += sm.u[i] * sm.u[j]
        }
        for (i in 0..2) { s[i][i] += lambda; a[i][i] += lambda }
        // 解 M·S = A → M = Aᵀ·S⁻¹（注意 A 是 v uᵀ）
        val inv = inv3(s) ?: return Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        return Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * inv[k][j] } } }
    }

    private fun inv3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (Math.abs(det) < 1e-12) return null
        val r = Array(3) { DoubleArray(3) }
        r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det
        r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det
        r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det
        r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det
        r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det
        r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det
        r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det
        r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det
        r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det
        return r
    }

    private fun sse(lat: Double, lon: Double, sights: List<Sighting>): Double {
        val site = Moon.Site(Math.toRadians(lat), Math.toRadians(lon))
        var sse = 0.0
        for ((_, list) in sights.groupBy { it.s }) {
            var sum = 0.0
            val res = list.map { s ->
                val pred = Moon.altitudeRad(site, Moon.Ephemeris(s.ra, s.dec, s.dist, 0.0), s.utc, s.gast)
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
