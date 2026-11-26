// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.GravityEstimator
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import org.junit.Test

/** 诊断：哪个 SENSOR_ORIENTATION 能让真实数据的解算落到真值附近。 */
class OrientationDebugTest {

    @Test
    fun `四种朝向对比`() {
        val root = File("../field-inbox")
        for (name in listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
        )) {
            val zip = File(root, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                val first = reader.loadFrame(metas.first().index)
                val w = first.width
                val h = first.height
                // 叠加 5 帧提高信噪比
                val acc = FloatArray(w * h)
                var n = 0
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    if (f.width != w || f.height != h) continue
                    for (i in acc.indices) acc[i] += f.luminance[i]
                    n++
                }
                for (i in acc.indices) acc[i] /= n
                val intr = reader.manifest.intrinsics ?: return
                val cam = intr.toCameraIntrinsics(w, h)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val det = FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 8.0)
                val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                val sol = runCatching { solver.solve(starLike) }.getOrNull()
                if (sol == null) { println("$name 未解出"); continue }
                val meta = metas.first()
                for (anchoring in listOf("完成时刻", "曝光开始")) {
                for (orient in listOf(90)) {
                    val anchor = if (anchoring == "曝光开始") meta.sensorTimestampNs else 0L
                    val g = FieldAnalyzer.gravityAt(imu, meta, orient, anchor)
                    val ctx = SkyContext.at(meta.wallClockUtcMs)
                    val pos = PositionSolver.solve(
                        rotation = sol.rotation,
                        upCam = g.upCam,
                        context = ctx,
                        gravitySigmaDeg = g.sigmaDeg,
                        attitudeSigmaDeg = sol.attitudeSigmaDeg,
                        timeUncertaintySec = 0.005,
                    )
                    val fix = pos.fix ?: continue
                    val errKm = FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
                    println(
                        "%s 锚定=%s orient=%3d° → fix=%.3f,%.3f 误差=%.1f km 重力σ=%.2f° (matched=%d rms=%.1f)".format(
                            name, anchoring, orient, fix.latDeg, fix.lonDeg, errKm,
                            g.sigmaDeg, sol.matchedStars, sol.rmsArcsec,
                        ),
                    )
                }
                }
            }
        }
    }
}


