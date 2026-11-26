// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.V
import com.dsh.starpos.field.MirrorPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 水镜像配对求解：直射+反射 → 天顶、视高度。 */
class MirrorPairTest {

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun `直射+反射恢复天顶与高度`() {
        val up = V.unit(doubleArrayOf(0.15, -0.25, 0.956))
        // 用叉积构造水平基
        val tmp = if (Math.abs(up[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val eh1 = V.unit(doubleArrayOf(
            tmp[1] * up[2] - tmp[2] * up[1],
            tmp[2] * up[0] - tmp[0] * up[2],
            tmp[0] * up[1] - tmp[1] * up[0],
        ))
        val eh2 = doubleArrayOf(
            up[1] * eh1[2] - up[2] * eh1[1],
            up[2] * eh1[0] - up[0] * eh1[2],
            up[0] * eh1[1] - up[1] * eh1[0],
        )
        for (altDeg in listOf(8.0, 15.0, 20.0, 26.0)) for (azDeg in listOf(0.0, 73.0, 200.0)) {
            val a = Math.toRadians(altDeg)
            val az = Math.toRadians(azDeg)
            val horiz = doubleArrayOf(
                eh1[0] * Math.cos(az) + eh2[0] * Math.sin(az),
                eh1[1] * Math.cos(az) + eh2[1] * Math.sin(az),
                eh1[2] * Math.cos(az) + eh2[2] * Math.sin(az),
            )
            val u = V.unit(doubleArrayOf(
                Math.cos(a) * horiz[0] + Math.sin(a) * up[0],
                Math.cos(a) * horiz[1] + Math.sin(a) * up[1],
                Math.cos(a) * horiz[2] + Math.sin(a) * up[2],
            ))
            // 物理反射像：r = u − 2(u·up)up（在地平线下方）
            val c = 2.0 * dot(u, up)
            val r = doubleArrayOf(u[0] - c * up[0], u[1] - c * up[1], u[2] - c * up[2])
            val res = MirrorPair.solve(u, r, up)
            assertNotNull(res)
            val n = res!!.upCam
            val upErr = Math.toDegrees(Math.acos(dot(n, up).coerceIn(-1.0, 1.0)))
            val altErr = Math.toDegrees(res.apparentAltRad) - altDeg
            println("alt=%.0f° az=%.0f° → 天顶误差 %.4f°，高度误差 %+.4f°，镜像残差 %.4f°".format(
                altDeg, azDeg, upErr, altErr, res.residualDeg,
            ))
            assertEquals(0.0, upErr, 0.001)
            assertEquals(0.0, altErr, 0.001)
            assertEquals(0.0, res.residualDeg, 0.001)
        }
    }

    @Test
    fun `低角度返回 null`() {
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        val a = Math.toRadians(-2.0)
        val u = doubleArrayOf(Math.cos(a), 0.0, Math.sin(a))          // 直射像在地平线下
        val r = doubleArrayOf(Math.cos(-a), 0.0, Math.sin(-a))        // 对应"反射"在地平线上
        assertNull(MirrorPair.solve(u, r, up))
    }

    @Test
    fun `只用反射像`() {
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        val alt = Math.toRadians(12.0)
        val r = doubleArrayOf(Math.cos(alt), 0.0, -Math.sin(alt))
        val altOut = MirrorPair.altitudeFromReflectionOnly(r, up)
        assertEquals(12.0, Math.toDegrees(altOut), 0.001)
    }
}
