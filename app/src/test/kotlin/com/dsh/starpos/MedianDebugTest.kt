package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test
import kotlin.math.max

/** 诊断：中值滤波去掉单像素热点后，真实夜空帧能否正常检测 + 解算。 */
class MedianDebugTest {

    private fun median3(lum: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(lum.size)
        val buf = FloatArray(9)
        for (y in 0 until h) {
            val y0 = if (y == 0) 0 else y - 1
            val y1 = if (y == h - 1) h - 1 else y + 1
            for (x in 0 until w) {
                val x0 = if (x == 0) 0 else x - 1
                val x1 = if (x == w - 1) w - 1 else x + 1
                var n = 0
                for (yy in y0..y1) for (xx in x0..x1) buf[n++] = lum[yy * w + xx]
                // 插入排序取中值（n<=9）
                for (i in 1 until n) {
                    val v = buf[i]
                    var j = i - 1
                    while (j >= 0 && buf[j] > v) { buf[j + 1] = buf[j]; j-- }
                    buf[j + 1] = v
                }
                out[y * w + x] = buf[n / 2]
            }
        }
        return out
    }

    @Test
    fun `中值滤波后检测与解算`() {
        val root = File("../field-inbox")
        for (name in listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
        )) {
            val zip = File(root, name)
            if (!zip.isFile) { println("跳过 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                val meta = reader.frameMetas().first()
                val f = reader.loadFrame(meta.index)
                val intr = reader.manifest.intrinsics ?: return
                val cam = intr.toCameraIntrinsics(f.width, f.height)
                val filtered = median3(f.luminance, f.width, f.height)
                for (thr in listOf(5.0, 8.0)) {
                    val det = FrameAnalyzer.analyze(filtered, f.width, f.height, thresholdSigma = thr)
                    val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    val solver = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val sol = runCatching { solver.solve(starLike) }.getOrNull()
                    println(
                        "%s thr=%.0fσ det=%d 像星=%d solved=%s matched=%d rms=%.1f".format(
                            name, thr, det.detections.size, starLike.size, sol != null,
                            sol?.matchedStars ?: 0, sol?.rmsArcsec ?: 0.0,
                        ),
                    )
                    if (sol != null && truth != null) {
                        val upCam = com.dsh.starpos.astro.V.unit(
                            doubleArrayOf(0.0, 0.0, 1.0),
                        )
                        println("   （有解，仅作参考）truth=%.4f,%.4f".format(truth.lat, truth.lon))
                    }
                }
            }
        }
    }
}

