// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.AccelBiasCalibration
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 六位置零偏标定的合成数据测试。 */
class AccelBiasCalibrationTest {

    @Test
    fun `六位置法恢复零偏`() {
        val bias = doubleArrayOf(0.03, -0.05, 0.08)   // m/s² ≈ 3/5/8 mg
        val g = 9.80665
        // 6 个姿态：±x/±y/±z，加入 ±2° 的倾斜误差（模拟摆放不精确）
        val samples = ArrayList<DoubleArray>()
        val axes = listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
        )
        val rnd = java.util.Random(1)
        for (u in axes) {
            // 每个姿态随机倾斜 2°
            val tilt = Math.toRadians(2.0)
            val v = doubleArrayOf(
                u[0] + tilt * (rnd.nextDouble() - 0.5),
                u[1] + tilt * (rnd.nextDouble() - 0.5),
                u[2] + tilt * (rnd.nextDouble() - 0.5),
            )
            val n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            samples.add(doubleArrayOf(bias[0] + g * v[0] / n, bias[1] + g * v[1] / n, bias[2] + g * v[2] / n))
        }
        val r = AccelBiasCalibration.solve(samples)
        assertNotNull(r)
        println("恢复零偏 (%.2f, %.2f, %.2f) mg，g=%.3f，残差 %.3f m/s²，倾角误差 %.2f°".format(
            r!!.bias[0] / g * 1000, r.bias[1] / g * 1000, r.bias[2] / g * 1000, r.g, r.rmsMps2, r.tiltErrorDeg,
        ))
        assertEquals(bias[0], r.bias[0], 0.01)
        assertEquals(bias[1], r.bias[1], 0.01)
        assertEquals(bias[2], r.bias[2], 0.01)
        // 真实零偏本身约 0.58°；这里校验"恢复误差"（2° 姿态倾斜引入的零偏误差应很小）
        val errBias = doubleArrayOf(r.bias[0] - bias[0], r.bias[1] - bias[1], r.bias[2] - bias[2])
        val errDeg = Math.toDegrees(Math.atan(Math.sqrt(errBias[0] * errBias[0] + errBias[1] * errBias[1] + errBias[2] * errBias[2]) / g))
        println("恢复误差 %.2f°（真实零偏等效 %.2f°）".format(errDeg, r.tiltErrorDeg))
        assertEquals(0.0, errDeg, 0.10)
    }

    @Test
    fun `缺少反向姿态应失败`() {
        val g = 9.80665
        val samples = ArrayList<DoubleArray>()
        val axes = listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
        )
        for (u in axes) samples.add(doubleArrayOf(g * u[0], g * u[1], g * u[2]))
        val r = AccelBiasCalibration.solve(samples)
        println("缺反向姿态 → ${if (r == null) "null（正确）" else "返回了结果（错误）"}")
        assertEquals(null, r)
    }
}
