package com.dsh.starpos.field

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 加速度计零偏标定（六位置法）：只需要 6 个"某个轴朝上/朝下"的静止姿态，
 * 结果对每个姿态的角度误差不敏感（零偏来自 ± 两姿态的平均，倾斜误差是二阶的）。
 *
 * 用法：现场把手机按提示摆放（屏幕朝上/下、左右侧/顶底朝下），每个姿态静置 ~15 s，
 * 取平均加速度向量，最后调用 [solve]。
 */
object AccelBiasCalibration {

    class Result(
        val bias: DoubleArray,
        /** 由正反姿态差估出的重力加速度大小（m/s²）。 */
        val g: Double,
        /** 拟合残差 RMS（m/s²）；>0.2 说明姿态不稳或摆放不对。 */
        val rmsMps2: Double,
        val warnings: List<String>,
    ) {
        /** 零偏对应的最大倾斜误差（度）。 */
        val tiltErrorDeg: Double get() = Math.toDegrees(kotlin.math.atan(Math.sqrt(bias[0] * bias[0] + bias[1] * bias[1] + bias[2] * bias[2]) / g))
    }

    private class Pose(val axis: Int, val sign: Int, val v: DoubleArray)

    fun solve(samples: List<DoubleArray>): Result? {
        if (samples.size < 6) return null
        val warnings = ArrayList<String>()
        val poses = samples.map { v ->
            var k = 0
            for (i in 1..2) if (abs(v[i]) > abs(v[k])) k = i
            Pose(k, if (v[k] >= 0) 1 else -1, v)
        }
        val bias = DoubleArray(3)
        var gSum = 0.0
        var gN = 0
        for (k in 0..2) {
            val pos = poses.firstOrNull { it.axis == k && it.sign == 1 }
            val neg = poses.firstOrNull { it.axis == k && it.sign == -1 }
            if (pos == null || neg == null) {
                warnings.add("第 ${k + 1} 轴缺少正向或反向姿态")
                return null
            }
            bias[k] = (pos.v[k] + neg.v[k]) / 2.0
            gSum += (pos.v[k] - neg.v[k]) / 2.0
            gN++
        }
        val g = if (gN > 0) gSum / gN else 9.81
        if (abs(g - 9.80665) > 0.5) warnings.add("估出重力 %.2f m/s²，偏离 9.81 较多（姿态可能歪了）".format(g))
        // 残差：每个姿态相对"bias + sign*g*e_axis"
        var sum = 0.0
        for (p in poses) {
            val pred = doubleArrayOf(bias[0], bias[1], bias[2])
            pred[p.axis] += p.sign * g
            for (i in 0..2) {
                val e = p.v[i] - pred[i]
                sum += e * e
            }
        }
        val rms = sqrt(sum / (poses.size * 3))
        if (rms > 0.2) warnings.add("姿态间残差 %.2f m/s² 偏大：检查是否抖动或倾斜过大".format(rms))
        return Result(bias, g, rms, warnings)
    }
}
