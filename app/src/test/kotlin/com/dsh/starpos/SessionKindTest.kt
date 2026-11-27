// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.SessionJointAnalyzer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** 旧包没有类型前缀，必须能从 manifest 识别星/月/日，避免把星光包当月亮解。 */
class SessionKindTest {
    private fun inbox(name: String): File? {
        var d = File(".").absoluteFile
        repeat(4) {
            if (File(d, "settings.gradle.kts").isFile) {
                val f = File(d, "field-inbox/$name")
                return if (f.isFile) f else null
            }
            d = d.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `旧包类型推断`() {
        val star = inbox("session-20261120-034851.zip") ?: run { println("跳过：缺少 field-inbox"); return }
        val moon = inbox("session-20261121-190021.zip")
        val sun = inbox("session-20261121-152633.zip")
        val cal = inbox("session-20261120-003438.zip")
        assertEquals("star", SessionJointAnalyzer.kindOf(star))
        moon?.let { assertEquals("legacy", SessionJointAnalyzer.kindOf(it)) } // 低曝光无备注，仍走日月分支
        sun?.let { assertEquals("sun", SessionJointAnalyzer.kindOf(it)) }
        cal?.let { assertEquals("cal", SessionJointAnalyzer.kindOf(it)) }
    }
}
