package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import org.junit.Test

/**
 * 太阳采集包诊断：太阳当时在哪（高度/方位）、相机朝哪（IMU）、太阳是否在视场内。
 */
class SunSessionDiagTest {

    @Test
    fun `太阳会话朝向诊断`() {
        val dir = File("../field-inbox")
        val files = dir.listFiles { f ->
            f.name.startsWith("session-20261121-15") && f.name.endsWith(".zip")
        }?.sortedBy { it.name } ?: emptyList()
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (zip in files) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                if (metas.isEmpty()) return@use
                val first = metas.first()
                val m = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = first.wallClockUtcMs
                }
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                val sun = Moon.sunEphemerisAt(first.wallClockUtcMs)
                val gast = SkyContext.at(first.wallClockUtcMs).gast
                val sunAlt = Math.toDegrees(Moon.altitudeRad(site, sun, first.wallClockUtcMs, gast))
                val sunAz = Math.toDegrees(Moon.azimuthRad(site, sun, gast))
                // 相机光轴高度：用重力（不依赖方位角）算"中心像素射线"与垂直的夹角
                val intrinsic = reader.manifest.intrinsics
                val cam = intrinsic?.toCameraIntrinsics(first.width, first.height)
                val g = FieldAnalyzer.gravityAt(
                    imu, first, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                )
                var axisAlt = Double.NaN
                var fovDeg = Double.NaN
                if (g != null && g.valid && cam != null) {
                    val up = V.unit(g.upCam)
                    // 中心射线（+Z 朝前）：高度角 = asin(dot(z, up))
                    val zAxis = doubleArrayOf(0.0, 0.0, 1.0)
                    axisAlt = Math.toDegrees(Math.asin((zAxis[0] * up[0] + zAxis[1] * up[1] + zAxis[2] * up[2]).coerceIn(-1.0, 1.0)))
                    fovDeg = Math.toDegrees(2 * Math.atan(max(first.width, first.height) / (2.0 * cam.fx)))
                }
                println("=".repeat(60))
                println(zip.name)
                println("  时间: ${String.format("%02d:%02d:%02d UTC", m.get(java.util.Calendar.HOUR_OF_DAY), m.get(java.util.Calendar.MINUTE), m.get(java.util.Calendar.SECOND))}")
                println("  太阳: 高度 %.2f° 方位 %.1f°".format(sunAlt, sunAz))
                println("  相机光轴高度（IMU gravity）: %.1f°（FOV %.0f°）".format(axisAlt, fovDeg))
                if (!axisAlt.isNaN()) {
                    val diff = sunAlt - axisAlt
                    println("  太阳与光轴高度差: %.1f°（|差| < FOV/2=%.0f° 才可能在画面内）".format(diff, fovDeg / 2))
                    println("  结论: ${if (Math.abs(diff) < fovDeg / 2) "太阳可能在视场内" else "太阳明显不在视场内"}")
                }
            }
        }
    }

    /** 从帧里找太阳圆面（盒式平滑取峰），并用 IMU 重力把它换算成高度角，和星历比。 */
    @Test
    fun `太阳圆面质心与高度角残差`() {
        val dir = File("../field-inbox")
        val files = dir.listFiles { f ->
            f.name.startsWith("session-20261121-15") && f.name.endsWith(".zip")
        }?.sortedBy { it.name } ?: emptyList()
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        for (zip in files) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val imu = reader.readImu()
                val metas = reader.frameMetas()
                val first = metas.first()
                val intr = reader.manifest.intrinsics ?: return@use
                val cam = intr.toCameraIntrinsics(first.width, first.height)
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                val sun = Moon.sunEphemerisAt(first.wallClockUtcMs)
                val gast = SkyContext.at(first.wallClockUtcMs).gast
                val sunAlt = Math.toDegrees(Moon.altitudeRad(site, sun, first.wallClockUtcMs, gast))
                // 3 帧平均 + 盒式平滑找峰
                val sum = DoubleArray(first.width * first.height)
                var n = 0
                for (meta in metas) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    for (i in f.luminance.indices) sum[i] += f.luminance[i].toDouble()
                    n++
                }
                if (n == 0) return@use
                for (i in sum.indices) sum[i] /= n
                val w = first.width; val h = first.height
                val r = 7
                var best = -1e9; var bx = 0; var by = 0
                for (y in r until h - r) for (x in r until w - r) {
                    var s = 0.0
                    for (dy in -r..r) for (dx in -r..r) s += sum[(y + dy) * w + (x + dx)]
                    if (s > best) { best = s; bx = x; by = y }
                }
                val peakMean = best / ((2 * r + 1) * (2 * r + 1))
                val g = FieldAnalyzer.gravityAt(imu, first, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                val up = V.unit(g.upCam)
                val ray = V.unit(
                    doubleArrayOf(
                        (bx - cam.cx) / cam.fx,
                        (by - cam.cy) / cam.fy,
                        1.0,
                    ),
                )
                val cosZ = (ray[0] * up[0] + ray[1] * up[1] + ray[2] * up[2]).coerceIn(-1.0, 1.0)
                val measuredAlt = 90.0 - Math.toDegrees(acos(cosZ))
                println("=".repeat(60))
                println("%s: 峰值均值 %.1f（背景中位 %.1f），质心像素 (%d,%d)".format(
                    zip.name, peakMean, sum.sorted()[sum.size / 2], bx, by))
                println("  太阳星历高度 %.2f°，IMU 测得高度 %.2f°，残差 %+.2f°".format(
                    sunAlt, measuredAlt, measuredAlt - sunAlt))
            }
        }
    }
}
