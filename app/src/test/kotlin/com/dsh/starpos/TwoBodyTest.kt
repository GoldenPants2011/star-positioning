package com.dsh.starpos

import com.dsh.starpos.astro.Attitude
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.TwoBody
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两体姿态约束定位的合成数据验证。 */
class TwoBodyTest {

    private fun enu(alt: Double, az: Double): DoubleArray {
        val ca = cos(alt)
        return doubleArrayOf(ca * sin(az), ca * cos(az), sin(alt))
    }

    /** 欧拉角 Z-Y-X 构造旋转矩阵 R（ENU→相机）。 */
    private fun rotation(yaw: Double, pitch: Double, roll: Double): Array<DoubleArray> {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        return arrayOf(
            doubleArrayOf(cy * cp, sy * cp, -sp),
            doubleArrayOf(cy * sp * sr - sy * cr, sy * sp * sr + cy * cr, cp * sr),
            doubleArrayOf(cy * sp * cr + sy * sr, sy * sp * cr - cy * sr, cp * cr),
        )
    }

    @Test
    fun `两体姿态定位合成数据`() {
        val truthLat = 30.0
        val truthLon = 120.0
        val utc = 1795260000000L  // 2026-11-21 约 19:20 本地
        val gast = SkyContext.at(utc).gast
        val site = Moon.Site(Math.toRadians(truthLat), Math.toRadians(truthLon))
        val moon = Moon.ephemerisAt(utc)
        val saturn = Planets.geocentric(Planets.Body.SATURN, utc)

        fun enuOf(ep: Moon.Ephemeris): DoubleArray {
            val alt = Moon.altitudeRad(site, ep, utc, gast)
            val az = Moon.azimuthRad(site, ep, gast)
            return enu(alt, az)
        }
        val em = enuOf(moon)
        val es = enuOf(saturn)
        println("月亮 ENU=${em.map { "%.3f".format(it) }} 土星 ENU=${es.map { "%.3f".format(it) }}")

        // 任意姿态 R0：相机系 = R0 * ENU（欧拉角构造真正交矩阵）
        val r0 = rotation(0.4, -0.3, 1.1)
        val camMoon = V.unit(apply(r0, em))
        val camSaturn = V.unit(apply(r0, es))
        println("camMoon=${camMoon.map { "%.3f".format(it) }} camSaturn=${camSaturn.map { "%.3f".format(it) }}")

        // 加 0.05° 噪声
        val rnd = java.util.Random(3)
        fun perturb(v: DoubleArray): DoubleArray {
            val n = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
            val eps = Math.toRadians(0.05)
            return V.unit(doubleArrayOf(v[0] + eps * n[0], v[1] + eps * n[1], v[2] + eps * n[2]))
        }
        val obs = listOf(
            TwoBody.Obs(utc, gast, moon.raRad, moon.decRad, moon.distanceKm, perturb(camMoon), 0.1),
            TwoBody.Obs(utc, gast, saturn.raRad, saturn.decRad, saturn.distanceKm, perturb(camSaturn), 0.2),
        )
        // 两体单次观测是退化的：应返回 null（而不是一个错误的"精确解"）
        val fix = TwoBody.solve(obs)
        assertTrue("两体单次观测应该判定为几何不足（返回 null）", fix == null)
        println("单次两体观测：正确判为几何不足")
    }

    @Test
    fun `两体姿态定位 多点观测`() {
        val truthLat = 30.0
        val truthLon = 120.0
        val site = Moon.Site(Math.toRadians(truthLat), Math.toRadians(truthLon))
        val obs = ArrayList<TwoBody.Obs>()
        val rnd = java.util.Random(7)
        // 同一姿态下，月亮+土星，3 帧（不同时刻）
        for (k in 0 until 3) {
            val utc = 1795260000000L + k * 30 * 60_000L
            val gast = SkyContext.at(utc).gast
            val moon = Moon.ephemerisAt(utc)
            val sat = Planets.geocentric(Planets.Body.SATURN, utc)
            fun enuOf(ep: Moon.Ephemeris): DoubleArray =
                enu(Moon.altitudeRad(site, ep, utc, gast), Moon.azimuthRad(site, ep, gast))
            val em = V.unit(enuOf(moon))
            val es = V.unit(enuOf(sat))
            val r0 = rotation(0.4, -0.3, 1.1)
            fun perturb(v: DoubleArray): DoubleArray {
                val n = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
                val eps = Math.toRadians(0.05)
                return V.unit(doubleArrayOf(v[0] + eps * n[0], v[1] + eps * n[1], v[2] + eps * n[2]))
            }
            obs.add(TwoBody.Obs(utc, gast, moon.raRad, moon.decRad, moon.distanceKm, perturb(V.unit(apply(r0, em))), 0.1))
            obs.add(TwoBody.Obs(utc, gast, sat.raRad, sat.decRad, sat.distanceKm, perturb(V.unit(apply(r0, es))), 0.2))
        }
        val fix = TwoBody.solve(obs)!!
        val err = com.dsh.starpos.field.FieldAnalyzer.haversineKm(fix.latDeg, fix.lonDeg, truthLat, truthLon)
        println("多点：%.4f, %.4f rms %.2f° 误差 %.1f km".format(fix.latDeg, fix.lonDeg, fix.rmsDeg, err))
        assertTrue("误差应 < 1500 km，实际 %.1f".format(err), err < 1500.0)
    }
}
