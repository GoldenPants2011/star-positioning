// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dsh.starpos.field.GnssRow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GNSS 记录（需求：GNSS 仅作真值校验，不参与融合）。
 *
 * 2026-11-15 重写要点：
 *  - 权限同时接受 FINE / COARSE（Android 12+ 只申请 FINE 会被系统忽略）；
 *  - **实时定位与"最后已知位置"严格分开**：只有本次会话的实时定位才能当真值，
 *    最后已知位置单独保存、只作参考，绝不写进 gnss.csv / gnss_truth；
 *  - 用 `elapsedRealtime` 计算定位新鲜度（墙钟可能不准）；
 *  - 记录卫星数与定位级别，供预检与采集包诊断。
 */
class GnssRecorder(private val context: Context) {

    enum class Permission { FINE, COARSE, NONE }

    private class Fix(val row: GnssRow, val elapsedMs: Long)

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val liveFixes = CopyOnWriteArrayList<Fix>()
    @Volatile private var listening = false

    /** 系统"最后已知位置"，仅作参考，永远不参与解算/真值。 */
    @Volatile var lastKnown: GnssRow? = null
        private set

    @Volatile var satellitesUsed: Int = 0
        private set
    @Volatile var satellitesVisible: Int = 0
        private set
    @Volatile private var lastFixElapsedMs: Long = -1L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (liveFixes.size > 5000) return
            liveFixes.add(Fix(toRow(location), SystemClock.elapsedRealtime()))
            lastFixElapsedMs = SystemClock.elapsedRealtime()
        }

        @Deprecated("兼容旧 API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesVisible = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesUsed = used
        }
    }

    val permission: Permission
        get() = when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Permission.FINE
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> Permission.COARSE
            else -> Permission.NONE
        }

    val permissionLabel: String
        get() = when (permission) {
            Permission.FINE -> "精确"
            Permission.COARSE -> "大致（精度受限）"
            Permission.NONE -> "未授予"
        }

    val providerEnabled: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    val hasRecentFix: Boolean
        get() = lastFixElapsedMs >= 0 && SystemClock.elapsedRealtime() - lastFixElapsedMs < 120_000L

    val liveFixCount: Int get() = liveFixes.size

    /** 距离最近一次实时定位的秒数；没有定位返回 -1。 */
    fun secondsSinceLastFix(): Double {
        if (lastFixElapsedMs < 0) return -1.0
        return (SystemClock.elapsedRealtime() - lastFixElapsedMs) / 1000.0
    }

    fun start(): Boolean {
        val m = manager ?: return false
        if (permission == Permission.NONE) return false
        if (listening) return true
        listening = true
        val gpsOk = runCatching {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener)
            true
        }.getOrDefault(false)
        runCatching { m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000L, 0f, listener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { m.registerGnssStatusCallback(statusCallback, null) }
        }
        if (!gpsOk) {
            // GPS provider 不可用时至少保留网络定位，但要在 status 里体现
            listening = true
        }
        return true
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager?.removeUpdates(listener) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { manager?.unregisterGnssStatusCallback(statusCallback) }
        }
    }

    /** 清空本次会话的实时定位（每次采集开始时调用，保证真值只来自本次）。 */
    fun clearLive() {
        liveFixes.clear()
        lastFixElapsedMs = -1L
    }

    /** 只读最后已知位置（不写入实时列表）。 */
    fun probeLastKnown() {
        val m = manager ?: return
        if (permission == Permission.NONE) return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                m.getLastKnownLocation(provider)?.let { loc ->
                    val row = toRow(loc).copy(provider = "${loc.provider}:last-known")
                    if (lastKnown == null || row.utcMillis > lastKnown!!.utcMillis) lastKnown = row
                }
            }
        }
    }

    /** 本次会话的实时定位（按新鲜度/来源/精度挑最好的一条），没有则 null。 */
    fun bestLive(maxAgeMs: Long = 180_000L): GnssRow? {
        val now = SystemClock.elapsedRealtime()
        val candidates = liveFixes.filter { now - it.elapsedMs <= maxAgeMs && it.row.accuracyM >= 0.0 }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<Fix> { it.row.provider.startsWith("gps") }
                .thenBy { it.row.accuracyM },
        ).first().row
    }

    /** 实时定位（只含本次会话），用于写 gnss.csv。 */
    fun rows(): List<GnssRow> = liveFixes.map { it.row }

    private fun toRow(location: Location): GnssRow = GnssRow(
        utcMillis = location.time,
        lat = location.latitude,
        lon = location.longitude,
        altM = if (location.hasAltitude()) location.altitude else 0.0,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
        provider = location.provider ?: "unknown",
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
