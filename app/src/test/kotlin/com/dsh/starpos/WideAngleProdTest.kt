package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/** 广角会话在生产路径（Analyzer 自适应参数）下的星解情况。 */
class WideAngleProdTest {
    @Test
    fun `广角生产路径星解`() {
        val root = File(".").absoluteFile.let { d ->
            var x = d
            repeat(4) {
                if (File(x, "settings.gradle.kts").isFile) return@let x
                x = x.parentFile ?: x
            }
            x
        }
        val zip = File(root, "field-inbox/session-20261124-035956.zip")
        if (!zip.isFile) { println("找不到会话"); return }
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        BundleReader.open(zip).use { reader ->
            val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
            println("帧数 ${a.frames.size}")
            for (f in a.frames) {
                println("帧${f.index}: 检测 ${f.detections} 匹配 ${f.matched} solved=${f.fix != null} rms=${"%.0f".format(f.rmsArcsec)}")
            }
            a.findings.filter { it.contains("星") || it.contains("匹配") || it.contains("位置") || it.contains("帧 ") }
                .take(15).forEach { println("  $it") }
        }
    }
}
