// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.StarVertical
import java.io.File
import kotlin.math.sqrt
import org.junit.Test

/**
 * 用 Analyzer 自身管线挑选"仅星光垂直修正"候选值：
 * 对每个候选 dt 跑同一批星光会话，比较逐帧真值误差（中位/平均）。
 * 输出表供选择默认值，不改配置。
 */
class StarVerticalGridTest {

    private fun root(): File {
        var d = File(".").absoluteFile
        repeat(4) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile ?: return d
        }
        return File(".").absoluteFile
    }

    @Test
    fun `候选 dt 网格`() {
        val root = root()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: return
        val bias = doubleArrayOf(26.3e-3 * 9.80665, -20.0e-3 * 9.80665, 1.4e-3 * 9.80665)
        val sessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261122-000444.zip",
        )
        // 候选（度）：当前拟合、上一版、零、以及缩放
        val candidates = linkedMapOf(
            "当前(precession重拟合)" to doubleArrayOf(2.974, -0.034, -4.835),
            "上一版(无precession)" to doubleArrayOf(2.718, -0.054, -4.998),
            "×0.5" to doubleArrayOf(1.487, -0.017, -2.418),
            "×0.75" to doubleArrayOf(2.231, -0.026, -3.626),
            "×1.25" to doubleArrayOf(3.718, -0.043, -6.044),
            "×1.5" to doubleArrayOf(4.461, -0.051, -7.253),
            "零(不修正)" to doubleArrayOf(0.0, 0.0, 0.0),
        )
        for ((name, deg) in candidates) {
            val sv = StarVertical(
                doubleArrayOf(Math.toRadians(deg[0]), Math.toRadians(deg[1]), Math.toRadians(deg[2])),
            )
            val errs = ArrayList<Double>()
            val perSession = StringBuilder()
            for (s in sessions) {
                val zip = File(inbox, s)
                if (!zip.isFile) continue
                val a = BundleReader.open(zip).use {
                    FieldAnalyzer.analyze(
                        it, Fixtures.catalog, extrinsic,
                        starVertical = if (deg[0] == 0.0 && deg[1] == 0.0 && deg[2] == 0.0) null else sv,
                        accelBiasOverride = bias,
                    )
                }
                val e = a.frames.mapNotNull { it.truthErrorKm }.filter { it.isFinite() }
                if (e.isEmpty()) { perSession.append("%s:— "); continue }
                errs.addAll(e)
                perSession.append("%s:%.0f ".format(s.substring(14, 20), e.sorted()[e.size / 2]))
            }
            if (errs.isEmpty()) continue
            val srt = errs.sorted()
            println("%-22s 平均 %6.1f km  中位 %6.1f km  | %s".format(
                name, errs.average(), srt[srt.size / 2], perSession,
            ))
        }
    }
}
