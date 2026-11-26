// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.Rodrigues
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

/**
 * 用 2026-11-20 00:42 的标定包重新算外参，并与现有 2.81° 在 5 组静止真值数据上对比。
 */
class ExtrinsicRecomputeTest {

    @Test
    fun `用夜间标定包重算外参并交叉验证`() {
        val inbox = File("../field-inbox")
        val zip = File(inbox, "session-20261120-020344.zip")
        if (!zip.isFile) { println("缺标定包"); return }
        val current = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        println("现用外参：%s".format(current?.let { "%.2f°".format(it.angleDeg) } ?: "无"))

        val est = BundleReader.open(zip).use { reader ->
            ExtrinsicEstimator.estimate(reader, Fixtures.catalog)
        }
        println("标定结果：${est.message}")
        println("帧对=${est.pairsUsed} 帧间中位转动=%.1f°".format(est.medianRotationDeg))
        val estCfg = est.config
        if (estCfg == null) { println("没有可用结果，结束"); return }
        println("新外参：rx=%.4f ry=%.4f rz=%.4f rad = %.2f°".format(
            estCfg.rx, estCfg.ry, estCfg.rz, estCfg.angleDeg))

        // 与 2.81° 的差异（旋转角）
        current?.let {
            val d = V.norm(
                com.dsh.starpos.astro.HandEye.rotationVector(
                    Rodrigues.exp(estCfg.rx - it.rx, estCfg.ry - it.ry, estCfg.rz - it.rz),
                ),
            )
            println("与现用值差 %.2f°".format(Math.toDegrees(d)))
        }

        // 在 5 组静止真值数据上比较定位误差
        val valids = listOf("011848", "012017", "012049", "044811", "044846")
        println("数据 | 无外参 | 现用值 | 新标定")
        for (tag in valids) {
            val s = File(inbox, "session-20261119-$tag.zip")
            if (!s.isFile) continue
            BundleReader.open(s).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val metas = reader.frameMetas()
                val intr = reader.manifest.intrinsics ?: return@use
                val f = reader.loadFrame(metas.first().index)
                val cam = intr.toCameraIntrinsics(f.width, f.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: run { println("$tag 未解出"); return@use }
                val g = FieldAnalyzer.gravityAt(
                    reader.readImu(), metas.first(), reader.manifest.camera?.sensorOrientation ?: 90,
                )
                val ctx = SkyContext.at(metas.first().wallClockUtcMs)
                fun err(up: DoubleArray): String {
                    val p = com.dsh.starpos.astro.PositionSolver.solve(
                        sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005,
                    )
                    val fx = p.fix ?: return "—"
                    return "%.0f km".format(FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon))
                }
                println("%s | %s | %s | %s".format(
                    tag,
                    err(g.upCam),
                    current?.let { err(it.applyTo(g.upCam)) } ?: "—",
                    err(estCfg.applyTo(g.upCam)),
                ))
            }
        }
    }

    @Test
    fun `用标定包自身真值做独立检验`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        val cur = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json")) ?: return
        val est = BundleReader.open(zip).use { ExtrinsicEstimator.estimate(it, Fixtures.catalog) }
        val new = est.config
        println("现用 %.2f°；手眼解 %.2f°".format(cur.angleDeg, new?.angleDeg ?: Double.NaN))
        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull() ?: run { println("无真值"); return }
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                val g = FieldAnalyzer.gravityAt(
                    reader.readImu(), m, reader.manifest.camera?.sensorOrientation ?: 90,
                )
                val ctx = SkyContext.at(m.wallClockUtcMs)
                fun err(up: DoubleArray): String {
                    val p = com.dsh.starpos.astro.PositionSolver.solve(
                        sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005,
                    )
                    val fx = p.fix ?: return "—"
                    return "%.0f".format(FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon))
                }
                println("帧%d m=%d 误差(km): 无=%s 现用=%s 手眼=%s".format(
                    m.index, sol.matchedStars,
                    err(g.upCam), err(cur.applyTo(g.upCam)),
                    new?.let { err(it.applyTo(g.upCam)) } ?: "—",
                ))
            }
        }
    }
}
