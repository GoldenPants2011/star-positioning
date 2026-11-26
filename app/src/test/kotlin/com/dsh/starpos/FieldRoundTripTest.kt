package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.JsonText
import com.dsh.starpos.field.SessionManifest
import com.dsh.starpos.field.SyntheticSession
import com.dsh.starpos.core.DemoRunner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证「采集 → 打包 → 离线分析」整条链路（用合成采集包，无需真机）。 */
class FieldRoundTripTest {

    private fun tempDir(): File = File.createTempFile("starpos-bundle-", "").apply {
        delete(); mkdirs()
    }

    @Test
    fun `合成采集包可以被离线分析并达到 T1`() {
        val scene = DemoRunner.defaultScenes()[0]
        val dir = File(tempDir(), "session")
        dir.mkdirs()
        val zip = SyntheticSession.build(dir, Fixtures.catalog, scene, frameCount = 2, gravityNoiseDeg = 0.1)
        assertTrue("应生成 zip", zip.isFile && zip.length() > 0)

        BundleReader.open(zip).use { reader ->
            val manifest = reader.manifest
            assertEquals("schema 应为 1", 1, manifest.schema)
            assertEquals("应有 2 帧", 2, reader.frameMetas().size)
            assertTrue("应有 IMU 数据", reader.readImu().size > 100)
            assertTrue("应有 GNSS 真值", reader.readGnss().isNotEmpty())
            assertEquals("内参来源", "synthetic-truth", manifest.intrinsics?.source)

            val analysis = FieldAnalyzer.analyze(reader, Fixtures.catalog, starVertical = null, applyPrecession = false)
            println("frames=${analysis.frames.size}")
            analysis.frames.forEach {
                println(
                    "  frame ${it.index}: det=${it.detections} matched=${it.matched} rms=${"%.1f".format(it.rmsArcsec)}\" " +
                        "att3σ=${"%.4f".format(3 * it.attitudeSigmaDeg)}° gravσ=${"%.3f".format(it.gravitySigmaDeg)}° " +
                        "static=${it.gravityStatic} fix=${it.fix} truthErr=${it.truthErrorKm} reason=${it.reason}",
                )
            }
            analysis.findings.forEach { println("  finding: $it") }

            assertTrue("应至少解析出一帧", analysis.frames.isNotEmpty())
            val ok = analysis.frames.filter { it.fix != null }
            assertTrue("至少一帧应给出位置", ok.isNotEmpty())
            ok.forEach { f ->
                assertTrue("姿态 3σ 应 ≤0.02°，实际 ${3 * f.attitudeSigmaDeg}", 3 * f.attitudeSigmaDeg <= 0.02)
                assertTrue("真值偏差应 ≤50 km，实际 ${f.truthErrorKm}", (f.truthErrorKm ?: 999.0) <= 50.0)
                assertTrue("应给出 95% 椭圆", f.ellipse != null)
            }

            // 报告落盘
            val outDir = File(tempDir(), "reports")
            FieldAnalyzer.writeMarkdown(analysis, File(outDir, "session.md"))
            FieldAnalyzer.writeJson(analysis, File(outDir, "session.json"))
            val md = File(outDir, "session.md")
            assertTrue("报告应生成", md.isFile && md.length() > 200)
            assertTrue("报告应含逐帧表", md.readText().contains("逐帧结果"))
            assertTrue("JSON 报告可被重新解析", JsonText.parse(File(outDir, "session.json").readText()) is Map<*, *>)
        }
    }

    @Test
    fun `清单 JSON 往返一致`() {
        val scene = DemoRunner.defaultScenes()[0]
        val dir = File(tempDir(), "session")
        dir.mkdirs()
        val zip = SyntheticSession.build(dir, Fixtures.catalog, scene, frameCount = 1)
        BundleReader.open(zip).use { reader ->
            val m = reader.manifest
            val reparsed = SessionManifest.fromJson(m.toJson())
            assertEquals(m.sessionId, reparsed.sessionId)
            assertEquals(m.frames.size, reparsed.frames.size)
            assertEquals(m.intrinsics?.fx ?: 0.0, reparsed.intrinsics?.fx ?: -1.0, 1e-9)
            assertEquals("back", reparsed.camera?.lensFacing)
            assertEquals(m.gnssTruth?.lat ?: 0.0, reparsed.gnssTruth?.lat ?: -1.0, 1e-9)
        }
    }

    @Test
    fun `坏包不会崩溃而是给出明确错误`() {
        val bad = File(tempDir(), "bad.zip")
        bad.writeText("这不是一个 zip")
        val threw = runCatching { BundleReader.open(bad).use { it.manifest } }.isFailure
        assertTrue("非法 zip 应抛出可解释异常", threw)
    }
}
