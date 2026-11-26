package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 诊断：扫时间偏移，找能让定位落回真值的 Δt（检验是否时钟偏差）。 */
class TimeOffsetTest {

    @Test
    fun `扫描时间偏移`() {
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
                // 扫"滚转偏差"：把 upCam 绕相机光轴转 θ，看是否能落回真值
                val ctx = SkyContext.at(meta.wallClockUtcMs)
                var best = 1e9
                var bestRoll = 0.0
                for (deg in -600..600 step 25) {
                    val th = Math.toRadians(deg / 100.0)
                    val c = Math.cos(th)
                    val s = Math.sin(th)
                    val up = g.upCam
                    val upRot = com.dsh.starpos.astro.V.unit(
                        doubleArrayOf(c * up[0] - s * up[1], s * up[0] + c * up[1], up[2]),
                    )
                    val pos = PositionSolver.solve(
                        sol.rotation, upRot, ctx, g.sigmaDeg, sol.attitudeSigmaDeg, 0.005,
                    )
                    val fix = pos.fix ?: continue
                    val err = FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
                    if (err < best) { best = err; bestRoll = deg / 100.0 }
                }
                println("%s: 最佳滚转偏差=%+.2f°，误差 %.1f km".format(name, bestRoll, best))
            }
        }
    }
}

