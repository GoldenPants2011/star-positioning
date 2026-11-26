package com.dsh.starpos.astro


/**
 * 加速度计"刻度/轴间串扰"矩阵的自标定（六位置法只能标零偏）：
 * 用星空姿态给出的真实天顶方向 b_i 与实测加速度方向 a_i 拟合
 *   S · a_i ≈ b_i        （S 接近单位阵）
 *
 * 每颗 IMU 的灵敏度容差（±0.3%）与轴安装误差会表现为"指向相关"的倾斜误差。
 */
object AccelMapCalibration {

    class Result(val s: DoubleArray, val maxCrossDeg: Double)

    /** @param aList 实测加速度方向（设备系，已扣零偏、单位化） */
    fun solve(aList: List<DoubleArray>, bList: List<DoubleArray>): Result? {
        if (aList.size < 6 || aList.size != bList.size) return null
        // S = (Σ b aᵀ)(Σ a aᵀ)^{-1}
        val ata = DoubleArray(9)
        val bta = DoubleArray(9)
        for (i in aList.indices) {
            val a = aList[i]; val b = bList[i]
            for (r in 0..2) for (c in 0..2) {
                ata[r * 3 + c] += a[r] * a[c]
                bta[r * 3 + c] += b[r] * a[c]
            }
        }
        val inv = invert3(ata) ?: return null
        val s = DoubleArray(9)
        for (r in 0..2) for (c in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += bta[r * 3 + k] * inv[k * 3 + c]
            s[r * 3 + c] = sum
        }
        var maxCross = 0.0
        for (r in 0..2) for (c in 0..2) if (r != c) {
            val deg = Math.toDegrees(Math.atan(s[r * 3 + c]))
            if (kotlin.math.abs(deg) > maxCross) maxCross = kotlin.math.abs(deg)
        }
        return Result(s, maxCross)
    }

    fun applyMap(s: DoubleArray, a: DoubleArray): DoubleArray = doubleArrayOf(
        s[0] * a[0] + s[1] * a[1] + s[2] * a[2],
        s[3] * a[0] + s[4] * a[1] + s[5] * a[2],
        s[6] * a[0] + s[7] * a[1] + s[8] * a[2],
    )

    private fun invert3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }
}
