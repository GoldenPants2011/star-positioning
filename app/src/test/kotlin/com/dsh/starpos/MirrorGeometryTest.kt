package com.dsh.starpos

import com.dsh.starpos.astro.MirrorGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 水镜几何：托盘距离与预测像素位置。 */
class MirrorGeometryTest {

    @Test
    fun `托盘距离`() {
        // H=0.4 m, h=20° → d = 0.4/tan20° ≈ 1.099 m
        assertEquals(1.099, MirrorGeometry.trayDistanceM(0.4, 20.0), 0.01)
        // H=0.5 m, h=5° → d = 5.71 m
        assertEquals(5.715, MirrorGeometry.trayDistanceM(0.5, 5.0), 0.02)
        // 高月亮：H=0.5, h=45° → 0.5 m
        assertEquals(0.5, MirrorGeometry.trayDistanceM(0.5, 45.0), 0.01)
        assertEquals(Double.NaN, MirrorGeometry.trayDistanceM(0.5, 0.2), 1e-9)
    }

    @Test
    fun `预测像素：正对目标时在画面中心`() {
        // 相机真方位 90°、俯仰 +20°；目标 alt=20°, az=90° → 画面中心
        val m = MirrorGeometry.mark(20.0, 90.0, headingDeg = 90.0, pitchDeg = 20.0, fx = 1468.9, fy = 1470.9)
        assertNotNull(m)
        assertEquals(0.5, m!!.first.toDouble(), 0.01)
        assertEquals(0.5, m.second.toDouble(), 0.01)
    }

    @Test
    fun `反射像在下方：相机水平时反射像落在画面下半部`() {
        // 目标 alt=10°（反射 -10°）；相机俯仰 0°、真方位对准 → 反射像在下方（ny>0.5）
        val m = MirrorGeometry.mark(-10.0, 90.0, headingDeg = 90.0, pitchDeg = 0.0, fx = 1468.9, fy = 1470.9)
        assertNotNull(m)
        println("反射像归一化坐标=%.3f, %.3f".format(m!!.first, m.second))
        assertEquals(0.5, m.first.toDouble(), 0.01)
        assertEquals(true, m.second > 0.5f)
        // 而直射像（alt=10°）在画面上半部
        val d = MirrorGeometry.mark(10.0, 90.0, headingDeg = 90.0, pitchDeg = 0.0, fx = 1468.9, fy = 1470.9)
        assertNotNull(d)
        assertEquals(true, d!!.second < 0.5f)
    }

    @Test
    fun `目标在后方返回 null`() {
        val m = MirrorGeometry.mark(20.0, 270.0, headingDeg = 90.0, pitchDeg = 20.0, fx = 1468.9, fy = 1470.9)
        assertNull(m)
    }
}
