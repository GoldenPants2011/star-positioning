// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 对照实验：阈值 × 中值预滤波，对三组三脚架真机数据的最优组合。 */
class ThresholdExperimentTest {

    @Test
    fun `阈值与中值组合对比`() {
        val root = File("../field-inbox")
        val names = listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
        )
        for (name in names) {
            val zip = File(root, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val intr = reader.manifest.intrinsics ?: return@use
                val meta = reader.frameMetas().first()
                val f = reader.loadFrame(meta.index)
                val cam = intr.toCameraIntrinsics(f.width, f.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val line = StringBuilder(name.removeSuffix(".zip"))
                for (med in listOf(false, true)) {
                    for (thr in listOf(5.0, 6.0, 7.0, 8.0, 9.0)) {
                        val det = FrameAnalyzer.analyze(
                            f.luminance, f.width, f.height,
                            thresholdSigma = thr, medianPrefilter = med,
                        )
                        val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                        val sol = runCatching { solver.solve(starLike) }.getOrNull()
                        val errKm = sol?.let {
                            val g = FieldAnalyzer.gravityAt(reader.readImu(), meta, 90)
                            val ext = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json"))
                            val up = ext?.applyTo(g.upCam) ?: g.upCam
                            val ctx = com.dsh.starpos.astro.SkyContext.at(meta.wallClockUtcMs)
                            val pos = com.dsh.starpos.astro.PositionSolver.solve(
                                it.rotation, up, ctx, g.sigmaDeg, it.attitudeSigmaDeg, 0.005,
                            )
                            pos.fix?.let { fx ->
                                FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon)
                            }
                        }
                        line.append(
                            " | %s%.0fσ det=%d m=%d %s".format(
                                if (med) "M" else "-", thr,
                                det.detections.size, sol?.matchedStars ?: 0,
                                errKm?.let { "%.0fkm".format(it) } ?: "失败",
                            ),
                        )
                    }
                }
                println(line)
            }
        }
    }
}
