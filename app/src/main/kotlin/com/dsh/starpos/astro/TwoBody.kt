// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 两体（或更多天体）**姿态约束**定位：
 *
 * 观测给的是"天体在相机系的方向"，预测给的是"天体在当地 ENU 的方向"。
 * 对候选位置，用 Wahba 求出把 ENU 转到相机系的姿态 R，再看两个天体是否同时对上
 * （2 个天体有 4 个约束、R 只有 3 个自由度 → 还剩 1 个自由度可用于定位）。
 *
 * 相比"只用高度角"的解算器，这个不需要重力/水平基准，手持也能用；
 * 而且两个天体高度接近时也不病态（这正是月亮+土星的情况）。
 */
object TwoBody {

    /** 一次天体观测：天体星历 + 相机系方向（单位向量）。 */
    class Obs(
        val utcMillis: Long,
        val gast: Double,
        val raRad: Double,
        val decRad: Double,
        val distanceKm: Double,
        val camDir: DoubleArray,
        val sigmaDeg: Double,
    )

    class Fix(
        val latDeg: Double,
        val lonDeg: Double,
        /** 姿态拟合后的角度残差 RMS（度）。 */
        val rmsDeg: Double,
        val obs: Int,
        val warnings: List<String>,
    )

    private fun enuUnit(altRad: Double, azRad: Double): DoubleArray {
        // ENU：东、北、天；方位角从北起顺时针
        val ca = cos(altRad)
        return doubleArrayOf(ca * sin(azRad), ca * cos(azRad), sin(altRad))
    }

    /** 给定位置，算所有观测的姿态残差（度²，未加权）。 */
    private fun residualSq(latDeg: Double, lonDeg: Double, obs: List<Obs>): Double {
        val site = Moon.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
        val enu = ArrayList<DoubleArray>(obs.size)
        for (o in obs) {
            val ep = Moon.Ephemeris(o.raRad, o.decRad, o.distanceKm, 0.0)
            val alt = Moon.altitudeRad(site, ep, o.utcMillis, o.gast)
            val az = Moon.azimuthRad(site, ep, o.gast)
            enu.add(enuUnit(alt, az))
        }
        val cam = obs.map { V.unit(it.camDir) }
        val r = runCatching { Attitude.wahba(enu, cam) }.getOrNull() ?: return 1e12
        val rT = transpose(r)
        var sum1 = 0.0
        var sum2 = 0.0
        for (k in obs.indices) {
            val a = V.angle(V.unit(apply(r, enu[k])), cam[k])
            val b = V.angle(V.unit(apply(rT, enu[k])), cam[k])
            sum1 += a * a
            sum2 += b * b
        }
        return minOf(sum1, sum2)
    }


    fun solve(obs: List<Obs>): Fix? {
        if (obs.size < 2) return null
        val warnings = ArrayList<String>()
        // 不同天体的赤经赤纬差异（同一天体多次也可，但需要时间跨度）
        var maxSep = 0.0
        for (i in obs.indices) for (j in i + 1 until obs.size) {
            val s = V.angle(V.fromRaDec(obs[i].raRad, obs[i].decRad), V.fromRaDec(obs[j].raRad, obs[j].decRad))
            if (s > maxSep) maxSep = s
        }
        val spanH = (obs.maxOf { it.utcMillis } - obs.minOf { it.utcMillis }) / 3_600_000.0
        // 两个天体、单次观测：姿态有 3 个自由度、4 个约束 → 位置只被约束 1 个自由度（退化）。
        // 需要多历元（固定相机）或 ≥3 个天体。
        val distinctBodies = Math.toDegrees(maxSep) > 0.5
        if (distinctBodies && spanH < 5.0 / 60.0) {
            warnings.add("两体单次观测无法定位置（退化：只剩 1 个约束）；需固定相机隔 ≥5 分钟多次拍，或一帧里有 ≥3 个天体")
            return null
        }
        if (Math.toDegrees(maxSep) < 0.5) {
            val spanH = (obs.maxOf { it.utcMillis } - obs.minOf { it.utcMillis }) / 3_600_000.0
            if (spanH < 0.5) {
                warnings.add("观测只涉及同一天体且时间跨度 %.1f h：几何不足，建议同框两个天体".format(spanH))
            }
        }

        var best = Double.MAX_VALUE
        var bl = 0.0
        var bn = 0.0
        var lat = -80.0
        while (lat <= 80.0) {
            var lon = -180.0
            while (lon < 180.0) {
                val c = residualSq(lat, lon, obs)
                if (c < best) { best = c; bl = lat; bn = lon }
                lon += 2.0
            }
            lat += 2.0
        }
        var step = 1.0
        repeat(40) {
            val c0 = residualSq(bl, bn, obs)
            var improved = false
            for (dl in doubleArrayOf(-step, 0.0, step)) for (dn in doubleArrayOf(-step, 0.0, step)) {
                if (dl == 0.0 && dn == 0.0) continue
                if (bl + dl < -85.0 || bl + dl > 85.0) continue
                val c = residualSq(bl + dl, bn + dn, obs)
                if (c < c0) { bl += dl; bn += dn; improved = true }
            }
            if (!improved) step *= 0.5
            if (step < 1e-4) return@repeat
        }
        val rms = Math.toDegrees(sqrt(residualSq(bl, bn, obs) / obs.size))
        if (rms > 5.0) warnings.add("姿态残差 %.1f° 偏大，可能不是同一天体组合".format(rms))
        return Fix(bl, ((bn + 540.0) % 360.0) - 180.0, rms, obs.size, warnings)
    }
}
