package com.dsh.starpos

import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import org.junit.Test

/**
 * 星空姿态能否当"天顶真值"：用星图求解的相机姿态反算天顶（不依赖重力），
 * 与 IMU 天顶比较。可验证新外参，也为"新数据用星空即可"提供依据。
 */
class StarZenithTest {

    private val biasMg = doubleArrayOf(25.3, -19.6, 1.2)

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    /** 天顶在 ICRS 的方向（忽略视差/极移，角秒级精度足够）。 */
    private fun zenithIcrs(gast: Double, latRad: Double, lonRad: Double): DoubleArray {
        var ra = gast + lonRad
        ra = ((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)
        return V.fromRaDec(ra, latRad)
    }

    @Test
    fun `星空姿态反算天顶 vs IMU 天顶`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        println("外参：${extrinsic?.angleDeg}°（${extrinsic?.source}）")
        FieldAnalyzer.accelBias = doubleArrayOf(biasMg[0] / 1000 * 9.80665, biasMg[1] / 1000 * 9.80665, biasMg[2] / 1000 * 9.80665)
        val catalog = Fixtures.catalog
        val index = SkyIndex(catalog)
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261122-000000" && f.name < "session-20261122-010000"
        }?.sortedBy { it.name } ?: return
        val upTrue = ArrayList<DoubleArray>()
        val upImu = ArrayList<DoubleArray>()
        var solvedFrames = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val siteLat = Math.toRadians(truth.lat)
                val siteLon = Math.toRadians(truth.lon)
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    // 太亮/太暗的帧跳过（月亮特写等）
                    val lum = f.luminance
                    val det = FrameAnalyzer.analyze(lum, f.width, f.height, thresholdSigma = 7.0)
                    if (det.detections.size < 20) continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val solver = PlateSolver(index, cam, maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx))
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: continue
                    // 只用质量较好的星解：≥8 颗、rms<100"（差解会把天顶真值带偏）
                    if (sol.matchedStars < 8 || sol.rmsArcsec > 100.0) continue
                    val sc = SkyContext.at(meta.wallClockUtcMs)
                    val upTod = zenithIcrs(sc.gast, siteLat, siteLon)
                    // 星解旋转是 相机→ICRS/J2000；天顶是"当日真赤道"方向 → 用岁差矩阵转回 J2000
                    val precession = com.dsh.starpos.astro.Precession.icrsToTod(sc.jdTt)
                    val upIcrs = V.unit(com.dsh.starpos.astro.apply(com.dsh.starpos.astro.transpose(precession), upTod))
                    val upCam = V.unit(apply(transpose(sol.rotation), upIcrs))
                    val g = FieldAnalyzer.gravityAt(imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (g.sigmaDeg > 5.0) continue
                    val upImuCam = V.unit(g.upCam)
                    val ang = Math.toDegrees(acos((upCam[0] * upImuCam[0] + upCam[1] * upImuCam[1] + upCam[2] * upImuCam[2]).coerceIn(-1.0, 1.0)))
                    val axisIcrs = V.unit(apply(sol.rotation, doubleArrayOf(0.0, 0.0, 1.0)))
                    val axisAlt = Math.toDegrees(Math.asin((axisIcrs[0] * upIcrs[0] + axisIcrs[1] * upIcrs[1] + axisIcrs[2] * upIcrs[2]).coerceIn(-1.0, 1.0)))
                    solvedFrames++
                    upTrue.add(upCam)
                    upImu.add(upImuCam)
                    println("%s 帧%d 星解 %d 颗 rms %.0f″ 光轴高度 %+.0f°：天顶差 %.2f°（IMU σ=%.2f° static=%s n=%d）".format(
                        zip.name.removePrefix("session-").removeSuffix(".zip"), meta.index, sol.matchedStars, sol.rmsArcsec, axisAlt, ang,
                        g.sigmaDeg, g.isStatic, g.samples,
                    ))
                }
            }
        }
        println("=".repeat(40))
        if (upTrue.size < 3) { println("解出星图的帧太少（$solvedFrames），无法统计"); return }
        val mean = upTrue.indices.map {
            Math.toDegrees(acos((upTrue[it][0] * upImu[it][0] + upTrue[it][1] * upImu[it][1] + upTrue[it][2] * upImu[it][2]).coerceIn(-1.0, 1.0)))
        }
        println("共 %d 帧：天顶差 均值 %.2f°，RMS %.2f°".format(upTrue.size, mean.average(), Math.sqrt(mean.sumOf { it * it } / mean.size)))
        val delta = runCatching { Attitude.wahba(upImu, upTrue) }.getOrNull()
        if (delta != null) {
            var sum = 0.0
            for (i in upTrue.indices) {
                val pred = V.unit(apply(delta, upImu[i]))
                val a = Math.toDegrees(acos((pred[0] * upTrue[i][0] + pred[1] * upTrue[i][1] + pred[2] * upTrue[i][2]).coerceIn(-1.0, 1.0)))
                sum += a * a
            }
            val dAng = Math.toDegrees(acos(((delta[0][0] + delta[1][1] + delta[2][2] - 1) / 2).coerceIn(-1.0, 1.0)))
            println("再拟合 Δ 旋转 %.2f°，残差 RMS %.2f°".format(dAng, Math.sqrt(sum / upTrue.size)))
            // 半集交叉验证：前一半拟合，后一半检验
            val half = upTrue.size / 2
            val dHalf = runCatching { Attitude.wahba(upImu.subList(0, half), upTrue.subList(0, half)) }.getOrNull()
            if (dHalf != null && upTrue.size - half >= 2) {
                var s2 = 0.0
                for (i in half until upTrue.size) {
                    val pred = V.unit(apply(dHalf, upImu[i]))
                    val a = Math.toDegrees(acos((pred[0] * upTrue[i][0] + pred[1] * upTrue[i][1] + pred[2] * upTrue[i][2]).coerceIn(-1.0, 1.0)))
                    s2 += a * a
                }
                println("交叉验证（前 %d 帧拟合 → 后 %d 帧检验）：RMS %.2f°".format(half, upTrue.size - half, Math.sqrt(s2 / (upTrue.size - half))))
            }
        }
        FieldAnalyzer.accelBias = null
    }
}
