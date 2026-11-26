package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.Detection
import com.dsh.starpos.astro.StarSolveParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 星点/解算参数按相机自适应（广角 vs 主摄）。 */
class StarSolveParamsTest {

    private fun cam(fx: Double) = CameraIntrinsics(1640, 1232, fx, fx, 820.0, 616.0)

    private fun det(area: Int, aspect: Float) = Detection(
        x = 10.0, y = 10.0, flux = 1.0, peak = 0.5, aspect = aspect.toDouble(), area = area,
        saturated = false,
    )

    @Test
    fun `广角放宽星点面积与长宽比`() {
        val wide = cam(719.5)
        assertTrue(StarSolveParams.isWide(wide))
        assertTrue("area=2 的小星点在广角应接受", StarSolveParams.accept(det(2, 1.0f), wide))
        assertTrue("aspect 2.5 在广角应接受", StarSolveParams.accept(det(4, 2.5f), wide))
        assertFalse("aspect 3.0 应拒绝", StarSolveParams.accept(det(4, 3.0f), wide))
        assertEquals(6.5, StarSolveParams.triangleMag(wide), 1e-9)
        assertEquals(60, StarSolveParams.maxImageStars(wide))
        assertTrue(StarSolveParams.thresholds(wide).any { it == 5.0 })
    }

    @Test
    fun `主摄保持原参数`() {
        val main = cam(1468.9)
        assertFalse(StarSolveParams.isWide(main))
        assertFalse("主摄 area=2 仍拒绝", StarSolveParams.accept(det(2, 1.0f), main))
        assertTrue(StarSolveParams.accept(det(3, 1.0f), main))
        assertEquals(5.0, StarSolveParams.triangleMag(main), 1e-9)
        assertEquals(30, StarSolveParams.maxImageStars(main))
        assertFalse(StarSolveParams.thresholds(main).any { it == 5.0 })
    }
}
