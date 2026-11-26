// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
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
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/**
 * 汇总：每个采集包的姿态、重力偏差、用/不用外参时的定位误差。
 * 用于判断"外参是否稳定"以及手持采集对重力估计的影响。
 */
class SessionSummaryTest {

    @Test
    fun `汇总所有真机采集包`() {
        val inbox = File("../field-inbox")
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val zips = inbox.listFiles { f -> f.isFile && f.name.startsWith("session-") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        println("外参配置：${extrinsic?.let { "%.2f°".format(it.angleDeg) } ?: "无"}")
        println("包名 | 姿态 | 静态 | 重力σ | 重力偏差 | 误差(无外参) | 误差(有外参)")
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
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
                // 两种阈值都试，取能解出的
                var sol = runCatching {
                    solver.solve(
                        FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 8.0)
                            .detections.filter { it.aspect < 2.0 && it.area >= 3 },
                    )
                }.getOrNull()
                if (sol == null) {
                    sol = runCatching {
                        solver.solve(
                            FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 12.0)
                                .detections.filter { it.aspect < 2.0 && it.area >= 3 },
                        )
                    }.getOrNull()
                }
                // 叠加失败时退化为逐帧解（手持时叠加会把星点拖糊）
                var usedFrame = -1
                if (sol == null) {
                    for (m in metas) {
                        val f = reader.loadFrame(m.index)
                        val d8 = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 10.0)
                        val s = runCatching {
                            solver.solve(d8.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                        }.getOrNull()
                        if (s != null) { sol = s; usedFrame = m.index; break }
                    }
                }
                if (sol == null) {
                    val d8 = FrameAnalyzer.analyze(first.luminance, w, h, thresholdSigma = 10.0)
                    println(
                        "${zip.name.removeSuffix(".zip")} | 未解出（单帧 det=%d） | - | - | - | - | -".format(
                            d8.detections.size,
                        ),
                    )
                    return@use
                }
                val meta = if (usedFrame >= 0) metas.first { it.index == usedFrame } else metas.first()
                val g = FieldAnalyzer.gravityAt(imu, meta, 90)
                val ctx = SkyContext.at(meta.wallClockUtcMs)
                val latRad = Math.toRadians(truth.lat)
                val lonRad = Math.toRadians(truth.lon)
                val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                val zenith = V.fromRaDec(
                    ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI), geoC,
                )
                val upTrue = V.unit(apply(transpose(sol.rotation), zenith))
                val bias = Math.toDegrees(acos(V.dot(V.unit(g.upCam), upTrue).coerceIn(-1.0, 1.0)))

                fun errKm(up: DoubleArray): Double {
                    val pos = PositionSolver.solve(sol.rotation, up, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005)
                    val fix = pos.fix ?: return Double.NaN
                    return FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
                }
                val e0 = errKm(g.upCam)
                val e1 = extrinsic?.let { errKm(it.applyTo(g.upCam)) } ?: Double.NaN
                // 陀螺强度（判断是否手持晃动）
                val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
                val near = imu.filter { Math.abs(it.tNs - anchor) <= 750_000_000L }
                val gyroMean = if (near.isEmpty()) 0.0 else
                    near.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average()

                println(
                    "%s | m=%d rms=%.0f″ | %s | %.2f° | %.2f° | %.1f km | %.1f km | gyro=%.3f/s".format(
                        zip.name.removeSuffix(".zip"), sol.matchedStars, sol.rmsArcsec,
                        if (g.valid) "是" else "否", g.sigmaDeg, bias, e0, e1, gyroMean,
                    ),
                )
            }
        }
    }
}

