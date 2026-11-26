package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/**
 * 现场数据入口：把手机导出的 zip 放进 `star-positioning/field-inbox/`，
 * 跑这个测试就会逐包分析，并把报告写到 `field-inbox/reports/`。
 *
 * 没有数据时静默通过（现场采集前也能正常跑全量单测）。
 */
class FieldInboxReportTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `分析 inbox 里的所有采集包`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        if (!inbox.isDirectory) {
            println("field-inbox 不存在，跳过（现场数据放进去后再跑）")
            return
        }
        val zips = inbox.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (zips.isEmpty()) {
            println("field-inbox 里没有 .zip，跳过")
            return
        }
        // 若有逐台外参配置（config/extrinsic.json），离线分析也应用它
        val extrinsic = com.dsh.starpos.field.ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        if (extrinsic != null) println("外参配置已载入：%.2f°（%s）".format(extrinsic.angleDeg, extrinsic.note.take(40)))
        val reports = File(inbox, "reports").apply { mkdirs() }
        println("发现 ${zips.size} 个采集包：${zips.joinToString { it.name }}")
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val analysis = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
                val base = zip.nameWithoutExtension
                FieldAnalyzer.writeMarkdown(analysis, File(reports, "$base.md"))
                FieldAnalyzer.writeJson(analysis, File(reports, "$base.json"))
                println("=== $base ===")
                println("  相机: ${analysis.cameraLine}")
                println("  内参来源: ${analysis.intrinsics.source}  IMU=${analysis.imuRows}  GNSS=${analysis.gnssRows}")
                analysis.truth?.let { println("  真值: %.6f, %.6f".format(it.lat, it.lon)) }
                analysis.frames.forEach { f ->
                    println(
                        "  帧${f.index}: det=${f.detections} matched=${f.matched} " +
                            "rms=${"%.1f".format(f.rmsArcsec)}\" 3σ=${"%.3f".format(3 * f.attitudeSigmaDeg)}° " +
                            "fix=${f.fix?.let { "%.5f,%.5f".format(it.latDeg, it.lonDeg) } ?: "—"} " +
                            "err=${f.truthErrorKm?.let { "%.1f km".format(it) } ?: "—"} reason=${f.reason ?: "OK"}",
                    )
                }
                analysis.findings.forEach { println("  · $it") }
            }
        }
    }
}

