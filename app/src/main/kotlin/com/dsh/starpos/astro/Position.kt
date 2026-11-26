// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.*

/**
 * 重力估计（需求 FR-07）。
 * 约定：Android 加速度计报告的是**比力** f = a − g_vec，静止时 f ≈ +9.81·up；
 * 因此 up = +mean(f)/|mean(f)|（注意与"重力向量"反向，别把纬度算反）。
 * 陀螺用于静止判决，运动时不允许出解。
 */
class GravityEstimate(
    /** 天顶方向在相机坐标系中的单位向量。 */
    val upCam: DoubleArray,
    /** 倾角 1σ（度）。 */
    val sigmaDeg: Double,
    val isStatic: Boolean,
    val samples: Int,
) {
    val valid: Boolean get() = isStatic && samples >= 5
}

object GravityEstimator {
    /**
     * @param accel 加速度计样本（设备坐标系，m/s²）
     * @param gyroMag 同步的陀螺角速度模值（rad/s）
     * @param deviceToCamera 设备→相机 旋转（出厂外参初值，需求 FR-03）
     */
    fun estimate(
        accel: List<DoubleArray>,
        gyroMag: List<Double>,
        deviceToCamera: Array<DoubleArray> = identity3(),
    ): GravityEstimate {
        if (accel.isEmpty()) {
            return GravityEstimate(doubleArrayOf(0.0, 0.0, 1.0), 90.0, false, 0)
        }
        val n = accel.size
        var mx = 0.0; var my = 0.0; var mz = 0.0
        for (a in accel) { mx += a[0]; my += a[1]; mz += a[2] }
        mx /= n; my /= n; mz /= n
        val mean = doubleArrayOf(mx, my, mz)
        val meanMag = V.norm(mean)
        if (meanMag < 1e-6) return GravityEstimate(doubleArrayOf(0.0, 0.0, 1.0), 90.0, false, n)

        // 静止判决：加速度模值波动 + 陀螺
        var magVar = 0.0
        for (a in accel) {
            val d = V.norm(a) - meanMag
            magVar += d * d
        }
        val magStd = sqrt(magVar / n)
        val gyroMean = if (gyroMag.isEmpty()) 0.0 else gyroMag.average()
        // 门限依据 2026-11-19 实测：三脚架 gyro≈0.002–0.003 rad/s，手持≈0.03 rad/s。
        // 旧值 0.08 会把明显晃动的手持采集判成"静止"，导致重力方向错 1° 以上。
        val isStatic = magStd / meanMag < 0.015 && gyroMean < 0.015

        // 倾角散布（与均值的夹角 RMS）
        val upDevice = V.unit(doubleArrayOf(mx, my, mz))
        var angSq = 0.0
        for (a in accel) {
            val u = V.unit(doubleArrayOf(a[0], a[1], a[2]))
            val ang = V.angle(u, upDevice)
            angSq += ang * ang
        }
        val sigma = Math.toDegrees(sqrt(angSq / n)).coerceAtLeast(0.01)
        val upCam = V.unit(apply(deviceToCamera, upDevice))
        return GravityEstimate(upCam, sigma, isStatic, n)
    }
}

/** 地心纬度 → WGS84 大地纬度。 */
object Geo {
    const val WGS84_E2 = 6.69437999014e-3

    fun geodeticFromGeocentric(latGeocentricRad: Double): Double =
        atan(tan(latGeocentricRad) / (1 - WGS84_E2))

    fun geocentricFromGeodetic(latGeodeticRad: Double): Double =
        atan((1 - WGS84_E2) * tan(latGeodeticRad))
}

/** 95% 误差椭圆（米/度）。 */
class ErrorEllipse(
    val semiMajorM: Double,
    val semiMinorM: Double,
    val azimuthDeg: Double,
)

class GeoFix(val latDeg: Double, val lonDeg: Double, val heightM: Double? = null)

class PositionResult(
    val fix: GeoFix?,
    val ellipse: ErrorEllipse?,
    /** 天顶方向强度（1−cos 误差）用于诊断。 */
    val warnings: List<String>,
    val zenithIcr: DoubleArray?,
)

/**
 * 位置解算（需求 FR-08）：
 *   z_ICRS = R · up_cam
 *   δ = asin(z_z)，α = atan2(z_y, z_x) = LST
 *   lon = α − GAST，lat = geodetic(δ)
 * 误差：重力倾角 + 姿态 + 时间，按 1/cos φ 放大经度方向。
 */
object PositionSolver {

    /** 95% 分位（二维圆正态，Rayleigh 分布）。 */
    const val CEP95_FACTOR = 2.4477

    fun solve(
        rotation: Array<DoubleArray>,
        upCam: DoubleArray,
        context: SkyContext,
        gravitySigmaDeg: Double,
        attitudeSigmaDeg: Double,
        timeUncertaintySec: Double,
    ): PositionResult {
        val warnings = ArrayList<String>()
        val z = V.unit(apply(rotation, upCam))
        val decGeo = asin(z[2].coerceIn(-1.0, 1.0))
        val ra = norm2pi(atan2(z[1], z[0]))
        val lonRad = normPi(ra - context.gast)
        val latRad = Geo.geodeticFromGeocentric(decGeo)

        val latDeg = Math.toDegrees(latRad)
        val lonDeg = Math.toDegrees(lonRad)

        if (abs(latDeg) > 70.0) warnings.add("high_latitude_longitude_amplification")

        val sigmaAng = sqrt(gravitySigmaDeg * gravitySigmaDeg + attitudeSigmaDeg * attitudeSigmaDeg)
        val meterPerDeg = 111_320.0
        val cosLat = cos(latRad).coerceAtLeast(0.05)
        val sigmaNs = sigmaAng * meterPerDeg
        val sigmaEw = sqrt((sigmaAng * meterPerDeg) * (sigmaAng * meterPerDeg) +
            (timeUncertaintySec * 465.1) * (timeUncertaintySec * 465.1)) / cosLat

        val semiMajor = CEP95_FACTOR * max(sigmaNs, sigmaEw)
        val semiMinor = CEP95_FACTOR * min(sigmaNs, sigmaEw)
        val azimuth = if (sigmaEw >= sigmaNs) 90.0 else 0.0

        return PositionResult(
            fix = GeoFix(latDeg, lonDeg, null),
            ellipse = ErrorEllipse(semiMajor, semiMinor, azimuth),
            warnings = warnings,
            zenithIcr = z,
        )
    }
}

