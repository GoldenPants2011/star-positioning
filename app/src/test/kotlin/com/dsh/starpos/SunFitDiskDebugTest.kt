// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test

class SunFitDiskDebugTest {
    @Test
    fun `debug sun fitDisk`() {
        val zips = listOf("session-20261121-152754.zip", "session-20261121-152633.zip")
        for (name in zips) {
            BundleReader.open(File("../field-inbox/$name")).use { reader ->
                val metas = reader.frameMetas()
                val meta = metas.first()
                val f = reader.loadFrame(meta.index)
                println("=".repeat(50))
                println("$name ${f.width}x${f.height} min=${f.luminance.min()} max=${f.luminance.max()}")
                for (minArea in intArrayOf(16, 78, 300)) {
                    val r = runCatching {
                        Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = minArea, minRadiusPx = 2.0)
                    }
                    println("  minArea=$minArea -> ${r.getOrNull()?.let { "cx=%.1f cy=%.1f r=%.2f edge=%.2f".format(it.cx, it.cy, it.radiusPx, it.edgeScore) } ?: "null"} ${r.exceptionOrNull()?.let { "ex=$it" } ?: ""}")
                }
                val intr = reader.manifest.intrinsics
                println("  intrinsics=$intr")
                val cam = intr?.toCameraIntrinsics(f.width, f.height)
                println("  cam(fx,cx,cy)=${cam?.fx},${cam?.cx},${cam?.cy}")
                val sun = Moon.sunEphemerisAt(meta.wallClockUtcMs)
                println("  sun angularRadiusRad=${sun.angularRadiusRad} -> expectedPx=${cam?.let { Math.tan(sun.angularRadiusRad) * it.fx }}")
            }
        }
    }
}
