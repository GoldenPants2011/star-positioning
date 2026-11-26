// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.StarVertical
import java.io.File

/**
 * 本机设备参数（星垂修正 / 相机-IMU 外参 / 加速度零偏）的落盘与首次初始化。
 *
 * 设计原则：
 *  - **不把上一台/调试机的标定值打进 APK**；首次安装只写"零值"文件；
 *  - 参数保存在 App 私有目录（卸载才会清）；升级安装时**只补缺失文件**，绝不覆盖已有标定；
 *  - 离线分析/结果页从这些文件读取，而不是从 assets 内置默认值读取。
 */
object DeviceParams {
    const val STAR_VERTICAL_FILE = "star-vertical.json"
    const val ACCEL_BIAS_FILE = "accel-bias.json"
    const val ZERO_NOTE = "首次安装置零（未标定）"

    /** 首次启动/升级时调用：只创建缺失文件，不覆盖任何已有参数。 */
    fun ensureInitialized(context: Context) = ensureInitialized(context.filesDir, context.getExternalFilesDir(null))

    /** 纯文件版本，便于单元测试；只写缺失文件。 */
    fun ensureInitialized(dir: File, externalDir: File?) {
        fun existsAny(name: String): Boolean =
            File(dir, name).exists() || externalDir?.let { File(it, name).exists() } == true
        val sv = File(dir, STAR_VERTICAL_FILE)
        if (!existsAny(STAR_VERTICAL_FILE)) {
            sv.writeText(
                """{"dt_deg":[0.0,0.0,0.0],"note":"$ZERO_NOTE","source":"default-zero"}""",
            )
        }
        val ext = File(dir, ExtrinsicConfig.FILE)
        if (!existsAny(ExtrinsicConfig.FILE)) {
            ext.writeText(
                """{"rx":0.0,"ry":0.0,"rz":0.0,""" +
                    """"m":[1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0],""" +
                    """"note":"$ZERO_NOTE","source":"default-zero"}""",
            )
        }
        val ab = File(dir, ACCEL_BIAS_FILE)
        if (!existsAny(ACCEL_BIAS_FILE)) {
            ab.writeText(
                """{"bx":0.0,"by":0.0,"bz":0.0,"g":9.80665,"rms":0.0,"note":"$ZERO_NOTE","saved_at":0}""",
            )
        }
    }

    /** 读取本机星垂修正；没有文件时返回 null（不做修正）。 */
    fun starVertical(context: Context): StarVertical? {
        StarVertical.load(File(context.filesDir, STAR_VERTICAL_FILE))?.let { return it }
        context.getExternalFilesDir(null)?.let { dir ->
            StarVertical.load(File(dir, STAR_VERTICAL_FILE))?.let { return it }
        }
        return null
    }
}
