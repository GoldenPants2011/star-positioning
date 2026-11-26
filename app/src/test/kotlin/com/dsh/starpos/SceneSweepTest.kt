// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.core.DemoRunner
import com.dsh.starpos.core.syncedClockStatus
import org.junit.Test

/** 临时诊断：逐场景打印检测/匹配/门控结果。 */
class SceneSweepTest {

    @Test
    fun sweep() {
        val runner = Fixtures.runner
        DemoRunner.defaultScenes().forEachIndexed { i, scene ->
            val t0 = System.nanoTime()
            val run = runner.run(scene, syncedClockStatus(uncertaintyMs = 250.0), gravitySigmaDeg = 0.1)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val r = run.outcome.result
            println(
                "scene#$i lat=${scene.latDeg} lon=${scene.lonDeg} cloud=${scene.cloudCover} " +
                    "rendered=${run.frame.starsRendered} detected=${run.detections.detections.size} " +
                    "matched=${run.outcome.plate?.matchedStars ?: 0} rms=${"%.1f".format(run.outcome.plate?.rmsArcsec ?: 0.0)}\" " +
                    "status=${r.status} reason=${r.reason} elapsed=${ms}ms",
            )
        }
    }
}
