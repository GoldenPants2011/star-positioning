// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import org.junit.Test

/** 19:00 两组月亮+土星数据的两体分析输出。 */
class TwoBodySessionTest {
    @Test
    fun `两体分析输出`() {
        val dir = File("../field-inbox")
        val zips = dir.listFiles { f -> f.name.startsWith("session-20261121-19") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, null)
                println("=".repeat(60))
                println(zip.name + " truth=" + a.truth?.lat + "," + a.truth?.lon + " gnssRows=" + a.gnssRows)
                a.findings.filter { it.contains("两体") || it.contains("月亮+") || it.contains("太阳") }
                    .forEach { println("  $it") }
            }
        }
    }
}
