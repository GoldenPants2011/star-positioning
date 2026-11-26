// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import org.junit.Test

/**
 * 液面人造地平线：用盒式平滑找画面里的"团状亮斑"（太阳/水面反射），
 * 再用 IMU 重力算高度角，检验"太阳 +h、反射 −h"镜像关系。
 */
class HorizonSessionTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun boxMean(lum: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(w * h)
        val k = 2 * r + 1
        for (y in 0 until h) {
            var sum = 0.0
            for (dy in -r..r) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -r..r) sum += lum[yy * w + (dx + r).coerceIn(0, w - 1)].toDouble()
            }
            for (x in 0 until w) {
                out[y * w + x] = (sum / (k * k)).toFloat()
                // 滑动窗口：去掉左列，加右列
                val xOut = (x - r).coerceIn(0, w - 1)
                val xIn = (x + r + 1).coerceIn(0, w - 1)
                for (dy in -r..r) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    sum += (lum[yy * w + xIn] - lum[yy * w + xOut]).toDouble()
                }
            }
        }
        return out
    }

    @Test
    fun `液面反射会话诊断`() {
        val root = projectRoot()
        val dir = File(root, "field-inbox")
        val zips = dir.listFiles { f -> f.name.startsWith("session-20261121-16") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                if (metas.isEmpty()) return@use
                println("=".repeat(70))
                println(zip.name + " | truth=" + truth?.lat + "," + truth?.lon + " notes=" + reader.readNotes())
                val intrinsic = reader.manifest.intrinsics
                for (meta in metas) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intrinsic?.toCameraIntrinsics(f.width, f.height) ?: continue
                    val g = FieldAnalyzer.gravityAt(
                        imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                    )
                    val up = V.unit(g.upCam)
                    val gast = SkyContext.at(meta.wallClockUtcMs).gast
                    val sun = Moon.sunEphemerisAt(meta.wallClockUtcMs)
                    val sunAlt = if (truth != null) {
                        Math.toDegrees(
                            Moon.altitudeRad(Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)), sun, meta.wallClockUtcMs, gast),
                        )
                    } else Double.NaN
                    val w = f.width; val h = f.height
                    val sm = boxMean(f.luminance, w, h, 6)
                    val sorted = sm.sorted()
                    val med = sorted[sorted.size / 2]
                    val sigma = (sm.sumOf { (it - med) * (it - med).toDouble() } / sm.size).let { Math.sqrt(it) }.toFloat()
                    // 找局部极大（与周围 30px 内比）
                    data class P(val x: Int, val y: Int, val v: Float)
                    val peaks = ArrayList<P>()
                    val step = 2
                    for (y in 20 until h - 20 step step) for (x in 20 until w - 20 step step) {
                        val v = sm[y * w + x]
                        if (v < med + 4 * sigma) continue
                        var isMax = true
                        for (dy in -30..30 step 3) for (dx in -30..30 step 3) {
                            if (dx == 0 && dy == 0) continue
                            if (sm[(y + dy) * w + (x + dx)] > v) { isMax = false }
                        }
                        if (isMax) peaks.add(P(x, y, v))
                    }
                    peaks.sortByDescending { it.v }
                    println("  帧%d sunAlt=%.2f° σ=%.2f° 亮斑 %d 个（取前 6）".format(meta.index, sunAlt, g.sigmaDeg, peaks.size))
                    for (p in peaks.take(6)) {
                        val ray = cam.unproject(p.x.toDouble(), p.y.toDouble())
                        val cosZ = ray[0] * up[0] + ray[1] * up[1] + ray[2] * up[2]
                        val alt = 90.0 - Math.toDegrees(acos(cosZ.coerceIn(-1.0, 1.0)))
                        val snr = (p.v - med) / sigma
                        val mark = when {
                            !sunAlt.isNaN() && Math.abs(alt - sunAlt) < 1.0 -> " <== 太阳"
                            !sunAlt.isNaN() && Math.abs(alt + sunAlt) < 1.0 -> " <== 液面反射"
                            else -> ""
                        }
                        println("     (x=%d,y=%d) 高度 %+.2f° 平滑SNR %.1fσ%s".format(p.x, p.y, alt, snr, mark))
                    }
                }
            }
        }
    }
}
