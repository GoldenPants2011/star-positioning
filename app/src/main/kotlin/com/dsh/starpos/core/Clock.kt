package com.dsh.starpos.core

/**
 * 时钟新鲜度校验（需求 FR-06）。
 *
 * 系统不提供"上次对时偏差"API，所以必须自己记录并持久化：
 *  - 每次成功网络/NTP 对时，记录 (墙钟, elapsedRealtime, 偏差)；
 *  - 之后用 elapsedRealtime 与墙钟差值监测脱网漂移与时钟跳变；
 *  - 结合 Settings.Global.AUTO_TIME 与网络状态给出不确定度。
 */
interface KeyValueStore {
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun getDouble(key: String): Double?
    fun putDouble(key: String, value: Double)
}

class MemoryStore : KeyValueStore {
    private val longs = HashMap<String, Long>()
    private val doubles = HashMap<String, Double>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getDouble(key: String): Double? = doubles[key]
    override fun putDouble(key: String, value: Double) { doubles[key] = value }
}

/** 平台抽象：便于在 JVM 单测中注入假时钟。 */
interface TimeSource {
    /** 当前墙钟（UTC 毫秒，可能不准）。 */
    fun wallClockMs(): Long
    /** CLOCK_BOOTTIME / elapsedRealtime（不受用户改表影响）。 */
    fun elapsedRealtimeMs(): Long
    /** Settings.Global.AUTO_TIME == 1。 */
    fun autoTimeEnabled(): Boolean
    /** 当前是否有网络。 */
    fun networkAvailable(): Boolean
}

class ClockStatus(
    val offlineHours: Double,
    val clockUncertaintyMs: Double,
    val lastSyncUtcMs: Long?,
    val autoTimeEnabled: Boolean,
    val networkAvailable: Boolean,
    val stale: Boolean,
    val warnings: List<String>,
)

class ClockMonitor(
    private val time: TimeSource,
    private val store: KeyValueStore,
) {
    companion object {
        private const val K_SYNC_WALL = "clock.sync.wall"
        private const val K_SYNC_ELAPSED = "clock.sync.elapsed"
        private const val K_SYNC_OFFSET = "clock.sync.offsetMs"
        private const val K_LAST_SYNC_UTC = "clock.lastSyncUtcMs"
        /** 阈值（FR-06）。 */
        const val OFFLINE_STALE_HOURS = 168.0    // 7 天
        const val UNCERTAINTY_STALE_MS = 1000.0  // 1 s
        const val EPHEMERIS_STALE_DAYS = 30.0
    }

    /** 记录一次成功的对时：offsetMs = 真实 UTC − 系统墙钟。 */
    fun recordSync(offsetMs: Long) {
        val wall = time.wallClockMs()
        val elapsed = time.elapsedRealtimeMs()
        store.putLong(K_SYNC_WALL, wall)
        store.putLong(K_SYNC_ELAPSED, elapsed)
        store.putLong(K_SYNC_OFFSET, offsetMs)
        store.putLong(K_LAST_SYNC_UTC, wall + offsetMs)
    }

    fun status(ephemerisAgeDays: Double = 0.0): ClockStatus {
        val wall = time.wallClockMs()
        val elapsed = time.elapsedRealtimeMs()
        val warnings = ArrayList<String>()

        val lastSyncWall = store.getLong(K_SYNC_WALL)
        val lastSyncElapsed = store.getLong(K_SYNC_ELAPSED)
        val lastSyncUtc = store.getLong(K_LAST_SYNC_UTC)
        val autoTime = time.autoTimeEnabled()
        val network = time.networkAvailable()

        if (lastSyncWall == null || lastSyncElapsed == null) {
            // 从未对时：只能依赖系统自动时间
            val uncertainty = if (autoTime && network) 250.0 else 30_000.0
            if (!autoTime) warnings.add("CLOCK_NEVER_SYNCED_AUTO_TIME_OFF")
            return ClockStatus(
                offlineHours = Double.POSITIVE_INFINITY,
                clockUncertaintyMs = uncertainty,
                lastSyncUtcMs = null,
                autoTimeEnabled = autoTime,
                networkAvailable = network,
                stale = true,
                warnings = warnings,
            )
        }

        val offlineHours = (wall - lastSyncUtc!!) / 3_600_000.0
        val wallDelta = wall - lastSyncWall
        val elapsedDelta = elapsed - lastSyncElapsed
        val skewMs = abs(wallDelta - elapsedDelta)   // 墙钟相对单调时钟的漂移/跳变

        if (skewMs > 2_000) warnings.add("WALLCLOCK_JUMP_DETECTED")
        if (!autoTime) warnings.add("AUTO_TIME_DISABLED")

        // 不确定度：对时残差 + 漂移（按 30 ppm 保守估计）+ 无网络时的额外项
        val baseUncertainty = 5.0
        val driftPpm = 30.0
        val driftMs = (elapsedDelta / 1000.0) * driftPpm / 1000.0
        val networkBonus = if (network) 0.0 else 50.0
        val uncertainty = baseUncertainty + skewMs + driftMs + networkBonus

        if (offlineHours > OFFLINE_STALE_HOURS) warnings.add("OFFLINE_TOO_LONG")
        if (uncertainty > UNCERTAINTY_STALE_MS) warnings.add("CLOCK_UNCERTAINTY_HIGH")
        if (ephemerisAgeDays > EPHEMERIS_STALE_DAYS) warnings.add("EPHEMERIS_STALE")
        val stale = offlineHours > OFFLINE_STALE_HOURS ||
            uncertainty > UNCERTAINTY_STALE_MS ||
            ephemerisAgeDays > EPHEMERIS_STALE_DAYS

        return ClockStatus(
            offlineHours = offlineHours,
            clockUncertaintyMs = uncertainty,
            lastSyncUtcMs = lastSyncUtc,
            autoTimeEnabled = autoTime,
            networkAvailable = network,
            stale = stale,
            warnings = warnings,
        )
    }

    private fun abs(v: Long) = if (v < 0) -v else v
}
