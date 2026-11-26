package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.Projection
import com.dsh.starpos.astro.V
import com.dsh.starpos.core.ClockMonitor
import com.dsh.starpos.core.FakeTimeSource
import com.dsh.starpos.core.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraTest {

    @Test
    fun `针孔相机投影与反投影自洽`() {
        val cam = CameraIntrinsics(
            width = 1080, height = 1440, fx = 1900.0, fy = 1890.0, cx = 540.0, cy = 720.0,
            k1 = -0.12, k2 = 0.05, p1 = 0.001, p2 = -0.002, k3 = -0.01,
        )
        for (x in listOf(0.0, 120.0, 540.0, 900.0, 1079.0)) {
            for (y in listOf(0.0, 200.0, 720.0, 1200.0, 1439.0)) {
                val u = cam.unproject(x, y)
                val p = cam.project(u)!!
                assertEquals("x 反投影误差", x, p[0], 1e-6)
                assertEquals("y 反投影误差", y, p[1], 1e-6)
            }
        }
    }

    @Test
    fun `鱼眼等距模型自洽`() {
        val cam = CameraIntrinsics.fromFov(1080, 1440, 120.0, Projection.FISHEYE_EQUIDISTANT)
        for (x in listOf(10.0, 400.0, 540.0, 800.0, 1070.0)) {
            for (y in listOf(10.0, 500.0, 720.0, 1100.0, 1430.0)) {
                val u = cam.unproject(x, y)
                val p = cam.project(u)!!
                assertEquals(x, p[0], 1e-6)
                assertEquals(y, p[1], 1e-6)
            }
        }
    }

    @Test
    fun `视场角计算正确`() {
        val cam = CameraIntrinsics.fromFov(1080, 1440, 30.0)
        assertEquals(30.0, cam.fovXDeg, 1e-9)
    }

    @Test
    fun `主点方向与光轴一致`() {
        val cam = CameraIntrinsics.fromFov(1080, 1440, 30.0)
        val axis = cam.opticalAxis()
        assertEquals(1.0, axis[2], 1e-9)
        assertEquals(0.0, axis[0], 1e-6)
    }
}

class ClockTest {

    @Test
    fun `从未对时判为陈旧`() {
        val time = FakeTimeSource(wall = 1_700_000_000_000L, elapsed = 1_000_000L)
        val monitor = ClockMonitor(time, MemoryStore())
        val status = monitor.status()
        assertTrue("从未对时应 stale", status.stale)
    }

    @Test
    fun `对时后一小时内可用`() {
        val time = FakeTimeSource(wall = 1_700_000_000_000L, elapsed = 1_000_000L)
        val store = MemoryStore()
        val monitor = ClockMonitor(time, store)
        monitor.recordSync(offsetMs = 120)
        time.advance(ms = 3_600_000)
        val status = monitor.status()
        assertTrue("1 小时后应可用，实际 warnings=${status.warnings}", !status.stale)
        assertTrue("脱网小时数约 1，实际 ${status.offlineHours}", abs(status.offlineHours - 1.0) < 0.01)
    }

    @Test
    fun `脱网超过七天判为陈旧`() {
        val time = FakeTimeSource(wall = 1_700_000_000_000L, elapsed = 1_000_000L)
        val monitor = ClockMonitor(time, MemoryStore())
        monitor.recordSync(offsetMs = 0)
        time.advance(ms = 8 * 24 * 3_600_000L)
        val status = monitor.status()
        assertTrue("8 天应 stale", status.stale)
        assertTrue("应含 OFFLINE_TOO_LONG，实际 ${status.warnings}", status.warnings.contains("OFFLINE_TOO_LONG"))
    }

    @Test
    fun `墙钟跳变被检出`() {
        val time = FakeTimeSource(wall = 1_700_000_000_000L, elapsed = 1_000_000L)
        val monitor = ClockMonitor(time, MemoryStore())
        monitor.recordSync(offsetMs = 0)
        time.advance(ms = 60_000)
        time.jumpWallClock(5_000)
        val status = monitor.status()
        assertTrue("应检出跳变，实际 ${status.warnings}", status.warnings.contains("WALLCLOCK_JUMP_DETECTED"))
        assertTrue("跳变后不确定度应升高", status.clockUncertaintyMs > 1000)
    }

    @Test
    fun `EOP 过期单独告警`() {
        val time = FakeTimeSource(wall = 1_700_000_000_000L, elapsed = 1_000_000L)
        val monitor = ClockMonitor(time, MemoryStore())
        monitor.recordSync(offsetMs = 0)
        time.advance(ms = 3_600_000)
        val status = monitor.status(ephemerisAgeDays = 45.0)
        assertTrue("EOP 过期应告警", status.warnings.contains("EPHEMERIS_STALE"))
        assertTrue("应判 stale", status.stale)
    }
}
