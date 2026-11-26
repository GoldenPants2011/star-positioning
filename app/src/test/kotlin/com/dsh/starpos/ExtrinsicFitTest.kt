// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.junit.Test

/**
 * 用三组带 GNSS 真值的数据，拟合"相机↔IMU 外参修正"（小角度旋转向量），
 * 目标是让三组位置误差同时降到最小。若能拟合出共同解 → 可以固化进配置。
 */
class ExtrinsicFitTest {

    private class Case(
        val rotation: Array<DoubleArray>,
        val upCam: DoubleArray,
        val ctx: SkyContext,
        val sigmaG: Double,
        val sigmaA: Double,
        val truthLat: Double,
        val truthLon: Double,
    )

    private fun rotVec(rx: Double, ry: Double, rz: Double): Array<DoubleArray> {
        // 小角度：R ≈ I + [r]x
        return arrayOf(
            doubleArrayOf(1.0, -rz, ry),
            doubleArrayOf(rz, 1.0, -rx),
            doubleArrayOf(-ry, rx, 1.0),
        )
    }

    @Test
    fun `拟合外参修正`() {
        val root = File("../field-inbox")
        val cases = ArrayList<Case>()
        for (name in listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
            "session-20261119-044811.zip",
            "session-20261119-044846.zip",
        )) {
            val zip = File(root, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                val first = reader.loadFrame(metas.first().index)
                val w = first.width
                val h = first.height
                val acc = FloatArray(w * h)
                var n = 0
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    if (f.width != w || f.height != h) continue
                    for (i in acc.indices) acc[i] += f.luminance[i]
                    n++
                }
                for (i in acc.indices) acc[i] /= n
                val intr = reader.manifest.intrinsics ?: return@use
                val cam = intr.toCameraIntrinsics(w, h)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val det = FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 8.0)
                val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                val sol = runCatching { solver.solve(starLike) }.getOrNull() ?: return@use
                val meta = metas.first()
                val g = FieldAnalyzer.gravityAt(imu, meta, 90)
                cases.add(
                    Case(
                        sol.rotation, g.upCam, SkyContext.at(meta.wallClockUtcMs),
                        g.sigmaDeg, sol.attitudeSigmaDeg, truth.lat, truth.lon,
                    ),
                )
                println("$name 姿态就绪 matched=${sol.matchedStars}")
            }
        }
        if (cases.size < 2) { println("数据不足"); return }

        fun totalError(rx: Double, ry: Double, rz: Double, verbose: Boolean): Double {
            var sum = 0.0
            for (c in cases) {
                val up = V.unit(apply(rotVec(rx, ry, rz), c.upCam))
                val pos = PositionSolver.solve(c.rotation, up, c.ctx, c.sigmaG, c.sigmaA, 0.005)
                val fix = pos.fix ?: return 1e9
                val e = FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, c.truthLat, c.truthLon)
                if (verbose) println("   rx=%.4f ry=%.4f rz=%.4f → %.1f km".format(rx, ry, rz, e))
                sum += e * e
            }
            return sum
        }

        // 粗网格 + 细化
        var best = Triple(0.0, 0.0, 0.0)
        var bestErr = totalError(0.0, 0.0, 0.0, false)
        println("未修正总误差² = %.3e" .format(bestErr))
        var step = 0.02
        repeat(4) {
            val cands = ArrayList<Triple<Double, Double, Double>>()
            for (i in -10..10) for (j in -10..10) for (k in -10..10) {
                cands.add(
                    Triple(best.first + i * step, best.second + j * step, best.third + k * step),
                )
            }
            for (c in cands) {
                val e = totalError(c.first, c.second, c.third, false)
                if (e < bestErr) { bestErr = e; best = c }
            }
            step /= 4.0
        }
        println("最佳修正 rx=%.4f ry=%.4f rz=%.4f rad（%.2f° / %.2f° / %.2f°）".format(
            best.first, best.second, best.third,
            Math.toDegrees(best.first), Math.toDegrees(best.second), Math.toDegrees(best.third)))
        totalError(best.first, best.second, best.third, true)
        val norm = Math.toDegrees(Math.sqrt(best.first * best.first + best.second * best.second + best.third * best.third))
        println("修正旋转总量 = %.2f°".format(norm))
    }
}

