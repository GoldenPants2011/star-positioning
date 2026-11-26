package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarSolveParams
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.FrameMeta
import com.dsh.starpos.field.ImuRow
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

/**
 * 垂直基准标定的"上限"评估（不需要拍摄）：
 * 用旧星光会话 + GNSS 真值，拟合一个**设备系**天顶倾角修正 dt，
 * 看单历元定位误差最好能压到多少，并用"留一组会话交叉验证"检验可迁移性。
 *
 * 这不是要写进 App 的标定，只是回答：值不值得专门去拍多朝向数据做垂直基准标定。
 */
class VerticalCeilingTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun matVec(m: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    )
    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val c = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i][k] * b[k][j]
            c[i][j] = s
        }
        return c
    }
    private fun inv3(a: Array<DoubleArray>): Array<DoubleArray> {
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val inv = Array(3) { DoubleArray(3) }
        inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det
        inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det
        inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det
        inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det
        inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det
        inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det
        inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det
        inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det
        inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det
        return inv
    }

    private fun extrinsicMatrix(ext: ExtrinsicConfig): Array<DoubleArray> {
        val mm = ext.m
        if (mm != null) return arrayOf(
            doubleArrayOf(mm[0], mm[1], mm[2]),
            doubleArrayOf(mm[3], mm[4], mm[5]),
            doubleArrayOf(mm[6], mm[7], mm[8]),
        )
        return arrayOf(
            doubleArrayOf(1.0, -ext.rz, ext.ry),
            doubleArrayOf(ext.rz, 1.0, -ext.rx),
            doubleArrayOf(-ext.ry, ext.rx, 1.0),
        )
    }

    private fun deviceToCamera(orientation: Int): Array<DoubleArray> {
        val m = DeviceFrame.cameraFromDevice(orientation)
        return m
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * Math.asin(sqrt(h).coerceIn(0.0, 1.0))
    }

    private class EvalFrame(
        val session: String,
        val rot: Array<DoubleArray>,
        val upBaseCam: DoubleArray,
        val devToCam: Array<DoubleArray>,
        val ctx: SkyContext,
        val gravitySigma: Double,
        val attitudeSigma: Double,
        val truthLat: Double,
        val truthLon: Double,
    )

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            for (k in col until 4) m[col][k] /= d
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }

    // ENU 误差（km）：(east, north)
    private fun enuError(fr: EvalFrame, upCam: DoubleArray): Pair<Double, Double>? {
        val pos = PositionSolver.solve(
            fr.rot, upCam, fr.ctx,
            gravitySigmaDeg = fr.gravitySigma, attitudeSigmaDeg = fr.attitudeSigma,
            timeUncertaintySec = 0.2,
        ).fix ?: return null
        val dLat = pos.latDeg - fr.truthLat
        val dLon = pos.lonDeg - fr.truthLon
        return (dLon * 111.32 * cos(Math.toRadians(fr.truthLat))) to (dLat * 111.32)
    }

    // 用设备系倾角 dt 修正，返回修正后的相机系 up
    private fun correctedUp(fr: EvalFrame, dt: DoubleArray): DoubleArray {
        // upBaseCam = M_ext · M_dev · up_dev ⇒ up_dev = (M_ext·M_dev)⁻¹ upBaseCam
        val E = fr.devToCam  // 这里存的是 M_ext·M_dev 的逆
        val upDev = V.unit(matVec(E, fr.upBaseCam))
        val upDevFix = V.unit(doubleArrayOf(upDev[0] + dt[2] * upDev[1] - dt[1] * upDev[2],
            upDev[1] + dt[0] * upDev[2] - dt[2] * upDev[0],
            upDev[2] + dt[1] * upDev[0] - dt[0] * upDev[1]))
        return upDevFix
    }

    // 注意：correctedUp 返回的是设备系修正后的 up；还需要乘回 E⁻¹（即 M_ext·M_dev）
    private fun correctedUpCam(fr: EvalFrame, dt: DoubleArray): DoubleArray {
        val invE = fr.devToCam                       // (M_ext·M_dev)⁻¹
        val E = inv3(invE)                           // M_ext·M_dev
        val upDev = V.unit(matVec(invE, fr.upBaseCam))
        val upDevFix = V.unit(
            doubleArrayOf(
                upDev[0] + dt[2] * upDev[1] - dt[1] * upDev[2],
                upDev[1] + dt[0] * upDev[2] - dt[2] * upDev[0],
                upDev[2] + dt[1] * upDev[0] - dt[0] * upDev[1],
            ),
        )
        return V.unit(matVec(E, upDevFix))
    }

    /** 拟合全局 dt（设备系小倾角），最小化位置误差平方和。 */
    private fun fitDt(frames: List<EvalFrame>): DoubleArray {
        var dt = doubleArrayOf(0.0, 0.0, 0.0)
        fun residuals(d: DoubleArray): DoubleArray {
            val out = ArrayList<Double>()
            for (fr in frames) {
                val e = enuError(fr, correctedUpCam(fr, d)) ?: continue
                out.add(e.first); out.add(e.second)
            }
            return out.toDoubleArray()
        }
        var r = residuals(dt)
        var cost = r.sumOf { it * it }
        repeat(8) {
            val n = r.size
            val j = Array(n) { DoubleArray(3) }
            val h = 1e-4
            for (k in 0 until 3) {
                val dp = dt.copyOf(); dp[k] += h
                val rp = residuals(dp)
                for (i in 0 until n) j[i][k] = (rp[i] - r[i]) / h
            }
            val jtj = Array(3) { DoubleArray(3) }
            val jtr = DoubleArray(3)
            for (i in 0 until n) for (a in 0 until 3) {
                jtr[a] += j[i][a] * r[i]
                for (b in 0 until 3) jtj[a][b] += j[i][a] * j[i][b]
            }
            for (a in 0 until 3) jtj[a][a] += 1e-8
            val delta = solve3(jtj, jtr.map { -it }.toDoubleArray())
            // 限制单步（3°）
            val step = sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2])
            val scale = if (step > 0.05) 0.05 / step else 1.0
            val cand = doubleArrayOf(dt[0] + delta[0] * scale, dt[1] + delta[1] * scale, dt[2] + delta[2] * scale)
            val rc = residuals(cand)
            val cc = rc.sumOf { it * it }
            if (cc < cost) {
                dt = cand; r = rc; cost = cc
                return@repeat
            }
        }
        return dt
    }

    private fun medians(frames: List<EvalFrame>, dt: DoubleArray): Triple<Int, Double, Double> {
        val errs = ArrayList<Double>()
        for (fr in frames) {
            val e = enuError(fr, correctedUpCam(fr, dt)) ?: continue
            errs.add(sqrt(e.first * e.first + e.second * e.second))
        }
        if (errs.isEmpty()) return Triple(0, Double.NaN, Double.NaN)
        val s = errs.sorted()
        return Triple(errs.size, s[s.size / 2], errs.average())
    }

    @Test
    fun `垂直基准标定上限与交叉验证`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: run {
            println("没有外参配置"); return
        }
        val catalog = Fixtures.catalog
        val index = SkyIndex(catalog)
        val extMat = extrinsicMatrix(extrinsic)

        val sessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261121-002708.zip",
            "session-20261121-193428.zip",
            "session-20261122-000444.zip",
            "session-20261122-190714.zip",
        )

        val frames = ArrayList<EvalFrame>()
        for (name in sessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) { println("缺文件 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu: List<ImuRow> = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                val gnss = reader.readGnss()
                if (gnss.isEmpty()) { println("$name 无 GNSS"); return@use }
                val truth = gnss[gnss.size / 2]
                val devToCam = matMul(extMat, deviceToCamera(orientation))   // M_ext·M_dev
                val invE = inv3(devToCam)
                for (meta: FrameMeta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!g.valid) continue
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0, medianPrefilter = false)
                    val starLike = det.detections.filter { StarSolveParams.accept(it, cam) }
                    if (starLike.size < 6) continue
                    val solver = PlateSolver(
                        index, cam,
                        triangleMagLimit = StarSolveParams.triangleMag(cam),
                        maxResidualArcsec = maxOf(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val plate = runCatching {
                        solver.solve(
                            starLike,
                            maxImageStars = StarSolveParams.maxImageStars(cam),
                            matchToleranceDeg = StarSolveParams.matchToleranceDeg(cam),
                            upCam = g.upCam,
                        )
                    }.getOrNull() ?: continue
                    if (plate.matchedStars < 4) continue
                    frames.add(
                        EvalFrame(
                            name, plate.rotation, g.upCam, invE, SkyContext.at(meta.wallClockUtcMs),
                            g.sigmaDeg, plate.attitudeSigmaDeg, truth.lat, truth.lon,
                        ),
                    )
                }
            }
        }
        println("可用帧：${frames.size}（${frames.map { it.session }.distinct().size} 组会话）")

        // 基线
        val zero = doubleArrayOf(0.0, 0.0, 0.0)
        val (n0, med0, avg0) = medians(frames, zero)
        println("基线（不修正）：%d 帧，中位 %.1f km，平均 %.1f km".format(n0, med0, avg0))

        // 全量拟合（上限，过拟合上界）
        val dtAll = fitDt(frames)
        val (n1, med1, avg1) = medians(frames, dtAll)
        println("全量拟合 dt=(%.3f, %.3f, %.3f)°，|dt|=%.2f°：%d 帧，中位 %.1f km，平均 %.1f km".format(
            Math.toDegrees(dtAll[0]), Math.toDegrees(dtAll[1]), Math.toDegrees(dtAll[2]),
            Math.toDegrees(sqrt(dot(dtAll, dtAll))), n1, med1, avg1,
        ))

        // 留一会话交叉验证
        val sessionsSeen = frames.map { it.session }.distinct()
        val cvErrs = ArrayList<Double>()
        for (held in sessionsSeen) {
            val train = frames.filter { it.session != held }
            val test = frames.filter { it.session == held }
            if (train.size < 6 || test.isEmpty()) continue
            val dt = fitDt(train)
            val (n, med, avg) = medians(test, dt)
            println("留出 %-32s：训练 %d 帧 → 测试 %d 帧，中位 %.1f km，平均 %.1f km".format(
                held.removeSuffix(".zip"), train.size, n, med, avg,
            ))
            for (fr in test) {
                val e = enuError(fr, correctedUpCam(fr, dt)) ?: continue
                cvErrs.add(sqrt(e.first * e.first + e.second * e.second))
            }
        }
        if (cvErrs.isNotEmpty()) {
            val s = cvErrs.sorted()
            println("交叉验证合计 %d 帧：中位 %.1f km，平均 %.1f km".format(
                cvErrs.size, s[s.size / 2], cvErrs.average(),
            ))
        }

        // 每帧 oracle（这一步只告出"仅靠 up 修正"的理论下界，不代表可实现）
        val oracle = ArrayList<Double>()
        for (fr in frames) {
            val dt = fitDt(listOf(fr))
            val e = enuError(fr, correctedUpCam(fr, dt)) ?: continue
            oracle.add(sqrt(e.first * e.first + e.second * e.second))
        }
        if (oracle.isNotEmpty()) {
            val s = oracle.sorted()
            println("逐帧 oracle（仅 up 修正的下界）：中位 %.1f km，平均 %.1f km".format(s[s.size / 2], oracle.average()))
        }
    }
}
