package com.dsh.starpos.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.dsh.starpos.field.ImuRow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * IMU 记录：加速度计 + 陀螺，`SENSOR_DELAY_FASTEST`。
 *
 * CSV 每行同时带 accel 与 gyro：陀螺采用"最近值保持"（两条流时间戳不同），
 * 只有陀螺样本比 accel 样本新 100 ms 以内才填入，否则记 0，避免把旧数据处理成新数据。
 */
class ImuRecorder(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rows = CopyOnWriteArrayList<ImuRow>()
    @Volatile private var recording = false
    @Volatile private var lastGx = 0.0
    @Volatile private var lastGy = 0.0
    @Volatile private var lastGz = 0.0
    @Volatile private var lastGyroNs = 0L
    @Volatile private var startedNs = 0L
    private val maxRows = 200_000

    val available: Boolean get() = accelSensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!recording || rows.size >= maxRows) return
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastGx = event.values[0].toDouble()
                    lastGy = event.values[1].toDouble()
                    lastGz = event.values[2].toDouble()
                    lastGyroNs = event.timestamp
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val fresh = lastGyroNs > 0 && abs(event.timestamp - lastGyroNs) < 100_000_000L
                    rows.add(
                        ImuRow(
                            tNs = event.timestamp,
                            ax = event.values[0].toDouble(),
                            ay = event.values[1].toDouble(),
                            az = event.values[2].toDouble(),
                            gx = if (fresh) lastGx else 0.0,
                            gy = if (fresh) lastGy else 0.0,
                            gz = if (fresh) lastGz else 0.0,
                        ),
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 请求的采样周期（µs）：0 = FASTEST(>200 Hz)，20000 = GAME(50 Hz)，60000 = UI(~16 Hz)。 */
    @Volatile var requestedDelayUs: Int = SensorManager.SENSOR_DELAY_FASTEST
        private set
    /** 实际注册成功的采样周期（µs）；-1 表示注册失败。 */
    @Volatile var effectiveDelayUs: Int = -1
        private set
    /** 注册失败原因（例如缺少 HIGH_SAMPLING_RATE_SENSORS 权限）。 */
    @Volatile var failureReason: String? = null
        private set

    /**
     * 启动 IMU。降级链：FASTEST(0) → GAME(20000) → UI(60000)。
     * Android 12+ 用 0 µs 采样需要 `HIGH_SAMPLING_RATE_SENSORS`（normal 权限，manifest 已声明）；
     * 万一机型仍拒绝，自动降级而不是让整次采集失败。
     */
    fun start(): Boolean {
        val a = accelSensor ?: run {
            failureReason = "设备没有加速度计"
            return false
        }
        rows.clear()
        lastGyroNs = 0
        startedNs = System.nanoTime()
        failureReason = null
        val ladder = intArrayOf(
            SensorManager.SENSOR_DELAY_FASTEST,
            SensorManager.SENSOR_DELAY_GAME,
            SensorManager.SENSOR_DELAY_UI,
        )
        for (delayUs in ladder) {
            val ok = runCatching {
                val accelOk = manager.registerListener(listener, a, delayUs)
                gyroSensor?.let { manager.registerListener(listener, it, delayUs) }
                accelOk
            }.getOrElse { e ->
                failureReason = "${e::class.java.simpleName}: ${e.message}"
                false
            }
            if (ok) {
                requestedDelayUs = delayUs
                effectiveDelayUs = delayUs
                recording = true
                return true
            }
        }
        // 全部失败：确保不残留半注册状态
        runCatching { manager.unregisterListener(listener) }
        recording = false
        effectiveDelayUs = -1
        if (failureReason == null) failureReason = "registerListener 返回 false"
        return false
    }

    fun stop() {
        recording = false
        runCatching { manager.unregisterListener(listener) }
    }

    fun rows(): List<ImuRow> = rows.toList()

    fun count(): Int = rows.size

    /** 实测采样率（Hz）；样本太少返回 0。 */
    fun sampleRateHz(): Double {
        val list = rows
        if (list.size < 2) return 0.0
        val dt = (list[list.size - 1].tNs - list[0].tNs) / 1e9
        return if (dt > 0) (list.size - 1) / dt else 0.0
    }

}
