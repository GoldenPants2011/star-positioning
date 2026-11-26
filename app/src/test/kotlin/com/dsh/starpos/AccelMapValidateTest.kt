// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/** 检验星空自标定的加速度计映射 S 对太阳/月亮高度残差的影响（留出数据）。 */
class AccelMapValidateTest {

    // JointCalibTest 拟合结果（63 帧星空，会话级交叉验证 1.18°→0.49°）
    private val sMap = doubleArrayOf(
        1.0166, 0.0748, 0.0072,
        -0.0776, 0.9888, -0.0472,
        0.0069, 0.0423, 1.0018,
    )
    private val bias = doubleArrayOf(25.3, -19.6, 1.2).map { it / 1000.0 * 9.80665 }.toDoubleArray()

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `加速度计映射留出验证`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val holdout = File(root, "field-inbox").listFiles { f ->
            f.name.endsWith(".zip") && (
                f.name.startsWith("session-20261121-15") || f.name.startsWith("session-20261121-16") ||
                    f.name.startsWith("session-20261121-190")
                )
        }?.sortedBy { it.name } ?: return

        fun meanResidual(useMap: Boolean): Double {
            FieldAnalyzer.accelBias = bias
            FieldAnalyzer.accelMap = if (useMap) sMap else null
            var sum = 0.0; var n = 0
            for (zip in holdout) {
                BundleReader.open(zip).use { reader ->
                    val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
                    for (f in a.findings) {
                        Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let {
                            sum += Math.abs(it.groupValues[1].toDouble()); n++
                        }
                    }
                }
            }
            return if (n > 0) sum / n else Double.NaN
        }

        val without = meanResidual(false)
        val with = meanResidual(true)
        println("留出集（8 个太阳/月亮包）：|高度残差| 均值  无 S %.3f° → 有 S %.3f°".format(without, with))

        // 俯仰扫描会话：太阳/月亮不在其中，改用月亮 19:00 环境；再检查星空天顶残差（用 JointCalibTest 的路径太慢，这里只报留出）
        FieldAnalyzer.accelBias = null
        FieldAnalyzer.accelMap = null
    }
}
