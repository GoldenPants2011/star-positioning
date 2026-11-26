// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/** 白天月亮单帧分析（2026-11-22 16:25，2 ms/ISO400，GPS ±3.8 m）。 */
class DayMoonTest {

    @Test
    fun `白天月亮分析`() {
        val root = File(".").absoluteFile.let { d ->
            var x = d
            repeat(4) {
                if (File(x, "settings.gradle.kts").isFile) return@let x
                x = x.parentFile ?: x
            }
            x
        }
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val zip = File(root, "field-inbox/session-20261122-162539.zip")
        if (!zip.isFile) { println("没有该采集包"); return }
        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull()
            println("GNSS: ${truth?.lat},${truth?.lon} ±${truth?.accuracyM} m provider=${truth?.provider}")
            if (truth != null) {
                val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                for (meta in reader.frameMetas()) {
                    val sc = SkyContext.at(meta.wallClockUtcMs)
                    val moon = Moon.ephemerisAt(meta.wallClockUtcMs)
                    val sun = Moon.sunEphemerisAt(meta.wallClockUtcMs)
                    val mAlt = Math.toDegrees(Moon.altitudeRad(site, moon, meta.wallClockUtcMs, sc.gast))
                    val mAz = Math.toDegrees(Moon.azimuthRad(site, moon, sc.gast))
                    val sAlt = Math.toDegrees(Moon.altitudeRad(site, sun, meta.wallClockUtcMs, sc.gast))
                    val sAz = Math.toDegrees(Moon.azimuthRad(site, sun, sc.gast))
                    println("帧${meta.index} ${meta.exposureNs / 1_000_000}ms/ISO${meta.iso}")
                    println("  月亮: 高度 %+.2f° 方位 %.1f° 相位角 %.1f° 距离 %.0f km 角半径 %.2f px".format(
                        mAlt, mAz, Math.toDegrees(moon.phaseAngleRad), moon.distanceKm,
                        Math.tan(moon.angularRadiusRad) * (reader.manifest.intrinsics?.fx ?: 2937.8) / 2,
                    ))
                    println("  太阳: 高度 %+.2f° 方位 %.1f°（月亮-太阳角距 %.1f°）".format(
                        sAlt, sAz, Math.toDegrees(com.dsh.starpos.astro.V.angle(
                            com.dsh.starpos.astro.V.fromRaDec(moon.raRad, moon.decRad),
                            com.dsh.starpos.astro.V.fromRaDec(sun.raRad, sun.decRad),
                        )),
                    ))
                }
            }
            // 预测月亮在画面里的位置（用 IMU+外参；没有星解也能算）
            run {
                val imu = reader.readImu()
                val intr = reader.manifest.intrinsics ?: return@run
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    val truth2 = reader.readGnss().lastOrNull() ?: continue
                    val site = Moon.Site(Math.toRadians(truth2.lat), Math.toRadians(truth2.lon))
                    val sc = SkyContext.at(meta.wallClockUtcMs)
                    val moon = Moon.ephemerisAt(meta.wallClockUtcMs)
                    val alt = Moon.altitudeRad(site, moon, meta.wallClockUtcMs, sc.gast)
                    val az = Moon.azimuthRad(site, moon, sc.gast)
                    val up = com.dsh.starpos.astro.V.unit(g.upCam)
                    // 东/北基在相机系：用重力方向 + 磁/真北近似（这里用 az/alt 直接构造：先构造天球方向再转到相机）
                    // 简化：只报"月亮高度/方位"和"相机光轴俯仰"，并给出画面亮度统计
                    val lum = f.luminance
                    val sorted = lum.copyOf(); sorted.sort()
                    val mean = lum.average()
                    val p99 = sorted[(sorted.size * 0.99).toInt()]
                    println("帧${meta.index}: 平均亮度 %.2f p99 %.2f（1.0=饱和）→ %s".format(
                        mean, p99,
                        if (mean > 0.55) "整体过曝：白天拍月亮需要更短曝光/更低 ISO 或 ND 滤镜" else "曝光尚可",
                    ))
                    println("  相机光轴俯仰（IMU）：%.1f°；月亮高度 %+.1f°".format(
                        Math.toDegrees(Math.asin(up[2].coerceIn(-1.0, 1.0))), Math.toDegrees(alt),
                    ))
                }
            }
            val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
            println("=".repeat(50))
            a.findings.forEach { println(it) }
        }
    }
}
