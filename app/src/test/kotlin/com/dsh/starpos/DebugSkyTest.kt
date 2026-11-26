package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test

/**
 * 临时诊断：真实夜空帧在"整幅 vs 中心裁剪"下的解算结果对比。
 * 假设：70° 视场 + 镜头畸变，纯针孔模型的三角形匹配会失效；裁掉边缘应能解出。
 */
class DebugSkyTest {

    @Test
    fun `对比整幅与中心裁剪`() {
        val root = File("../field-inbox")
        val zip = File(root, "session-20261119-011848.zip")
        if (!zip.isFile) { println("跳过：没有 ${zip.absolutePath}"); return }
        BundleReader.open(zip).use { reader ->
            val frames = reader.frameMetas()
            val cat = Fixtures.catalog
            for (meta in frames.take(3).toList()) {
                val loaded = reader.loadFrame(meta.index)
                val w = loaded.width
                val h = loaded.height
                val lum = loaded.luminance
                val intr = reader.manifest.intrinsics ?: continue
                run {
                    val detD = FrameAnalyzer.analyze(lum, w, h)
                    println("  帧${meta.index} 前 12 个检测（按亮度）：")
                    detD.detections.take(12).forEach {
                        println("    (%.0f,%.0f) flux=%.0f area=%d aspect=%.2f".format(it.x, it.y, it.flux, it.area, it.aspect))
                    }
                    // 残余上限放到极大，看是不是被残差门限挡掉
                    val camL = intr.toCameraIntrinsics(w, h)
                    val solverL = PlateSolver(com.dsh.starpos.astro.SkyIndex(cat), camL, maxResidualArcsec = 100000.0)
                    val sl = detD.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    val solL = runCatching { solverL.solve(sl) }.getOrNull()
                    println("  帧${meta.index} 关掉残差门限：solved=%s matched=%d stats=%s".format(
                        solL != null, solL?.matchedStars ?: 0, solverL.stats))
                }
                // 扫描假设焦距：检验是不是内参/FOV 不对导致三角形尺度不匹配
                for (scale in listOf(0.6, 0.8, 1.0, 1.25, 1.6, 2.0)) {
                    val fullCamS = intr.toCameraIntrinsics(w, h)
                    val camS = com.dsh.starpos.astro.CameraIntrinsics(
                        width = w, height = h,
                        fx = fullCamS.fx * scale, fy = fullCamS.fy * scale,
                        cx = w / 2.0, cy = h / 2.0,
                    )
                    val solverS = PlateSolver(com.dsh.starpos.astro.SkyIndex(cat), camS)
                    val detS = FrameAnalyzer.analyze(lum, w, h)
                    val sl = detS.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    val solS = runCatching { solverS.solve(sl) }.getOrNull()
                    println("  帧${meta.index} fx×%.2f → solved=%s matched=%d rms=%.1f bestInliers=%s".format(
                        scale, solS != null, solS?.matchedStars ?: 0, solS?.rmsArcsec ?: 0.0,
                        solverS.stats["bestInliers"]))
                }
                for (frac in listOf(1.0)) {
                    val cw = ((w * frac).toInt() / 16) * 16
                    val ch = ((h * frac).toInt() / 16) * 16
                    val x0 = (w - cw) / 2
                    val y0 = (h - ch) / 2
                    val crop = FloatArray(cw * ch)
                    for (y in 0 until ch) {
                        System.arraycopy(lum, (y0 + y) * w + x0, crop, y * cw, cw)
                    }
                    val fullCam = intr.toCameraIntrinsics(w, h)
                    val cam = com.dsh.starpos.astro.CameraIntrinsics(
                        width = cw, height = ch,
                        fx = fullCam.fx, fy = fullCam.fy,
                        cx = fullCam.cx - x0, cy = fullCam.cy - y0,
                    )
                    val index = com.dsh.starpos.astro.SkyIndex(cat)
                    val solver = PlateSolver(index, cam)
                    val det = FrameAnalyzer.analyze(crop, cw, ch)
                    val aspects = det.detections.take(30).map { it.aspect }.sorted()
                    val areas = det.detections.take(30).map { it.area }.sorted()
                    val medAsp = if (aspects.isEmpty()) 0.0 else aspects[aspects.size / 2]
                    val medArea = if (areas.isEmpty()) 0 else areas[areas.size / 2]
                    val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    if (frac == 1.0) {
                        println("  帧${meta.index} 前30: 中位aspect=%.2f 中位area=%d 像星=%d/%d".format(
                            medAsp, medArea, starLike.size, det.detections.size))
                    }
                    val sol = runCatching { solver.solve(starLike) }.getOrNull()
                    println(
                        "帧${meta.index} crop=%.2f (%dx%d) det=%d solved=%s matched=%d rms=%.1f stats=%s".format(
                            frac, cw, ch, det.detections.size, sol != null,
                            sol?.matchedStars ?: 0, sol?.rmsArcsec ?: 0.0, solver.stats,
                        ),
                    )
                }
            }
        }
    }
}





