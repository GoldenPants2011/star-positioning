// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

/**
 * 星点检测/解算参数按相机自适应。
 *
 * 超广角镜头焦距短（fx≈720 px@1640 宽）：星点只有 1–2 px，
 * 用主摄的 area>=3 / triangleMag=5.0 会把绝大部分星点过滤掉 → 现场"0 匹配"。
 */
object StarSolveParams {

    /** fx 小于该值视为小焦距（超广角/前置）。 */
    const val WIDE_FX_THRESHOLD = 1000.0

    fun isWide(cam: CameraIntrinsics): Boolean = cam.fx < WIDE_FX_THRESHOLD

    /** 星点候选过滤：广角放宽到 1–2 px、略宽的长宽比。 */
    fun accept(det: Detection, cam: CameraIntrinsics): Boolean =
        if (isWide(cam)) det.aspect < 2.8f && det.area in 2..400
        else det.aspect < 2.0f && det.area in 3..400

    /** 检测阈值阶梯（广角星暗，需要更低的 5.0σ 档）。 */
    fun thresholds(cam: CameraIntrinsics): DoubleArray =
        if (isWide(cam)) doubleArrayOf(7.0, 5.5, 5.0) else doubleArrayOf(7.0, 5.5, 9.0)

    /** 星表三角形索引的星等上限（广角里检出星少，索引也要用更暗的星）。 */
    fun triangleMag(cam: CameraIntrinsics): Double = if (isWide(cam)) 6.5 else 5.0

    fun maxImageStars(cam: CameraIntrinsics): Int = if (isWide(cam)) 60 else 30

    fun matchToleranceDeg(cam: CameraIntrinsics): Double = if (isWide(cam)) 0.12 else 0.08
}
