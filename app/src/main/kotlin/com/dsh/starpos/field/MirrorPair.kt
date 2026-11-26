package com.dsh.starpos.field

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.V

/**
 * 水镜像配对：画面里同框的"直射像"（地平线以上）与"反射像"（地平线以下）
 * 关于**水平面对称**，因此 u_direct − u_reflected 归一化后就是相机坐标系里的
 * "天顶方向"（不依赖 IMU！），天体高度 alt = asin(u_direct · n)。
 *
 * 注意符号：反射像是"反射后的视线方向"（在水平面下方），
 *   反射关系 r = u − 2(u·n)n
 * 所以天顶 n ∥ (u − r)，不是 u + r（2026-11-24 真机数据发现旧公式符号写反）。
 *
 * 用途：
 *  - 同框：图像自定垂直基准 → 高度 → 位置 LOP；
 *  - 只拍到反射像：需要 IMU 天顶，alt = -asin(u_reflect · n)。
 */
object MirrorPair {

    class Result(
        /** 相机坐标系里的天顶（= 直射/反射方向之和的归一化）。 */
        val upCam: DoubleArray,
        /** 视高度（弧度，含大气折射，未改正）；>0 表示在地平线上。 */
        val apparentAltRad: Double,
        /** 镜像对称残差（度）：理想镜面反射时≈0。 */
        val residualDeg: Double,
        val directVec: DoubleArray,
        val reflectedVec: DoubleArray,
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double =
        Math.toDegrees(Math.acos(dot(a, b).coerceIn(-1.0, 1.0)))

    /**
     * 由直射像方向与反射像方向求天顶与视高度。
     * [upHint]（可选，通常用 IMU 天顶）用于排除"关于水平轴镜像"的歧义解：
     * 只有与 hint 夹角 <20° 的解才接受。
     */
    fun solve(direct: DoubleArray, reflected: DoubleArray, upHint: DoubleArray? = null): Result? {
        val u = V.unit(direct)
        val r = V.unit(reflected)
        // 天顶 ∥ (直射 − 反射)：反射像在水平面下方，r = u − 2(u·n)n
        val diff = doubleArrayOf(u[0] - r[0], u[1] - r[1], u[2] - r[2])
        val n = V.unit(diff)
        val alt = Math.asin(dot(u, n).coerceIn(-1.0, 1.0))
        if (alt <= Math.toRadians(0.5)) return null  // 直射像必须在地平线上
        if (upHint != null) {
            val h = V.unit(upHint)
            val ang = Math.toDegrees(Math.acos(dot(n, h).coerceIn(-1.0, 1.0)))
            if (ang > 20.0) return null
        }
        // 镜像检查：反射像应 ≈ u − 2(u·n)n（在地平线下方）
        val c = 2.0 * dot(u, n)
        val pred = doubleArrayOf(u[0] - c * n[0], u[1] - c * n[1], u[2] - c * n[2])
        val resid = angleDeg(pred, r)
        return Result(n, alt, resid, u, r)
    }

    /** 用一张画面找"直射+反射"圆盘对（离线分析用）。返回（结果、直射像素、反射像素）。 */
    fun findPair(
        lum: FloatArray,
        w: Int,
        h: Int,
        cam: CameraIntrinsics,
        thresholdSigma: Double = 6.0,
        upHint: DoubleArray? = null,
    ): Triple<Result, DoubleArray, DoubleArray>? {
        val det = FrameAnalyzer.analyze(lum, w, h, thresholdSigma = thresholdSigma)
        val cand = det.detections
            .filter { it.area in 12..20000 && it.aspect < 3.0 }
            .map { it to V.unit(cam.unproject(it.x.toDouble(), it.y.toDouble())) }
        if (cand.size < 2) return null
        var best: Triple<Result, DoubleArray, DoubleArray>? = null
        var bestScore = Double.MAX_VALUE
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val (di, ui) = cand[i]
            val (dj, uj) = cand[j]
            // 两像应大致同亮、同大小（反射像偏暗/略散焦，放宽）
            val ratio = di.peak.coerceAtLeast(1e-9) / dj.peak.coerceAtLeast(1e-9)
            if (ratio < 0.01 || ratio > 50.0) continue
            val areaRatio = di.area.toDouble() / dj.area.coerceAtLeast(1)
            if (areaRatio < 0.1 || areaRatio > 10.0) continue
            val options = listOfNotNull(
                solve(ui, uj, upHint)?.let { Triple(it, di, dj) },
                solve(uj, ui, upHint)?.let { Triple(it, dj, di) },
            )
            for (triple in options) {
                val (pair, d0, r0) = triple
                val uRef = V.unit(cam.unproject(r0.x.toDouble(), r0.y.toDouble()))
                val altRef = Math.asin(
                    (uRef[0] * pair.upCam[0] + uRef[1] * pair.upCam[1] + uRef[2] * pair.upCam[2]).coerceIn(-1.0, 1.0),
                )
                if (altRef > -Math.toRadians(1.0)) continue
                val score = pair.residualDeg * 3 + Math.abs(Math.toDegrees(pair.apparentAltRad + altRef)) * 2
                if (score < bestScore) {
                    bestScore = score
                    best = Triple(
                        pair,
                        doubleArrayOf(d0.x.toDouble(), d0.y.toDouble()),
                        doubleArrayOf(r0.x.toDouble(), r0.y.toDouble()),
                    )
                }
            }
        }
        return best
    }

    /** 反射像单独使用：给定 IMU 天顶，视高度 alt = -asin(u_reflect · n)。 */
    fun altitudeFromReflectionOnly(reflected: DoubleArray, upCam: DoubleArray): Double =
        -Math.asin(dot(V.unit(reflected), V.unit(upCam)).coerceIn(-1.0, 1.0))
}
