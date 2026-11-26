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
import org.junit.Test

/**
 * 用 HDR 两体数据拟合外参修正 Δ，再用**留出数据**（15:xx/16:xx 太阳、19:00 月亮）
 * 检验"太阳/月亮高度残差"是否真的下降。
 */
class ExtrinsicRefitValidateTest {

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

    private fun mat3(m: DoubleArray) = arrayOf(
        doubleArrayOf(m[0], m[1], m[2]),
        doubleArrayOf(m[3], m[4], m[5]),
        doubleArrayOf(m[6], m[7], m[8]),
    )

    /** 从 11 组 HDR 数据拟合 Δ（upTrue ≈ Δ·upImu）。 */
    private fun fitDelta(root: File, extrinsic: ExtrinsicConfig?): DoubleArray? {
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name >= "session-20261121-200000" && f.name < "session-20261121-213000"
        }?.sortedBy { it.name } ?: return null
        val upTrueList = ArrayList<DoubleArray>()
        val upImuList = ArrayList<DoubleArray>()
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
                    moonF = fi; moonDir = V.unit(fi.cam.unproject(fit.cx, fit.cy)); moonEp = ep
                    break
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
                upTrueList.add(upTrue)
                upImuList.add(V.unit(g.upCam))
            }
        }
        if (upTrueList.size < 3) return null
        return runCatching { Attitude.wahba(upImuList, upTrueList) }.getOrNull()?.let { m ->
            DoubleArray(9) { m[it / 3][it % 3] }
        }
    }

    @Test
    fun `两体拟合外参的留出验证`() {
        val root = projectRoot()
        val current = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val delta = fitDelta(root, current) ?: run { println("拟合失败"); return }
        // 当前外参矩阵
        val rx = current?.rx ?: 0.0; val ry = current?.ry ?: 0.0; val rz = current?.rz ?: 0.0
        val mCur = if (current?.m != null) current.m!! else doubleArrayOf(
            1.0, -rz, ry,
            rz, 1.0, -rx,
            -ry, rx, 1.0,
        )
        // M_new = Δ · M_cur
        val d3 = mat3(delta); val c3 = mat3(mCur)
        val newM = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += d3[i][k] * c3[k][j]
            newM[i * 3 + j] = s
        }
        val trace = delta[0] + delta[4] + delta[8]
        val dAng = Math.toDegrees(Math.acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
        val nTrace = newM[0] + newM[4] + newM[8]
        val nAng = Math.toDegrees(Math.acos(((nTrace - 1) / 2).coerceIn(-1.0, 1.0)))
        println("Δ 旋转 %.2f°；当前外参 %.2f°；合成后新外参 %.2f°".format(dAng, current?.angleDeg ?: 0.0, nAng))
        val newCfg = ExtrinsicConfig(0.0, 0.0, 0.0, note = "two-body refit", source = "test", m = newM)

        // 留出集：15:xx/16:xx 太阳、19:00 月亮
        val holdout = File(root, "field-inbox").listFiles { f ->
            f.name.endsWith(".zip") && (
                f.name.startsWith("session-20261121-15") || f.name.startsWith("session-20261121-16") ||
                    f.name.startsWith("session-20261121-190")
                )
        }?.sortedBy { it.name } ?: return
        var sumCur = 0.0
        var sumNew = 0.0
        var n = 0
        for (zip in holdout) {
            BundleReader.open(zip).use { reader ->
                val aCur = FieldAnalyzer.analyze(reader, Fixtures.catalog, current)
                val aNew = FieldAnalyzer.analyze(reader, Fixtures.catalog, newCfg)
                fun resid(a: com.dsh.starpos.field.SessionAnalysis): Double {
                    for (f in a.findings) {
                        Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let { return it.groupValues[1].toDouble() }
                    }
                    return Double.NaN
                }
                val rc = resid(aCur); val rn = resid(aNew)
                if (rc.isNaN() || rn.isNaN()) return@use
                println("%s: 高度残差 当前 %+.3f° → 新 %+.3f°".format(zip.name, rc, rn))
                sumCur += Math.abs(rc); sumNew += Math.abs(rn); n++
            }
        }
        if (n > 0) {
            println("留出集 %d 个包：|残差| 均值 当前 %.3f° → 新 %.3f°".format(n, sumCur / n, sumNew / n))
        }
    }
}
