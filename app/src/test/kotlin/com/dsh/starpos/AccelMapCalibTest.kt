package com.dsh.starpos

import com.dsh.starpos.astro.AccelMapCalibration
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
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
import org.junit.Test

/**
 * 用星空姿态做真值，自标定**加速度计的刻度/轴间串扰矩阵 S**（六位置法只能标零偏）：
 * up_cam_true ≈ E · S · a_meas。若残差随指向变化主要来自 S，拟合后应显著下降。
 */
class AccelMapCalibTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun zenithIcrs(gast: Double, latRad: Double, lonRad: Double): DoubleArray {
        var ra = gast + lonRad
        ra = ((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)
        return V.fromRaDec(ra, latRad)
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

    @Test
    fun `加速度计刻度串扰自标定`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        // E：device→camera 线性映射（up_cam = E·up_dev）
        val eMat = extrinsic?.m ?: run {
            val rx = extrinsic?.rx ?: 0.0; val ry = extrinsic?.ry ?: 0.0; val rz = extrinsic?.rz ?: 0.0
            doubleArrayOf(1.0, -rz, ry, rz, 1.0, -rx, -ry, rx, 1.0)
        }
        fun matMul3(x: DoubleArray, y: Array<DoubleArray>): DoubleArray {
            val out = DoubleArray(9)
            for (r in 0..2) for (c in 0..2) {
                var s = 0.0
                for (k in 0..2) s += x[r * 3 + k] * y[k][c]
                out[r * 3 + c] = s
            }
            return out
        }
        var eInv = inv3(eMat)

        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") &&
                (f.name >= "session-20261122-000000" || f.name >= "session-20261120-050000" && f.name < "session-20261121-010000")
        }?.sortedBy { it.name } ?: return
        val index = SkyIndex(Fixtures.catalog)
        val catPos = HashMap<Int, Int>()
        index.ids.forEachIndexed { i, id -> catPos[id] = i }
        FieldAnalyzer.accelBias = doubleArrayOf(25.3, -19.6, 1.2).map { it / 1000.0 * 9.80665 }.toDoubleArray()

        val aList = ArrayList<DoubleArray>()
        val bList = ArrayList<DoubleArray>()
        val eFullList = ArrayList<DoubleArray>()
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@use
                val lat = Math.toRadians(truth.lat); val lon = Math.toRadians(truth.lon)
                val sensorOrientation = reader.manifest.camera?.sensorOrientation ?: 90
                val mDev = com.dsh.starpos.astro.DeviceFrame.deviceToCameraRear(sensorOrientation)
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
                    val sc = SkyContext.at(meta.wallClockUtcMs)
                    val upTod = zenithIcrs(sc.gast, lat, lon)
                    val prec = com.dsh.starpos.astro.Precession.icrsToTod(sc.jdTt)
                    val upIcr = V.unit(apply(transpose(prec), upTod))
                    val upCamTrue = V.unit(apply(transpose(sol.rotation), upIcr))
                    // 设备系真值方向
                    val upDevTrue = V.unit(apply(arrayOf(
                        doubleArrayOf(eFullInv[0], eFullInv[1], eFullInv[2]),
                        doubleArrayOf(eFullInv[3], eFullInv[4], eFullInv[5]),
                        doubleArrayOf(eFullInv[6], eFullInv[7], eFullInv[8]),
                    ), upCamTrue))
                    // 实测平均加速度（已扣零偏）
                    val g = FieldAnalyzer.gravityAt(imu, meta, sensorOrientation, extrinsic = null)
                    if (!g.isStatic) continue
                    // gravityAt 返回的是修正后的 up_cam；这里要原始 accel 平均：从 imu 取窗
                    val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
                    val near = imu.filter { abs(it.tNs - anchor) <= 750_000_000L }
                    if (near.size < 20) continue
                    val b = FieldAnalyzer.accelBias
                    val ax = near.sumOf { it.ax } / near.size - (b?.get(0) ?: 0.0)
                    val ay = near.sumOf { it.ay } / near.size - (b?.get(1) ?: 0.0)
                    val az = near.sumOf { it.az } / near.size - (b?.get(2) ?: 0.0)
                    val a = V.unit(doubleArrayOf(ax, ay, az))
                    aList.add(a)
                    bList.add(upDevTrue)
                    eFullList.add(eFull)
                }
            }
        }
        println("样本帧：${aList.size}")
        if (aList.size < 8) return
        val cal = AccelMapCalibration.solve(aList, bList) ?: run { println("拟合失败"); return }
        println("S 矩阵：")
        for (r in 0..2) println("  [%.4f %.4f %.4f]".format(cal.s[r * 3], cal.s[r * 3 + 1], cal.s[r * 3 + 2]))
        println("刻度 = (%.4f, %.4f, %.4f)，轴间串扰最大 %.3f°".format(cal.s[0], cal.s[4], cal.s[8], cal.maxCrossDeg))
        // 残差对比
        var before = 0.0
        var after = 0.0
        for (i in aList.indices) {
            val ef = eFullList[i]
            val e = arrayOf(
                doubleArrayOf(ef[0], ef[1], ef[2]),
                doubleArrayOf(ef[3], ef[4], ef[5]),
                doubleArrayOf(ef[6], ef[7], ef[8]),
            )
            val pred0 = V.unit(apply(e, aList[i]))
            val targetCam = V.unit(apply(e, bList[i]))
            val pred1 = V.unit(apply(e, AccelMapCalibration.applyMap(cal.s, aList[i])))
            before += Math.toDegrees(acos((pred0[0] * targetCam[0] + pred0[1] * targetCam[1] + pred0[2] * targetCam[2]).coerceIn(-1.0, 1.0)))
            after += Math.toDegrees(acos((pred1[0] * targetCam[0] + pred1[1] * targetCam[1] + pred1[2] * targetCam[2]).coerceIn(-1.0, 1.0)))
        }
        println("天顶残差均值：S=I %.2f° → 拟合 S 后 %.2f°".format(before / aList.size, after / aList.size))
        FieldAnalyzer.accelBias = null
    }
}
