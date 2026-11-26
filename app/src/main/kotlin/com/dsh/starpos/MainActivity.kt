// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dsh.starpos.android.DiagLog
import com.dsh.starpos.ui.StarPosApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        DiagLog.i("App", "MainActivity.onCreate")
        com.dsh.starpos.android.DeviceParams.ensureInitialized(this)
        com.dsh.starpos.android.AccelBiasStore.loadAndApply(this)
        setContent { StarPosApp() }
    }
}
