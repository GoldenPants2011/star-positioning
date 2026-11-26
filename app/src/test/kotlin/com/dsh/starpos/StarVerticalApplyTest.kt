// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.StarVertical
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅星光分支垂直修正的落地验证：
 *  - 星光会话：Analyzer 加/不加 StarVertical，比较逐帧真值误差（应明显改善）；
 *  - 太阳会话：加/不加 StarVertical，findings 应完全一致（证明只作用于星光分支）。
 */
class StarVerticalApplyTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sorted()
        return s[s.size / 2]
    }

    @Test
    fun `仅星光垂直修正落地`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: run {
            println("无外参"); return
        }
        val starVertical = StarVertical.load(File(root, "config/star-vertical.json"))
        assertNotNull("需要 config/star-vertical.json", starVertical)
        // 老会话没记录零偏：用六位置标定值兜底（m/s²）
        val bias = doubleArrayOf(26.3e-3 * 9.80665, -20.0e-3 * 9.80665, 1.4e-3 * 9.80665)

        val starSessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261122-000444.zip",
        )
        var sumBase = 0.0
        var sumFixed = 0.0
        var nBase = 0
        var nFixed = 0
        for (name in starSessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            // 基线：显式关闭星光垂直修正（precession 修正已经是全局行为）
            val base = BundleReader.open(zip).use {
                FieldAnalyzer.analyze(
                    it, Fixtures.catalog, extrinsic,
                    starVertical = null, accelBiasOverride = bias,
                )
            }
            val fixed = BundleReader.open(zip).use {
                FieldAnalyzer.analyze(
                    it, Fixtures.catalog, extrinsic,
                    starVertical = starVertical, accelBiasOverride = bias,
                )
            }
            val eb = base.frames.mapNotNull { it.truthErrorKm }.filter { it.isFinite() }
            val ef = fixed.frames.mapNotNull { it.truthErrorKm }.filter { it.isFinite() }
            println("%-32s 修正前 中位 %6.1f km（%d 帧）→ 修正后 中位 %6.1f km（%d 帧）".format(
                name.removeSuffix(".zip"), median(eb), eb.size, median(ef), ef.size,
            ))
            for (v in eb) { sumBase += v; nBase++ }
            for (v in ef) { sumFixed += v; nFixed++ }
        }
        assertTrue("应有星光帧", nBase > 0 && nFixed > 0)
        println("合计：修正前平均 %.1f km → 修正后平均 %.1f km".format(sumBase / nBase, sumFixed / nFixed))
        // Analyzer 自身管线（含它自己的星点检测/回退）上：85.4 → 59.9 km（约 30% 改善）。
        // 003438/202453 两组的可用帧质量差、误差不随倾角修正下降，故不要求每组都变好。
        assertTrue("仅星光修正应改善平均误差", sumFixed / nFixed < sumBase / nBase * 0.8)

        // 太阳会话：starVertical 不应改变任何 findings（只作用星光分支）
        for (name in listOf("session-20261124-150126.zip", "session-20261124-153438.zip")) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            // 基线：显式关闭星光垂直修正（precession 修正已经是全局行为）
            val base = BundleReader.open(zip).use {
                FieldAnalyzer.analyze(
                    it, Fixtures.catalog, extrinsic,
                    starVertical = null, accelBiasOverride = bias,
                )
            }
            val fixed = BundleReader.open(zip).use {
                FieldAnalyzer.analyze(
                    it, Fixtures.catalog, extrinsic,
                    starVertical = starVertical, accelBiasOverride = bias,
                )
            }
            val baseFind = base.findings.joinToString("\n")
            val fixedFind = fixed.findings.joinToString("\n")
            println("太阳会话 $name: findings 完全一致=${baseFind == fixedFind}")
            assertEquals("仅星光修正不应影响太阳分支", baseFind, fixedFind)
        }
    }
}
