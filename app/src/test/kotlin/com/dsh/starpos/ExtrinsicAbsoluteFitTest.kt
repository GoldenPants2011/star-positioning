package com.dsh.starpos

import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.HandEye
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
 * 用 2026-11-20 00:42 标定包（8 帧、8 种姿态、带 GNSS 真值）**直接拟合外参**：
 * 每帧都有"设备系重力 g_dev"和"真实天顶在相机系的方向 z_cam"（由星图姿态 R + 真值位置算），
 * 用 Wahba 求 X（设备→相机），再转成相对离散朝向的修正量。
 *
 * 相比手眼（AX=XB）：不依赖转轴的多样性，只要姿态变化够多就能定 3 个自由度。
 */
class ExtrinsicAbsoluteFitTest {

    @Test
    fun `绝对重力拟合外参并交叉验证`() {
        val zip = File("../field-inbox/session-20261120-004205.zip")
        if (!zip.isFile) { println("缺包"); return }
        val current = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json"))
        println("现用外参：%s".format(current?.let { "%.2f°".format(it.angleDeg) } ?: "无"))

        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull()
            if (truth == null) { println("包里没有 GNSS 真值，无法做绝对拟合"); return }
            println("真值：%.5f, %.5f ±%.0f m".format(truth.lat, truth.lon, truth.accuracyM))
            val imu = reader.readImu()
            val intr = reader.manifest.intrinsics ?: return
            val metas = reader.frameMetas()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )

            val gDev = ArrayList<DoubleArray>()
            val zCam = ArrayList<DoubleArray>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                val g = FieldAnalyzer.gravityAt(
                    imu, m, reader.manifest.camera?.sensorOrientation ?: 90,
                )
                if (!g.valid) continue
                val ctx = SkyContext.at(m.wallClockUtcMs)
                val latRad = Math.toRadians(truth.lat)
                val lonRad = Math.toRadians(truth.lon)
                val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                val ra = ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)
                val zenithI = V.fromRaDec(ra, geoC)
                zCam.add(V.unit(apply(transpose(sol.rotation), zenithI)))
                gDev.add(V.unit(g.upCam))
            }
            println("可用帧：${gDev.size}")
            if (gDev.size < 4) { println("帧数不足"); return }

            // Wahba：X·g_dev ≈ z_cam（cam=g_dev 当输入，sky=z_cam 当输出）
            val x = Attitude.wahba(gDev, zCam)
            // 残差
            var sum = 0.0
            for (k in gDev.indices) {
                val ang = acos(V.dot(V.unit(apply(x, gDev[k])), zCam[k]).coerceIn(-1.0, 1.0))
                sum += ang * ang
            }
            val rmsDeg = Math.toDegrees(Math.sqrt(sum / gDev.size))
            // 注意：输入的 gDev 已经是"相机系的上方向"（FieldAnalyzer 已乘过离散朝向 X0），
            // 所以 Wahba 解出的 x 直接就是修正量 C，不能再乘 X0。
            val rv = HandEye.rotationVector(x)
            val angleDeg = Math.toDegrees(V.norm(rv))
            println("拟合 X（设备→相机）后：修正旋转 %.2f°，残差 rms %.3f°".format(angleDeg, rmsDeg))
            println("修正向量 rx=%.5f ry=%.5f rz=%.5f rad".format(rv[0], rv[1], rv[2]))
            val fitCfg = com.dsh.starpos.field.ExtrinsicConfig(rv[0], rv[1], rv[2], "absolute-fit", "fit-20261120")
            println("与现用值差 %.2f°".format(
                current?.let {
                    Math.toDegrees(
                        V.norm(
                            HandEye.rotationVector(
                                com.dsh.starpos.astro.Rodrigues.exp(
                                    rv[0] - it.rx, rv[1] - it.ry, rv[2] - it.rz,
                                ),
                            ),
                        ),
                    )
                } ?: Double.NaN,
            ))

            // 在 5 组静止真值数据上交叉验证
            val valids = listOf("011848", "012017", "012049", "044811", "044846")
            println("数据 | 无外参 | 现用值 | 新拟合")
            for (tag in valids) {
                val s = File("../field-inbox", "session-20261119-$tag.zip")
                if (!s.isFile) continue
                BundleReader.open(s).use { r2 ->
                    val t2 = r2.readGnss().lastOrNull() ?: return@use
                    val metas2 = r2.frameMetas()
                    val intr2 = r2.manifest.intrinsics ?: return@use
                    val f2 = r2.loadFrame(metas2.first().index)
                    val cam2 = intr2.toCameraIntrinsics(f2.width, f2.height)
                    val solver2 = PlateSolver(
                        SkyIndex(Fixtures.catalog), cam2,
                        maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam2.fx),
                    )
                    val det2 = FrameAnalyzer.analyze(f2.luminance, f2.width, f2.height, thresholdSigma = 7.0)
                    val sol2 = runCatching {
                        solver2.solve(det2.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: run { println("$tag 未解出"); return@use }
                    val g = FieldAnalyzer.gravityAt(
                        r2.readImu(), metas2.first(), r2.manifest.camera?.sensorOrientation ?: 90,
                    )
                    val ctx = SkyContext.at(metas2.first().wallClockUtcMs)
                    fun err(up: DoubleArray): String {
                        val p = com.dsh.starpos.astro.PositionSolver.solve(
                            sol2.rotation, up, ctx, g.sigmaDeg, sol2.attitudeSigmaDeg, 0.005,
                        )
                        val fx = p.fix ?: return "—"
                        return "%.0f km".format(FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, t2.lat, t2.lon))
                    }
                    println("%s | %s | %s | %s".format(
                        tag, err(g.upCam),
                        current?.let { err(it.applyTo(g.upCam)) } ?: "—",
                        err(fitCfg.applyTo(g.upCam)),
                    ))
                }
            }
        }
    }
}

