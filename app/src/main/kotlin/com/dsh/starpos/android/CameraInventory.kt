// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** 罗列本机所有相机/物理子相机的关键参数（自检页展示用）。 */
object CameraInventory {

    fun describe(context: Context): List<String> {
        val out = ArrayList<String>()
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return listOf("CameraManager 不可用")
        val ids = runCatching { cm.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "?"
            }
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                0 -> "LIMITED"; 1 -> "FULL"; 2 -> "LEGACY"; 3 -> "LEVEL_3"; else -> "?"
            }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(",") { "%.2f".format(it) } ?: "-"
            val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sizeStr = if (physSize != null) "%.2fx%.2fmm".format(physSize.width, physSize.height) else "-"
            val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val expStr = if (exp != null) "%.1fµs–%.0fms".format(exp.lower / 1000.0, exp.upper / 1e6) else "-"
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val isoStr = if (iso != null) "%d–%d".format(iso.lower, iso.upper) else "-"
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val pixel = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val fovH = if (focal != "-" && physSize != null) {
                runCatching {
                    val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
                    "%.0f°".format(2 * Math.toDegrees(Math.atan(physSize.width.toDouble() / (2 * f))))
                }.getOrDefault("?")
            } else "?"
            out.add(
                "camera $id: $facing $level | 焦距 ${focal}mm 传感器 ${sizeStr} 水平视场≈$fovH | 曝光 $expStr ISO $isoStr | RAW=${if (raw) "是" else "否"}" +
                    (if (logical) " | 逻辑多摄" else "") +
                    (if (pixel != null) " | 像素 ${pixel.width}x${pixel.height}" else ""),
            )
        }
        // 物理子相机（有些 OEM 允许直接查询 characteristics）
        for (pid in listOf("2", "3", "4")) {
            val c = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString(",") { "%.2f".format(it) } ?: "-"
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sizeStr = if (size != null) "%.2fx%.2fmm".format(size.width, size.height) else "-"
            val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val expStr = if (exp != null) "%.1fµs–%.0fms".format(exp.lower / 1000.0, exp.upper / 1e6) else "-"
            val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val isoStr = if (iso != null) "%d–%d".format(iso.lower, iso.upper) else "-"
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            out.add("physical $pid: 焦距 ${focal}mm 传感器 ${sizeStr} | 曝光 $expStr ISO $isoStr | RAW=${if (raw) "是" else "否"} | 像素 ${px?.width}x${px?.height}")
        }
        return out
    }
}
