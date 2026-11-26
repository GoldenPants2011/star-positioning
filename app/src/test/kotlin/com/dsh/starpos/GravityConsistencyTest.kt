// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.asin
import kotlin.math.max
import org.junit.Test

/**
 * 诊断：解出的姿态是否与 IMU 重力一致。
 * 相机光轴仰角：从解 R 得 asin(axis·zenith)，从 IMU 得 asin(upCam.z)；两者差大了就是假解。
 */
class GravityConsistencyTest {

    @Test
    fun `解算姿态与重力一致性`() {
        val root = File("../field-inbox")
        for (name in listOf(
            "session-20261119-011848.zip",
            "session-20261119-012017.zip",
            "session-20261119-012049.zip",
        )) {
            val zip = File(root, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return
                for (mode in listOf("单帧", "叠加5帧")) {
                    val metas = reader.frameMetas()
                    val first = reader.loadFrame(metas.first().index)
                    val w = first.width
                    val h = first.height
                    val acc = FloatArray(w * h)
                    var n = 0
                    if (mode == "叠加5帧") {
                        for (m in metas) {
                            val f = reader.loadFrame(m.index)
                            if (f.width != w || f.height != h) continue
                            for (i in acc.indices) acc[i] += f.luminance[i]
                            n++
                        }
                        for (i in acc.indices) acc[i] /= n
                    } else {
                        System.arraycopy(first.luminance, 0, acc, 0, acc.size)
                        n = 1
                    }
                    val cam = intr.toCameraIntrinsics(w, h)
                    val solver = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val det = FrameAnalyzer.analyze(acc, w, h, thresholdSigma = 12.0)
                    val starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    val sol = runCatching { solver.solve(starLike) }.getOrNull()
                    if (sol == null) {
                        println("$name $mode: 未解出 (像星=${starLike.size})")
                        continue
                    }
                    val meta = metas.first()
                    val g = FieldAnalyzer.gravityAt(imu, meta)
                    val ctx = SkyContext.at(meta.wallClockUtcMs)
                    val truthRow = reader.readGnss().lastOrNull()
                    val latRad = Math.toRadians(truthRow?.lat ?: 0.0)
                    val lonRad = Math.toRadians(truthRow?.lon ?: 0.0)
                    val geocentricLat = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                    val zenith = V.fromRaDec(
                        ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI),
                        geocentricLat,
                    )
                    val axis = V.unit(apply(sol.rotation, doubleArrayOf(0.0, 0.0, 1.0)))
                    val elevFromR = Math.toDegrees(asin(V.dot(axis, zenith).coerceIn(-1.0, 1.0)))
                    val elevFromImu = Math.toDegrees(asin(g.upCam[2].coerceIn(-1.0, 1.0)))
                    println(
                        "%s %s: matched=%d rms=%.1f 光轴仰角(R)=%.1f° 光轴仰角(IMU)=%.1f° 差=%.1f° 重力valid=%s σg=%.2f°"
                            .format(
                                name, mode, sol.matchedStars, sol.rmsArcsec, elevFromR, elevFromImu,
                                elevFromR - elevFromImu, g.valid, g.sigmaDeg,
                            ),
                    )
                }
            }
        }
    }
}

