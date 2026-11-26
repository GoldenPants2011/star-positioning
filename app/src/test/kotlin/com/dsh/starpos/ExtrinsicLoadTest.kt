package com.dsh.starpos

import com.dsh.starpos.field.ExtrinsicConfig
import java.io.File
import org.junit.Test

class ExtrinsicLoadTest {
    @Test
    fun `加载外参配置`() {
        val cfg = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        println("loaded=" + (cfg != null) + " angle=" + cfg?.angleDeg + " m=" + (cfg?.m?.size) + " source=" + cfg?.source)
        println("rx,ry,rz=" + cfg?.rx + "," + cfg?.ry + "," + cfg?.rz)
    }
}
