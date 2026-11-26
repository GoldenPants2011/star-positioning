// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.*

enum class Projection { PINHOLE, FISHEYE_EQUIDISTANT }

/**
 * 相机内参模型（需求 FR-03）。逐台标定；畸变参数为 Brown-Conrady。
 * 图像坐标系：x 向右、y 向下、光轴 +z 指向天空（与 Camera2 的 SENSOR 坐标系一致）。
 */
class CameraIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val k3: Double = 0.0,
    val projection: Projection = Projection.PINHOLE,
) {
    /** 水平视场角（度）。 */
    val fovXDeg: Double get() = 2 * Math.toDegrees(atan(width / (2 * fx)))


    /** 相机坐标系单位向量 → 像素坐标；不可投影（在背后）返回 null。 */
    fun project(u: DoubleArray): DoubleArray? {
        if (projection == Projection.FISHEYE_EQUIDISTANT) {
            val r = sqrt(u[0] * u[0] + u[1] * u[1])
            if (r < 1e-12) return doubleArrayOf(cx, cy)
            val theta = atan2(r, u[2])
            val scale = (fx * theta) / r
            return doubleArrayOf(cx + u[0] * scale, cy + u[1] * scale)
        }
        if (u[2] <= 1e-6) return null
        val x = u[0] / u[2]
        val y = u[1] / u[2]
        val r2 = x * x + y * y
        val radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
        val xd = x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
        val yd = y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
        return doubleArrayOf(fx * xd + cx, fy * yd + cy)
    }

    /** 像素坐标 → 相机坐标系单位向量（畸变用牛顿迭代反解）。 */
    fun unproject(px: Double, py: Double): DoubleArray {
        if (projection == Projection.FISHEYE_EQUIDISTANT) {
            val dx = (px - cx) / fx
            val dy = (py - cy) / fy
            val theta = sqrt(dx * dx + dy * dy)
            if (theta < 1e-12) return doubleArrayOf(0.0, 0.0, 1.0)
            val phi = atan2(dy, dx)
            return doubleArrayOf(
                sin(theta) * cos(phi),
                sin(theta) * sin(phi),
                cos(theta),
            )
        }
        val xd = (px - cx) / fx
        val yd = (py - cy) / fy
        var xu = xd
        var yu = yd
        // 牛顿迭代反解畸变（2x2 数值雅可比）
        for (iter in 0 until 15) {
            val f = distort(xu, yu)
            val ex = f[0] - xd
            val ey = f[1] - yd
            if (abs(ex) < 1e-12 && abs(ey) < 1e-12) break
            val h = 1e-6
            val fx1 = distort(xu + h, yu)
            val fy1 = distort(xu, yu + h)
            val j00 = (fx1[0] - f[0]) / h
            val j10 = (fx1[1] - f[1]) / h
            val j01 = (fy1[0] - f[0]) / h
            val j11 = (fy1[1] - f[1]) / h
            val det = j00 * j11 - j01 * j10
            if (abs(det) < 1e-14) break
            xu -= (j11 * ex - j01 * ey) / det
            yu -= (-j10 * ex + j00 * ey) / det
        }
        return V.unit(doubleArrayOf(xu, yu, 1.0))
    }

    /** 归一化坐标 + 畸变模型。 */
    private fun distort(x: Double, y: Double): DoubleArray {
        val r2 = x * x + y * y
        val radial = 1 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
        return doubleArrayOf(
            x * radial + 2 * p1 * x * y + p2 * (r2 + 2 * x * x),
            y * radial + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y,
        )
    }

    /** 图像主点方向（光轴）。 */
    fun opticalAxis(): DoubleArray = when (projection) {
        Projection.PINHOLE -> V.unit(doubleArrayOf((width / 2.0 - cx) / fx, (height / 2.0 - cy) / fy, 1.0))
        Projection.FISHEYE_EQUIDISTANT -> unproject(width / 2.0, height / 2.0)
    }



    companion object {
        /** 按水平视场角构造一个理想针孔相机（合成数据/演示用）。 */
        fun fromFov(width: Int, height: Int, fovXDeg: Double, projection: Projection = Projection.PINHOLE): CameraIntrinsics {
            val fx = (width / 2.0) / tan(Math.toRadians(fovXDeg) / 2)
            return CameraIntrinsics(
                width = width, height = height,
                fx = fx, fy = fx,
                cx = width / 2.0, cy = height / 2.0,
                projection = projection,
            )
        }
    }
}
