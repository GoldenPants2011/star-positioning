// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 拍摄期稳定度监视：给用户"现在能不能拍"的实时反馈。
 *
 * 依据 2026-11-19 实测：三脚架 gyro≈0.002–0.003 rad/s，手持≈0.03 rad/s。
 * 单历元定位要求静止（重力方向要准），所以这个指示直接决定数据能不能用。
 */
class ShakeMonitor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile var gyroMag: Double = 0.0
        private set
    @Volatile var accelMag: Double = 0.0
        private set
    @Volatile var active: Boolean = false
        private set

    fun start() {
        if (active) return
        active = true
        gyro?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        active = false
        manager.unregisterListener(this)
    }

    /** 稳定等级：0=稳，1=轻晃，2=在动。 */
    fun level(): Int = when {
        gyroMag < 0.015 -> 0
        gyroMag < 0.05 -> 1
        else -> 2
    }

    fun text(): String {
        val lv = level()
        val label = when (lv) {
            0 -> "稳"
            1 -> "轻晃"
            else -> "在动"
        }
        return "稳定度：%s（%.3f rad/s）".format(label, gyroMag)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                gyroMag = sqrt(x * x + y * y + z * z)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                accelMag = sqrt(x * x + y * y + z * z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
