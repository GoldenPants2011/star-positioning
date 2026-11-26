// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.ImuRow
import com.dsh.starpos.field.ImuShake
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手持晃动指标的单元测试：静止 vs 窗口内低频倾斜漂移。 */
class ImuShakeTest {

    private fun row(tNs: Long, ax: Double, ay: Double, az: Double, gyro: Double = 0.002): ImuRow =
        ImuRow(tNs, ax, ay, az, gyro, 0.0, 0.0)

    @Test
    fun `静止时漂移应很小`() {
        val g = 9.80665
        val rows = ArrayList<ImuRow>()
        val rnd = java.util.Random(7)
        for (i in 0 until 700) {
            val t = i * 2_000_000L
            // 轻微噪声（±0.05°）
            val jitter = Math.toRadians(0.05) * (rnd.nextDouble() - 0.5)
            rows.add(row(t, g * sin(jitter), g * 0.01, g * cos(jitter)))
        }
        val info = ImuShake.analyze(rows, anchorNs = 700_000_000L)
        assertNotNull(info)
        println("静止：drift=%.3f° sigma=%.3f° gyro=%.4f".format(info!!.driftDeg, info.sigmaDeg, info.gyroMean))
        assertTrue("静止漂移应 <0.05°，实际 %.3f".format(info.driftDeg), info.driftDeg < 0.05)
        assertEquals("", info.text)
    }

    @Test
    fun `窗口内缓慢倾斜 0_5 度应报警`() {
        val g = 9.80665
        val rows = ArrayList<ImuRow>()
        val total = 700
        for (i in 0 until total) {
            val t = i * 2_000_000L
            // 1.5 s 内缓慢倾斜 0.5°（低频漂移，不被平均掉）
            val frac = i.toDouble() / (total - 1)
            val tilt = Math.toRadians(0.5) * (frac - 0.5)
            rows.add(row(t, g * sin(tilt), g * 0.01, g * cos(tilt), gyro = 0.01))
        }
        val info = ImuShake.analyze(rows, anchorNs = 700_000_000L)
        assertNotNull(info)
        println("慢倾斜：drift=%.3f° sigma=%.3f° gyro=%.4f → \"%s\"".format(
            info!!.driftDeg, info.sigmaDeg, info.gyroMean, info.text,
        ))
        // 线性斜坡 0.5°：前半/后半窗均值差 ≈ 0.25°（这正是"会污染重力估计"的量）
        assertTrue("应检出约 0.25° 漂移，实际 %.3f".format(info.driftDeg), info.driftDeg in 0.2..0.32)
        assertTrue("应给出轻微晃动提示：${info.text}", info.text.contains("轻微晃动"))
    }

    @Test
    fun `窗口内倾斜 1 度应报警`() {
        val g = 9.80665
        val rows = ArrayList<ImuRow>()
        val total = 700
        for (i in 0 until total) {
            val t = i * 2_000_000L
            val frac = i.toDouble() / (total - 1)
            val tilt = Math.toRadians(1.0) * (frac - 0.5)
            rows.add(row(t, g * sin(tilt), g * 0.01, g * cos(tilt), gyro = 0.02))
        }
        val info = ImuShake.analyze(rows, anchorNs = 700_000_000L)
        assertNotNull(info)
        println("1°慢倾斜：drift=%.3f° → \"%s\"".format(info!!.driftDeg, info.text))
        assertTrue("应判为明显晃动（drift %.3f）".format(info.driftDeg), info.bad)
        assertTrue(info.text.contains("明显晃动"))
    }
}
