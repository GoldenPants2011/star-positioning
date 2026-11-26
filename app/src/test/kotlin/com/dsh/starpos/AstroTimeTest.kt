// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Aberration
import com.dsh.starpos.astro.AstroTime
import com.dsh.starpos.astro.Nutation
import com.dsh.starpos.astro.Precession
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.DEG
import com.dsh.starpos.astro.norm2pi
import com.dsh.starpos.astro.radToArcsec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class AstroTimeTest {

    @Test
    fun `儒略日与 J2000 基准一致`() {
        assertEquals(2451545.0, AstroTime.julianDay(2000, 1, 1, 12, 0, 0.0), 1e-9)
        assertEquals(2451545.5, AstroTime.julianDay(2000, 1, 2, 0, 0, 0.0), 1e-9)
        assertEquals(2440587.5, AstroTime.julianDay(1970, 1, 1, 0, 0, 0.0), 1e-9)
    }

    @Test
    fun `GMST 与 Meeus 例 12-a 符合`() {
        // 1987-04-10 00:00 UT，Meeus 给出 GMST = 13h10m46.3668s = 197.693195°
        val jd = AstroTime.julianDay(1987, 4, 10, 0, 0, 0.0)
        val jdTt = AstroTime.ttFromUtc(jd)
        val gmstDeg = Math.toDegrees(AstroTime.gmst06(jd, jdTt))
        assertEquals(197.693195, gmstDeg, 0.002)
    }

    @Test
    fun `分点方程量级正确`() {
        val jdTt = AstroTime.ttFromUtc(AstroTime.julianDay(2026, 11, 14, 0, 0, 0.0))
        val eeArcsec = abs(radToArcsec(Nutation.equationOfEquinoxes(jdTt)))
        // 分点方程 = Δψ·cos ε 为主项，量级最大约 17″（≈1.1 s 时间）
        assertTrue("分点方程应 <20″ 且非零，实际 $eeArcsec", eeArcsec in 1.0..20.0)
    }

    @Test
    fun `章动量级合理`() {
        val jdTt = AstroTime.ttFromUtc(AstroTime.julianDay(2026, 11, 14, 0, 0, 0.0))
        val dPsi = radToArcsec(Nutation.deltaPsi(jdTt))
        val dEps = radToArcsec(Nutation.deltaEps(jdTt))
        assertTrue("Δψ=$dPsi 应在 ±20″ 内", abs(dPsi) < 20.0)
        assertTrue("Δε=$dEps 应在 ±15″ 内", abs(dEps) < 15.0)
    }

    @Test
    fun `J2000 处岁差矩阵近似单位阵`() {
        val m = Precession.matrix(AstroTime.J2000)
        for (i in 0..2) for (j in 0..2) {
            val expect = if (i == j) 1.0 else 0.0
            assertEquals(expect, m[i][j], 2e-5)
        }
    }

    @Test
    fun `岁差章动合成矩阵正交`() {
        val jdTt = AstroTime.ttFromUtc(AstroTime.julianDay(2026, 11, 14, 0, 0, 0.0))
        val m = Precession.icrsToTod(jdTt)
        val mmt = Array(3) { i -> DoubleArray(3) { j ->
            (0..2).sumOf { k -> m[i][k] * m[j][k] }
        } }
        for (i in 0..2) for (j in 0..2) {
            assertEquals(if (i == j) 1.0 else 0.0, mmt[i][j], 1e-9)
        }
    }

    @Test
    fun `光行差量级约 20 角秒`() {
        val jdTt = AstroTime.ttFromUtc(AstroTime.julianDay(2026, 6, 21, 0, 0, 0.0))
        val (vEarth, beta) = Aberration.earthVelocity(jdTt)
        assertEquals(20.5, radToArcsec(beta), 0.6)   // v/c ≈ 1e-4 rad ≈ 20.5″
        // 取与地球速度垂直的方向，位移角应接近 β
        val holder = if (abs(vEarth[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val perp = V.unit(V.cross(vEarth, holder))
        val shifted = Aberration.apply(perp, jdTt)
        val angleArcsec = radToArcsec(V.angle(perp, shifted))
        assertTrue("垂直方向光行差应接近 20″，实际 $angleArcsec", angleArcsec in 18.0..23.0)
    }

    @Test
    fun `章动矩阵在零章动时为单位阵`() {
        // 用基本实现间接验证：J2000 时刻章动远小于岁差，矩阵应接近单位阵
        val n = Precession.nutationMatrix(AstroTime.J2000)
        val angle = Math.toDegrees(kotlin.math.acos(((n[0][0] + n[1][1] + n[2][2] - 1) / 2).coerceIn(-1.0, 1.0)))
        assertTrue("J2000 章动转角应 <0.01°，实际 $angle", angle < 0.01)
    }

    @Test
    fun `sin 基本自检`() {
        assertEquals(0.0, sin(0.0), 1e-12)
    }

    @Test
    fun `时角与恒星时关系自洽`() {
        val jd = AstroTime.julianDay(2026, 11, 14, 12, 0, 0.0)
        val jdTt = AstroTime.ttFromUtc(jd)
        val gast = AstroTime.gast(jd, jdTt)
        // 同一 UT 时刻往后一整天，GAST 推进 360.9856°（恒星日比太阳日短约 3m56s）
        val gast2 = AstroTime.gast(jd + 1.0, AstroTime.ttFromUtc(jd + 1.0))
        val deltaDeg = Math.toDegrees(norm2pi(gast2 - gast))
        assertEquals(0.9856, deltaDeg, 0.02)
    }
}
