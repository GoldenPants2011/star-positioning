package com.dsh.starpos

import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test

/** 对照：App 内部 GAST 与外部标准 GMST 是否一致（差值会被误当成经度误差）。 */
class GastDebugTest {
    @Test
    fun `GAST 对照`() {
        val zip = File("../field-inbox/session-20261119-011848.zip")
        if (!zip.isFile) return
        BundleReader.open(zip).use { reader ->
            val meta = reader.frameMetas().first()
            val ctx = SkyContext.at(meta.wallClockUtcMs)
            val gastH = ctx.gast * 12.0 / Math.PI
            println("frame utcMs = ${meta.wallClockUtcMs}")
            println("App GAST  = %.6f h  (%.4f°)".format(gastH, Math.toDegrees(ctx.gast)))
            println("参考 GMST = 21.166270 h  (317.494047°)")
            val diffDeg = Math.toDegrees(ctx.gast) - 317.494047
            val diffMin = diffDeg / 360.0 * 1436.068
            println("差值 = %.4f° = %.2f 分钟".format(diffDeg, diffMin))
            println("gast 与 gmst 的差（真赤道 vs 平赤道）= 岁差/章动项，量级 ~0.005°")
        }
    }
}
