package com.dsh.starpos.ui

import kotlin.math.PI
import kotlin.math.pow

/**
 * 对准辅助：按相机视场 + 光污染等级粗估"肉眼可见星数"。
 * 模型：全天星等累积 N(<m) ≈ 10^(0.45m+1.0)，半球取 1/2；视场立体角 ≈ hFov×vFov（rad²）；
 * 只统计地平线以上部分（按俯仰与竖直视场比例）。
 * 这是给现场找星的量级参考，不是测光结果。
 */
object AimStars {
    fun limitingMag(bortle: Int): Double = when (bortle) {
        1 -> 7.4
        2 -> 7.0
        3 -> 6.6
        4 -> 6.2
        5 -> 5.8
        6 -> 5.4
        7 -> 5.0
        8 -> 4.5
        else -> 4.0
    }

    /** 返回可见星数；俯仰完全在地平线下时返回 0。 */
    fun visibleStars(hFovDeg: Double, vFovDeg: Double, pitchDeg: Double, bortle: Int): Int {
        if (hFovDeg <= 0 || vFovDeg <= 0) return 0
        val aboveFrac = ((pitchDeg + vFovDeg / 2) / vFovDeg).coerceIn(0.0, 1.0)
        if (aboveFrac <= 0.0) return 0
        val lim = limitingMag(bortle)
        val hemisphereStars = 0.5 * 10.0.pow(0.45 * lim + 1.0)
        val solid = Math.toRadians(hFovDeg) * Math.toRadians(vFovDeg)
        val fraction = solid / (2 * PI)
        return (hemisphereStars * fraction * aboveFrac).toInt()
    }
}
