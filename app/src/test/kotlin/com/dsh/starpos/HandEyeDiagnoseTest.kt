// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.HandEye
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.field.FieldAnalyzer
import kotlin.math.acos
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.astro.transpose
import java.io.File
import kotlin.math.max
import org.junit.Test

/** 诊断标定包：逐帧姿态 + 每对"星图相对旋转 vs 陀螺积分"，判断数据能否做手眼标定。 */
class HandEyeDiagnoseTest {

    @Test
    fun `标定包逐帧与逐对诊断`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        if (!zip.isFile) { println("缺包"); return }
        BundleReader.open(zip).use { reader ->
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return@use
            val imu = reader.readImu()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            data class S(val m: com.dsh.starpos.field.FrameMeta, val rot: Array<DoubleArray>, val matched: Int)
            val solved = ArrayList<S>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull()
                println("帧${m.index}: det=%d %s".format(
                    det.detections.size,
                    sol?.let { "m=%d rms=%.0f″ 3σ=%.3f°".format(it.matchedStars, it.rmsArcsec, Math.toDegrees(3 * it.attitudeSigmaDeg)) }
                        ?: "未解出",
                ))
                if (sol != null) solved.add(S(m, sol.rotation, sol.matchedStars))
            }
            println()
            println("对 | 星图相对角 | 陀螺角 | 差 | 星点匹配(帧i,帧j)")
            for (k in 0 until solved.size - 1) {
                val a = solved[k]
                val b = solved[k + 1]
                val rel = mul(transpose(a.rot), b.rot)
                val g = HandEye.integrateGyro(imu, a.m.sensorTimestampNs, b.m.sensorTimestampNs) ?: continue
                val angA = Math.toDegrees(V.norm(HandEye.rotationVector(rel)))
                val angG = Math.toDegrees(V.norm(HandEye.rotationVector(g)))
                println("%d-%d | %.2f° | %.2f° | %+.2f° | m=%d,%d".format(
                    a.m.index, b.m.index, angA, angG, angA - angG, a.matched, b.matched,
                ))
            }
        }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val o = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) o[i][j] += a[i][k] * b[k][j]
        return o
    }

    @Test
    fun `转轴多样性检查`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        if (!zip.isFile) { println("缺包"); return }
        BundleReader.open(zip).use { reader ->
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return@use
            val imu = reader.readImu()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            val rots = ArrayList<Array<DoubleArray>>()
            val idx = ArrayList<Int>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                rots.add(sol.rotation); idx.add(m.index)
            }
            val axes = ArrayList<DoubleArray>()
            for (k in 0 until rots.size - 1) {
                val rel = mul(transpose(rots[k]), rots[k + 1])
                val rv = HandEye.rotationVector(rel)
                val n = V.norm(rv)
                if (n < Math.toRadians(1.0)) continue
                axes.add(doubleArrayOf(rv[0] / n, rv[1] / n, rv[2] / n))
                println("对 %d-%d: 角度 %.1f° 轴=(%.2f, %.2f, %.2f)".format(
                    idx[k], idx[k + 1], Math.toDegrees(n), rv[0] / n, rv[1] / n, rv[2] / n,
                ))
            }
            var maxAng = 0.0
            for (i in axes.indices) for (j in i + 1 until axes.size) {
                val ang = Math.toDegrees(acos(V.dot(axes[i], axes[j]).coerceIn(-1.0, 1.0)))
                val eff = if (ang > 90) 180 - ang else ang
                if (eff > maxAng) maxAng = eff
            }
            println("转轴最大夹角 %.1f°（<15° 说明几何退化，手眼解不稳定）".format(maxAng))
        }
    }


    @Test
    fun `用现用外参检验每对一致性`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        val cur = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json")) ?: return
        BundleReader.open(zip).use { reader ->
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return@use
            val imu = reader.readImu()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            val solved = ArrayList<Triple<Int, Array<DoubleArray>, Long>>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                solved.add(Triple(m.index, sol.rotation, m.sensorTimestampNs))
            }
            val x0 = com.dsh.starpos.astro.DeviceFrame.cameraFromDevice(90)
            // X_true = C · X0
            val c = com.dsh.starpos.astro.Rodrigues.exp(cur.rx, cur.ry, cur.rz)
            val xt = mul(c, x0)
            println("用现用 2.81° 检验每对：残差角 = 星图相对旋转 vs X·G·Xᵀ")
            for (k in 0 until solved.size - 1) {
                val a = solved[k]
                val b = solved[k + 1]
                val rel = mul(transpose(a.second), b.second)
                val g = HandEye.integrateGyroDebiased(imu, a.third, b.third, 1_000_000_000L) ?: continue
                val predicted = mul(mul(xt, g), transpose(xt))
                val rv = HandEye.rotationVector(mul(transpose(predicted), rel))
                println("对 %d-%d: 残差 %.2f°".format(a.first, b.first, Math.toDegrees(V.norm(rv))))
            }
        }
    }


    @Test
    fun `曝光中点时间戳对手眼解的影响`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        val cur = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json")) ?: return
        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull() ?: return@use
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return@use
            val imu = reader.readImu()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            data class S(val idx: Int, val rot: Array<DoubleArray>, val start: Long, val exp: Long)
            val solved = ArrayList<S>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                solved.add(S(m.index, sol.rotation, m.sensorTimestampNs, m.exposureNs))
            }
            fun solveWith(midpoint: Boolean): Pair<Double, Pair<Int, Int>> {
                val pairs = ArrayList<Pair<Array<DoubleArray>, Array<DoubleArray>>>()
                for (k in 0 until solved.size - 1) {
                    val a = solved[k]
                    val b = solved[k + 1]
                    val rel = mul(transpose(a.rot), b.rot)
                    val t0 = a.start + (if (midpoint) a.exp / 2 else 0L)
                    val t1 = b.start + (if (midpoint) b.exp / 2 else 0L)
                    val g = HandEye.integrateGyroDebiased(imu, t0, t1, a.exp) ?: continue
                    pairs.add(rel to g)
                }
                val x = HandEye.solve(pairs) ?: return Double.NaN to (0 to 0)
                val x0 = com.dsh.starpos.astro.DeviceFrame.cameraFromDevice(90)
                val corr = mul(x, transpose(x0))
                val rv = HandEye.rotationVector(corr)
                // 用该解在自身帧上算中位误差
                val errs = ArrayList<Double>()
                for (s in solved) {
                    val m = metas.first { it.index == s.idx }
                    val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90)
                    if (!g.valid) continue
                    val up = applyCorr(rv, g.upCam)
                    val ctx = SkyContext.at(m.wallClockUtcMs)
                    val p = com.dsh.starpos.astro.PositionSolver.solve(
                        s.rot, up, ctx, g.sigmaDeg, Math.toRadians(0.1), 0.005,
                    )
                    val fx = p.fix ?: continue
                    errs.add(FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon))
                }
                errs.sort()
                return Math.toDegrees(V.norm(rv)) to (errs.size to (errs.getOrNull(errs.size / 2)?.toInt() ?: -1))
            }
            val (a1, e1) = solveWith(false)
            val (a2, e2) = solveWith(true)
            println("用曝光起点：修正 %.2f° → 自身帧中位误差 %d km".format(a1, e1.second))
            println("用曝光中点：修正 %.2f° → 自身帧中位误差 %d km".format(a2, e2.second))
            println("现用 %.2f° 参考".format(cur.angleDeg))
        }
    }

    private fun applyCorr(rv: DoubleArray, up: DoubleArray): DoubleArray {
        val r = com.dsh.starpos.astro.Rodrigues.exp(rv[0], rv[1], rv[2])
        return com.dsh.starpos.astro.apply(r, up)
    }

    /** AX=XB 完整最小二乘解：min ||AX−XB||²，kron 形式求最小特征向量再投影到 SO(3)。 */
    private fun solveHandEyeKron(
        pairs: List<Pair<Array<DoubleArray>, Array<DoubleArray>>>,
    ): Array<DoubleArray>? {
        val q = Array(9) { DoubleArray(9) }
        for ((a, b) in pairs) {
            val k = Array(9) { DoubleArray(9) }
            for (r in 0..2) for (c in 0..2) {
                val idx = r * 3 + c
                for (m in 0..2) for (nn in 0..2) {
                    if (r == m) k[idx][m * 3 + nn] += a[c][nn]
                    if (c == nn) k[idx][m * 3 + r] -= b[m][r]
                }
            }
            for (i in 0..8) for (j in 0..8) {
                var s = 0.0
                for (kk in 0..8) s += k[kk][i] * k[kk][j]
                q[i][j] += s
            }
        }
        return runCatching {
            val mat = org.apache.commons.math3.linear.MatrixUtils.createRealMatrix(q)
            val eig = org.apache.commons.math3.linear.EigenDecomposition(mat)
            var bestIdx = 0
            var bestVal = Double.MAX_VALUE
            for (i in 0..8) {
                val v = eig.getRealEigenvalue(i)
                if (v < bestVal) { bestVal = v; bestIdx = i }
            }
            val vec = eig.getEigenvector(bestIdx).toArray()
            val m = Array(3) { r -> DoubleArray(3) { c -> vec[r * 3 + c] } }
            // 投影到 SO(3)
            val svd = org.apache.commons.math3.linear.SingularValueDecomposition(
                org.apache.commons.math3.linear.MatrixUtils.createRealMatrix(m),
            )
            val u = svd.u
            val v = svd.v
            val d = if (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]) < 0) -1.0 else 1.0
            Array(3) { r ->
                DoubleArray(3) { c ->
                    var s = 0.0
                    for (kk in 0..2) {
                        val dd = if (kk == 2) d else 1.0
                        s += u.getEntry(r, kk) * dd * v.getEntry(c, kk)
                    }
                    s
                }
            }
        }.getOrNull()
    }

    @Test
    fun `Kron 法解手眼并验证`() {
        val zip = File("../field-inbox/session-20261120-020344.zip")
        val cur = com.dsh.starpos.field.ExtrinsicConfig.load(File("../config/extrinsic.json")) ?: return
        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull() ?: return@use
            val metas = reader.frameMetas()
            val intr = reader.manifest.intrinsics ?: return@use
            val imu = reader.readImu()
            val first = reader.loadFrame(metas.first().index)
            val cam = intr.toCameraIntrinsics(first.width, first.height)
            val solver = PlateSolver(
                SkyIndex(Fixtures.catalog), cam,
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            data class S(val idx: Int, val rot: Array<DoubleArray>, val start: Long, val exp: Long)
            val solved = ArrayList<S>()
            for (m in metas) {
                val f = reader.loadFrame(m.index)
                val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                val sol = runCatching {
                    solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                }.getOrNull() ?: continue
                solved.add(S(m.index, sol.rotation, m.sensorTimestampNs, m.exposureNs))
            }
            val pairs = ArrayList<Pair<Array<DoubleArray>, Array<DoubleArray>>>()
            for (k in 0 until solved.size - 1) {
                val a = solved[k]
                val b = solved[k + 1]
                val rel = mul(transpose(a.rot), b.rot)
                val g = HandEye.integrateGyroDebiased(imu, a.start, b.start, a.exp) ?: continue
                pairs.add(rel to g)
            }
            val x = solveHandEyeKron(pairs) ?: run { println("Kron 解失败"); return@use }
            val x0 = com.dsh.starpos.astro.DeviceFrame.cameraFromDevice(90)
            val corr = mul(x, transpose(x0))
            val rv = HandEye.rotationVector(corr)
            println("Kron 法：修正 %.2f°（现用 %.2f°）".format(Math.toDegrees(V.norm(rv)), cur.angleDeg))
            val errs = ArrayList<Double>()
            for (s in solved) {
                val m = metas.first { it.index == s.idx }
                val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90)
                if (!g.valid) continue
                val up = com.dsh.starpos.astro.apply(
                    com.dsh.starpos.astro.Rodrigues.exp(rv[0], rv[1], rv[2]), g.upCam,
                )
                val ctx = SkyContext.at(m.wallClockUtcMs)
                val p = com.dsh.starpos.astro.PositionSolver.solve(
                    s.rot, up, ctx, g.sigmaDeg, Math.toRadians(0.1), 0.005,
                )
                val fx = p.fix ?: continue
                errs.add(FieldAnalyzer.haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon))
            }
            errs.sort()
            println("自身帧中位误差 %d km（现用值 8–65 km）".format(errs.getOrNull(errs.size / 2)?.toInt() ?: -1))
        }
    }
}
