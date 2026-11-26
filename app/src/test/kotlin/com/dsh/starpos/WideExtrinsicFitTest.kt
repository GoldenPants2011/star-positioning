// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicEstimator
import java.io.File
import org.junit.Test

/** 用广角标定会话（camera 3）跑一次手眼外参估计。 */
class WideExtrinsicFitTest {
    @Test
    fun `广角外参拟合`() {
        val root = File(".").absoluteFile.let { d ->
            var x = d
            repeat(4) {
                if (File(x, "settings.gradle.kts").isFile) return@let x
                x = x.parentFile ?: x
            }
            x
        }
        val zip = File(root, "field-inbox/session-20261124-035956.zip")
        if (!zip.isFile) { println("找不到会话"); return }
        BundleReader.open(zip).use { reader ->
            val est = ExtrinsicEstimator.estimate(reader, Fixtures.catalog)
            println("pairs=${est.pairsUsed} medianRot=%.2f° cfgs=${est.config?.angleDeg}".format(est.medianRotationDeg))
            println("message=${est.message}")
            est.config?.let { c ->
                println("rx=%.5f ry=%.5f rz=%.5f source=${c.source}".format(c.rx, c.ry, c.rz))
                println("m=${c.m?.joinToString(",")}")
            }
        }
    }
}
