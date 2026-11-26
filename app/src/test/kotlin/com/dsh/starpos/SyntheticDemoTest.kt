package com.dsh.starpos

import com.dsh.starpos.astro.SyntheticScene
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.SyntheticSession
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合成样例必须稳定可解算。
 *
 * 2026-11-19 的现场包 `synthetic-20261119-004933.zip` 解不出来：
 * 样例用 `System.currentTimeMillis()` 生成，随一天中的时间变化，有时指向星稀天区。
 * 现在固定到 [SyntheticSession.DEMO_UTC_MS]（天鹅座/银河在天顶附近），并用本测试守住。
 */
class SyntheticDemoTest {

    @Test
    fun `合成样例在固定时刻必须能解算`() {
        val dir = Files.createTempDirectory("demo").toFile()
        val scene = SyntheticScene(
            width = 1080,
            height = 1440,
            fovXDeg = 30.0,
            latDeg = 31.2304,
            lonDeg = 121.4737,
            utcMillis = SyntheticSession.DEMO_UTC_MS,
            cloudCover = 0.0,
            seed = 7L,
        )
        val zip = SyntheticSession.build(dir, Fixtures.catalog, scene, frameCount = 2)
        BundleReader.open(zip).use { reader ->
            val analysis = FieldAnalyzer.analyze(reader, Fixtures.catalog)
            analysis.frames.forEach { f ->
                println("demo 帧${f.index}: det=${f.detections} matched=${f.matched} rms=${"%.1f".format(f.rmsArcsec)}\" fix=${f.fix != null}")
                assertTrue("合成样例解不出来（matched=${f.matched}, reason=${f.reason}）", f.fix != null)
                assertTrue("匹配星数过少：${f.matched}", f.matched >= 10)
            }
        }
    }
}
