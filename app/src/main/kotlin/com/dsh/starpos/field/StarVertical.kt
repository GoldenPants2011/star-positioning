// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.V
import java.io.File
import kotlin.math.sqrt

/**
 * **仅星光定位分支**的垂直修正（FR-03 的补充项）。
 *
 * 2026-11-24 用旧星光会话 + GNSS 真值拟合：扣除加速度计零偏后，星光分支仍有约 0.75° 的
 * 固定天顶偏差（日月高度路径不体现），用设备系小倾角 dt 修正后，星光单历元定位中位误差
 * 63.8 km → 19 km（留一会话交叉验证 24.8 km）。
 *
 * **只作用于星图解算后的 PositionSolver**：不修改重力估计、不影响太阳/月亮/两体/水镜路径。
 * 单位：弧度（设备系小旋转向量）。
 */
class StarVertical(
    val dtRad: DoubleArray,
    val note: String = "",
    val source: String = "file",
) {
    val magnitudeDeg: Double get() = Math.toDegrees(sqrt(dtRad[0] * dtRad[0] + dtRad[1] * dtRad[1] + dtRad[2] * dtRad[2]))

    /** 把相机系天顶 upCam 按设备系小倾角 dt 修正后返回（单位向量）。 */
    fun correctUp(upCam: DoubleArray, extrinsic: ExtrinsicConfig?, orientation: Int): DoubleArray {
        val mext = extrinsicMatrix(extrinsic)
        val mdev = DeviceFrame.cameraFromDevice(orientation)
        val e = matMul(mext, mdev)
        val invE = inv3(e)
        val upDev = V.unit(matVec(invE, upCam))
        val rotated = V.unit(
            doubleArrayOf(
                upDev[0] + dtRad[1] * upDev[2] - dtRad[2] * upDev[1],
                upDev[1] + dtRad[2] * upDev[0] - dtRad[0] * upDev[2],
                upDev[2] + dtRad[0] * upDev[1] - dtRad[1] * upDev[0],
            ),
        )
        return V.unit(matVec(e, rotated))
    }

    fun toJson(): String = """{"dt_deg":[${Math.toDegrees(dtRad[0])},${Math.toDegrees(dtRad[1])},${Math.toDegrees(dtRad[2])}],"note":"$note","source":"$source"}"""

    companion object {
        /**
         * 内置默认（2026-11-24 重拟合，已应用岁差/章动修正 + 加速度零偏）：
         * 基线中位 55.0 km → 修正后 33.0 km（留一会话交叉验证 42.1 km）。
         * 默认开启；传 null 可显式关闭。
         */
        val DEFAULT: StarVertical = StarVertical(
            doubleArrayOf(
                Math.toRadians(2.974), Math.toRadians(-0.034), Math.toRadians(-4.835),
            ),
            note = "2026-11-24 重拟合（岁差/章动修正 + 零偏扣除）",
            source = "builtin-default-20261124",
        )

        fun load(file: File): StarVertical? {
            if (!file.isFile) return null
            return runCatching {
                val m = JsonText.parse(file.readText()).asMap()
                val deg = (m["dt_deg"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
                    ?.takeIf { it.size == 3 } ?: return null
                StarVertical(
                    doubleArrayOf(Math.toRadians(deg[0]), Math.toRadians(deg[1]), Math.toRadians(deg[2])),
                    m.str("note", ""), m.str("source", "file"),
                )
            }.getOrNull()
        }

        private fun extrinsicMatrix(ext: ExtrinsicConfig?): Array<DoubleArray> {
            val mm = ext?.m
            if (mm != null) return arrayOf(
                doubleArrayOf(mm[0], mm[1], mm[2]),
                doubleArrayOf(mm[3], mm[4], mm[5]),
                doubleArrayOf(mm[6], mm[7], mm[8]),
            )
            val rx = ext?.rx ?: 0.0
            val ry = ext?.ry ?: 0.0
            val rz = ext?.rz ?: 0.0
            return arrayOf(
                doubleArrayOf(1.0, -rz, ry),
                doubleArrayOf(rz, 1.0, -rx),
                doubleArrayOf(-ry, rx, 1.0),
            )
        }

        private fun matVec(m: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
        )

        private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val c = Array(3) { DoubleArray(3) }
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[i][k] * b[k][j]
                c[i][j] = s
            }
            return c
        }

        private fun inv3(a: Array<DoubleArray>): Array<DoubleArray> {
            val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
            val inv = Array(3) { DoubleArray(3) }
            inv[0][0] = (a[1][1] * a[2][2] - a[1][2] * a[2][1]) / det
            inv[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det
            inv[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det
            inv[1][0] = (a[1][2] * a[2][0] - a[1][0] * a[2][2]) / det
            inv[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det
            inv[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det
            inv[2][0] = (a[1][0] * a[2][1] - a[1][1] * a[2][0]) / det
            inv[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det
            inv[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det
            return inv
        }
    }
}
