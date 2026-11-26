package com.dsh.starpos.field

import com.dsh.starpos.astro.V

/**
 * 手持晃动量化（帧曝光附近的 IMU 窗口）：
 * 高频抖动会被窗口平均掉，真正污染重力估计的是**窗口内的低频倾斜漂移**，
 * 所以判据用"前半窗 vs 后半窗 平均重力方向的夹角"（drift），而非总 σ。
 */
object ImuShake {

    class Info(val gyroMean: Double, val sigmaDeg: Double, val driftDeg: Double) {
        val text: String
            get() = when {
                driftDeg > 0.35 -> "⚠ 明显晃动（窗口倾斜漂移 %.2f°）：建议重拍".format(driftDeg)
                driftDeg > 0.12 -> "轻微晃动（漂移 %.2f°）：尽量稳住".format(driftDeg)
                else -> ""
            }
        val bad: Boolean get() = driftDeg > 0.35
    }

    fun analyze(rows: List<ImuRow>, anchorNs: Long, windowNs: Long = 750_000_000L): Info? {
        val near = rows.filter { kotlin.math.abs(it.tNs - anchorNs) <= windowNs }
        if (near.size < 40) return null
        val gyro = near.map { Math.sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average()
        val ups = near.map { V.unit(doubleArrayOf(it.ax, it.ay, it.az)) }
        val mean = meanUp(ups) ?: return null
        var sq = 0.0
        for (u in ups) {
            val c = (u[0] * mean[0] + u[1] * mean[1] + u[2] * mean[2]).coerceIn(-1.0, 1.0)
            val ang = Math.toDegrees(Math.acos(c))
            sq += ang * ang
        }
        val sigma = Math.sqrt(sq / ups.size)
        val half = ups.size / 2
        val u1 = meanUp(ups.take(half)) ?: return null
        val u2 = meanUp(ups.drop(half)) ?: return null
        val drift = Math.toDegrees(
            Math.acos((u1[0] * u2[0] + u1[1] * u2[1] + u1[2] * u2[2]).coerceIn(-1.0, 1.0)),
        )
        return Info(gyro, sigma, drift)
    }

    private fun meanUp(list: List<DoubleArray>): DoubleArray? {
        if (list.isEmpty()) return null
        val x = list.sumOf { it[0] }; val y = list.sumOf { it[1] }; val z = list.sumOf { it[2] }
        val n = Math.sqrt(x * x + y * y + z * z)
        if (n < 1e-9) return null
        return doubleArrayOf(x / n, y / n, z / n)
    }
}
