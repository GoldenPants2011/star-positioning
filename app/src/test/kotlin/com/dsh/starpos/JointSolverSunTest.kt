// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.JointSolver
import java.io.File
import kotlin.math.asin
import kotlin.math.sqrt
import org.junit.Test

/**
 * 联合解面试跑：把今天 15:00–15:40 的多包太阳视高度放在一起解位置。
 *  - 高度观测来自"图像太阳质心 + 重力天顶"（含折射/视差修正的模型对比）；
 *  - 先用 GNSS 真值当初值验证几何/算法，再跑"无初值、无真值"版本；
 *  - 报告 1σ/95% 椭圆、逐包贡献、钟差/垂直偏差估计与真值对比。
 */
class JointSolverSunTest {

    private fun root(): File {
        var d = File(".").absoluteFile
        repeat(4) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile ?: return d
        }
        return File(".").absoluteFile
    }

    /** 5×5 盒均值最大值（太阳圆面中心）。 */
    private fun sunCenter(lum: FloatArray, w: Int, h: Int): Pair<Double, Double>? {
        val r = 5
        var best = -1.0
        var bx = -1
        var by = -1
        for (y in r until h - r step 3) for (x in r until w - r step 3) {
            var s = 0.0
            for (dy in -r..r) for (dx in -r..r) s += lum[(y + dy) * w + (x + dx)]
            if (s > best) { best = s; bx = x; by = y }
        }
        return if (bx < 0) null else bx.toDouble() to by.toDouble()
    }

    @Test
    fun `多包太阳联合解算试跑`() {
        val root = root()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: return
        // 与 App 一致：先扣零偏
        FieldAnalyzer.accelBias = doubleArrayOf(26.3e-3 * 9.80665, -20.0e-3 * 9.80665, 1.4e-3 * 9.80665)
        val names = listOf(
            "session-20261124-150016.zip", "session-20261124-150112.zip",
            "session-20261124-150126.zip", "session-20261124-150152.zip",
            "session-20261124-150218.zip", "session-20261124-150242.zip",
            "session-20261124-150613.zip", "session-20261124-150635.zip",
            "session-20261121-152633.zip", "session-20261121-152650.zip",
            "session-20261121-152731.zip", "session-20261121-152754.zip",
            "session-20261121-164129.zip", "session-20261121-164210.zip",
            "session-20261121-164319.zip", "session-20261121-164356.zip",
        )
        val obs = ArrayList<JointSolver.AltObs>()
        var truthLat = 0.0
        var truthLon = 0.0
        var truthN = 0
        for (name in names) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                var latSum = 0.0
                var lonSum = 0.0
                var n = 0
                for (g in reader.readGnss()) { latSum += g.lat; lonSum += g.lon; n++ }
                if (n > 0) { truthLat = latSum / n; truthLon = lonSum / n; truthN++ }
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val c = sunCenter(f.luminance, f.width, f.height) ?: continue
                    val grav = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!grav.valid) continue
                    val up = V.unit(grav.upCam)
                    val dir = V.unit(cam.unproject(c.first, c.second))
                    val alt = Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0))
                    val sigma = Math.toRadians(sqrt(grav.sigmaDeg * grav.sigmaDeg + 0.05 * 0.05))
                    println("  %s 帧%d: alt=%.2f° sigma=%.2f°".format(
                        name.removeSuffix(".zip"), meta.index, Math.toDegrees(alt), Math.toDegrees(sigma),
                    ))
                    obs.add(
                        JointSolver.AltObs(
                            utcMs = meta.wallClockUtcMs,
                            body = JointSolver.Body.SUN,
                            bodyName = "太阳",
                            apparentAltRad = alt,
                            sigmaRad = sigma,
                            session = name.removeSuffix(".zip"),
                        ),
                    )
                }
            }
        }
        // 追加月亮观测（傍晚不同方位/不同高度，改善几何）
        val moonNames = listOf(
            "session-20261121-190021.zip", "session-20261121-190101.zip",
        )
        for (name in moonNames) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 32, minRadiusPx = 2.5) ?: continue
                    val expPx = Math.tan(Moon.ephemerisAt(meta.wallClockUtcMs).angularRadiusRad) * cam.fx
                    if (fit.radiusPx < 0.3 * expPx || fit.radiusPx > 10.0 * expPx) continue
                    val grav = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!grav.valid) continue
                    val up = V.unit(grav.upCam)
                    val dir = V.unit(cam.unproject(fit.cx, fit.cy))
                    val alt = Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0))
                    val sigma = Math.toRadians(sqrt(grav.sigmaDeg * grav.sigmaDeg + 0.05 * 0.05))
                    obs.add(
                        JointSolver.AltObs(
                            utcMs = meta.wallClockUtcMs,
                            body = JointSolver.Body.MOON,
                            bodyName = "月亮",
                            apparentAltRad = alt,
                            sigmaRad = sigma,
                            session = name.removeSuffix(".zip"),
                        ),
                    )
                    println("  月亮 %s 帧%d: alt=%.2f° sigma=%.2f°".format(
                        name.removeSuffix(".zip"), meta.index, Math.toDegrees(alt), Math.toDegrees(sigma),
                    ))
                }
            }
        }
        println("观测：${obs.size} 条，来自 ${obs.map { it.session }.distinct().size} 个包；真值包 $truthN")
        if (obs.isEmpty() || truthN == 0) return
        val tLat = Math.toRadians(truthLat)
        val tLon = Math.toRadians(truthLon)

        fun show(tag: String, r: JointSolver.Result?) {
            if (r == null) { println("$tag：无解"); return }
            println("%s：lat=%.6f lon=%.6f".format(tag, r.latDeg, r.lonDeg))
            println(
                "  1σ 半轴 %.1f/%.1f m（方位 %.0f°）· 95%% 半轴 %.1f/%.1f m · 面积95 %.2f km²".format(
                    r.sigmaMajorM, r.sigmaMinorM, r.sigmaAzimuthDeg,
                    r.major95M, r.minor95M, r.area95Km2,
                ),
            )
            println(
                "  残差RMS %.3f° · dof %d · DOP %.2f · 观测 %d · 钟差 %.1fs · 垂直偏差 %.3f° · T级 %s · 真值误差 %s".format(
                    Math.toDegrees(r.residualRmsRad), r.dof, r.gdop, r.obsCount,
                    r.clockOffsetSec, Math.toDegrees(r.verticalBiasRad), r.tier,
                    r.truthErrorKm?.let { "%.1f km".format(it) } ?: "—",
                ),
            )
            r.contributions.sortedBy { it.session }.forEach {
                println("    %-28s %d 条 · rms %.3f°".format(it.session, it.count, Math.toDegrees(it.rmsRad)))
            }
            r.warnings.forEach { println("    ⚠ $it") }
        }

        // 模型对纬度的有限差分（诊断）
        run {
            val o = obs.first()
            val h = 1e-6
            val a0 = JointSolver.modelAltRad(tLat, tLon, o.utcMs, o)
            val a1 = JointSolver.modelAltRad(tLat + h, tLon, o.utcMs, o)
            val a2 = JointSolver.modelAltRad(tLat, tLon + h, o.utcMs, o)
            println("dAlt/dlat=%.2f  dAlt/dlon=%.2f（rad/rad）".format((a1 - a0) / h, (a2 - a0) / h))
        }
        // A：用真值当初值（只验证几何/算法）
        val optA = JointSolver.Options(initialLatRad = tLat, initialLonRad = tLon, systemFloorRad = Math.toRadians(0.3))
        val a = JointSolver.solve(obs, options = optA, truthLatRad = tLat, truthLonRad = tLon)
        show("A 真值初值", a)

        // B：无初值、无真值（模拟现场）
        val b = JointSolver.solve(obs, options = JointSolver.Options(systemFloorRad = Math.toRadians(0.3)), truthLatRad = tLat, truthLonRad = tLon)
        show("B 无初值（无真值模式）", b)

        // D：系统偏差迁移（参考=11-21 组，目标=今天 11-24 组）
        val ref = obs.filter { it.session.contains("20261121") }
        val target = obs.filter { it.session.contains("20261124") }
        val (bias, transferred) = JointSolver.solveWithBiasTransfer(
            refObs = ref, targetObs = obs,
            options = JointSolver.Options(systemFloorRad = Math.toRadians(0.3)),
            truthLatRad = tLat, truthLonRad = tLon,
        )
        bias?.let {
            println(
                "D 偏差迁移：参考 %d 条 · δv=%.3f° · scatter=%.3f° · δt=%.1fs".format(
                    it.count, Math.toDegrees(it.verticalBiasRad), Math.toDegrees(it.scatterRad), it.clockOffsetSec,
                ),
            )
            println("  参考组逐包偏差（°）：" + it.perSession.entries.joinToString { e -> "%s=%.3f".format(e.key.removePrefix("session-"), Math.toDegrees(e.value)) })
        }
        show("D 迁移后全部数据", transferred)

        // D2：只迁移太阳（同一天体）
        val (biasSun, transSun) = JointSolver.solveWithBiasTransfer(
            refObs = ref, targetObs = obs, bodyFilter = JointSolver.Body.SUN,
            options = JointSolver.Options(systemFloorRad = Math.toRadians(0.3)),
            truthLatRad = tLat, truthLonRad = tLon,
        )
        biasSun?.let {
            println(
                "D2 只迁移太阳：参考 %d 条 · δv=%.3f° · scatter=%.3f°".format(
                    it.count, Math.toDegrees(it.verticalBiasRad), Math.toDegrees(it.scatterRad),
                ),
            )
        }
        show("D2 迁移后全部数据（只估太阳偏差）", transSun)

        // E：反向迁移（参考=今天，目标=11-21 组）
        val (bias2, transferred2) = JointSolver.solveWithBiasTransfer(
            refObs = target, targetObs = obs,
            options = JointSolver.Options(systemFloorRad = Math.toRadians(0.3)),
            truthLatRad = tLat, truthLonRad = tLon,
        )
        bias2?.let {
            println("E 反向：参考 %d 条 · δv=%.3f° · scatter=%.3f°".format(it.count, Math.toDegrees(it.verticalBiasRad), Math.toDegrees(it.scatterRad)))
        }
        show("E 迁移后全部数据", transferred2)

        // C：无初值 + 估钟差 + 估垂直偏差
        val c = JointSolver.solve(
            obs,
            options = JointSolver.Options(estimateClock = true, estimateVertical = true, systemFloorRad = Math.toRadians(0.3)),
            truthLatRad = tLat, truthLonRad = tLon,
        )
        show("C 估 δt/δv", c)
    }
}
