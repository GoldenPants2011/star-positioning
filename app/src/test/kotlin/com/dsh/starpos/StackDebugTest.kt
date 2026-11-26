package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 诊断：把同一组的 5 帧叠加（简单平均），看能不能把星点从噪声里拉出来并解出。 */
class StackDebugTest {

    @Test
    fun `叠加后检测与解算`() {
        val root = File("../field-inbox")
        for (name in listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
        )) {
            val zip = File(root, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
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
                if (n == 0) continue
                for (i in acc.indices) acc[i] /= n

                val intr = reader.manifest.intrinsics ?: return
                val cam = if (intr.width == w) intr.toCameraIntrinsics(w, h)
                else intr.toCameraIntrinsics(w, h)
                for (thr in listOf(5.0, 8.0, 12.0)) {
                    val det = FrameAnalyzer.analyze(acc, w, h, thresholdSigma = thr)
                    val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    val solver = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val sol = runCatching { solver.solve(starLike) }.getOrNull()
                    println(
                        "%s 叠加%d帧 thr=%.0fσ det=%d 像星=%d solved=%s matched=%d rms=%.1f".format(
                            name, n, thr, det.detections.size, starLike.size, sol != null,
                            sol?.matchedStars ?: 0, sol?.rmsArcsec ?: 0.0,
                        ),
                    )
                }
            }
        }
    }
}
