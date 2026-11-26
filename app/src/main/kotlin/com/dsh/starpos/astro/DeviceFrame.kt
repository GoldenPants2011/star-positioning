package com.dsh.starpos.astro

/**
 * 设备坐标系与相机坐标系的关系。
 *
 * Android 设备轴：X 右、Y 上、Z 指向屏幕外（朝用户）。
 * Camera2 相机轴：X 右、Y 下、Z 指向被摄景物（从镜头射出）。
 * 后置相机二者相差绕 X 轴 180°：R = diag(1, −1, −1)。
 * 前置相机还需要叠加镜像，本期不支持前置。
 */
object DeviceFrame {

    /**
     * 设备 → **传感器原始图像** 坐标系（我们的采集包存的就是传感器原始帧，不做旋转）。
     *
     * `sensorOrientation` 是 CameraCharacteristics.SENSOR_ORIENTATION：
     * 把传感器图像顺时针转多少度才是"正立"画面。
     *
     * 旧实现一律用 diag(1,−1,−1)（等价于"已经把图转正"的情况），
     * 用在未旋转的原始帧上会差一个滚转 → 天顶方向算错 → 位置偏出几千公里
     * （2026-11-19 真机数据暴露，合成用例因为生成/求解用同一约定而没暴露）。
     */
    fun cameraFromDevice(sensorOrientationDeg: Int): Array<DoubleArray> = when (
        ((sensorOrientationDeg % 360) + 360) % 360
    ) {
        90 -> arrayOf(
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        180 -> arrayOf(
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        270 -> arrayOf(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
        else -> arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        )
    }

    /** 兼容旧调用：默认按常见手机（90°）处理。 */
    fun deviceToCameraRear(sensorOrientationDeg: Int = 90): Array<DoubleArray> =
        cameraFromDevice(sensorOrientationDeg)
}

