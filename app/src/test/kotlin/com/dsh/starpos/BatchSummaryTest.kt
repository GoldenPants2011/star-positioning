// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.Stacker
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.ExtrinsicEstimator
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 汇总本轮 5 个地平线 + 2 个暗场 + 3 个标定包。 */
class BatchSummaryTest {

    private val names = listOf(
        "044418", "044501", "044538", "044602", "044634",   // 地平线/光污染
        "044811", "044846",                                  // 暗场静态
        "045001", "045135", "045303",                        // 标定
    )

    @Test
    fun `本轮十个包汇总`() {
        val inbox = File("../field-inbox")
        val ext = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (tag in names) {
            val zip = File(inbox, "session-20261119-$tag.zip")
            if (!zip.isFile) { println("$tag 缺失"); continue }
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                val metas = reader.frameMetas()
                val frames = metas.mapNotNull { m -> runCatching { reader.loadFrame(m.index) }.getOrNull() }
                val intr = reader.manifest.intrinsics ?: return@use
                val w = frames.firstOrNull()?.width ?: return@use
                val h = frames.first().height
                val cam = intr.toCameraIntrinsics(w, h)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                // 单帧最佳
                var best = -1
                var bestMatched = 0
                var bestDet = 0
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull()
                    if ((sol?.matchedStars ?: 0) > bestMatched) {
                        bestMatched = sol!!.matchedStars
                        best = m.index
                        bestDet = det.detections.size
                    }
                    if (bestDet == 0) bestDet = det.detections.size
                }
                // 叠加
                var stackInfo = "—"
                if (frames.size >= 3) {
                    val st = Stacker.stack(frames.map { it.luminance }, w, h)
                    if (st != null) {
                        val det = FrameAnalyzer.analyze(st.image, w, h, thresholdSigma = 7.0)
                        val sol = runCatching {
                            solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                        }.getOrNull()
                        stackInfo = "det=%d m=%d".format(det.detections.size, sol?.matchedStars ?: 0)
                    }
                }
                // 定位误差（用最佳帧 + 外参）
                var errTxt = "—"
                if (best >= 0 && truth != null) {
                    val meta = metas.first { it.index == best }
                    val g = FieldAnalyzer.gravityAt(
                        reader.readImu(), meta, reader.manifest.camera?.sensorOrientation ?: 90,
                    )
                    val up = ext?.applyTo(g.upCam) ?: g.upCam
                    val f = reader.loadFrame(best)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull()
                    if (sol != null) {
                        val ctx = SkyContext.at(meta.wallClockUtcMs)
                        val pos = PositionSolver.solve(sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005)
                        pos.fix?.let { fx ->
                            errTxt = "%.1f km".format(
                                FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon),
                            )
                        }
                    }
                }
                println(
                    "$tag: 帧=%d 最佳帧#%d det=%d m=%d 误差=%s | 叠加 %s".format(
                        metas.size, best, bestDet, bestMatched, errTxt, stackInfo,
                    ),
                )
                if (tag.startsWith("045")) {
                    val est = ExtrinsicEstimator.estimate(reader, Fixtures.catalog)
                    println("     标定：${est.message}；${est.config?.let { "修正 %.2f°".format(it.angleDeg) } ?: "无结果"}")
                }
            }
        }
    }
}
