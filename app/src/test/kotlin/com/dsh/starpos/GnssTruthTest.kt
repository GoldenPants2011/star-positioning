package com.dsh.starpos

import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.GnssRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GNSS 真值选取规则（2026-11-15 修复）：
 * 系统"最后已知位置"可能来自几小时前甚至另一个城市，绝不能当成本次采集的真值。
 */
class GnssTruthTest {

    private fun row(provider: String, accuracy: Double, lat: Double = 31.23) =
        GnssRow(utcMillis = 1_763_000_000_000L, lat = lat, lon = 121.47, altM = 5.0, accuracyM = accuracy, provider = provider)

    @Test
    fun `最后已知位置不得作为真值`() {
        val seed = row("gps:last-known", 5.0)
        assertNull("只有 last-known 时必须返回 null", FieldAnalyzer.pickTruth(null, listOf(seed)))
        assertNull("manifest 里塞了 last-known 也必须拒绝", FieldAnalyzer.pickTruth(seed, emptyList()))
    }

    @Test
    fun `本次会话的实时定位优先于最后已知位置`() {
        val seed = row("gps:last-known", 5.0)
        val live = row("gps", 20.0)
        val picked = FieldAnalyzer.pickTruth(seed, listOf(seed, live))
        assertEquals("gps", picked?.provider)
    }

    @Test
    fun `GPS 优先于网络定位`() {
        val net = row("network", 30.0)
        val gps = row("gps", 50.0)
        assertEquals("gps", FieldAnalyzer.pickTruth(null, listOf(net, gps))?.provider)
    }

    @Test
    fun `同来源时选精度更好的`() {
        val coarse = row("gps", 30.0, lat = 30.0)
        val fine = row("gps", 6.0, lat = 31.5)
        val picked = FieldAnalyzer.pickTruth(null, listOf(coarse, fine))
        assertEquals(6.0, picked!!.accuracyM, 1e-9)
        assertEquals(31.5, picked.lat, 1e-9)
    }

    @Test
    fun `没有任何定位时返回 null`() {
        assertNull(FieldAnalyzer.pickTruth(null, emptyList()))
    }
}
