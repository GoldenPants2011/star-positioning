// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import android.content.Context
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import java.io.File
import kotlin.math.sqrt

/**
 * 相机 ↔ IMU 外参修正（需求 FR-03）。
 *
 * 传感器朝向（0/90/180/270）只解决整数部分；剩下的安装/模块小倾斜必须逐台标定。
 * 本机（Redmi 24122RKC7C）实测：不修正时定位误差约 293 km，用本文件给出的
 * 小角度旋转向量 (rx, ry, rz) 修正后，三组真值数据误差 1.8–20.2 km。
 *
 * 配置文件：`filesDir/extrinsic.json`，`{"rx":…, "ry":…, "rz":…, "note":…}`
 * （单位弧度，作用在"相机坐标系里的重力/天顶方向"上，小角度近似 R ≈ I + [r]×）。
 *
 * **没有配置文件时不做任何修正**，并在 UI 标出"未标定"——错误的确定性比错误的数值更危险。
 */
class ExtrinsicConfig(
    val rx: Double,
    val ry: Double,
    val rz: Double,
    val note: String = "",
    val source: String = "file",
    /**
     * 可选的完整 3×3 线性映射（行优先 9 个数）：up_corrected ∝ M · up_est。
     * 比小旋转多吸收"加速度计轴间串扰/刻度误差"。2026-11-20 实测：残差 0.396° → 0.198°。
     */
    val m: DoubleArray? = null,
) {
    /** 修正旋转总量（度），用于界面显示。 */
    val angleDeg: Double get() = if (m != null) {
        Math.toDegrees(kotlin.math.acos(((m[0] + m[4] + m[8]) / 3.0).coerceIn(-1.0, 1.0)))
    } else Math.toDegrees(sqrt(rx * rx + ry * ry + rz * rz))

    /** 对"相机坐标系里的方向向量"施加修正（单位化）。 */
    fun applyTo(up: DoubleArray): DoubleArray {
        val mm = m
        if (mm != null) {
            val mat = arrayOf(
                doubleArrayOf(mm[0], mm[1], mm[2]),
                doubleArrayOf(mm[3], mm[4], mm[5]),
                doubleArrayOf(mm[6], mm[7], mm[8]),
            )
            return V.unit(apply(mat, up))
        }
        val mat = arrayOf(
            doubleArrayOf(1.0, -rz, ry),
            doubleArrayOf(rz, 1.0, -rx),
            doubleArrayOf(-ry, rx, 1.0),
        )
        return V.unit(apply(mat, up))
    }

    fun toJson(): String {
        val mPart = m?.let { mm -> ",\"m\":[" + mm.joinToString(",") + "]" } ?: ""
        return """{"rx":$rx,"ry":$ry,"rz":$rz,"note":"$note","source":"$source"$mPart}"""
    }

    companion object {
        const val FILE = "extrinsic.json"

        /**
         * 查找顺序（不再从 APK assets 读取任何调试机参数）：
         *  1. 内部私有目录（App 自标定写入，`/data/data/<pkg>/files/extrinsic.json`）
         *  2. 外部私有目录（adb push 方便，`/sdcard/Android/data/<pkg>/files/extrinsic.json`）
         * 首次安装由 DeviceParams 写入零值文件；升级不覆盖已有文件。
         */
        fun load(context: Context): ExtrinsicConfig? {
            load(File(context.filesDir, FILE))?.let { return it }
            context.getExternalFilesDir(null)?.let { dir ->
                load(File(dir, FILE))?.let { return it }
            }
            return null
        }

        /** 合理的修正量上限：超过 15° 说明标定数据有问题，直接忽略（沿用下一来源）。 */
        private const val MAX_PLAUSIBLE_DEG = 15.0

        fun load(file: File): ExtrinsicConfig? {
            if (!file.isFile) return null
            return runCatching {
                val m = JsonText.parse(file.readText()).asMap()
                // 可选完整矩阵 m（9 个数）：比小角度更准，能吸收刻度/轴间串扰
                val mMatrix = (m["m"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }
                    ?.takeIf { it.size == 9 }?.toDoubleArray()
                val cfg = ExtrinsicConfig(
                    rx = m.num("rx"), ry = m.num("ry"), rz = m.num("rz"),
                    note = m.str("note"), source = m.str("source", "file"),
                    m = mMatrix,
                )
                // 早期自动标定曾写出 137°/161° 的垃圾值，载入时必须拦住
                if (cfg.angleDeg > MAX_PLAUSIBLE_DEG) null else cfg
            }.getOrNull()
        }
    }
}


