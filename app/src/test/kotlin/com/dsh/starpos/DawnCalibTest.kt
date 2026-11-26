// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.ExtrinsicEstimator
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 黎明（天亮）拍的标定数据能否用：自标定结果 vs 现有 2.81° 参考值，并用其它会话验证。 */
class DawnCalibTest {

    private val dawn = listOf("054852", "055053")

    @Test
    fun `黎明标定数据可用性`() {
        val inbox = File("../field-inbox")
        val reference = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        println("现有参考外参：%.2f°".format(reference?.angleDeg ?: Double.NaN))
        val estimates = ArrayList<ExtrinsicConfig>()

        for (tag in dawn) {
            val zip = File(inbox, "session-20261119-$tag.zip")
            if (!zip.isFile) { println("$tag 缺失"); continue }
            BundleReader.open(zip).use { reader ->
                val metas = reader.frameMetas()
                println("=== $tag（${metas.size} 帧） ===")
                val m0 = metas.first()
                println("  曝光 %.0f ms / ISO %d" .format(m0.exposureNs / 1e6, m0.iso))
                for (m in metas.take(3)) {
                    val f = reader.loadFrame(m.index)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    println("  帧${m.index}: det=%d 背景=%.4f 噪声=%.4f".format(
                        det.detections.size, det.quality.background, det.quality.noiseSigma))
                }
                // 逐阈值看能否解出
                val intr0 = reader.manifest.intrinsics
                for (thr in listOf(4.0, 5.0, 6.0, 7.0)) {
                    var solvedN = 0
                    var matchedSum = 0
                    for (m in metas) {
                        val f = reader.loadFrame(m.index)
                        if (intr0 == null) break
                        val cam0 = intr0.toCameraIntrinsics(f.width, f.height)
                        val sv = PlateSolver(
                            SkyIndex(Fixtures.catalog), cam0,
                            maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam0.fx),
                        )
                        val d = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = thr)
                        val s = runCatching {
                            sv.solve(d.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                        }.getOrNull()
                        if (s != null && s.matchedStars >= 4) { solvedN++; matchedSum += s.matchedStars }
                    }
                    println("  %sσ：可解帧 %d/%d，匹配合计 %d".format(thr, solvedN, metas.size, matchedSum))
                }
                val est = ExtrinsicEstimator.estimate(reader, Fixtures.catalog)
                println("  标定：${est.message}")
                est.config?.let {
                    estimates.add(it)
                    println("  修正向量 (%.4f, %.4f, %.4f) rad = %.2f°；与参考值差 %.2f°".format(
                        it.rx, it.ry, it.rz, it.angleDeg,
                        reference?.let { r ->
                            Math.toDegrees(
                                V.norm(
                                    com.dsh.starpos.astro.HandEye.rotationVector(
                                        com.dsh.starpos.astro.Rodrigues.exp(
                                            it.rx - r.rx, it.ry - r.ry, it.rz - r.rz,
                                        ),
                                    ),
                                ),
                            )
                        } ?: Double.NaN,
                    ))
                }
            }
        }
        if (estimates.isEmpty()) { println("两份都没有可用结果"); return }

        // 用其它已知真值的会话验证黎明标定值
        val valids = listOf("011848", "012017", "012049", "044811", "044846")
        for (tag in valids) {
            val zip = File(inbox, "session-20261119-$tag.zip")
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val metas = reader.frameMetas()
                val intr = reader.manifest.intrinsics ?: return@use
                var line = "$tag: "
                for ((label, cfg) in listOf("参考" to reference) + estimates.mapIndexed { i, c -> "黎明$i" to c }) {
                    val f = reader.loadFrame(metas.first().index)
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val solver = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull()
                    if (sol == null || cfg == null) { line += "$label=— "; continue }
                    val g = FieldAnalyzer.gravityAt(
                        reader.readImu(), metas.first(), reader.manifest.camera?.sensorOrientation ?: 90,
                    )
                    val up = cfg.applyTo(g.upCam)
                    val ctx = SkyContext.at(metas.first().wallClockUtcMs)
                    val pos = PositionSolver.solve(sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005)
                    val err = pos.fix?.let {
                        FieldAnalyzer.haversineKm(it.latDeg, it.lonDeg, truth.lat, truth.lon)
                    }
                    line += "$label=%.0f km ".format(err ?: Double.NaN)
                }
                println(line)
            }
        }
    }
}

