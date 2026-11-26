package com.dsh.starpos.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File

/** 采集单个姿态的静止加速度均值（用于六位置零偏标定）。 */
class ImuBiasCalibrator(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples = ArrayList<DoubleArray>(2048)
    @Volatile private var collecting = false
    @Volatile var lastStd = 0.0
        private set
    @Volatile var sampleCount = 0
        private set

    fun startPose() {
        samples.clear()
        lastStd = 0.0
        sampleCount = 0
        collecting = true
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    /** 结束当前姿态，返回平均加速度向量（设备系）与"抖动"标准差；无数据返回 null。 */
    fun endPose(): Pair<DoubleArray, Double>? {
        collecting = false
        sm.unregisterListener(this)
        if (samples.size < 40) return null
        val n = samples.size.toDouble()
        val mean = doubleArrayOf(
            samples.sumOf { it[0] } / n,
            samples.sumOf { it[1] } / n,
            samples.sumOf { it[2] } / n,
        )
        var varSum = 0.0
        for (s in samples) for (i in 0..2) {
            val d = s[i] - mean[i]
            varSum += d * d
        }
        lastStd = Math.sqrt(varSum / (samples.size * 3))
        return mean to lastStd
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!collecting || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        samples.add(doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
        sampleCount = samples.size
    }
}

/** 加速度计零偏的持久化（filesDir/accel-bias.json）。 */
object AccelBiasStore {
    private const val FILE = "accel-bias.json"

    class Entry(val bias: DoubleArray, val g: Double, val rms: Double, val note: String, val savedAtUtcMs: Long)

    fun load(context: Context): Entry? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return null
        val t = f.readText()
        fun num(key: String): Double =
            Regex("\"" + key + "\"\\s*:\\s*([-0-9.eE+]+)").find(t)?.groupValues?.get(1)?.toDouble() ?: 0.0
        fun str(key: String): String =
            Regex("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
        Entry(
            doubleArrayOf(num("bx"), num("by"), num("bz")),
            num("g"), num("rms"), str("note"), num("saved_at").toLong(),
        )
    }.getOrNull()

    fun save(context: Context, bias: DoubleArray, g: Double, rms: Double, note: String = "六位置法标定"): Boolean =
        runCatching {
            val f = File(context.filesDir, FILE)
            f.writeText(
                """{"bx":${bias[0]},"by":${bias[1]},"bz":${bias[2]},"g":$g,"rms":$rms,"note":"$note","saved_at":${System.currentTimeMillis()}}""",
            )
            apply(bias)
            true
        }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
        apply(null)
    }

    /** 把零偏应用到离线/在线重力估计（FieldAnalyzer.gravityAt）。 */
    fun apply(bias: DoubleArray?) {
        com.dsh.starpos.field.FieldAnalyzer.accelBias = bias
    }

    /** 加速度计映射矩阵（星空自标定结果），存 filesDir/accel-map.json。 */
    fun loadMap(context: Context): DoubleArray? = runCatching {
        val f = File(context.filesDir, "accel-map.json")
        if (!f.isFile) return null
        val t = f.readText()
        val arr = Regex("""\[([^\]]*)\]""").find(t)?.groupValues?.get(1)
            ?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        if (arr.size == 9) arr.toDoubleArray() else null
    }.getOrNull()


    fun applyMap(m: DoubleArray?) {
        com.dsh.starpos.field.FieldAnalyzer.accelMap = m
    }

    /** App 启动时调用：读取并生效。 */
    fun loadAndApply(context: Context) {
        apply(load(context)?.bias)
        applyMap(loadMap(context))
    }
}

