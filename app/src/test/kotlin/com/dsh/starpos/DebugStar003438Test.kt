// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.JointSolver
import com.dsh.starpos.field.StarVertical
import java.io.File
import org.junit.Test

/** 排查：003438 星包在手机上单包解算出现上万 km 误差。 */
class DebugStar003438Test {

    private fun root(): File {
        var d = File(".").absoluteFile
        repeat(4) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile ?: return d
        }
        return File(".").absoluteFile
    }

    @Test
    fun `003438 星包 FixObs 检查`() {
        val ws = root()
        val zip = File(ws, "field-inbox/session-20261120-003438.zip")
        if (!zip.isFile) { println("缺包"); return }
        val extrinsic = ExtrinsicConfig.load(File(ws, "config/extrinsic.json"))
        BundleReader.open(zip).use { reader ->
            println("note=${reader.manifest.note}  biasMg=${reader.manifest.accelBiasMg?.joinToString()}")
            val catalog = Fixtures.catalog
            val a = FieldAnalyzer.analyze(reader, catalog, extrinsic, StarVertical.DEFAULT)
            println("findings=" + a.findings)
            val truth = a.truth
            println("truth=${truth?.lat}, ${truth?.lon} acc=${truth?.accuracyM}")
            for (f in a.frames) {
                println(
                    "frame ${f.index}: fix=%s ellipse=%s matched=%d rms=%.1f reason=%s".format(
                        f.fix?.let { "%.5f,%.5f".format(it.latDeg, it.lonDeg) } ?: "null",
                        f.ellipse?.let { "%.0f/%.0f az%.0f".format(it.semiMajorM, it.semiMinorM, it.azimuthDeg) } ?: "null",
                        f.matched, f.rmsArcsec, f.reason,
                    ),
                )
            }
            // 与手机端一致的转换：帧 fix 平均、1σ=95%/2.4477
            val fixes = a.frames.mapNotNull { fr ->
                val fx = fr.fix ?: return@mapNotNull null
                val el = fr.ellipse ?: return@mapNotNull null
                Triple(fx, el, fr.matched)
            }
            if (fixes.isEmpty()) { println("无解"); return }
            val lat = fixes.map { Math.toRadians(it.first.latDeg) }.average()
            val lon = fixes.map { Math.toRadians(it.first.lonDeg) }.average()
            val major = fixes.map { it.second.semiMajorM }.average() / 2.4477
            val minor = fixes.map { it.second.semiMinorM }.average() / 2.4477
            println("平均 fix: %.6f, %.6f  sigma1=%.0f/%.0f m".format(Math.toDegrees(lat), Math.toDegrees(lon), major, minor))
            val fixObs = JointSolver.FixObs(
                latRad = lat, lonRad = lon, sigmaMajorM = major, sigmaMinorM = minor,
                azimuthDeg = fixes.first().second.azimuthDeg, session = "star-003438",
            )
            val tLat = truth?.lat?.let { Math.toRadians(it) }
            val tLon = truth?.lon?.let { Math.toRadians(it) }
            val r = JointSolver.solve(emptyList(), listOf(fixObs), truthLatRad = tLat, truthLonRad = tLon)
            println("JointSolver fix-only: " + (r?.let {
                "%.6f,%.6f err=%.1f km major95=%.1f km tier=%s".format(
                    it.latDeg, it.lonDeg, it.truthErrorKm ?: Double.NaN, it.major95M / 1000, it.tier,
                )
            } ?: "null"))
        }
    }
}
