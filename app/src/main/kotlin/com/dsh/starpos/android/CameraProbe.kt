// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.dsh.starpos.field.CameraCaps
import com.dsh.starpos.field.IntrinsicsRecord
import com.dsh.starpos.field.JsonText
import com.dsh.starpos.field.asMap
import com.dsh.starpos.field.num

/**
 * 相机能力探测与内参解析（需求 FR-03 的"出厂数据仅作初值"部分）。
 *
 * 优先级：
 *  1) Camera2 `LENS_INTRINSIC_CALIBRATION`（厂商标定，通常只在部分机型/后置主摄可用）；
 *  2) 由 `SENSOR_INFO_PHYSICAL_SIZE` + `SENSOR_INFO_PIXEL_ARRAY_SIZE` + 焦距推算（主点假设为图像中心）；
 *  3) 兜底假设。
 * 无论走哪条，`IntrinsicsRecord.source` 都会写进采集包，分析端据此判断结果可信度。
 */
object CameraProbe {

    fun cameraManager(context: Context): CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** 优先选后置主摄（LENS_FACING_BACK 且支持的输出最大）。 */
    fun pickBackCamera(cm: CameraManager): String? {
        var best: String? = null
        var bestScore = -1L
        for (id in cm.cameraIdList) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: continue
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val raw = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            val size = map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.firstOrNull()
            val score = (if (raw) 1_000_000_000_000L else 0L) + (size?.let { it.width.toLong() * it.height } ?: 0L)
            if (score > bestScore) { bestScore = score; best = id }
        }
        return best ?: cm.cameraIdList.firstOrNull()
    }

    fun describe(cm: CameraManager, id: String): CameraCaps? {
        val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: return null
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixel = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val yuv = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.isNotEmpty() == true
        val exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val calibration = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList() ?: emptyList()
        val distortion = ch.get(CameraCharacteristics.LENS_DISTORTION)?.toList() ?: emptyList()
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            0 -> "LIMITED"
            1 -> "FULL"
            2 -> "LEGACY"
            3 -> "LEVEL_3"
            4 -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        val tsSource = when (ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            else -> "UNKNOWN"
        }
        return CameraCaps(
            usedCameraId = id,
            cameraIds = cm.cameraIdList.toList(),
            physicalCameraIds = if (Build.VERSION.SDK_INT >= 28) ch.physicalCameraIds.toList() else emptyList(),
            lensFacing = facing,
            hardwareLevel = level,
            activeArrayW = if (active != null) active.right - active.left else 0,
            activeArrayH = if (active != null) active.bottom - active.top else 0,
            pixelArrayW = pixel?.width ?: 0,
            pixelArrayH = pixel?.height ?: 0,
            sensorPhysicalWmm = phys?.width?.toDouble() ?: 0.0,
            sensorPhysicalHmm = phys?.height?.toDouble() ?: 0.0,
            focalLengthsMm = focal.map { it.toDouble() },
            rawSupported = raw,
            yuvSupported = yuv,
            timestampSource = tsSource,
            exposureRangeNs = listOf(exposureRange?.lower ?: 0L, exposureRange?.upper ?: 0L),
            isoRange = listOf(isoRange?.lower ?: 0, isoRange?.upper ?: 0),
            intrinsicCalibration = calibration.map { it.toDouble() },
            distortion = distortion.map { it.toDouble() },
            oisUsed = false,
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
        )
    }

    /** 解析内参；width/height 采用相机输出的实际尺寸（调用方传入）。 */
    /** 读取 tools/calibrate_intrinsics.py 产出的 calibration.json（adb push 到 app 私有目录）。 */
    fun loadCalibrationFile(context: Context, cameraId: String? = null): IntrinsicsRecord? {
        // 每个相机单独标定：calibration-cam<id>.json；主摄（id=0/空）兼容旧文件名 calibration.json
        val dir = context.filesDir
        val f = sequenceOf(
            cameraId?.let { java.io.File(dir, "calibration-cam$it.json") },
            java.io.File(dir, "calibration.json"),
        ).filterNotNull().firstOrNull { it.isFile } ?: return null
        return runCatching {
            val m = JsonText.parse(f.readText()).asMap()
            IntrinsicsRecord(
                width = m.num("width").toInt(),
                height = m.num("height").toInt(),
                fx = m.num("fx"),
                fy = m.num("fy"),
                cx = m.num("cx"),
                cy = m.num("cy"),
                k1 = 0.0, k2 = 0.0, p1 = 0.0, p2 = 0.0, k3 = 0.0,
                projection = "pinhole",
                source = "calibration",
            )
        }.getOrNull()
    }

    fun resolveIntrinsics(
        caps: CameraCaps,
        width: Int,
        height: Int,
        calibration: IntrinsicsRecord? = null,
    ): IntrinsicsRecord {
        // 桌面标定文件优先：它是真实测出来的，比"按传感器尺寸估算"可靠一个量级
        if (calibration != null && calibration.fx > 1.0 && calibration.width > 0) {
            val sx = width.toDouble() / calibration.width
            val sy = height.toDouble() / calibration.height
            return IntrinsicsRecord(
                width = width,
                height = height,
                fx = calibration.fx * sx,
                fy = calibration.fy * sy,
                cx = calibration.cx * sx,
                cy = calibration.cy * sy,
                k1 = calibration.k1,
                k2 = calibration.k2,
                p1 = calibration.p1,
                p2 = calibration.p2,
                k3 = calibration.k3,
                projection = "pinhole",
                source = "calibration",
            )
        }
        val calib = caps.intrinsicCalibration
        if (calib.size >= 4 && calib[0] > 1.0 && calib[1] > 1.0) {
            // LENS_INTRINSIC_CALIBRATION 以 active array 像素为基准；此处按 active array 尺寸缩放
            val baseW = if (caps.activeArrayW > 0) caps.activeArrayW else width
            val baseH = if (caps.activeArrayH > 0) caps.activeArrayH else height
            val sx = width.toDouble() / baseW
            val sy = height.toDouble() / baseH
            val dist = caps.distortion
            // Android CameraCharacteristics.LENS_DISTORTION 的顺序是
            //   [k1, k2, k3, p1, p2]（三个径向 + 两个切向）
            // 旧代码按 [k1,k2,p1,p2,k3] 读，把 k3 当 p1、p2 当 k3（2026-11-24 文档核对发现）。
            return IntrinsicsRecord(
                width = width,
                height = height,
                fx = calib[0] * sx,
                fy = calib[1] * sy,
                cx = calib[2] * sx,
                cy = calib[3] * sy,
                k1 = dist.getOrElse(0) { 0.0 },
                k2 = dist.getOrElse(1) { 0.0 },
                k3 = dist.getOrElse(2) { 0.0 },
                p1 = dist.getOrElse(3) { 0.0 },
                p2 = dist.getOrElse(4) { 0.0 },
                projection = "pinhole",
                source = "calibration",
            )
        }
        val focal = caps.focalLengthsMm.firstOrNull() ?: 0.0
        if (focal > 0.0 && caps.sensorPhysicalWmm > 0.0 && caps.pixelArrayW > 0) {
            val pixelSizeMm = caps.sensorPhysicalWmm / caps.pixelArrayW
            val fPx = focal / pixelSizeMm
            // 若输出尺寸小于 pixel array（裁切），按比例换算
            val sx = width.toDouble() / caps.pixelArrayW
            val sy = height.toDouble() / caps.pixelArrayH.coerceAtLeast(1)
            return IntrinsicsRecord(
                width = width,
                height = height,
                fx = fPx * sx,
                fy = fPx * sy,
                cx = width / 2.0,
                cy = height / 2.0,
                projection = "pinhole",
                source = "estimated",
            )
        }
        return IntrinsicsRecord(
            width = width,
            height = height,
            fx = width * 1.2,
            fy = width * 1.2,
            cx = width / 2.0,
            cy = height / 2.0,
            projection = "pinhole",
            source = "assumed",
        )
    }
}









