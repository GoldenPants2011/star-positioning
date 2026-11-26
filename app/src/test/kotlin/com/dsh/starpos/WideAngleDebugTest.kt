// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test

/** 广角（camera 3）会话 0 匹配诊断：星检测质量 + 参数组合尝试。 */
class WideAngleDebugTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `广角会话诊断`() {
        val root = projectRoot()
        val zip = File(root, "field-inbox/session-20261124-035956.zip")
        if (!zip.isFile) { println("找不到广角会话"); return }
        val index = SkyIndex(Fixtures.catalog)
        BundleReader.open(zip).use { reader ->
            val intr = reader.manifest.intrinsics ?: return
            val metas = reader.frameMetas()
            for (meta in metas.take(3)) {
                val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                val cam = intr.toCameraIntrinsics(f.width, f.height)
                println("=".repeat(70))
                println("帧${meta.index}: ${f.width}x${f.height} exp=${meta.exposureNs / 1_000_000}ms ISO${meta.iso}")
                println("内参: fx=%.1f fy=%.1f cx=%.1f cy=%.1f k1=%.4f k2=%.4f p1=%.4f".format(
                    cam.fx, cam.fy, cam.cx, cam.cy, cam.k1, cam.k2, cam.p1,
                ))
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val aspects = det.detections.map { it.aspect }.sorted()
                val areas = det.detections.map { it.area }.sorted()
                println("检测 %d 颗；aspect 中位 %.2f；area 中位 %d；云量 %.2f".format(
                    det.detections.size,
                    if (aspects.isEmpty()) 0.0 else aspects[aspects.size / 2],
                    if (areas.isEmpty()) 0 else areas[areas.size / 2],
                    det.quality.cloudCover,
                ))
                // 关键组合：放宽星点面积(>=2) + 提高 triangleMag（广角下星表亮星子集需与检出集匹配）
                for (tm in doubleArrayOf(5.0, 5.5, 6.0, 6.5, 7.0)) {
                    for (thr in doubleArrayOf(7.0, 5.5, 5.0)) {
                        val d2 = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = thr)
                        val sl = d2.detections.filter { it.area in 2..400 && it.aspect < 3.0 }
                        if (sl.size < 6) continue
                        val solver = PlateSolver(
                            index, cam,
                            triangleMagLimit = tm,
                            maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx),
                        )
                        val sol = runCatching { solver.solve(sl, maxImageStars = 60) }.getOrNull()
                        println("triangleMag=%.1f thr=%.1f starLike=%d -> %s".format(
                            tm, thr, sl.size,
                            if (sol == null) "未解" else "匹配 %d 颗 rms %.0f″".format(sol.matchedStars, sol.rmsArcsec),
                        ))
                    }
                }
                // 跨帧：真星会移动
                if (metas.size >= 2) {
                    val f2 = runCatching { reader.loadFrame(metas[1].index) }.getOrNull()
                    if (f2 != null) {
                        val d2 = FrameAnalyzer.analyze(f2.luminance, f2.width, f2.height, thresholdSigma = 7.0)
                        var same = 0
                        for (a in det.detections) for (b in d2.detections) {
                            if (Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < 3.0) same++
                        }
                        println("帧0 vs 帧1：检测 %d vs %d，位置几乎不变的点 %d 个".format(
                            det.detections.size, d2.detections.size, same,
                        ))
                    }
                }
            }
        }
    }
}
