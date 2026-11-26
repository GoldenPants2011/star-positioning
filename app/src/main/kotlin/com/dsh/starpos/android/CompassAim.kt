// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 电子罗盘 + 俯仰：给现场"对准天体"用。
 *
 * - 方位：磁北起算（0=磁北，顺时针）；
 * - 俯仰：**相机光轴**在水平面以上的角度（不是屏幕倾斜角）；
 * - 精度：来自 Rotation Vector 传感器（SENSOR_STATUS_*）。
 *
 * 注：城市里钢筋/电子设备会让磁罗盘偏差 5–15°，仅作"大致对准"用；
 * 真方位 ≈ 磁方位 + 磁差（杭州附近约 −5.5°，见 FieldSessionController.DECLINATION_DEG）。
 */
class CompassAim(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    @Volatile var headingDeg: Double = Double.NaN
        private set
    @Volatile var cameraPitchDeg: Double = Double.NaN
        private set
    @Volatile var accuracy: Int = -1
        private set
    @Volatile var running = false
        private set

    private val r = FloatArray(9)
    private val orient = FloatArray(3)
    private var accelVals: FloatArray? = null
    private var magVals: FloatArray? = null

    fun start() {
        if (running) return
        running = true
        if (rotVec != null) {
            sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
        } else {
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                accuracy = event.accuracy
                updateFromMatrix()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelVals = event.values.clone()
                updateFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magVals = event.values.clone()
                updateFromAccelMag()
            }
        }
    }

    private fun updateFromAccelMag() {
        val a = accelVals ?: return
        val m = magVals ?: return
        if (!SensorManager.getRotationMatrix(r, null, a, m)) return
        updateFromMatrix()
    }

    /** 由 device→world 旋转矩阵 R 求：相机光轴（设备系 −Z）的方位/仰角。 */
    private fun updateFromMatrix() {
        val out = aimFromMatrix(r)
        if (out == null) return
        headingDeg = out[0]
        cameraPitchDeg = out[1]
    }

    companion object {
        /**
         * 纯函数（可单测）：R 为 device→world（X 东、Y 北、Z 上），行主序 3×3。
         * 相机光轴是设备系 −Z；返回 [方位角 0..360（0=北，顺时针）, 俯仰角 −90..90]；
         * 矩阵退化时返回 null。
         */
        fun aimFromMatrix(r: FloatArray): DoubleArray? {
            if (r.size < 9) return null
            // 世界系方向 = R * (0,0,-1) = −第三列
            val ax = -r[2].toDouble()
            val ay = -r[5].toDouble()
            val az = -r[8].toDouble()
            val n = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            if (n < 1e-6) return null
            var hdg = Math.toDegrees(atan2(ax, ay))
            if (hdg < 0) hdg += 360.0
            return doubleArrayOf(hdg, Math.toDegrees(asin((az / n).coerceIn(-1.0, 1.0))))
        }
    }
}
