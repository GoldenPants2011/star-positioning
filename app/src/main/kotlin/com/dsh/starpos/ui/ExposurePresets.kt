// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 曝光/ISO 预设：现场按任务一键套用；支持导入/导出 JSON。 */
data class ExposurePreset(
    val name: String,
    val exposureNs: Long,
    val iso: Int,
    /** 预览模式：1 找星 / 3 月亮 / 4 太阳 / 5 白天月亮。 */
    val mode: Int,
    /** 相机：null=保持当前；"0" 主摄 / "3" 超广角 / "4" 长焦。 */
    val cameraId: String? = null,
) {
    val exposureText: String
        get() = when {
            exposureNs >= 1_000_000_000L -> "%.1fs".format(exposureNs / 1_000_000_000.0)
            exposureNs >= 1_000_000L -> "%.0fms".format(exposureNs / 1_000_000.0)
            else -> "%.0fµs".format(exposureNs / 1_000.0)
        }
    val cameraText: String
        get() = when (cameraId) {
            "0" -> "主摄"
            "3" -> "超广角"
            "4" -> "长焦"
            else -> "保持镜头"
        }
    val modeText: String
        get() = when (mode) {
            3 -> "月亮"
            4 -> "太阳"
            5 -> "白天月亮"
            else -> "找星"
        }
}

object ExposurePresets {
    private const val PREF_KEY = "exposure_presets_json"

    val DEFAULTS = listOf(
        ExposurePreset("夜景星空 1s/3200", 1_000_000_000L, 3200, 1),
        ExposurePreset("月亮 2ms/800", 2_000_000L, 800, 3),
        ExposurePreset("太阳 2ms/400", 2_000_000L, 400, 4),
        ExposurePreset("白天月亮 1ms/400", 1_000_000L, 400, 5),
        ExposurePreset("水镜 500ms/400", 500_000_000L, 400, 1),
    )

    fun load(context: Context): List<ExposurePreset> {
        val raw = context.getSharedPreferences("starpos", Context.MODE_PRIVATE)
            .getString(PREF_KEY, null) ?: return DEFAULTS
        return fromJson(raw) ?: DEFAULTS
    }

    fun save(context: Context, presets: List<ExposurePreset>) {
        context.getSharedPreferences("starpos", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, toJson(presets)).apply()
    }

    fun toJson(presets: List<ExposurePreset>): String {
        val arr = JSONArray()
        for (p in presets) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("exposure_ns", p.exposureNs)
                    put("iso", p.iso)
                    put("mode", p.mode)
                    if (p.cameraId != null) put("camera", p.cameraId)
                },
            )
        }
        return arr.toString(2)
    }

    fun fromJson(text: String): List<ExposurePreset>? = runCatching {
        val arr = JSONArray(text)
        val out = ArrayList<ExposurePreset>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").ifBlank { "预设 ${i + 1}" }
            val exp = o.optLong("exposure_ns", 1_000_000_000L)
            val iso = o.optInt("iso", 3200)
            val mode = o.optInt("mode", 1)
            val cam = o.optString("camera", "").ifBlank { null }
            out.add(ExposurePreset(name, exp, iso, mode, cam))
        }
        if (out.isEmpty()) null else out
    }.getOrNull()
}
