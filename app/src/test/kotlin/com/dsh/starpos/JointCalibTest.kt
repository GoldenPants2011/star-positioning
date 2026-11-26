package com.dsh.starpos

import com.dsh.starpos.astro.AccelMapCalibration
import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.Precession
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import org.junit.Test

/**
 * 离线联合标定工具：用星空姿态真值，联合估计
 *   1) 加速度计 3×3 刻度/串扰映射 S；
 *   2) 残余外参小旋转 C（设备系）。
 * 同时打印各会话的指向（高度/方位），便于找出"同位置纯俯仰"的数据。
 */
class JointCalibTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun inv3(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    private fun matMul3(x: DoubleArray, y: Array<DoubleArray>): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var s = 0.0
            for (k in 0..2) s += x[r * 3 + k] * y[k][c]
            out[r * 3 + c] = s
        }
        return out
    }

    private fun arr(m: DoubleArray, r: Int) = doubleArrayOf(m[r * 3], m[r * 3 + 1], m[r * 3 + 2])

    private fun mat3(m: DoubleArray) = arrayOf(arr(m, 0), arr(m, 1), arr(m, 2))

    private fun angleDeg(u: DoubleArray, v: DoubleArray): Double =
        Math.toDegrees(acos((u[0] * v[0] + u[1] * v[1] + u[2] * v[2]).coerceIn(-1.0, 1.0)))

    private class Sample(
        val session: String,
        val frame: Int,
        val a: DoubleArray,       // 实测加速度方向（设备系，已扣零偏）
        val upCamTrue: DoubleArray,
        val eFull: DoubleArray,   // 完整 device→camera
        val altDeg: Double,
        val azDeg: Double,
    )

    @Test
    fun `联合标定与指向清单`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val eMat = extrinsic?.m ?: run {
            val rx = extrinsic?.rx ?: 0.0; val ry = extrinsic?.ry ?: 0.0; val rz = extrinsic?.rz ?: 0.0
            doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
        }
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f -> f.name.endsWith(".zip") && f.name < "session-20261122-004500" }
            ?.sortedBy { it.name } ?: return
        val index = SkyIndex(Fixtures.catalog)
        val catPos = HashMap<Int, Int>()
        index.ids.forEachIndexed { i, id -> catPos[id] = i }
        val bias = doubleArrayOf(25.3, -19.6, 1.2).map { it / 1000.0 * 9.80665 }.toDoubleArray()
        FieldAnalyzer.accelBias = null

        val samples = ArrayList<Sample>()
        val sessionsInfo = LinkedHashMap<String, MutableList<DoubleArray>>() // alt, az
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val lat = Math.toRadians(truth.lat); val lon = Math.toRadians(truth.lon)
                val so = reader.manifest.camera?.sensorOrientation ?: 90
                val mDev = DeviceFrame.deviceToCameraRear(so)
                val eFull = matMul3(eMat, mDev)
                val eFullInv = inv3(eFull)
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val filtered = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    if (filtered.size < 20) continue
                    val solver = PlateSolver(index, cam, maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx))
                    val sol = runCatching { solver.solve(filtered) }.getOrNull() ?: continue
                    if (sol.matchedStars < 8 || sol.rmsArcsec > 100.0) continue
                    val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
                    val near = imu.filter { abs(it.tNs - anchor) <= 750_000_000L }
                    if (near.size < 20) continue
                    val g = FieldAnalyzer.gravityAt(imu, meta, so, extrinsic = null)
                    if (!g.isStatic) continue
                    val mean = doubleArrayOf(
                        near.sumOf { it.ax } / near.size, near.sumOf { it.ay } / near.size, near.sumOf { it.az } / near.size,
                    )
                    val a = V.unit(doubleArrayOf(mean[0] - bias[0], mean[1] - bias[1], mean[2] - bias[2]))
                    val sc = SkyContext.at(meta.wallClockUtcMs)
                    val upTod = run {
                        var ra = sc.gast + lon
                        ra = ((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)
                        V.fromRaDec(ra, lat)
                    }
                    val prec = Precession.icrsToTod(sc.jdTt)
                    val upIcr = V.unit(apply(transpose(prec), upTod))
                    val upCamTrue = V.unit(apply(transpose(sol.rotation), upIcr))
                    val axisIcr = V.unit(apply(sol.rotation, doubleArrayOf(0.0, 0.0, 1.0)))
                    val altDeg = Math.toDegrees(asin((axisIcr[0] * upIcr[0] + axisIcr[1] * upIcr[1] + axisIcr[2] * upIcr[2]).coerceIn(-1.0, 1.0)))
                    // 方位：用 TOD 的东北天基转回 ICRS
                    val eastTod = doubleArrayOf(-Math.sin(sc.gast + lon), Math.cos(sc.gast + lon), 0.0)
                    val northTod = doubleArrayOf(
                        -Math.sin(lat) * Math.cos(sc.gast + lon), -Math.sin(lat) * Math.sin(sc.gast + lon), Math.cos(lat),
                    )
                    val eastIcr = V.unit(apply(transpose(prec), eastTod))
                    val northIcr = V.unit(apply(transpose(prec), northTod))
                    val azDeg = Math.toDegrees(Math.atan2(axisIcr[0] * eastIcr[0] + axisIcr[1] * eastIcr[1] + axisIcr[2] * eastIcr[2],
                        axisIcr[0] * northIcr[0] + axisIcr[1] * northIcr[1] + axisIcr[2] * northIcr[2]))
                    val upDevTrue = V.unit(apply(mat3(eFullInv), upCamTrue))
                    samples.add(Sample(zip.name, meta.index, a, upCamTrue, eFull, altDeg, azDeg))
                    sessionsInfo.getOrPut(zip.name) { ArrayList() }.add(doubleArrayOf(altDeg, azDeg))
                }
            }
        }
        println("样本帧：${samples.size}，会话：${sessionsInfo.size}")
        println("---- 各会话指向范围（高度 / 方位）----")
        for ((name, list) in sessionsInfo.entries.sortedBy { it.key }) {
            val alts = list.map { it[0] }
            val azs = list.map { it[1] }
            println("%s  n=%d  alt %.0f..%.0f°  az %.0f..%.0f°".format(
                name.removePrefix("session-").removeSuffix(".zip"), list.size, alts.min(), alts.max(), azs.min(), azs.max(),
            ))
        }
        if (samples.size < 10) return

        // 1) 基线：S=I，C=I
        fun resid(pred: (Sample) -> DoubleArray): Triple<Double, Double, Double> {
            var sum = 0.0; var sumSq = 0.0
            for (s in samples) {
                val ang = angleDeg(pred(s), s.upCamTrue)
                sum += ang; sumSq += ang * ang
            }
            return Triple(sum / samples.size, Math.sqrt(sumSq / samples.size), samples.size.toDouble())
        }
        val base = resid { V.unit(apply(mat3(it.eFull), it.a)) }
        println("基线（S=I）：均值 %.2f°，RMS %.2f°".format(base.first, base.second))

        // 2) 拟合 S
        val cal = AccelMapCalibration.solve(samples.map { it.a }, samples.map { V.unit(apply(mat3(inv3(it.eFull)), it.upCamTrue)) })
        if (cal == null) { println("S 拟合失败"); return }
        println("S: [%.4f %.4f %.4f / %.4f %.4f %.4f / %.4f %.4f %.4f]".format(
            cal.s[0], cal.s[1], cal.s[2], cal.s[3], cal.s[4], cal.s[5], cal.s[6], cal.s[7], cal.s[8]))
        val sOnly = resid { V.unit(apply(mat3(it.eFull), AccelMapCalibration.applyMap(cal.s, it.a))) }
        println("S 拟合后：均值 %.2f°，RMS %.2f°".format(sOnly.first, sOnly.second))

        // 3) 再拟合残余旋转 C（设备系）
        val cRot = runCatching {
            Attitude.wahba(
                samples.map { V.unit(AccelMapCalibration.applyMap(cal.s, it.a)) },
                samples.map { V.unit(apply(mat3(inv3(it.eFull)), it.upCamTrue)) },
            )
        }.getOrNull()
        if (cRot != null) {
            val cFlat = DoubleArray(9) { cRot[it / 3][it % 3] }
            val cAng = Math.toDegrees(acos(((cFlat[0] + cFlat[4] + cFlat[8] - 1) / 2).coerceIn(-1.0, 1.0)))
            val both = resid { s ->
                val up = AccelMapCalibration.applyMap(cal.s, s.a)
                V.unit(apply(mat3(s.eFull), doubleArrayOf(
                    cFlat[0] * up[0] + cFlat[1] * up[1] + cFlat[2] * up[2],
                    cFlat[3] * up[0] + cFlat[4] * up[1] + cFlat[5] * up[2],
                    cFlat[6] * up[0] + cFlat[7] * up[1] + cFlat[8] * up[2],
                )))
            }
            println("S+C（C 旋转 %.2f°）：均值 %.2f°，RMS %.2f°".format(cAng, both.first, both.second))
        }

        // ---- 会话级交叉验证：半数会话拟合 S，另一半检验 ----
        val sessNames = samples.map { it.session }.distinct().sorted()
        val fitSess = sessNames.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val fitSamples = samples.filter { it.session in fitSess }
        val testSamples = samples.filter { it.session !in fitSess }
        if (fitSamples.size >= 10 && testSamples.size >= 5) {
            val cal2 = AccelMapCalibration.solve(
                fitSamples.map { it.a },
                fitSamples.map { V.unit(apply(mat3(inv3(it.eFull)), it.upCamTrue)) },
            )
            if (cal2 != null) {
                fun meanResid(list: List<Sample>, useS: Boolean): Double {
                    var sum = 0.0
                    for (s in list) {
                        val up = if (useS) V.unit(apply(mat3(s.eFull), AccelMapCalibration.applyMap(cal2.s, s.a)))
                        else V.unit(apply(mat3(s.eFull), s.a))
                        sum += angleDeg(up, s.upCamTrue)
                    }
                    return sum / list.size
                }
                println("交叉验证（拟合 %d 会话 / 检验 %d 会话）：检验集 基线 %.2f° → S %.2f°".format(
                    fitSess.size, sessNames.size - fitSess.size,
                    meanResid(testSamples, false), meanResid(testSamples, true),
                ))
                println("  拟合集：基线 %.2f° → S %.2f°".format(meanResid(fitSamples, false), meanResid(fitSamples, true)))
            }
        }

        // ---- 纯俯仰数据残差（同一方位、不同高度）----
        println("---- 俯仰扫描会话残差（基线 → S）----")
        val pitchSweep = listOf("20261121-003520", "20261121-003559", "20261121-003721", "20261121-003748",
            "20261121-003817", "20261121-003930", "20261121-004019")
        for (nm in pitchSweep) {
            val list = samples.filter { it.session.contains(nm) }
            if (list.isEmpty()) continue
            var b = 0.0; var a2 = 0.0
            for (s in list) {
                b += angleDeg(V.unit(apply(mat3(s.eFull), s.a)), s.upCamTrue)
                a2 += angleDeg(V.unit(apply(mat3(s.eFull), AccelMapCalibration.applyMap(cal.s, s.a))), s.upCamTrue)
            }
            println("%s  alt %.0f°  az %.0f°  基线 %.2f° → S %.2f°".format(
                nm, list.map { it.altDeg }.average(), list.map { it.azDeg }.average(), b / list.size, a2 / list.size,
            ))
        }
    }
}
