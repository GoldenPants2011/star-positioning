package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.field.BundleReader
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/** 诊断：把检测到的点画到真实夜空帧上，判断它们是不是真的星。 */
class OverlayDebugTest {

    @Test
    fun `画出检测点`() {
        val zip = File("../field-inbox/session-20261119-011848.zip")
        if (!zip.isFile) { println("跳过"); return }
        val outDir = File("E:/dsh-toolchain/.tmp/real").apply { mkdirs() }
        BundleReader.open(zip).use { reader ->
            val meta = reader.frameMetas().first()
            val loaded = reader.loadFrame(meta.index)
            val w = loaded.width
            val h = loaded.height
            val lum = loaded.luminance
            for (threshold in listOf(5.0, 10.0, 15.0, 25.0)) {
            val det = FrameAnalyzer.analyze(lum, w, h, thresholdSigma = threshold)

            // 拉伸到 8 位
            val sorted = lum.sortedArray()
            val lo = sorted[(sorted.size * 0.02).toInt()]
            val hi = sorted[(sorted.size * 0.999).toInt()]
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) {
                val v = ((lum[y * w + x] - lo) / (hi - lo).coerceAtLeast(1e-6f) * 255)
                    .coerceIn(0f, 255f).toInt()
                img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
            }
            val g = img.createGraphics()
            g.color = Color.RED
            for (d in det.detections) {
                val r = if (d.aspect < 2.0 && d.area >= 3) 6 else 3
                g.drawOval((d.x - r).toInt(), (d.y - r).toInt(), 2 * r, 2 * r)
            }
            g.dispose()
            val out = File(outDir, "overlay-thr${threshold.toInt()}.png")
            ImageIO.write(img, "png", out)
            println("已写出 $out，阈值 ${threshold}σ 检测 ${det.detections.size} 颗（红圈：大=像星，小=其它）")
            println("亮度统计: lo=%.5f hi=%.5f  背景=%.5f 噪声=%.5f".format(
                lo, hi, det.quality.background, det.quality.noiseSigma))
            }
        }
    }
}


