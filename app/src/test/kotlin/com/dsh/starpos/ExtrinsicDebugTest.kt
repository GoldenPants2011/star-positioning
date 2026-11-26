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
import org.junit.Test

/**
 * 估计相机↔IMU 的外参误差（需求 FR-03）：
 * 用真值天顶反推"相机坐标系里真正的天顶方向"，与实际重力估计比较。
 */
class ExtrinsicDebugTest {

    @Test
    fun `估计重力方向偏差`() {
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
                val sol = runCatching { solver.solve(starLike) }.getOrNull() ?: continue
                val meta = metas.first()
                val g = FieldAnalyzer.gravityAt(imu, meta, 90)
                val ctx = SkyContext.at(meta.wallClockUtcMs)
                val latRad = Math.toRadians(truth.lat)
                val lonRad = Math.toRadians(truth.lon)
                val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                val zenith = V.fromRaDec(
                    ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI), geoC,
                )
                // 真值天顶在相机坐标系的表示
                val upTrue = V.unit(apply(transpose(sol.rotation), zenith))
                val ang = Math.toDegrees(acos(V.dot(V.unit(g.upCam), upTrue).coerceIn(-1.0, 1.0)))
                val axis = V.unit(V.cross(g.upCam, upTrue))
                println(
                    "%s: 重力偏差 %.2f°  轴(cam)=(%.3f, %.3f, %.3f)  up估计=(%.3f,%.3f,%.3f) up真=(%.3f,%.3f,%.3f)".format(
                        name, ang, axis[0], axis[1], axis[2],
                        g.upCam[0], g.upCam[1], g.upCam[2],
                        upTrue[0], upTrue[1], upTrue[2],
                    ),
                )
            }
        }
    }
}
