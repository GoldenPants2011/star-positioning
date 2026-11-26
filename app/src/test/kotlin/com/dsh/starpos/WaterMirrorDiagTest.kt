package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.MirrorPair
import java.io.File
import org.junit.Test

/**
 * 水镜（太阳）数据诊断：
 * - 直接像 = 最亮点（已装滤镜的太阳）；
 * - 反射像 = 屏蔽太阳后次亮点（水面反光，暗很多）；
 * - 用镜像关系求天顶/太阳视高度，与星历比较。
 */
class WaterMirrorDiagTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    /** 盒均值峰值（半径 r），返回 (cx, cy, peakMean)。 */
    private fun peak(lum: FloatArray, w: Int, h: Int, r: Int, mask: (Int, Int) -> Boolean): Triple<Int, Int, Double>? {
        var best = Double.NEGATIVE_INFINITY
        var bx = -1; var by = -1
        val step = (r / 2).coerceAtLeast(1)
        for (y in r until h - r step step) for (x in r until w - r step step) {
            if (!mask(x, y)) continue
            var sum = 0.0
            for (dy in -r..r) for (dx in -r..r) sum += lum[(y + dy) * w + (x + dx)]
            val m = sum / ((2 * r + 1) * (2 * r + 1))
            if (m > best) { best = m; bx = x; by = y }
        }
        if (bx < 0) return null
        return Triple(bx, by, best)
    }

    @Test
    fun `水镜太阳诊断`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val zips = inbox.listFiles { f -> f.name.startsWith("session-20261124-15") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull()
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val site = if (truth != null) Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)) else null
                println("=".repeat(72))
                println("${zip.name}  truth=${truth?.lat},${truth?.lon} ±${truth?.accuracyM?.toInt()}m")
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    // 1) 直接像：最亮 5×5 盒均值
                    val direct = peak(f.luminance, f.width, f.height, r = 5) { _, _ -> true } ?: continue
                    val (dx, dy, dMean) = direct
                    // 2) 反射像：屏蔽太阳周围 60 px 后找次亮
                    val refl = peak(f.luminance, f.width, f.height, r = 5) { x, y ->
                        (x - dx) * (x - dx) + (y - dy) * (y - dy) > 60 * 60
                    } ?: continue
                    val (rx, ry, rMean) = refl
                    // 背景/噪声
                    val sorted = f.luminance.copyOf(); sorted.sort()
                    val min = sorted[0]; val max = sorted[sorted.size - 1]
                    val dynamic = (max - min).toDouble().coerceAtLeast(1e-6)
                    val bgMean = sorted[sorted.size / 2]
                    val g = FieldAnalyzer.gravityAt(imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    val up = V.unit(g.upCam)
                    val uD = V.unit(cam.unproject(dx.toDouble(), dy.toDouble()))
                    val uR = V.unit(cam.unproject(rx.toDouble(), ry.toDouble()))
                    val pair = MirrorPair.solve(uD, uR, upHint = up)
                    val altEphem = if (site != null) {
                        val sun = Moon.sunEphemerisAt(meta.wallClockUtcMs)
                        val sc = SkyContext.at(meta.wallClockUtcMs)
                        Math.toDegrees(Moon.altitudeRad(site, sun, meta.wallClockUtcMs, sc.gast))
                    } else Double.NaN
                    val altGravity = Math.toDegrees(Math.asin((uD[0] * up[0] + uD[1] * up[1] + uD[2] * up[2]).coerceIn(-1.0, 1.0)))
                    println("帧${meta.index}: 太阳(%d,%d) 亮度+%.3f；反射(%d,%d) 亮度+%.3f（比值 %.1f%%）".format(
                        dx, dy, (dMean - bgMean) / dynamic * 100,
                        rx, ry, (rMean - bgMean) / dynamic * 100,
                        (rMean - bgMean) / (dMean - bgMean).coerceAtLeast(1e-9) * 100,
                    ))
                    println("  星历太阳高度 %+.2f°；IMU 直射像高度 %+.2f°；镜像解高度 %s；镜像残差 %s；天顶差 %s".format(
                        altEphem, altGravity,
                        pair?.let { "%+.2f°".format(Math.toDegrees(it.apparentAltRad)) } ?: "无（未成对）",
                        pair?.let { "%.2f°".format(it.residualDeg) } ?: "-",
                        pair?.let { "%.2f°".format(Math.toDegrees(Math.acos((pair.upCam[0] * up[0] + pair.upCam[1] * up[1] + pair.upCam[2] * up[2]).coerceIn(-1.0, 1.0)))) } ?: "-",
                    ))
                }
            }
        }
    }
}
