package com.dsh.starpos

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.HandEye
import com.dsh.starpos.astro.Rodrigues
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手眼标定的合成自检：已知 X，能否只用相对旋转复现出来。 */
class HandEyeTest {

    @Test
    fun `合成数据应能复现外参`() {
        val rnd = Random(7)
        // 真值：传感器朝向 90° 的离散解 + 3.1° 的小安装误差
        val x0 = DeviceFrame.cameraFromDevice(90)
        val errAngle = Math.toRadians(3.1)
        val axis = V.unit(doubleArrayOf(0.3, -0.5, 0.81))
        val err = Rodrigues.exp(axis[0] * errAngle, axis[1] * errAngle, axis[2] * errAngle)
        val xTrue = mul(err, x0)

        // 生成 8 帧：设备每步随机转 4–12°，星图姿态由 R_{i+1} = R_i · (X D^T X^T) 递推
        var r = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val pairs = ArrayList<Pair<Array<DoubleArray>, Array<DoubleArray>>>()
        repeat(8) {
            val ang = Math.toRadians(4.0 + rnd.nextDouble() * 8.0)
            val ax = V.unit(
                doubleArrayOf(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5),
            )
            val d = Rodrigues.exp(ax[0] * ang, ax[1] * ang, ax[2] * ang)
            val a = mul(mul(xTrue, d), transpose(xTrue))   // A = X D Xᵀ
            val b = d                                       // B = G（陀螺积分：W_{i+1}=W_i·G）
            pairs.add(a to b)
            r = mul(r, a)
        }

        val x = HandEye.solve(pairs)
        assertTrue("手眼解算失败", x != null)
        val rv = HandEye.rotationVector(mul(x!!, transpose(x0)))
        val recovered = Math.toDegrees(V.norm(rv))
        println("真值偏差 %.3f°，解出 %.3f°（旋转向量 %.2f, %.2f, %.2f）".format(
            3.1, recovered, rv[0], rv[1], rv[2]))
        assertTrue("解出的外参偏差过大：%.2f°".format(abs(recovered - 3.1)), abs(recovered - 3.1) < 0.5)
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val out = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) for (k in 0..2) out[i][j] += a[i][k] * b[k][j]
        return out
    }
}

