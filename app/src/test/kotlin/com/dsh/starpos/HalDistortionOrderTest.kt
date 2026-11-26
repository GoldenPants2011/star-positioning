package com.dsh.starpos

import com.dsh.starpos.android.CameraProbe
import com.dsh.starpos.field.CameraCaps
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android LENS_DISTORTION 的元素顺序：官方定义是 [k1, k2, k3, p1, p2]
 * （三个径向 + 两个切向）。旧代码按 [k1,k2,p1,p2,k3] 读，本测试锁死正确映射。
 */
class HalDistortionOrderTest {

    private fun caps(distortion: List<Double>) = CameraCaps(
        usedCameraId = "3",
        cameraIds = listOf("0", "3"),
        physicalCameraIds = emptyList(),
        lensFacing = "back",
        hardwareLevel = "LEVEL_3",
        activeArrayW = 1000,
        activeArrayH = 800,
        pixelArrayW = 1000,
        pixelArrayH = 800,
        sensorPhysicalWmm = 4.0,
        sensorPhysicalHmm = 3.2,
        focalLengthsMm = listOf(1.86),
        rawSupported = true,
        yuvSupported = true,
        timestampSource = "REALTIME",
        exposureRangeNs = listOf(34_664L, 1_000_000_000L),
        isoRange = listOf(50, 6400),
        intrinsicCalibration = listOf(1000.0, 1000.0, 500.0, 400.0, 0.0),
        distortion = distortion,
        oisUsed = false,
    )

    @Test
    fun `HAL 畸变顺序 k1 k2 k3 p1 p2`() {
        val rec = CameraProbe.resolveIntrinsics(
            caps(listOf(-0.01, 0.02, -0.03, 0.004, 0.005)),
            width = 500,
            height = 400,
            calibration = null,
        )
        assertEquals("k1", -0.01, rec.k1, 1e-12)
        assertEquals("k2", 0.02, rec.k2, 1e-12)
        assertEquals("k3", -0.03, rec.k3, 1e-12)
        assertEquals("p1", 0.004, rec.p1, 1e-12)
        assertEquals("p2", 0.005, rec.p2, 1e-12)
        // 内参按 active array（1000×800）缩放到输出（500×400）
        assertEquals(500.0, rec.fx, 1e-9)
        assertEquals(250.0, rec.cx, 1e-9)
        assertEquals(200.0, rec.cy, 1e-9)
    }

    @Test
    fun `HAL 畸变缺项时补 0`() {
        val rec = CameraProbe.resolveIntrinsics(
            caps(listOf(-0.02, 0.01)),
            width = 500,
            height = 400,
            calibration = null,
        )
        assertEquals(-0.02, rec.k1, 1e-12)
        assertEquals(0.01, rec.k2, 1e-12)
        assertEquals(0.0, rec.k3, 1e-12)
        assertEquals(0.0, rec.p1, 1e-12)
        assertEquals(0.0, rec.p2, 1e-12)
    }
}
