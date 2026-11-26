package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 太阳采集包的离线分析验证：圆面拟合 → 高度角 → 残差 / 联合定位。
 */
class SunAnalyzerTest {

    @Test
    fun `太阳包分析`() {
        val dir = File("../field-inbox")
        val zips = dir.listFiles { f ->
            (f.name.startsWith("session-20261121-15") || f.name.startsWith("session-20261121-16")) &&
                f.name.endsWith(".zip")
        }?.sortedBy { it.name } ?: emptyList()
        if (zips.isEmpty()) {
            println("没有太阳采集包，跳过")
            return
        }
        var bestFrames = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val analysis = FieldAnalyzer.analyze(reader, Fixtures.catalog, null)
                val sunLines = analysis.findings.filter {
                    it.startsWith("太阳") || it.contains("太阳 峰值") || it.contains("太阳：")
                }
                println("=".repeat(60))
                println(zip.name)
                println("  truth=${analysis.truth?.lat}, ${analysis.truth?.lon}")
                sunLines.forEach { println("  " + it) }
                val n = sunLines.count { it.startsWith("帧 ") }
                if (zip.name.contains("152754")) {
                    bestFrames = n
                    assertTrue("152754 应至少识别 1 帧太阳，实际 $n", n >= 1)
                }
                if (zip.name.contains("164356") || zip.name.contains("164319")) {
                    assertTrue("16xx 会话应识别到太阳帧，实际 $n", n >= 1)
                }
                if (zip.name.contains("152754") || zip.name.contains("152731")) {
                    val resid = Regex("残差 ([+-]?[0-9.]+)°").findAll(sunLines.joinToString(" ")).mapNotNull {
                        it.groupValues[1].toDoubleOrNull()
                    }.toList()
                    println("  残差样本: $resid")
                    if (resid.isNotEmpty()) {
                        assertTrue("残差应在 ±5° 内，实际 $resid", resid.all { Math.abs(it) < 5.0 })
                    }
                }
            }
        }
        assertTrue("152754 应有太阳帧", bestFrames >= 1)
    }
}
