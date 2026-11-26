// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import androidx.compose.ui.graphics.Color

/**
 * 全 App 统一配色：通过 [applyTheme] 切换暗色/琥珀夜视。
 * 说明：这些是进程级可变色值；切换时 App 会用 key(night) 重建整棵界面，保证所有页面取到新色。
 */
internal var AppBg = Color(0xFF070B12)
internal var AppCard = Color(0xFF121826)
internal var AppCard2 = Color(0xFF0D1320)
internal var AppAccent = Color(0xFF7FB2FF)
internal var AppAmber = Color(0xFFFFC15E)
internal var AppText = Color(0xFFE6ECF7)
internal var AppDim = Color(0xFF8A93A6)
internal var AppOk = Color(0xFF57D39B)
internal var AppBad = Color(0xFFFF6B6B)
internal var AppWarn = Color(0xFFFFB020)

/** 夜视（琥珀）主题色。 */
internal val NightBg = Color(0xFF0A0703)
internal val NightCard = Color(0xFF17110A)
internal val NightFg = Color(0xFFFFB35C)

/** 切换主题（全 App 统一）；colorAssist=用蓝/橙替代红绿语义色。 */
internal fun applyTheme(night: Boolean, colorAssist: Boolean = false) {
    if (night) {
        AppBg = Color(0xFF0A0703)
        AppCard = Color(0xFF17110A)
        AppCard2 = Color(0xFF120C06)
        AppAccent = Color(0xFFFFB35C)
        AppAmber = Color(0xFFFFC15E)
        AppText = Color(0xFFFFD9A6)
        AppDim = Color(0xFFB08A5A)
        AppOk = Color(0xFFD6C08A)
        AppBad = Color(0xFFFF7A5C)
        AppWarn = Color(0xFFFFA040)
    } else {
        AppBg = Color(0xFF070B12)
        AppCard = Color(0xFF121826)
        AppCard2 = Color(0xFF0D1320)
        AppAccent = Color(0xFF7FB2FF)
        AppAmber = Color(0xFFFFC15E)
        AppText = Color(0xFFE6ECF7)
        AppDim = Color(0xFF8A93A6)
        AppOk = Color(0xFF57D39B)
        AppBad = Color(0xFFFF6B6B)
        AppWarn = Color(0xFFFFB020)
    }
    if (colorAssist) {
        // 色觉辅助：状态语义用蓝/橙/黄，避免红绿难辨
        AppOk = if (night) Color(0xFF8FD3FF) else Color(0xFF4FC3F7)
        AppBad = if (night) Color(0xFFFFA060) else Color(0xFFFF8A3D)
        AppWarn = if (night) Color(0xFFFFE082) else Color(0xFFFFD54F)
    }
}
