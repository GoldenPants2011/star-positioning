package com.dsh.starpos.astro

import com.dsh.starpos.field.ImuRow

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 相机 ↔ IMU 外参自标定（手眼标定，AX = XB）。
 *
 * 只用两样东西：星图给出的姿态、陀螺给出的相对旋转。**不需要 GNSS，也不需要"静止"**——
 * 手持晃动只影响质心质量，不影响相对旋转（2026-11-19 实测：手持 1 s 曝光的绝对重力 σ 达 1.19°，
 * 比外参本身还大，所以绝对重力不能用来标定）。
 */
object HandEye {

    /**
     * 带零偏扣除的积分：曝光期间手机是静止的，所以每个曝光窗口内的陀螺均值就是零偏估计。
     * 2026-11-20 实测：跨 47° 大转角的帧对，星图与陀螺差 3.73°，而短间隔只差 0.3°——
     * 说明零偏在长间隔里累积成系统误差，必须先扣掉。
     */
    fun integrateGyroDebiased(rows: List<ImuRow>, t0: Long, t1: Long, exposureNs: Long): Array<DoubleArray>? {
        val win = (exposureNs.coerceIn(100_000_000L, 1_000_000_000L) * 0.6).toLong()
        fun biasAt(startNs: Long): DoubleArray? {
            val seg = rows.filter { it.tNs in startNs..(startNs + win) }
            if (seg.size < 5) return null
            return doubleArrayOf(
                seg.map { it.gx }.average(), seg.map { it.gy }.average(), seg.map { it.gz }.average(),
            )
        }
        val b0 = biasAt(t0) ?: return null
        val b1 = biasAt(t1) ?: return null
        val bias = doubleArrayOf(
            (b0[0] + b1[0]) / 2, (b0[1] + b1[1]) / 2, (b0[2] + b1[2]) / 2,
        )
        val seg = rows.filter { it.tNs in t0..t1 }.sortedBy { it.tNs }
        if (seg.size < 3) return null
        var wx = 0.0
        var wy = 0.0
        var wz = 0.0
        for (i in 1 until seg.size) {
            val dt = (seg[i].tNs - seg[i - 1].tNs) / 1e9
            if (dt <= 0) continue
            wx += 0.5 * (seg[i - 1].gx + seg[i].gx - 2 * bias[0]) * dt
            wy += 0.5 * (seg[i - 1].gy + seg[i].gy - 2 * bias[1]) * dt
            wz += 0.5 * (seg[i - 1].gz + seg[i].gz - 2 * bias[2]) * dt
        }
        return Rodrigues.exp(wx, wy, wz)
    }

    /** 由陀螺样本积分出 t0→t1 的**机体**旋转 D（满足 R(t1)=R(t0)·D）。 */
    fun integrateGyro(rows: List<ImuRow>, t0: Long, t1: Long): Array<DoubleArray>? {
        if (t1 <= t0) return null
        val seg = rows.filter { it.tNs in t0..t1 }.sortedBy { it.tNs }
        if (seg.size < 3) return null
        var wx = 0.0
        var wy = 0.0
        var wz = 0.0
        for (i in 1 until seg.size) {
            val dt = (seg[i].tNs - seg[i - 1].tNs) / 1e9
            if (dt <= 0) continue
            wx += 0.5 * (seg[i - 1].gx + seg[i].gx) * dt
            wy += 0.5 * (seg[i - 1].gy + seg[i].gy) * dt
            wz += 0.5 * (seg[i - 1].gz + seg[i].gz) * dt
        }
        return Rodrigues.exp(wx, wy, wz)
    }

    /** 旋转矩阵 → 旋转向量（轴×角）。 */
    fun rotationVector(r: Array<DoubleArray>): DoubleArray {
        val tr = r[0][0] + r[1][1] + r[2][2]
        val cosTheta = ((tr - 1) / 2).coerceIn(-1.0, 1.0)
        val theta = acos(cosTheta)
        if (abs(theta) < 1e-9) return doubleArrayOf(0.0, 0.0, 0.0)
        val s = 2 * sin(theta)
        if (abs(s) < 1e-9) {
            // θ≈π：从对角线取轴
            val x = sqrt(((r[0][0] + 1) / 2).coerceAtLeast(0.0))
            val y = sqrt(((r[1][1] + 1) / 2).coerceAtLeast(0.0))
            val z = sqrt(((r[2][2] + 1) / 2).coerceAtLeast(0.0))
            return doubleArrayOf(x * theta, y * theta, z * theta)
        }
        return doubleArrayOf(
            (r[2][1] - r[1][2]) / s * theta,
            (r[0][2] - r[2][0]) / s * theta,
            (r[1][0] - r[0][1]) / s * theta,
        )
    }

    /**
     * 解 AX = XB：给定若干对 (A_i = R_iᵀ R_{i+1}，B_i = D_iᵀ)，
     * 求 X（设备→相机）。用 Park–Martin：把 A、B 的旋转轴配对，再用 Wahba 求 X。
     */
    fun solve(pairs: List<Pair<Array<DoubleArray>, Array<DoubleArray>>>): Array<DoubleArray>? {
        if (pairs.size < 2) return null
        val a = ArrayList<DoubleArray>()
        val b = ArrayList<DoubleArray>()
        for ((rotA, rotB) in pairs) {
            val va = rotationVector(rotA)
            val vb = rotationVector(rotB)
            val na = V.norm(va)
            val nb = V.norm(vb)
            if (na < Math.toRadians(0.5) || nb < Math.toRadians(0.5)) continue  // 转动太小，方向无意义
            a.add(doubleArrayOf(va[0] / na, va[1] / na, va[2] / na))
            b.add(doubleArrayOf(vb[0] / nb, vb[1] / nb, vb[2] / nb))
        }
        if (a.size < 2) return null
        // Wahba：最小化 Σ|X·b - a|²（cam=b, sky=a）
        return runCatching { Attitude.wahba(b, a) }.getOrNull()
    }
}

/** 罗德里格斯公式。 */
object Rodrigues {
    fun exp(wx: Double, wy: Double, wz: Double): Array<DoubleArray> {
        val theta = sqrt(wx * wx + wy * wy + wz * wz)
        if (theta < 1e-12) {
            return arrayOf(
                doubleArrayOf(1.0, -wz, wy),
                doubleArrayOf(wz, 1.0, -wx),
                doubleArrayOf(-wy, wx, 1.0),
            )
        }
        val kx = wx / theta
        val ky = wy / theta
        val kz = wz / theta
        val c = Math.cos(theta)
        val s = Math.sin(theta)
        val v = 1 - c
        return arrayOf(
            doubleArrayOf(c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s),
            doubleArrayOf(ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s),
            doubleArrayOf(kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v),
        )
    }
}



