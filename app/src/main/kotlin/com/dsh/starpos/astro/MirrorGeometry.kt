package com.dsh.starpos.astro

/** 水镜（水平液面）几何：托盘距离与"直射像/反射像"在画面里的预测位置。 */
object MirrorGeometry {

    /** 镜头高度 H（距水面）与天体高度 h → 托盘水平距离 d = H / tan(h)（米）。 */
    fun trayDistanceM(heightM: Double, altDeg: Double): Double {
        if (altDeg <= 0.5) return Double.NaN
        return heightM / Math.tan(Math.toRadians(altDeg))
    }

    /**
     * 把天球方向（altDeg/azDeg，真方位）投影到相机画面（归一化 0..1）。
     * 相机姿态用"真方位 headingDeg + 俯仰 pitchDeg、假设无滚转"近似（只用于粗对准）。
     * @return (nx, ny) 归一化像素坐标；目标在相机后方/画面外返回 null。
     */
    fun mark(
        altDeg: Double,
        azDeg: Double,
        headingDeg: Double,
        pitchDeg: Double,
        fx: Double,
        fy: Double,
        width: Int = 2048,
        height: Int = 1536,
    ): Pair<Float, Float>? {
        val a = Math.toRadians(altDeg)
        val z = Math.toRadians(azDeg)
        val b = doubleArrayOf(Math.cos(a) * Math.sin(z), Math.cos(a) * Math.cos(z), Math.sin(a))
        val h = Math.toRadians(headingDeg)
        val p = Math.toRadians(pitchDeg)
        val fwd = doubleArrayOf(Math.cos(p) * Math.sin(h), Math.cos(p) * Math.cos(h), Math.sin(p))
        val right = doubleArrayOf(Math.cos(h), -Math.sin(h), 0.0)
        val down = doubleArrayOf(
            fwd[1] * right[2] - fwd[2] * right[1],
            fwd[2] * right[0] - fwd[0] * right[2],
            fwd[0] * right[1] - fwd[1] * right[0],
        )
        fun dot(u: DoubleArray, v: DoubleArray) = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
        val zc = dot(b, fwd)
        if (zc <= 0.05) return null
        val xc = dot(b, right) / zc
        val yc = dot(b, down) / zc
        val px = fx * xc + width / 2.0
        val py = fy * yc + height / 2.0
        if (px < 0 || py < 0 || px > width || py > height) return null
        return (px / width).toFloat() to (py / height).toFloat()
    }
}
