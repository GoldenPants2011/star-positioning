// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.core

import com.dsh.starpos.astro.ErrorEllipse
import com.dsh.starpos.astro.GeoFix
import java.util.Locale

/** 降级链模式（需求 FR-10）。顺序即优先级：星图 → 月/日 → 偏振 → PDR → GNSS/地标。 */
enum class SolveMode(val cn: String) {
    AUTO("自动"),
    STAR("星图"),
    MOON_SUN("月/日"),
    POLARIZATION("天空偏振"),
    PDR("仅 PDR"),
    GNSS("GNSS"),
    LANDMARK("地标"),
}

class ModeAvailability(val mode: SolveMode, val available: Boolean, val reason: String)

class ModeSwitch(val from: SolveMode, val to: SolveMode, val reason: String, val atMs: Long)

/**
 * 模式管理：切换不得静默发生（FR-10）。
 * M1 只实现 STAR；其余模式显式标记为未实现，并给出原因与所需条件。
 */
class ModeManager(initial: SolveMode = SolveMode.AUTO) {
    var current: SolveMode = initial
        private set
    val log = ArrayList<ModeSwitch>()

    /** 落到 GNSS/地标之前必须提示"已无可用外部信源"。 */
    var externalSourcesExhausted: Boolean = false
        private set

    fun availability(): List<ModeAvailability> = listOf(
        ModeAvailability(SolveMode.STAR, true, "可用：需要 ≥8 颗检测星与静止姿态"),
        ModeAvailability(SolveMode.MOON_SUN, false, "未实现（M3）：需要日月星历 + 重力 + 时间"),
        ModeAvailability(SolveMode.POLARIZATION, false, "未实现（M4）：需要偏振传感器/偏振滤镜"),
        ModeAvailability(SolveMode.PDR, false, "未实现（M3）：需要步数/航向，只能短时递推"),
        ModeAvailability(SolveMode.GNSS, false, "未启用：本期 GNSS 仅作真值校验，不参与融合"),
        ModeAvailability(SolveMode.LANDMARK, false, "未实现（M4）：需要离线 OSM 矢量数据"),
    )

    fun request(target: SolveMode, reason: String, nowMs: Long) {
        if (target == current) return
        val from = current
        current = target
        log.add(ModeSwitch(from, target, reason, nowMs))
        if (target == SolveMode.GNSS || target == SolveMode.LANDMARK) {
            externalSourcesExhausted = true
        }
    }

}

/** 解算结果（需求 §5 输出格式）。 */
class SolveResult(
    val status: String,                 // FIX / NO_FIX
    val tier: String?,                  // T0 / T1 / T2
    val mode: SolveMode,
    val timestampUtcMs: Long,
    val timeUncertaintyMs: Double,
    val lastSyncUtcMs: Long?,
    val offlineHours: Double,
    val ephemerisAgeDays: Double,
    val position: GeoFix?,
    val ellipse: ErrorEllipse?,
    val attitude: Array<DoubleArray>?,
    val attitudeSigmaDeg: Double,
    val gravitySigmaDeg: Double,
    val starsUsed: Int,
    val cloudCover: Double,
    val confidence: Double,
    val warnings: List<String>,
    val reason: String? = null,
) {
    fun isFix() = status == "FIX"

    fun toJson(): String {
        val sb = StringBuilder(1024)
        sb.append("{\n")
        sb.append("  \"status\": \"").append(status).append("\",\n")
        sb.append("  \"tier\": ").append(tier?.let { "\"$it\"" } ?: "null").append(",\n")
        sb.append("  \"mode\": \"").append(mode.name.lowercase(Locale.US)).append("\",\n")
        sb.append("  \"timestamp_utc\": \"").append(isoUtc(timestampUtcMs)).append("\",\n")
        sb.append("  \"time_uncertainty_ms\": ").append(num(timeUncertaintyMs)).append(",\n")
        sb.append("  \"last_sync_utc\": ").append(lastSyncUtcMs?.let { "\"${isoUtc(it)}\"" } ?: "null").append(",\n")
        sb.append("  \"offline_hours\": ").append(if (offlineHours.isInfinite()) "null" else num(offlineHours)).append(",\n")
        sb.append("  \"ephemeris_age_days\": ").append(num(ephemerisAgeDays)).append(",\n")
        sb.append("  \"position\": ")
        if (position == null) sb.append("null") else {
            sb.append("{\"lat\": ").append(num(position.latDeg, 6))
                .append(", \"lon\": ").append(num(position.lonDeg, 6))
                .append(", \"alt_m\": null}")
        }
        sb.append(",\n")
        sb.append("  \"position_ellipse_95\": ")
        if (ellipse == null) sb.append("null") else {
            sb.append("{\"semi_major_m\": ").append(num(ellipse.semiMajorM, 0))
                .append(", \"semi_minor_m\": ").append(num(ellipse.semiMinorM, 0))
                .append(", \"azimuth_deg\": ").append(num(ellipse.azimuthDeg, 1)).append("}")
        }
        sb.append(",\n")
        sb.append("  \"attitude_icrs\": ")
        if (attitude == null) sb.append("null") else {
            sb.append("[")
            for (i in 0..2) {
                if (i > 0) sb.append(", ")
                sb.append("[")
                for (j in 0..2) {
                    if (j > 0) sb.append(", ")
                    sb.append(num(attitude!![i][j], 9))
                }
                sb.append("]")
            }
            sb.append("]")
        }
        sb.append(",\n")
        sb.append("  \"attitude_sigma_deg\": ").append(num(attitudeSigmaDeg, 4)).append(",\n")
        sb.append("  \"gravity_sigma_deg\": ").append(num(gravitySigmaDeg, 3)).append(",\n")
        sb.append("  \"stars_used\": ").append(starsUsed).append(",\n")
        sb.append("  \"cloud_cover\": ").append(num(cloudCover, 2)).append(",\n")
        sb.append("  \"confidence\": ").append(num(confidence, 2)).append(",\n")
        sb.append("  \"warnings\": [").append(warnings.joinToString(", ") { "\"$it\"" }).append("],\n")
        sb.append("  \"reason\": ").append(reason?.let { "\"$it\"" } ?: "null").append(",\n")
        sb.append("  \"model_versions\": \"").append(MODEL_VERSIONS).append("\"\n")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        const val MODEL_VERSIONS =
            "sky=IAU2006-P03+IAU2000A(trunc20)+Bennett1982;catalog=HYG-v41(mag<=7);solver=trihash+ransac"

        fun num(v: Double, digits: Int = 3): String {
            if (v.isNaN() || v.isInfinite()) return "null"
            return String.format(Locale.US, "%.${digits}f", v)
        }

        fun isoUtc(ms: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(ms))
        }
    }
}
