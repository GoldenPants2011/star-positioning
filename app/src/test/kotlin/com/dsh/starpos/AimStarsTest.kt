package com.dsh.starpos

import com.dsh.starpos.ui.AimStars
import org.junit.Test

class AimStarsTest {
    @Test
    fun `可见星估计随光污染和俯仰变化`() {
        val high = AimStars.visibleStars(55.0, 42.0, 80.0, 3)
        val city = AimStars.visibleStars(55.0, 42.0, 80.0, 9)
        require(high > city) { "暗天空应比城市看到更多星：$high vs $city" }
        require(city > 0) { "城市也应 >0" }
        require(AimStars.visibleStars(55.0, 42.0, -80.0, 5) == 0) { "地平面下应为 0" }
        val half = AimStars.visibleStars(55.0, 42.0, 0.0, 5)
        val full = AimStars.visibleStars(55.0, 42.0, 90.0, 5)
        require(half in 1..full) { "平视应约为仰视一半：$half / $full" }
        println("Bortle3 仰视80°=$high，Bortle9=$city，平视=$half，仰视90°=$full")
    }
}
