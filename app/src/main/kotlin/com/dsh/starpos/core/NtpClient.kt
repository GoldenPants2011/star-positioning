package com.dsh.starpos.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * 极简 SNTP 客户端（需求 FR-06：系统不提供"上次对时偏差"，必须自行对时并持久化）。
 * 只做一次一问一答，失败即返回 null，不抛异常。
 */
object NtpClient {

    class SyncResult(val offsetMs: Long, val rttMs: Long, val server: String, val atUtcMs: Long)

    private const val NTP_EPOCH_DIFF_MS = 2_208_988_800_000L  // 1900-01-01 → 1970-01-01

    fun query(server: String = "ntp.aliyun.com", timeoutMs: Int = 2500): SyncResult? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val buffer = ByteArray(48)
                buffer[0] = 0x1B.toByte()   // LI=0, VN=3, Mode=3 (client)
                val t1 = System.currentTimeMillis()
                writeTimestamp(buffer, 40, t1)
                val address = InetAddress.getByName(server)
                socket.send(DatagramPacket(buffer, buffer.size, address, 123))

                val response = ByteArray(48)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                val t4 = System.currentTimeMillis()

                val t2 = readTimestamp(response, 32)
                val t3 = readTimestamp(response, 40)
                if (t2 <= 0 || t3 <= 0) return null
                val offset = ((t2 - t1) + (t3 - t4)) / 2
                val rtt = (t4 - t1) - (t3 - t2)
                SyncResult(offset, rtt, server, t4 + offset)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeTimestamp(buffer: ByteArray, offset: Int, unixMs: Long) {
        val ntpMs = unixMs + NTP_EPOCH_DIFF_MS
        val seconds = ntpMs / 1000
        val fraction = ((ntpMs % 1000) * 0x100000000L) / 1000
        writeUInt32(buffer, offset, seconds)
        writeUInt32(buffer, offset + 4, fraction)
    }

    private fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = readUInt32(buffer, offset)
        val fraction = readUInt32(buffer, offset + 4)
        if (seconds == 0L && fraction == 0L) return 0
        return (seconds * 1000 + (fraction * 1000) / 0x100000000L) - NTP_EPOCH_DIFF_MS
    }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }
}

/** 演示/测试用的假时钟（不依赖 Android）。 */
class FakeTimeSource(
    private var wall: Long,
    private var elapsed: Long,
    private val autoTime: Boolean = true,
    private val network: Boolean = true,
) : TimeSource {
    override fun wallClockMs() = wall
    override fun elapsedRealtimeMs() = elapsed
    override fun autoTimeEnabled() = autoTime
    override fun networkAvailable() = network

    fun advance(ms: Long, wallDriftMs: Long = 0) {
        elapsed += ms
        wall += ms + wallDriftMs
    }

    fun jumpWallClock(deltaMs: Long) { wall += deltaMs }
}

/** 演示用：生成一份"刚刚对过时"的时钟状态。 */
fun syncedClockStatus(offlineHours: Double = 1.0, uncertaintyMs: Double = 250.0, stale: Boolean = false): ClockStatus {
    val now = System.currentTimeMillis()
    return ClockStatus(
        offlineHours = offlineHours,
        clockUncertaintyMs = uncertaintyMs,
        lastSyncUtcMs = now - (offlineHours * 3_600_000).toLong(),
        autoTimeEnabled = true,
        networkAvailable = true,
        stale = stale,
        warnings = if (stale) listOf("OFFLINE_TOO_LONG") else emptyList(),
    )
}

@Suppress("unused")
private val rngSeed = Random(1)
