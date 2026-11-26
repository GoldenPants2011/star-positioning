package com.dsh.starpos

import com.dsh.starpos.astro.AstroTime
import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.DEG
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.V
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.tan
import org.junit.Test

/** 对照：同一张真实天文照片（SH2-91）在不同阈值下的检测与解算。 */
class RealImageThresholdTest {

    @Test
    fun `照片在不同阈值下的表现`() {
        val file = listOf(
            File("../real-images/sh2-91.jpg"),
            File("../star-positioning-real-images/sh2-91.jpg"),
        ).firstOrNull { it.isFile }
        if (file == null) { println("跳过：真实照片素材已移出仓库"); return }
        val img = ImageIO.read(file)
        val w = img.width
        val h = img.height
        val lum = FloatArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            img.getRGB(0, y, w, 1, row, 0, w)
            for (x in 0 until w) {
                val p = row[x]
                lum[y * w + x] = (
                    (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)) / 255.0
                    ).toFloat()
            }
        }
        val diag = hypot(w.toDouble(), h.toDouble())
        val fx = (diag / 2) / tan(Math.toRadians(4.1751))
        val cam = CameraIntrinsics(w, h, fx, fx, w / 2.0, h / 2.0)
        val raRad = (19 + 33 / 60.0 + 20.0 / 3600.0) * 15 * DEG
        val decRad = (31 + 14 / 60.0 + 2.0 / 3600.0) * DEG
        val center = V.fromRaDec(raRad, decRad)
        val cat = Fixtures.catalog
        val ids = (0 until cat.size).filter {
            cat.vmag(it) <= 7.0 && V.angle(cat.icrs(it, AstroTime.J2000), center) < 20 * DEG
        }
        val sub = StarCatalog(
            ids.map { cat.ra[it] }.toDoubleArray(),
            ids.map { cat.dec[it] }.toDoubleArray(),
            ids.map { cat.mag[it] }.toDoubleArray(),
            ids.map { cat.pmRa[it] }.toDoubleArray(),
            ids.map { cat.pmDec[it] }.toDoubleArray(),
            ids.map { cat.hr[it] }.toIntArray(),
        )
        val index = SkyIndex(sub, magLimit = 7.0)
        for (thr in listOf(4.0, 5.0, 7.0, 9.0, 12.0)) {
            val solver = PlateSolver(index, cam, triangleMagLimit = 7.0)
            val det = FrameAnalyzer.analyze(lum, w, h, thresholdSigma = thr)
            val starLike = det.detections.filter { it.aspect < 2.0 && it.area in 3..400 }
            val sol = runCatching { solver.solve(starLike, maxImageStars = 60) }.getOrNull()
            println(
                "SH2-91 %2.0fσ: det=%d 像星=%d solved=%s matched=%d rms=%.1f″".format(
                    thr, det.detections.size, starLike.size, sol != null,
                    sol?.matchedStars ?: 0, sol?.rmsArcsec ?: 0.0,
                ),
            )
        }
    }
}
