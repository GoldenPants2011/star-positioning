// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Test

/** 排查暗场静态组 205 km 偏差：是重力方向错、还是时间/姿态问题。 */
class DarkSessionDebugTest {

    @Test
    fun `暗场组偏差来源`() {
        val root = File("../field-inbox")
        val ext = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (tag in listOf("044811", "044846", "011848")) {
            val zip = File(root, "session-20261119-$tag.zip")
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                val intr = reader.manifest.intrinsics ?: return@use
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val solver = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: continue
                    val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90)
                    val anchor = if (m.sensorTimestampNs > 0) m.sensorTimestampNs else m.elapsedRealtimeNs
                    val near = imu.filter { Math.abs(it.tNs - anchor) <= 750_000_000L }
                    val gyro = if (near.isEmpty()) 0.0 else
                        near.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average()
                    val ctx = SkyContext.at(m.wallClockUtcMs)
                    val latRad = Math.toRadians(truth.lat)
                    val lonRad = Math.toRadians(truth.lon)
                    val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                    val zenith = V.fromRaDec(
                        ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI), geoC,
                    )
                    val upTrue = V.unit(apply(transpose(sol.rotation), zenith))
                    val bias = Math.toDegrees(acos(V.dot(V.unit(g.upCam), upTrue).coerceIn(-1.0, 1.0)))
                    val upExt = ext?.applyTo(g.upCam) ?: g.upCam
                    val biasExt = Math.toDegrees(acos(V.dot(V.unit(upExt), upTrue).coerceIn(-1.0, 1.0)))
                    println(
                        "$tag 帧${m.index}: m=%d 静态=%s σg=%.2f° gyro=%.3f 偏差(无外参)=%.2f° 偏差(带外参)=%.2f°".format(
                            sol.matchedStars, g.isStatic, g.sigmaDeg, gyro, bias, biasExt,
                        ),
                    )
                }
            }
        }
    }
}
