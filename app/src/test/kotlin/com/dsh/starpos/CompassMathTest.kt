package com.dsh.starpos

import com.dsh.starpos.android.CompassAim
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** 罗盘/俯仰数学（相机光轴 = 设备系 −Z）的纯函数测试。 */
class CompassMathTest {

    private fun r(e: Double): Float = e.toFloat()

    @Test
    fun `手机竖立朝北相机水平`() {
        // 设备 X→东、Y→上、Z→南；相机（−Z）→北
        val m = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val out = CompassAim.aimFromMatrix(m)!!
        assertEquals("方位应朝北", 0.0, out[0], 0.5)
        assertEquals("俯仰应为 0", 0.0, out[1], 0.5)
    }

    @Test
    fun `仰角 30 度`() {
        val c = cos(Math.toRadians(30.0))
        val s = sin(Math.toRadians(30.0))
        // 相机光轴世界方向 (0, cos30, sin30)：北偏上 30°
        val m = floatArrayOf(
            r(1.0), r(0.0), r(0.0),
            r(0.0), r(-s), r(-c),
            r(0.0), r(c), r(-s),
        )
        val out = CompassAim.aimFromMatrix(m)!!
        assertEquals(0.0, out[0], 1.0)
        assertEquals("俯仰应为 +30°", 30.0, out[1], 1.0)
    }

    @Test
    fun `朝东且俯角 20 度`() {
        // 相机光轴世界方向 = 东偏下 20°：(sin? ) 用构造：R 的第三列 = −光轴方向
        val el = Math.toRadians(-20.0)
        val dir = doubleArrayOf(1.0, 0.0, sin(el))       // 东 + 俯 20°
        val z = doubleArrayOf(-dir[0], -dir[1], -dir[2]) // 设备 Z = −光轴
        // 设备 X 取世界"北偏上"方向与 Z 正交：X = normalize(Z × 某向量)
        // 简单取 X = (0,1,0) 与 Z 的叉积，再 Y = Z × X
        val x = doubleArrayOf(z[1] * 0.0 - z[2] * 1.0, z[2] * 0.0 - z[0] * 0.0, z[0] * 1.0 - z[1] * 0.0)
        val nx = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
        val xr = doubleArrayOf(x[0] / nx, x[1] / nx, x[2] / nx)
        val y = doubleArrayOf(z[1] * xr[2] - z[2] * xr[1], z[2] * xr[0] - z[0] * xr[2], z[0] * xr[1] - z[1] * xr[0])
        val m = floatArrayOf(
            r(xr[0]), r(y[0]), r(z[0]),
            r(xr[1]), r(y[1]), r(z[1]),
            r(xr[2]), r(y[2]), r(z[2]),
        )
        val out = CompassAim.aimFromMatrix(m)!!
        println("朝东俯 20°: 方位 %.1f 俯仰 %.1f".format(out[0], out[1]))
        assertEquals("方位应 90°", 90.0, out[0], 2.0)
        assertEquals("俯仰应 −20°", -20.0, out[1], 2.0)
    }
}
