// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.SyntheticSky
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.core.DemoRunner
import org.junit.Test

/** 临时诊断：南半球场景为什么解不出来。 */
class SouthSceneDebugTest {

    @Test
    fun debugSouth() {
        val catalog = Fixtures.catalog
        val scene = DemoRunner.defaultScenes()[3]
        val frame = SyntheticSky.render(catalog, scene)
        val det = FrameAnalyzer.analyze(frame.image, frame.width, frame.height)
        println("detections=${det.detections.size} cloud=${det.quality.cloudCover}")
        det.detections.take(10).forEach {
            println("  det (${"%.1f".format(it.x)}, ${"%.1f".format(it.y)}) flux=${"%.1f".format(it.flux)} area=${it.area}")
        }
        val index = SkyIndex(catalog)
        val truth = frame.truthRotation
        var hit = 0
        val angles = ArrayList<Double>()
        for (d in det.detections.take(30)) {
            val u = frame.camera.unproject(d.x, d.y)
            val v = apply(truth, u)
            val n = index.nearest(v, 1.0 * Math.PI / 180)
            if (n >= 0) {
                hit++
                angles.add(V.angle(v, index.vectorOf(n)) * 180 / Math.PI)
            }
        }
        println("truth inliers(1°)= $hit / 30, angles=${angles.take(8).map { "%.3f".format(it) }}")
        println("upCam truth=${frame.truthUpCam.map { "%.4f".format(it) }}")
        val solver = PlateSolver(index, frame.camera)
        val sol = solver.solve(det.detections, upCam = frame.truthUpCam)
        println("stats=${solver.stats}")
        println("solution=${sol?.matchedStars} rms=${sol?.rmsArcsec}")
    }
}
