// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.AstroTime
import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.DEG
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/**
 * 真实照片验证：用天文社区公开作品（图页带赤经/赤纬、视场半径、像素尺度、旋转角）跑
 * 「星点检测 → 星图识别 → 姿态」，与公布的**指向**比对。
 *
 * 范围与约定（重要）：
 * - 真实照片没有 IMU/重力 → 只验证姿态/指向，不产出经纬度。
 * - 只把**中心指向**当验收依据：它与旋转角的约定无关，可信。
 * - 图页的"旋转角"约定至今没对上（见 PROGRESS §15.1），因此不用于断言，只打印供参考。
 */
class RealImageTest {

    private data class Spec(
        val name: String,
        val file: String,
        val raHours: Double,
        val decDeg: Double,
        val fovRadiusDeg: Double,
        /** 图页公布的旋转角（E of N，顺时针）；仅打印，不用于断言 */
        val rotationDeg: Double,
        val source: String,
        /** 星表深度上限 */
        val maxMag: Double = 7.0,
        /** 指定星表文件（相对 real-images/）；null 表示用内置 HYG（mag≤7） */
        val catalogFile: String? = null,
        /** 局部星表半径（度） */
        val subsetRadiusDeg: Double = 20.0,
        /** 处理过的照片（拉伸/降噪）需要更高阈值与更严的形状过滤 */
        val thresholdSigma: Double = 5.0,
        val maxAspect: Double = 2.0,
        val maxArea: Int = 400,
        /** 是否要求盲解成功；窄视场/重处理图目前设 false，只记录不断言 */
        val expectBlindSolve: Boolean = true,
    )

    private val specs = listOf(
        Spec(
            name = "SH2-91 天鹅座超新星遗迹（200mm 广域）",
            file = "sh2-91.jpg",
            raHours = 19 + 33 / 60.0 + 20.0 / 3600.0,
            decDeg = 31 + 14 / 60.0 + 2.0 / 3600.0,
            fovRadiusDeg = 4.1751,
            rotationDeg = 90.82,
            source = "istarshooter.com/zh/gallery/82271",
        ),
        Spec(
            name = "吃豆人 NGC 281（130apo 远程深空，窄视场）",
            file = "pacman.jpg",
            raHours = 0 + 52 / 60.0 + 30.3 / 3600.0,
            decDeg = 56 + 42 / 60.0 + 57.9 / 3600.0,
            fovRadiusDeg = 1.0420,
            rotationDeg = 351.16,
            source = "istarshooter.com/zh/gallery/82468",
            maxMag = 11.0,
            catalogFile = "stars-pacman.bin",   // Gaia DR3 子集（G<13，4° 内 19406 颗）
            subsetRadiusDeg = 3.0,
            thresholdSigma = 8.0,
            maxAspect = 1.6,
            maxArea = 120,
            expectBlindSolve = false,
        ),
    )

    @Test
    fun solvePublishedAstroPhotos() {
        val dir = listOf(File("../real-images"), File("../star-positioning-real-images"))
            .firstOrNull { it.isDirectory }
        if (dir == null) {
            println("[跳过] 真实照片素材已移出仓库（找不到 real-images 目录）")
            return
        }
        var solved = 0
        for (spec in specs) {
            val file = File(dir, spec.file)
            if (!file.isFile) {
                println("[跳过] ${spec.name}: 缺少 ${file.absolutePath}")
                continue
            }
            val img = ImageIO.read(file)
            val w = img.width
            val h = img.height
            val lum = FloatArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                img.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val p = row[x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    lum[y * w + x] = ((0.299 * r + 0.587 * g + 0.114 * b) / 255.0).toFloat()
                }
            }
            // 视场半径按"半对角线"理解：fx = (diag/2) / tan(半径)
            val diag = hypot(w.toDouble(), h.toDouble())
            val fx = (diag / 2) / tan(Math.toRadians(spec.fovRadiusDeg))
            val camera = CameraIntrinsics(w, h, fx, fx, w / 2.0, h / 2.0)
            val raRad = spec.raHours * 15 * DEG
            val decRad = spec.decDeg * DEG
            val center = V.fromRaDec(raRad, decRad)

            val cat = spec.catalogFile?.let { name ->
                File(dir, name).takeIf { it.isFile }?.let { StarCatalog.fromFile(it) }
            } ?: Fixtures.catalog
            val maxMag = if (spec.catalogFile != null) spec.maxMag else 7.0
            val ids = (0 until cat.size).filter {
                cat.vmag(it) <= maxMag && V.angle(cat.icrs(it, AstroTime.J2000), center) < spec.subsetRadiusDeg * DEG
            }
            // 星表深度守卫：视场内星太少时不可能可靠匹配
            val inField = ids.count {
                V.angle(cat.icrs(it, AstroTime.J2000), center) < spec.fovRadiusDeg * DEG
            }
            if (inField < 40) {
                println("=== ${spec.name} ===")
                println("  来源 ${spec.source}")
                println("  ⚠ 星表深度不足：视场内 mag≤%.1f 只有 %d 颗（<40），跳过。".format(maxMag, inField))
                continue
            }

            val sub = StarCatalog(
                ids.map { cat.ra[it] }.toDoubleArray(),
                ids.map { cat.dec[it] }.toDoubleArray(),
                ids.map { cat.mag[it] }.toDoubleArray(),
                ids.map { cat.pmRa[it] }.toDoubleArray(),
                ids.map { cat.pmDec[it] }.toDoubleArray(),
                ids.map { cat.hr[it] }.toIntArray(),
            )
            val index = SkyIndex(sub, magLimit = maxMag)
            val solver = PlateSolver(index, camera, triangleMagLimit = maxMag, maxResidualArcsec = 90.0)
            val detAll = FrameAnalyzer.analyze(lum, w, h, thresholdSigma = spec.thresholdSigma)
            val det = detAll.detections.filter { it.aspect < spec.maxAspect && it.area in 3..spec.maxArea }

            val t0 = System.nanoTime()
            val sol = runCatching {
                solver.solve(
                    det,
                    maxImageStars = 60,
                    maxHypotheses = 200_000,
                    matchToleranceDeg = if (spec.expectBlindSolve) 0.08 else 0.03,
                )
            }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000

            println("=== ${spec.name} ===")
            println("  来源 ${spec.source}")
            println("  图像 ${w}×${h}；fx=%.0f px（视场半径 %.2f°）；星表 mag≤%.1f，视场内 %d 颗 / 局部 %d 颗".format(
                fx, spec.fovRadiusDeg, maxMag, inField, sub.size))
            println("  检测 ${detAll.detections.size} 颗（像星 ${det.size}）；分块背景 %.3f / 噪声 %.4f".format(
                detAll.quality.background, detAll.quality.noiseSigma))
            if (sol == null) {
                println("  ✗ 未解出姿态")
                if (spec.expectBlindSolve) assertTrue("${spec.name} 应能解出", false)
                continue
            }
            val centerSolved = V.unit(apply(sol.rotation, doubleArrayOf(0.0, 0.0, 1.0)))
            val centerErrArcmin = V.angle(centerSolved, center) / DEG * 60
            val paUp = positionAngleDeg(sol.rotation, raRad, decRad)
            println("  匹配 ${sol.matchedStars} 颗，残差 RMS %.1f″，姿态 1σ %.4f°（3σ %.3f°）".format(
                sol.rmsArcsec, sol.attitudeSigmaDeg, 3 * sol.attitudeSigmaDeg))
            println("  解算中心 RA=%.5f h Dec=%.4f° vs 公布 RA=%.5f h Dec=%.4f° → 指向误差 %.2f′".format(
                normRa(V.raOf(centerSolved) * 180 / PI / 15), V.decOf(centerSolved) / DEG,
                spec.raHours, spec.decDeg, centerErrArcmin))
            println("  上方向 PA 解算 %.2f°；图页公布 %.2f°（约定未定，仅参考）".format(paUp, spec.rotationDeg))
            println("  耗时 ${ms} ms")

            if (!spec.expectBlindSolve) {
                println("  （该样本不要求盲解通过：窄视场+深星表，需先验窗口搜索，见 PROGRESS §15.5）")
                continue
            }
            solved++
            assertTrue("${spec.name} 匹配星数过少：${sol.matchedStars}", sol.matchedStars >= 5)
            assertTrue("${spec.name} 指向误差过大：${centerErrArcmin}′", centerErrArcmin < 2.0)
            assertTrue("${spec.name} 姿态 σ 过大：${3 * sol.attitudeSigmaDeg}°", 3 * sol.attitudeSigmaDeg < 0.05)
        }
        assertTrue("没有任何真实照片解算成功", solved > 0)
    }

    private fun positionAngleDeg(r: Array<DoubleArray>, raRad: Double, decRad: Double): Double {
        val up = V.unit(apply(r, doubleArrayOf(0.0, -1.0, 0.0)))
        val north = V.unit(
            doubleArrayOf(-sin(decRad) * cos(raRad), -sin(decRad) * sin(raRad), cos(decRad)),
        )
        val east = doubleArrayOf(-sin(raRad), cos(raRad), 0.0)
        val pa = Math.toDegrees(atan2(V.dot(up, east), V.dot(up, north)))
        return ((pa % 360) + 360) % 360
    }

    private fun normRa(v: Double): Double = ((v % 24) + 24) % 24
}

