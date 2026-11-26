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
 * 诊断：不同月心估计方法对"测得高度角"的影响（用 GNSS 真值算期望高度做对比）。
 * 目的：选一个对"小月面 + 不对称光晕"稳健的估计器。
 */
class MoonCenterDiagTest {

    @Test
    fun `月心估计方法对比`() {
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (name in listOf("session-20261120-214304.zip", "session-20261120-202658.zip")) {
            val zip = File("../field-inbox/" + name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                println("=== $name 真值=${truth.lat},${truth.lon} ===")
                for (m in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(
                        imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    if (!g.valid) continue
                    val ephem = Moon.ephemerisAt(m.wallClockUtcMs)
                    val gast = SkyContext.at(m.wallClockUtcMs).gast
                    val expected = Moon.altitudeRad(
                        Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)), ephem, m.wallClockUtcMs, gast,
                    )
                    fun altOf(px: Double, py: Double): Double {
                        val dir = cam.unproject(px, py)
                        val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                            .coerceIn(-1.0, 1.0)
                        val app = Math.PI / 2 - acos(cosZ)
                        return app - Moon.refractionRad(app)
                    }
                    val row = StringBuilder("帧 ${m.index}: ")
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 24, minRadiusPx = 1.8)
                    if (fit != null) row.append("fit(%.0fpx,%.1f,%.1f)=%+.2f° ".format(
                        fit.radiusPx, fit.cx, fit.cy, Math.toDegrees(altOf(fit.cx, fit.cy) - expected)))
                    for (r in listOf(6, 10, 16, 24, 36)) {
                        val c = localCentroid(f.luminance, f.width, f.height, r, quadratic = false) ?: continue
                        val cq = localCentroid(f.luminance, f.width, f.height, r, quadratic = true) ?: continue
                        row.append("r${r}=%+.2f/%+.2f ".format(
                            Math.toDegrees(altOf(c.first, c.second) - expected),
                            Math.toDegrees(altOf(cq.first, cq.second) - expected)))
                    }
                    row.append("期望=%.2f°".format(Math.toDegrees(expected)))
                    println(row)
                }
            }
        }
    }

    /** 以最亮点为心，窗口半径 r，扣背景（窗口 20% 分位）后按权重求质心。 */
    private fun localCentroid(
        lum: FloatArray, w: Int, h: Int, r: Int, quadratic: Boolean,
    ): Pair<Double, Double>? {
        var best = -1f
        var bx = 0
        var by = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (lum[row + x] > best) {
                    best = lum[row + x]
                    bx = x
                    by = y
                }
            }
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
            val wgt = if (quadratic) (dv * dv).toDouble() else dv.toDouble()
            sx += x * wgt; sy += y * wgt; sw += wgt
        }
        if (sw <= 0.0) return null
        return (sx / sw) to (sy / sw)
    }
}
