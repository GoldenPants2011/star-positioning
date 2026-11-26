// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.HandEye
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.transpose
import kotlin.math.max
import kotlin.math.sqrt

/** 标定结果。 */
class ExtrinsicEstimate(
    val config: ExtrinsicConfig?,
    val pairsUsed: Int,
    val medianRotationDeg: Double,
    val message: String,
)

/**
 * 从一份采集包里估计相机↔IMU 外参（星+陀螺手眼标定）。
 *
 * 前提：拍摄时**缓慢转动**手机（每帧之间转 3–15°），曝光要短（0.1–0.25 s）以免星点拖糊。
 * 不需要 GNSS，也不需要静止。
 */
object ExtrinsicEstimator {

    fun estimate(reader: BundleReader, catalog: StarCatalog, thresholdSigma: Double = 9.0): ExtrinsicEstimate {
        val metas = reader.frameMetas()
        if (metas.size < 3) {
            return ExtrinsicEstimate(null, 0, 0.0, "帧数不足（需要 ≥3，建议 10）")
        }
        val intr = reader.manifest.intrinsics
            ?: return ExtrinsicEstimate(null, 0, 0.0, "缺少内参，无法解算")
        val imu = reader.readImu()
        val orientation = reader.manifest.camera?.sensorOrientation ?: 90

        // 逐帧解姿态；顺手剔除"曝光期间在动"的帧（标定必须用三脚架/固定支撑）
        val solved = ArrayList<Pair<FrameMeta, Array<DoubleArray>>>()
        var unsteady = 0
        for (m in metas) {
            val expEnd = m.sensorTimestampNs + m.exposureNs
            val expRows = imu.filter { it.tNs in m.sensorTimestampNs..expEnd }
            val expGyro = if (expRows.isEmpty()) Double.NaN else
                expRows.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average()
            if (!expGyro.isNaN() && expGyro > 0.02) {   // ≈1.1°/s：曝光期间在动 → 星点会糊
                unsteady++
                continue
            }
            val f = reader.loadFrame(m.index)
            val cam = intr.toCameraIntrinsics(f.width, f.height)
            val solver = PlateSolver(
                SkyIndex(catalog), cam,
                triangleMagLimit = com.dsh.starpos.astro.StarSolveParams.triangleMag(cam),
                maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
            )
            // 自适应阈值阶梯：亮天/黎明帧需要更低的阈值才能出星（7→5.5→4→9）
            var sol: com.dsh.starpos.astro.PlateSolution? = null
            for (thr in (doubleArrayOf(thresholdSigma) + com.dsh.starpos.astro.StarSolveParams.thresholds(cam))) {
                val det = FrameAnalyzer.analyze(
                    f.luminance, f.width, f.height,
                    thresholdSigma = thr, medianPrefilter = false,
                )
                val starLike = det.detections.filter { com.dsh.starpos.astro.StarSolveParams.accept(it, cam) }
                val attempt = runCatching {
                    solver.solve(
                        starLike,
                        maxImageStars = com.dsh.starpos.astro.StarSolveParams.maxImageStars(cam),
                        matchToleranceDeg = com.dsh.starpos.astro.StarSolveParams.matchToleranceDeg(cam),
                    )
                }.getOrNull()
                if (attempt != null && attempt.matchedStars >= 4) {
                    sol = attempt
                    break
                }
            }
            if (sol != null) solved.add(m to sol.rotation)
        }
        if (solved.size < 3) {
            return ExtrinsicEstimate(
                null, 0, 0.0,
                "只有 ${solved.size}/${metas.size} 帧可用（剔除晃动帧 $unsteady 帧；本标定必须用三脚架/固定支撑）",
            )
        }

        // 相邻帧：A = R_iᵀR_{i+1}，B = 陀螺积分 G_i
        val pairs = ArrayList<Pair<Array<DoubleArray>, Array<DoubleArray>>>()
        var medRot = 0.0
        val rotations = ArrayList<Double>()
        for (i in 0 until solved.size - 1) {
            val (m0, r0) = solved[i]
            val (m1, r1) = solved[i + 1]
            val a = mul(transpose(r0), r1)
            val g = HandEye.integrateGyroDebiased(
                imu, m0.sensorTimestampNs, m1.sensorTimestampNs, m0.exposureNs,
            ) ?: continue
            pairs.add(a to g)
            rotations.add(Math.toDegrees(V.norm(HandEye.rotationVector(a))))
        }
        if (rotations.isNotEmpty()) medRot = rotations.sorted()[rotations.size / 2]
        if (pairs.size < 4) {
            return ExtrinsicEstimate(
                null, pairs.size, medRot,
                "有效帧对不足（${pairs.size}，需 ≥4）：每帧前要转 5–15°，再停稳拍",
            )
        }
        if (medRot < 3.0) {
            return ExtrinsicEstimate(
                null, pairs.size, medRot,
                "帧间转动太小（中位 %.1f°，需 ≥3°）：换角度幅度不够".format(medRot),
            )
        }
        // 转轴多样性：手眼标定（AX=XB）需要至少两三个不平行的转轴，
        // 2026-11-20 实测：只绕一个轴滚转时，7 个帧对的轴最大夹角只有 1.7°，解出 49.5° 垃圾值。
        val axes = ArrayList<DoubleArray>()
        for ((rotA, _) in pairs) {
            val v = HandEye.rotationVector(rotA)
            val n = V.norm(v)
            if (n > Math.toRadians(0.5)) axes.add(doubleArrayOf(v[0] / n, v[1] / n, v[2] / n))
        }
        var axisSpread = 0.0
        for (i in axes.indices) for (j in i + 1 until axes.size) {
            val c = kotlin.math.abs(V.dot(axes[i], axes[j])).coerceIn(0.0, 1.0)
            val eff = Math.toDegrees(Math.acos(c))
            if (eff > axisSpread) axisSpread = eff
        }
        if (axes.size >= 2 && axisSpread < 15.0) {
            return ExtrinsicEstimate(
                null, pairs.size, medRot,
                ("转轴太单一（最大夹角 %.1f°）：请分别做三种动作——左右转、上下俯仰、绕光轴滚转各 3 帧"
                    + "（只绕一个轴转，手眼标定在数学上无解）").format(axisSpread),
            )
        }

        // 帧对级鲁棒剔除：跨天顶/长间隔的那一对陀螺积分可能差十几度，
        // 会把整体解带偏（2026-11-20 实测：6 对里 1 对残差 17.7° → 外参 7.98°，反而比 2.81° 差）。
        var use = pairs.toMutableList()
        var x: Array<DoubleArray>? = null
        repeat(4) {
            val sol = HandEye.solve(use) ?: return@repeat
            x = sol
            val residuals = use.map { (a, b) ->
                val pred = mulMat(mulMat(sol, b), transpose(sol))
                Math.toDegrees(V.norm(HandEye.rotationVector(mulMat(transpose(pred), a))))
            }
            val worst = residuals.maxOrNull() ?: 0.0
            if (worst <= 4.0) return@repeat
            // 去掉最差的一对再解
            val worstIdx = residuals.indexOf(worst)
            if (use.size <= 3) return@repeat
            use.removeAt(worstIdx)
        }
        val xf = x ?: return ExtrinsicEstimate(null, pairs.size, medRot, "手眼解算失败（转动轴过于单一？请换多个方向转）")

        // 与离散朝向相比的修正量：up_true = X·X0⁻¹·up_est
        val x0 = DeviceFrame.cameraFromDevice(orientation)
        val corr = mul(xf, transpose(x0))
        val rv = HandEye.rotationVector(corr)
        val angleDeg = Math.toDegrees(V.norm(rv))
        if (angleDeg > 15.0) {
            return ExtrinsicEstimate(
                null, pairs.size, medRot,
                "解出的修正 %.1f° 不合理（>15°）：帧间转动或星点匹配质量不足，请重拍".format(angleDeg),
            )
        }
        val config = ExtrinsicConfig(
            rx = rv[0], ry = rv[1], rz = rv[2],
            note = "App 自标定 %.2f°（%d 帧对，中位转动 %.1f°）".format(angleDeg, pairs.size, medRot),
            source = "app-handeye",
        )
        return ExtrinsicEstimate(
            config, pairs.size, medRot,
            "标定完成：外参修正 %.2f°（%d 帧对，帧间中位转动 %.1f°，剔除晃动帧 %d）".format(
                angleDeg, pairs.size, medRot, unsteady,
            ),
        )
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val out = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) out[i][j] += a[i][k] * b[k][j]
        return out
    }

    private fun mulMat(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val o = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) o[i][j] += a[i][k] * b[k][j]
        return o
    }
}
