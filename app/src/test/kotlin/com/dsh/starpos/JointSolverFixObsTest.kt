// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.field.JointSolver
import kotlin.math.abs
import org.junit.Test

/**
 * FixObs（星光/两体 2D 位置观测）并入联合解的合成验证：
 *  - 用高度观测把解算约束在真值附近；
 *  - 加入带协方差/方位的 2D 位置观测，验证加权与各向异性（长轴方向）；
 *  - 无真值时结果不输出 T 级。
 */
class JointSolverFixObsTest {

    private val truthLat = Math.toRadians(30.0)
    private val truthLon = Math.toRadians(120.0)

    private fun altObs(timesMin: List<Long>, sigmaDeg: Double = 0.2): List<JointSolver.AltObs> =
        timesMin.mapIndexed { i, m ->
            val t = 1_763_970_000_000L + m * 60_000L
            val o = JointSolver.AltObs(t, JointSolver.Body.SUN, "太阳", 0.0, Math.toRadians(sigmaDeg), "alt-$i")
            o.copy(apparentAltRad = JointSolver.modelAltRad(truthLat, truthLon, t, o))
        }

    @Test
    fun `FixObs 合成约束与各向异性`() {
        val alt = altObs(listOf(0L, 60L, 150L))
        val seedLat = truthLat + Math.toRadians(0.3)
        val seedLon = truthLon + Math.toRadians(0.3)

        // 1) 仅高度观测：应回到真值附近
        val r1 = JointSolver.solve(
            alt, options = JointSolver.Options(initialLatRad = seedLat, initialLonRad = seedLon),
            truthLatRad = truthLat, truthLonRad = truthLon,
        )
        requireNotNull(r1)
        println("仅高度：lat=%.5f lon=%.5f 误差=%.2f km".format(r1.latDeg, r1.lonDeg, r1.truthErrorKm!!))

        // 2) 高度 + 一个 20 km 北偏的 FixObs（sigma 5 km，圆）：结果应被拉向 FixObs 一侧
        val fix20 = JointSolver.FixObs(
            latRad = truthLat + Math.toRadians(20.0 / 111.0), lonRad = truthLon,
            sigmaMajorM = 5_000.0, sigmaMinorM = 5_000.0, azimuthDeg = 0.0, session = "star-fix",
        )
        val r2 = JointSolver.solve(
            alt, listOf(fix20), options = JointSolver.Options(initialLatRad = seedLat, initialLonRad = seedLon),
            truthLatRad = truthLat, truthLonRad = truthLon,
        )
        requireNotNull(r2)
        println("高度+20km北 FixObs：lat=%.5f 误差=%.2f km（应介于 0~20 km）".format(r2.latDeg, r2.truthErrorKm!!))
        require(r2.truthErrorKm!! in 10.0..20.5) { "FixObs 未按权重拉动" }

        // 3) 各向异性：同样 20 km 北偏，但长轴沿北（sigma_north=20 km, sigma_east=0.5 km）
        //    北向几乎不约束、东向强约束；解应主要在南北方向不可信，但东西方向贴近真值
        val aniso = JointSolver.FixObs(
            latRad = truthLat + Math.toRadians(20.0 / 111.0), lonRad = truthLon,
            sigmaMajorM = 20_000.0, sigmaMinorM = 500.0, azimuthDeg = 0.0, session = "star-fix-aniso",
        )
        val r3 = JointSolver.solve(
            alt, listOf(aniso), options = JointSolver.Options(initialLatRad = seedLat, initialLonRad = seedLon),
            truthLatRad = truthLat, truthLonRad = truthLon,
        )
        requireNotNull(r3)
        val dLonM = Math.toDegrees(Math.abs(r3.lonDeg * Math.PI / 180 - truthLon)) * 111_000.0 * Math.cos(truthLat)
        println("各向异性 FixObs：lat=%.5f 误差=%.2f km · 经向偏差≈%.2f km".format(r3.latDeg, r3.truthErrorKm!!, dLonM))
        // 长轴沿南北 => 经向（东向）约束强，东西偏差应明显小于 20 km
        require(abs(dLonM) < 10_000.0) { "各向异性权重未生效" }

        // 4) 无真值模式：不给 T 级、不给误差
        val r4 = JointSolver.solve(alt, listOf(fix20), options = JointSolver.Options(initialLatRad = seedLat, initialLonRad = seedLon))
        requireNotNull(r4)
        require(r4.tier == null && r4.truthErrorKm == null) { "无真值不应输出 T 级/误差" }
        println("无真值模式：椭圆 95%% = %.1f/%.1f km，T 级=%s（应为空）".format(r4.major95M / 1000, r4.minor95M / 1000, r4.tier))
    }
}
