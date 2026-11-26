package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.HandEye
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.ExtrinsicEstimator
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/** 用两份手持标定包验证"外参自标定"：估计值 vs 三脚架真值，并用 GNSS 真值验证定位。 */
class ExtrinsicCalibValidationTest {

    @Test
    fun `验证手持标定结果`() {
        val inbox = File("../field-inbox")
        val tripod = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        println("三脚架拟合值：%s".format(tripod?.let { "%.2f°".format(it.angleDeg) } ?: "无"))
        val zips = inbox.listFiles { f ->
            f.isFile && f.name.matches(Regex("session-20261119-03(1054|1200)\\.zip"))
        }?.sortedBy { it.name } ?: return
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                // 先诊断：每帧在不同阈值下的检测数
                val ms0 = reader.frameMetas()
                for (m in ms0.take(4)) {
                    val fr = reader.loadFrame(m.index)
                    val c5 = FrameAnalyzer.analyze(fr.luminance, fr.width, fr.height, thresholdSigma = 5.0)
                    val c7 = FrameAnalyzer.analyze(fr.luminance, fr.width, fr.height, thresholdSigma = 7.0)
                    val sorted = fr.luminance.sortedArray()
                    val lo = sorted[(sorted.size * 0.02).toInt()]
                    val hi = sorted[(sorted.size * 0.999).toInt()]
                    println("  帧${m.index}: exp=%.0fms det(5σ)=%d det(7σ)=%d 背景=%.4f 噪声=%.4f p99.9=%.4f".format(
                        m.exposureNs / 1e6, c5.detections.size, c7.detections.size,
                        c5.quality.background, c5.quality.noiseSigma, hi))
                }
                val est = ExtrinsicEstimator.estimate(reader, Fixtures.catalog)
                println("=== ${zip.name.removeSuffix(".zip")} ===")
                println("  标定结果：${est.message}")
                val cfg = est.config
                if (cfg != null) {
                    println("  修正向量 (%.4f, %.4f, %.4f) rad = %.2f°；与三脚架值差 %.2f°".format(
                        cfg.rx, cfg.ry, cfg.rz, cfg.angleDeg,
                        tripod?.let {
                            val d = HandEye.rotationVector(
                                com.dsh.starpos.astro.Rodrigues.exp(
                                    cfg.rx - it.rx, cfg.ry - it.ry, cfg.rz - it.rz,
                                ),
                            )
                            Math.toDegrees(V.norm(d))
                        } ?: Double.NaN,
                    ))
                }
                // 用三种配置算定位误差
                val truth = reader.readGnss().lastOrNull()
                val imu = reader.readImu()
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
                for (i in acc.indices) acc[i] /= n
                val intr = reader.manifest.intrinsics ?: return@use
                val cam = intr.toCameraIntrinsics(w, h)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val det = FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 8.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull()
                if (sol == null || truth == null) { println("  （姿态或真值缺失，跳过定位验证）"); return@use }
                val meta = metas.first()
                val g = FieldAnalyzer.gravityAt(imu, meta, 90)
                val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
                val near = imu.filter { Math.abs(it.tNs - anchor) <= 750_000_000L }
                val gyro = if (near.isEmpty()) 0.0 else
                    near.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average()
                val ctx = SkyContext.at(meta.wallClockUtcMs)

                fun err(up: DoubleArray): Double {
                    val p = PositionSolver.solve(sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005)
                    val f = p.fix ?: return Double.NaN
                    return FieldAnalyzer.haversineKm(f.latDeg, f.lonDeg, truth.lat, truth.lon)
                }
                println("  定位误差：无外参 %.1f km / 三脚架值 %.1f km / 自标定值 %.1f km".format(
                    err(g.upCam),
                    tripod?.let { err(it.applyTo(g.upCam)) } ?: Double.NaN,
                    cfg?.let { err(it.applyTo(g.upCam)) } ?: Double.NaN,
                ))
                println("  采集时陀螺均值 %.3f rad/s；重力 σ=%.2f°；匹配 %d 颗 rms=%.0f″".format(
                    gyro, g.sigmaDeg, sol.matchedStars, sol.rmsArcsec))
            }
        }
    }
}

