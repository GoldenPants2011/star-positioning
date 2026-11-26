// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.android.DeviceParams
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.StarVertical
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首次安装只写零值；升级时不得覆盖已有设备参数。 */
class DeviceParamsTest {

    @Test
    fun `首次安装写零值且升级不覆盖已有参数`() {
        val root = kotlin.io.path.createTempDirectory("device-params").toFile()
        try {
            DeviceParams.ensureInitialized(root, null)
            val svFile = File(root, DeviceParams.STAR_VERTICAL_FILE)
            val extFile = File(root, ExtrinsicConfig.FILE)
            val biasFile = File(root, DeviceParams.ACCEL_BIAS_FILE)
            assertTrue("缺少星垂修正文件", svFile.isFile)
            assertTrue("缺少外参文件", extFile.isFile)
            assertTrue("缺少零偏文件", biasFile.isFile)

            val sv = StarVertical.load(svFile)
            assertNotNull(sv)
            assertEquals(0.0, sv!!.magnitudeDeg, 1e-9)
            val ext = ExtrinsicConfig.load(extFile)
            assertNotNull(ext)
            assertEquals(0.0, ext!!.angleDeg, 1e-6)

            // 模拟已标定/用户已有参数，再次启动（升级）必须原样保留
            val calibrated = """{"dt_deg":[1.0,2.0,3.0],"note":"user","source":"file"}"""
            svFile.writeText(calibrated)
            DeviceParams.ensureInitialized(root, null)
            assertEquals(calibrated, svFile.readText())
            assertEquals("default-zero", ExtrinsicConfig.load(extFile)!!.source)
        } finally {
            root.deleteRecursively()
        }
    }
}
