package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断凌晨真机采集包：逐帧检测数、像星数、解算结果，验证"认出星星但匹配失败"。 */
class NightDiagnosticTest {

    @Test
    fun `凌晨会话逐帧诊断`() {
        val inbox = File("../field-inbox")
        val ext = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (name in listOf("session-20261120-044617.zip","session-20261120-044722.zip","session-20261120-044811.zip","session-20261120-044920.zip")) {
            val zip = File(inbox, name)
            if (!zip.isFile) { println("缺 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val metas = reader.frameMetas()
                val intr = reader.manifest.intrinsics ?: return@use
                println("=== $name 帧数=${metas.size} 外参=${ext?.let { "%.2f°".format(it.angleDeg) } ?: "无"} ===")
                val first = reader.loadFrame(metas.first().index)
                val cam = intr.toCameraIntrinsics(first.width, first.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    val row = StringBuilder("帧%2d 曝光%.0fms:".format(m.index, m.exposureNs / 1e6))
                    for (thr in listOf(5.0, 7.0, 9.0)) {
                        val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = thr)
                        val starLike = det.detections.filter { it.aspect < 2.0 && it.area in 3..400 }
                        val elong = if (det.detections.isEmpty()) 0.0
                        else det.detections.count { it.aspect > 2.5 }.toDouble() / det.detections.size
                        val sol = runCatching { solver.solve(starLike) }.getOrNull()
                        row.append(
                            " | %.0fσ det=%d 像星=%d 拖尾=%.0f%%".format(
                                thr, det.detections.size, starLike.size, elong * 100,
                            ),
                        )
                        row.append(if (sol != null) " m=%d".format(sol.matchedStars) else " ✗")
                    }
                    println(row)
                }
            }
        }
    }

    @Test
    fun `实时质量必须用真实帧尺寸的内参`() {
        // 回归：0.9.1 之前 liveSolver 用全分辨率 4096x3072 内参，而保存帧是 2048x1536，
        // 焦距差 2 倍 → 实时"认出星星但匹配失败"（离线分析正常）。这里锁死正确行为。
        val zip = File("../field-inbox/session-20261120-004205.zip")
        if (!zip.isFile) return
        BundleReader.open(zip).use { reader ->
            val m = reader.frameMetas().first()
            val f = reader.loadFrame(m.index)
            val intr = reader.manifest.intrinsics ?: return@use
            val starLike = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                .detections.filter { it.aspect < 2.0 && it.area in 3..400 }

            val rightCam = intr.toCameraIntrinsics(f.width, f.height)
            val rightSolver = PlateSolver(
                SkyIndex(Fixtures.catalog), rightCam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / rightCam.fx),
            )
            val right = runCatching { rightSolver.solve(starLike) }.getOrNull()

            val wrongCam = intr.toCameraIntrinsics(f.width * 2, f.height * 2)   // 旧 bug
            val wrongSolver = PlateSolver(
                SkyIndex(Fixtures.catalog), wrongCam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / wrongCam.fx),
            )
            val wrong = runCatching { wrongSolver.solve(starLike) }.getOrNull()

            println("正确内参 fx=%.1f → m=%s；错误内参 fx=%.1f → m=%s".format(
                rightCam.fx, right?.matchedStars ?: "失败",
                wrongCam.fx, wrong?.matchedStars ?: "失败",
            ))
            assertTrue("用真实帧尺寸应能解出", right != null && right.matchedStars >= 5)
            assertTrue("用 2 倍焦距应当解不出（复现旧 bug）", wrong == null || wrong.matchedStars < 4)
        }
    }
}



