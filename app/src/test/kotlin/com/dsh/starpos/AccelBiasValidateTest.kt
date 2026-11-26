// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import org.junit.Test

/**
 * 把手机六位置标定得到的零偏 (26.3, -20.0, 1.4) mg 应用到历史数据：
 * 1) 11 组 HDR：两体天顶 vs IMU 天顶的夹角（标定前后）；
 * 2) 留出集太阳包：高度残差（标定前后）。
 */
class AccelBiasValidateTest {

    private val biasMg = doubleArrayOf(26.3, -20.0, 1.4)

    private fun biasMps2(): DoubleArray {
        val g = 9.80665
        return doubleArrayOf(biasMg[0] / 1000 * g, biasMg[1] / 1000 * g, biasMg[2] / 1000 * g)
    }

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun enu(alt: Double, az: Double): DoubleArray {
        val ca = Math.cos(alt)
        return doubleArrayOf(ca * Math.sin(az), ca * Math.cos(az), Math.sin(alt))
    }

    /** 11 组 HDR：两体天顶 vs IMU 天顶（度）。 */
    private fun zenithMismatch(root: File, extrinsic: ExtrinsicConfig?): List<Double> {
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Double>()
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                data class F(val meta: com.dsh.starpos.field.FrameMeta, val cam: com.dsh.starpos.astro.CameraIntrinsics, val f: com.dsh.starpos.field.LoadedFrame, val gast: Double)
                val infos = reader.frameMetas().mapNotNull { m ->
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: return@mapNotNull null
                    F(m, intr.toCameraIntrinsics(f.width, f.height), f, SkyContext.at(m.wallClockUtcMs).gast)
                }
                var moonF: F? = null; var moonDir: DoubleArray? = null; var moonEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs < 100_000_000L }) {
                    val fit = Moon.fitDisk(fi.f.luminance, fi.f.width, fi.f.height, minAreaPx = 32, minRadiusPx = 2.5) ?: continue
                    val ep = Moon.ephemerisAt(fi.meta.wallClockUtcMs)
                    val expPx = Math.tan(ep.angularRadiusRad) * fi.cam.fx
                    if (fit.radiusPx < 0.3 * expPx || fit.radiusPx > 40.0 * expPx) continue
                    moonF = fi; moonDir = V.unit(fi.cam.unproject(fit.cx, fit.cy)); moonEp = ep; break
                }
                if (moonF == null || moonDir == null || moonEp == null) return@use
                var satF: F? = null; var satDir: DoubleArray? = null; var satEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs >= 500_000_000L }) {
                    val sat = Planets.geocentric(Planets.Body.SATURN, fi.meta.wallClockUtcMs)
                    val sepEphem = Math.toDegrees(V.angle(V.fromRaDec(moonEp.raRad, moonEp.decRad), V.fromRaDec(sat.raRad, sat.decRad)))
                    val satAlt = Math.toDegrees(Moon.altitudeRad(site, sat, fi.meta.wallClockUtcMs, fi.gast))
                    val g = FieldAnalyzer.gravityAt(imu, fi.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (g.sigmaDeg > 5.0) continue
                    val up = V.unit(g.upCam)
                    val det = FrameAnalyzer.analyze(fi.f.luminance, fi.f.width, fi.f.height, thresholdSigma = 6.0, maxComponents = 4000)
                    var best: Pair<DoubleArray, Double>? = null
                    for (d in det.detections) {
                        if (d.peak < 0.5f) continue
                        val dir = V.unit(fi.cam.unproject(d.x.toDouble(), d.y.toDouble()))
                        val sep = Math.toDegrees(V.angle(V.unit(moonDir), dir))
                        val alt = Math.toDegrees(Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)))
                        val score = Math.abs(sep - sepEphem) + Math.abs(alt - satAlt)
                        if (score > 2.5) continue
                        if (best == null || score < best.second) best = dir to score
                    }
                    if (best != null) { satF = fi; satDir = best.first; satEp = sat; break }
                }
                if (satF == null || satDir == null || satEp == null) return@use
                val r = runCatching {
                    Attitude.wahba(
                        listOf(
                            enu(Moon.altitudeRad(site, moonEp, moonF.meta.wallClockUtcMs, moonF.gast), Moon.azimuthRad(site, moonEp, moonF.gast)),
                            enu(Moon.altitudeRad(site, satEp, satF.meta.wallClockUtcMs, satF.gast), Moon.azimuthRad(site, satEp, satF.gast)),
                        ),
                        listOf(moonDir, satDir),
                    )
                }.getOrNull() ?: return@use
                val upTrue = V.unit(apply(r, doubleArrayOf(0.0, 0.0, 1.0)))
                val g = FieldAnalyzer.gravityAt(imu, moonF.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                if (g.sigmaDeg > 5.0) return@use
                val upImu = V.unit(g.upCam)
                out.add(Math.toDegrees(acos((upTrue[0] * upImu[0] + upTrue[1] * upImu[1] + upTrue[2] * upImu[2]).coerceIn(-1.0, 1.0))))
            }
        }
        return out
    }

    /** 返回每组的 (upTrue, upImu) 便于拟合 Δ。 */
    private fun zenithPairs(root: File, extrinsic: ExtrinsicConfig?): List<Pair<DoubleArray, DoubleArray>> {
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Pair<DoubleArray, DoubleArray>>()
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                data class F(val meta: com.dsh.starpos.field.FrameMeta, val cam: com.dsh.starpos.astro.CameraIntrinsics, val f: com.dsh.starpos.field.LoadedFrame, val gast: Double)
                val infos = reader.frameMetas().mapNotNull { m ->
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: return@mapNotNull null
                    F(m, intr.toCameraIntrinsics(f.width, f.height), f, SkyContext.at(m.wallClockUtcMs).gast)
                }
                var moonF: F? = null; var moonDir: DoubleArray? = null; var moonEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs < 100_000_000L }) {
                    val fit = Moon.fitDisk(fi.f.luminance, fi.f.width, fi.f.height, minAreaPx = 32, minRadiusPx = 2.5) ?: continue
                    val ep = Moon.ephemerisAt(fi.meta.wallClockUtcMs)
                    val expPx = Math.tan(ep.angularRadiusRad) * fi.cam.fx
                    if (fit.radiusPx < 0.3 * expPx || fit.radiusPx > 40.0 * expPx) continue
                    moonF = fi; moonDir = V.unit(fi.cam.unproject(fit.cx, fit.cy)); moonEp = ep; break
                }
                if (moonF == null || moonDir == null || moonEp == null) return@use
                var satF: F? = null; var satDir: DoubleArray? = null; var satEp: Moon.Ephemeris? = null
                for (fi in infos.filter { it.meta.exposureNs >= 500_000_000L }) {
                    val sat = Planets.geocentric(Planets.Body.SATURN, fi.meta.wallClockUtcMs)
                    val sepEphem = Math.toDegrees(V.angle(V.fromRaDec(moonEp.raRad, moonEp.decRad), V.fromRaDec(sat.raRad, sat.decRad)))
                    val satAlt = Math.toDegrees(Moon.altitudeRad(site, sat, fi.meta.wallClockUtcMs, fi.gast))
                    val g = FieldAnalyzer.gravityAt(imu, fi.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (g.sigmaDeg > 5.0) continue
                    val up = V.unit(g.upCam)
                    val det = FrameAnalyzer.analyze(fi.f.luminance, fi.f.width, fi.f.height, thresholdSigma = 6.0, maxComponents = 4000)
                    var best: Pair<DoubleArray, Double>? = null
                    for (d in det.detections) {
                        if (d.peak < 0.5f) continue
                        val dir = V.unit(fi.cam.unproject(d.x.toDouble(), d.y.toDouble()))
                        val sep = Math.toDegrees(V.angle(V.unit(moonDir), dir))
                        val alt = Math.toDegrees(Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)))
                        val score = Math.abs(sep - sepEphem) + Math.abs(alt - satAlt)
                        if (score > 2.5) continue
                        if (best == null || score < best.second) best = dir to score
                    }
                    if (best != null) { satF = fi; satDir = best.first; satEp = sat; break }
                }
                if (satF == null || satDir == null || satEp == null) return@use
                val r = runCatching {
                    Attitude.wahba(
                        listOf(
                            enu(Moon.altitudeRad(site, moonEp, moonF.meta.wallClockUtcMs, moonF.gast), Moon.azimuthRad(site, moonEp, moonF.gast)),
                            enu(Moon.altitudeRad(site, satEp, satF.meta.wallClockUtcMs, satF.gast), Moon.azimuthRad(site, satEp, satF.gast)),
                        ),
                        listOf(moonDir, satDir),
                    )
                }.getOrNull() ?: return@use
                val upTrue = V.unit(apply(r, doubleArrayOf(0.0, 0.0, 1.0)))
                val g = FieldAnalyzer.gravityAt(imu, moonF.meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                if (g.sigmaDeg > 5.0) return@use
                out.add(upTrue to V.unit(g.upCam))
            }
        }
        return out
    }

    @Test
    fun `标定零偏应用到历史数据`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        FieldAnalyzer.accelBias = null
        val base = zenithMismatch(root, extrinsic)
        println("标定前：两体天顶 vs IMU 天顶 = ${base.map { "%.2f".format(it) }}  均值 %.2f°".format(base.average()))
        FieldAnalyzer.accelBias = biasMps2()
        val after = zenithMismatch(root, extrinsic)
        println("应用零偏 (%s mg) 后：${after.map { "%.2f".format(it) }}  均值 %.2f°".format(
            biasMg.joinToString(","), after.average()))
        println("零偏等效倾斜 %.2f°".format(
            Math.toDegrees(kotlin.math.atan(Math.sqrt(biasMps2().let { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }) / 9.80665)),
        ))

        // 留出集太阳包：高度残差
        val holdout = File(root, "field-inbox").listFiles { f ->
            f.name.endsWith(".zip") && (f.name.startsWith("session-20261121-15") || f.name.startsWith("session-20261121-16"))
        }?.sortedBy { it.name } ?: return
        fun residMean(bias: DoubleArray?): Double {
            FieldAnalyzer.accelBias = bias
            var sum = 0.0; var n = 0
            for (zip in holdout) {
                BundleReader.open(zip).use { reader ->
                    val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
                    for (f in a.findings) {
                        Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let { sum += Math.abs(it.groupValues[1].toDouble()); n++ }
                    }
                }
            }
            return if (n > 0) sum / n else Double.NaN
        }
        val r0 = residMean(null)
        val r1 = residMean(biasMps2())
        println("留出集太阳包：|高度残差| 均值 标定前 %.3f° → 标定后 %.3f°".format(r0, r1))

        // 组合：先扣零偏，再用两体天顶重拟外参 Δ
        FieldAnalyzer.accelBias = biasMps2()
        val pairs = zenithPairs(root, extrinsic)
        val delta = runCatching { Attitude.wahba(pairs.map { it.second }, pairs.map { it.first }) }.getOrNull()
        if (delta != null) {
            val rx = extrinsic?.rx ?: 0.0; val ry = extrinsic?.ry ?: 0.0; val rz = extrinsic?.rz ?: 0.0
            val mCur = extrinsic?.m ?: doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
            val newM = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += delta[i][k] * mCur[k * 3 + j]
                newM[i * 3 + j] = s
            }
            val dAng = Math.toDegrees(acos(((delta[0][0] + delta[1][1] + delta[2][2] - 1) / 2).coerceIn(-1.0, 1.0)))
            val nAng = Math.toDegrees(acos(((newM[0] + newM[4] + newM[8] - 1) / 2).coerceIn(-1.0, 1.0)))
            println("零偏+重拟外参：Δ 旋转 %.2f°，合成外参 %.2f°（当前 %.2f°）".format(dAng, nAng, extrinsic?.angleDeg ?: 0.0))
            println("新外参矩阵 m = [${newM.joinToString(",")}]")
            val ang = Math.toRadians(nAng)
            val rvx = (newM[7] - newM[5]) / (2 * Math.sin(ang)) * ang
            val rvy = (newM[2] - newM[6]) / (2 * Math.sin(ang)) * ang
            val rvz = (newM[3] - newM[1]) / (2 * Math.sin(ang)) * ang
            println("新外参旋转向量 ≈ (%.5f, %.5f, %.5f) rad（弧度，小角度近似矩阵）".format(rvx, rvy, rvz))
            val newCfg = ExtrinsicConfig(0.0, 0.0, 0.0, note = "bias+delta", source = "test", m = newM)
            var sum = 0.0; var n = 0
            for (zip in holdout) {
                BundleReader.open(zip).use { reader ->
                    val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, newCfg)
                    for (f in a.findings) {
                        Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let { sum += Math.abs(it.groupValues[1].toDouble()); n++ }
                    }
                }
            }
            println("留出集太阳包（零偏+新外参）：|高度残差| 均值 %.3f°（对比：当前 %.3f°）".format(if (n > 0) sum / n else Double.NaN, r0))
        }
        FieldAnalyzer.accelBias = null
    }
}
