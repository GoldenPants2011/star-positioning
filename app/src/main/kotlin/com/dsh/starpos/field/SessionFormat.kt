// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.Projection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 采集包格式（schema = 1）。目录结构：
 *
 *   session.json               会话清单（设备/相机能力/内参来源/采集参数/时钟/GNSS 真值）
 *   frames/frame_000.bin       亮度图（SPF1 头 + 8 或 16 位像素，小端）
 *   frames/frame_000.json      该帧的逐帧元数据
 *   imu/imu.csv                t_elapsed_ns,ax,ay,az,gx,gy,gz
 *   gnss/gnss.csv              utc_ms,lat,lon,alt_m,accuracy_m,provider
 *   notes.txt                  现场备注（可选）
 *
 * 设计原则：**分析端不需要 Android**，只需要这个 zip；因此所有真值、参数、时间基都必须在包里。
 */

class IntrinsicsRecord(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val k3: Double = 0.0,
    /** pinhole | fisheye_equidistant */
    val projection: String = "pinhole",
    /** calibration（厂商标定）| estimated（由焦距+像元推算）| assumed（兜底） */
    val source: String = "estimated",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "width" to width,
        "height" to height,
        "fx" to fx,
        "fy" to fy,
        "cx" to cx,
        "cy" to cy,
        "k1" to k1,
        "k2" to k2,
        "p1" to p1,
        "p2" to p2,
        "k3" to k3,
        "projection" to projection,
        "source" to source,
    )

    /** 按实际帧尺寸缩放（相机可能按像素阵列裁切）。 */
    fun toCameraIntrinsics(frameWidth: Int, frameHeight: Int): CameraIntrinsics {
        val sx = frameWidth.toDouble() / width
        val sy = frameHeight.toDouble() / height
        val proj = if (projection == "fisheye_equidistant") Projection.FISHEYE_EQUIDISTANT else Projection.PINHOLE
        return CameraIntrinsics(
            width = frameWidth,
            height = frameHeight,
            fx = fx * sx,
            fy = fy * sy,
            cx = cx * sx,
            cy = cy * sy,
            k1 = k1,
            k2 = k2,
            p1 = p1,
            p2 = p2,
            k3 = k3,
            projection = proj,
        )
    }

    companion object {
        fun from(map: Map<String, Any?>): IntrinsicsRecord = IntrinsicsRecord(
            width = map.int("width", 1),
            height = map.int("height", 1),
            fx = map.num("fx"),
            fy = map.num("fy"),
            cx = map.num("cx"),
            cy = map.num("cy"),
            k1 = map.num("k1"),
            k2 = map.num("k2"),
            p1 = map.num("p1"),
            p2 = map.num("p2"),
            k3 = map.num("k3"),
            projection = map.str("projection", "pinhole"),
            source = map.str("source", "estimated"),
        )
    }
}

class CameraCaps(
    val usedCameraId: String,
    val cameraIds: List<String>,
    val physicalCameraIds: List<String>,
    val lensFacing: String,
    val hardwareLevel: String,
    val activeArrayW: Int,
    val activeArrayH: Int,
    val pixelArrayW: Int,
    val pixelArrayH: Int,
    val sensorPhysicalWmm: Double,
    val sensorPhysicalHmm: Double,
    val focalLengthsMm: List<Double>,
    val rawSupported: Boolean,
    val yuvSupported: Boolean,
    val timestampSource: String,
    val exposureRangeNs: List<Long>,
    val isoRange: List<Int>,
    val intrinsicCalibration: List<Double>,
    val distortion: List<Double>,
    val oisUsed: Boolean,
    /** CameraCharacteristics.SENSOR_ORIENTATION：传感器图像需顺时针旋转多少度为正立。 */
    val sensorOrientation: Int = 90,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "used_camera_id" to usedCameraId,
        "camera_ids" to cameraIds,
        "physical_camera_ids" to physicalCameraIds,
        "lens_facing" to lensFacing,
        "hardware_level" to hardwareLevel,
        "active_array" to listOf(activeArrayW, activeArrayH),
        "pixel_array" to listOf(pixelArrayW, pixelArrayH),
        "sensor_physical_size_mm" to listOf(sensorPhysicalWmm, sensorPhysicalHmm),
        "focal_lengths_mm" to focalLengthsMm,
        "raw_supported" to rawSupported,
        "yuv_supported" to yuvSupported,
        "timestamp_source" to timestampSource,
        "exposure_range_ns" to exposureRangeNs,
        "iso_range" to isoRange,
        "lens_intrinsic_calibration" to intrinsicCalibration,
        "lens_distortion" to distortion,
        "ois_used" to oisUsed,
        "sensor_orientation" to sensorOrientation,
    )

    companion object {
        fun from(map: Map<String, Any?>): CameraCaps = CameraCaps(
            usedCameraId = map.str("used_camera_id"),
            cameraIds = map.strList("camera_ids"),
            physicalCameraIds = map.strList("physical_camera_ids"),
            lensFacing = map.str("lens_facing"),
            hardwareLevel = map.str("hardware_level"),
            activeArrayW = map.numList("active_array").getOrElse(0) { 0.0 }.toInt(),
            activeArrayH = map.numList("active_array").getOrElse(1) { 0.0 }.toInt(),
            pixelArrayW = map.numList("pixel_array").getOrElse(0) { 0.0 }.toInt(),
            pixelArrayH = map.numList("pixel_array").getOrElse(1) { 0.0 }.toInt(),
            sensorPhysicalWmm = map.numList("sensor_physical_size_mm").getOrElse(0) { 0.0 },
            sensorPhysicalHmm = map.numList("sensor_physical_size_mm").getOrElse(1) { 0.0 },
            focalLengthsMm = map.numList("focal_lengths_mm"),
            rawSupported = map.bool("raw_supported"),
            yuvSupported = map.bool("yuv_supported", true),
            timestampSource = map.str("timestamp_source", "unknown"),
            exposureRangeNs = (map["exposure_range_ns"] as? List<Any?>)?.mapNotNull { (it as? Number)?.toLong() }
                ?: emptyList(),
            isoRange = (map["iso_range"] as? List<Any?>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
            intrinsicCalibration = map.numList("lens_intrinsic_calibration"),
            distortion = map.numList("lens_distortion"),
            oisUsed = map.bool("ois_used"),
            sensorOrientation = map.int("sensor_orientation", 90),
        )
    }
}

class FrameMeta(
    val index: Int,
    val sensorTimestampNs: Long,
    val elapsedRealtimeNs: Long,
    val wallClockUtcMs: Long,
    val exposureNs: Long,
    val iso: Int,
    val focalLengthMm: Double,
    val focusDistanceDiopters: Double,
    val zoomRatio: Double,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    /** raw16 | yuv8 */
    val source: String,
    val oisEnabled: Boolean,
    val awbMode: Int,
    val frameDurationNs: Long,
    val note: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "index" to index,
        "sensor_timestamp_ns" to sensorTimestampNs,
        "elapsed_realtime_ns" to elapsedRealtimeNs,
        "wall_clock_utc_ms" to wallClockUtcMs,
        "exposure_ns" to exposureNs,
        "iso" to iso,
        "focal_length_mm" to focalLengthMm,
        "focus_distance_diopters" to focusDistanceDiopters,
        "zoom_ratio" to zoomRatio,
        "width" to width,
        "height" to height,
        "bit_depth" to bitDepth,
        "source" to source,
        "ois_enabled" to oisEnabled,
        "awb_mode" to awbMode,
        "frame_duration_ns" to frameDurationNs,
        "note" to note,
    )

    companion object {
        fun from(map: Map<String, Any?>): FrameMeta = FrameMeta(
            index = map.int("index"),
            sensorTimestampNs = map.long("sensor_timestamp_ns"),
            elapsedRealtimeNs = map.long("elapsed_realtime_ns"),
            wallClockUtcMs = map.long("wall_clock_utc_ms"),
            exposureNs = map.long("exposure_ns"),
            iso = map.int("iso"),
            focalLengthMm = map.num("focal_length_mm"),
            focusDistanceDiopters = map.num("focus_distance_diopters", -1.0),
            zoomRatio = map.num("zoom_ratio", 1.0),
            width = map.int("width"),
            height = map.int("height"),
            bitDepth = map.int("bit_depth", 8),
            source = map.str("source", "yuv8"),
            oisEnabled = map.bool("ois_enabled"),
            awbMode = map.int("awb_mode", -1),
            frameDurationNs = map.long("frame_duration_ns"),
            note = map.str("note"),
        )
    }
}

class ImuRow(
    val tNs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
)

data class GnssRow(
    val utcMillis: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val accuracyM: Double,
    val provider: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "utc_ms" to utcMillis,
        "lat" to lat,
        "lon" to lon,
        "alt_m" to altM,
        "accuracy_m" to accuracyM,
        "provider" to provider,
    )

    companion object {
        fun from(map: Map<String, Any?>): GnssRow = GnssRow(
            utcMillis = map.long("utc_ms"),
            lat = map.num("lat"),
            lon = map.num("lon"),
            altM = map.num("alt_m"),
            accuracyM = map.num("accuracy_m"),
            provider = map.str("provider"),
        )
    }
}

class SessionManifest(
    val sessionId: String,
    val createdAtUtcMs: Long,
    val appVersion: String,
    val device: Map<String, Any?>,
    val camera: CameraCaps?,
    val intrinsics: IntrinsicsRecord?,
    val capture: Map<String, Any?>,
    val clock: Map<String, Any?>,
    val note: String = "",
    val frames: List<FrameMeta> = emptyList(),
    val gnssTruth: GnssRow? = null,
    /** 采集时实际使用的加速度计零偏（mg，设备系）；离线分析必须减掉，否则天顶偏 ~1.4°。 */
    val accelBiasMg: DoubleArray? = null,
    /** 采集时实际使用的加速度计映射矩阵（9 个数，行优先）。 */
    val accelMap: DoubleArray? = null,
    val schema: Int = 1,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "schema" to schema,
        "session_id" to sessionId,
        "created_at_utc_ms" to createdAtUtcMs,
        "app_version" to appVersion,
        "device" to device,
        "camera" to camera?.toMap(),
        "intrinsics" to intrinsics?.toMap(),
        "capture" to capture,
        "clock" to clock,
        "note" to note,
        "frames" to frames.map { it.toMap() },
        "gnss_truth" to gnssTruth?.toMap(),
        "accel_bias_mg" to accelBiasMg?.toList(),
        "accel_map" to accelMap?.toList(),
    )

    fun toJson(): String = JsonText.write(toMap())

    companion object {
        const val FILE = "session.json"

        fun fromJson(text: String): SessionManifest {
            val root = JsonText.parse(text).asMap()
            return SessionManifest(
                sessionId = root.str("session_id"),
                createdAtUtcMs = root.long("created_at_utc_ms"),
                appVersion = root.str("app_version"),
                device = root.sub("device"),
                camera = root["camera"]?.asMapOrNull()?.let { CameraCaps.from(it) },
                intrinsics = root["intrinsics"]?.asMapOrNull()?.let { IntrinsicsRecord.from(it) },
                capture = root.sub("capture"),
                clock = root.sub("clock"),
                note = root.str("note"),
                frames = (root["frames"] as? List<Any?>).orEmpty().map { FrameMeta.from(it.asMap()) },
                gnssTruth = root["gnss_truth"]?.asMapOrNull()?.let { GnssRow.from(it) },
                accelBiasMg = (root["accel_bias_mg"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 3 }?.toDoubleArray(),
                accelMap = (root["accel_map"] as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 9 }?.toDoubleArray(),
                schema = root.int("schema", 1),
            )
        }
    }
}

/** 亮度图二进制：SPF1 + u16 version + u16 bitDepth + u32 width + u32 height + 像素（小端）。 */
object FrameBinary {
    private const val MAGIC = "SPF1"

    fun write(out: OutputStream, width: Int, height: Int, bitDepth: Int, pixels: ShortArray) {
        DataOutputStream(out.buffered()).use { d ->
            d.write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeShortLE(d, 1)
            writeShortLE(d, bitDepth)
            writeIntLE(d, width)
            writeIntLE(d, height)
            for (p in pixels) writeShortLE(d, p.toInt())
            d.flush()
        }
    }

    fun write8(out: OutputStream, width: Int, height: Int, pixels: ByteArray) {
        DataOutputStream(out.buffered()).use { d ->
            d.write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeShortLE(d, 1)
            writeShortLE(d, 8)
            writeIntLE(d, width)
            writeIntLE(d, height)
            d.write(pixels)
            d.flush()
        }
    }

    class Decoded(val width: Int, val height: Int, val bitDepth: Int, val values: ShortArray)

    fun read(input: InputStream): Decoded {
        DataInputStream(input.buffered()).use { d ->
            val magic = ByteArray(4)
            d.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "帧文件魔数不匹配" }
            val version = readShortLE(d)
            require(version == 1) { "不支持的帧格式版本 $version" }
            val bitDepth = readShortLE(d)
            val w = readIntLE(d)
            val h = readIntLE(d)
            val n = w * h
            val out = ShortArray(n)
            if (bitDepth == 16) {
                for (i in 0 until n) out[i] = readShortLE(d).toShort()
            } else {
                val buf = ByteArray(n)
                d.readFully(buf)
                for (i in 0 until n) out[i] = (((buf[i].toInt() and 0xFF)) * 257).toShort()
            }
            return Decoded(w, h, bitDepth, out)
        }
    }

    private fun writeShortLE(d: DataOutputStream, v: Int) {
        d.write(v and 0xFF); d.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(d: DataOutputStream, v: Int) {
        d.write(v and 0xFF); d.write((v shr 8) and 0xFF); d.write((v shr 16) and 0xFF); d.write((v shr 24) and 0xFF)
    }

    private fun readShortLE(d: DataInputStream): Int {
        val b0 = d.readUnsignedByte(); val b1 = d.readUnsignedByte()
        return b0 or (b1 shl 8)
    }

    private fun readIntLE(d: DataInputStream): Int {
        val b0 = d.readUnsignedByte(); val b1 = d.readUnsignedByte()
        val b2 = d.readUnsignedByte(); val b3 = d.readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

object Csv {
    fun writeImu(rows: List<ImuRow>): String = buildString {
        append("t_ns,ax,ay,az,gx,gy,gz\n")
        for (r in rows) {
            append(r.tNs).append(',')
            append(fmt(r.ax)).append(',').append(fmt(r.ay)).append(',').append(fmt(r.az)).append(',')
            append(fmt(r.gx)).append(',').append(fmt(r.gy)).append(',').append(fmt(r.gz)).append('\n')
        }
    }

    fun readImu(text: String): List<ImuRow> = text.lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 7) return@mapNotNull null
            ImuRow(
                tNs = p[0].toLongOrNull() ?: return@mapNotNull null,
                ax = p[1].toDoubleOrNull() ?: 0.0,
                ay = p[2].toDoubleOrNull() ?: 0.0,
                az = p[3].toDoubleOrNull() ?: 0.0,
                gx = p[4].toDoubleOrNull() ?: 0.0,
                gy = p[5].toDoubleOrNull() ?: 0.0,
                gz = p[6].toDoubleOrNull() ?: 0.0,
            )
        }
        .toList()

    fun writeGnss(rows: List<GnssRow>): String = buildString {
        append("utc_ms,lat,lon,alt_m,accuracy_m,provider\n")
        for (r in rows) {
            append(r.utcMillis).append(',')
            append(fmt(r.lat)).append(',').append(fmt(r.lon)).append(',')
            append(fmt(r.altM)).append(',').append(fmt(r.accuracyM)).append(',').append(r.provider).append('\n')
        }
    }

    fun readGnss(text: String): List<GnssRow> = text.lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 6) return@mapNotNull null
            GnssRow(
                utcMillis = p[0].toLongOrNull() ?: return@mapNotNull null,
                lat = p[1].toDoubleOrNull() ?: 0.0,
                lon = p[2].toDoubleOrNull() ?: 0.0,
                altM = p[3].toDoubleOrNull() ?: 0.0,
                accuracyM = p[4].toDoubleOrNull() ?: 0.0,
                provider = p[5],
            )
        }
        .toList()

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.7f", v)
}


