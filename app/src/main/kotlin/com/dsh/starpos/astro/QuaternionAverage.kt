package com.dsh.starpos.astro

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 旋转的四元数加权平均（Markley 方法）：用于把同一会话里多帧独立解算的姿态融合成一个，
 * 随机误差按 1/√N 下降。要求各帧是同一姿态（采集时手机静止/同一朝向）。
 */
object QuaternionAverage {

    /** 旋转矩阵 → 四元数 (w, x, y, z)。 */
    fun toQuat(r: Array<DoubleArray>): DoubleArray {
        val tr = r[0][0] + r[1][1] + r[2][2]
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            doubleArrayOf(0.25 * s, (r[2][1] - r[1][2]) / s, (r[0][2] - r[2][0]) / s, (r[1][0] - r[0][1]) / s)
        } else if (r[0][0] > r[1][1] && r[0][0] > r[2][2]) {
            val s = sqrt(1.0 + r[0][0] - r[1][1] - r[2][2]) * 2
            doubleArrayOf((r[2][1] - r[1][2]) / s, 0.25 * s, (r[0][1] + r[1][0]) / s, (r[0][2] + r[2][0]) / s)
        } else if (r[1][1] > r[2][2]) {
            val s = sqrt(1.0 + r[1][1] - r[0][0] - r[2][2]) * 2
            doubleArrayOf((r[0][2] - r[2][0]) / s, (r[0][1] + r[1][0]) / s, 0.25 * s, (r[1][2] + r[2][1]) / s)
        } else {
            val s = sqrt(1.0 + r[2][2] - r[0][0] - r[1][1]) * 2
            doubleArrayOf((r[1][0] - r[0][1]) / s, (r[0][2] + r[2][0]) / s, (r[1][2] + r[2][1]) / s, 0.25 * s)
        }
    }

    /** 四元数 → 旋转矩阵。 */
    fun toMatrix(q: DoubleArray): Array<DoubleArray> {
        val (w, x, y, z) = q
        return arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
            doubleArrayOf(2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
            doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)),
        )
    }

    /**
     * 加权平均。返回 null 表示失败（空输入/退化）。
     * 注意四元数双覆盖：先把所有四元数与第一个对齐符号（点积为负则取反）。
     */
    fun average(rotations: List<Array<DoubleArray>>, weights: List<Double>? = null): Array<DoubleArray>? {
        if (rotations.isEmpty()) return null
        val quats = rotations.map { toQuat(it) }
        val ref = quats.first()
        val aligned = quats.map { q ->
            val dot = ref[0] * q[0] + ref[1] * q[1] + ref[2] * q[2] + ref[3] * q[3]
            if (dot < 0) q.map { -it }.toDoubleArray() else q
        }
        var sw = 0.0
        val sum = DoubleArray(4)
        for (i in aligned.indices) {
            val w = weights?.getOrNull(i)?.coerceAtLeast(0.1) ?: 1.0
            for (k in 0..3) sum[k] += w * aligned[i][k]
            sw += w
        }
        if (sw <= 0) return null
        val norm = sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2] + sum[3] * sum[3])
        if (norm < 1e-9 || abs(norm).isNaN()) return null
        val q = sum.map { it / norm }.toDoubleArray()
        // 对齐回参考四元数的半球，保证符号一致（旋转本身相同，但后续比较用得到）
        val dotRef = ref[0] * q[0] + ref[1] * q[1] + ref[2] * q[2] + ref[3] * q[3]
        val qq = if (dotRef < 0) q.map { -it }.toDoubleArray() else q
        return toMatrix(qq)
    }
}
