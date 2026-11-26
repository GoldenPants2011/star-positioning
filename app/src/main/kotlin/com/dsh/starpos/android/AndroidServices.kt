package com.dsh.starpos.android

import android.content.Context
import android.provider.Settings
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.core.KeyValueStore
import com.dsh.starpos.core.TimeSource

/** 从 assets 读取内置星表（需求 NFR-02：离线可用）。 */
object Assets {
    fun loadCatalog(
        context: Context,
        file: String = "stars.bin",
        refEpochJd: Double = com.dsh.starpos.astro.AstroTime.J2000,
    ): StarCatalog = context.assets.open(file).use { StarCatalog.parse(it, refEpochJd) }
}

class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("starpos", Context.MODE_PRIVATE)
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    // SharedPreferences 没有 double：用字符串存储，避免精度陷阱
    override fun getDouble(key: String): Double? = prefs.getString(key, null)?.toDoubleOrNull()
    override fun putDouble(key: String, value: Double) { prefs.edit().putString(key, value.toString()).apply() }
}

class AndroidTimeSource(private val context: Context) : TimeSource {
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun autoTimeEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) == 1
    } catch (e: Exception) {
        false
    }

    override fun networkAvailable(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active == null) null else cm.getNetworkCapabilities(active)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        false
    }
}

