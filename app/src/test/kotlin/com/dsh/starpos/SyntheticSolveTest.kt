// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.core.DemoRun
import com.dsh.starpos.core.DemoRunner
import com.dsh.starpos.core.syncedClockStatus
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** 共享夹具：星表只加载一次，索引只构建一次。 */
object Fixtures {
    val catalog: StarCatalog by lazy {
        val candidates = listOf(
            "src/main/assets/stars.bin",
            "app/src/main/assets/stars.bin",
            "../app/src/main/assets/stars.bin",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 stars.bin，工作目录=${File(".").absolutePath}")
        StarCatalog.fromFile(file)
    }
    val camera = CameraIntrinsics.fromFov(1080, 1440, 30.0)
    val runner: DemoRunner by lazy { DemoRunner(catalog, camera) }
}

class SyntheticSolveTest {

    private fun runScene(index: Int, staleClock: Boolean = false): DemoRun {
        val scene = DemoRunner.defaultScenes()[index]
        val clock = if (staleClock) syncedClockStatus(offlineHours = 240.0, stale = true)
        else syncedClockStatus(uncertaintyMs = 250.0)
        return Fixtures.runner.run(scene, clock, gravitySigmaDeg = 0.1)
    }

    @Test
    fun `星表规模与亮度分布合理`() {
        assertTrue("星表应包含 >10000 颗 mag<=7 的星，实际 ${Fixtures.catalog.size}", Fixtures.catalog.size > 10_000)
        val bright = Fixtures.catalog.brighterThan(1.6)
        assertTrue("应有天狼星级别的亮星，实际 ${bright.size}", bright.isNotEmpty())
        val firstMag = Fixtures.catalog.vmag(bright.first())
        assertTrue("最亮星应 <= 1.5 等，实际 $firstMag", firstMag <= 1.5)
    }

    @Test
    fun `T0 姿态精度达标（3σ ≤ 0_02°）`() {
        val run = runScene(0)
        val plate = run.outcome.plate
        assertNotNull("应能解出姿态，实际原因=${run.outcome.result.reason}", plate)
        val err = rotationErrorDeg(plate!!.rotation, run.frame.truthRotation)
        println("T0 姿态误差 = $err°，姿态 1σ = ${plate.attitudeSigmaDeg}°，匹配 ${plate.matchedStars} 颗")
        assertTrue("姿态 3σ 应 <= 0.02°，实际 3σ=${3 * plate.attitudeSigmaDeg}", 3 * plate.attitudeSigmaDeg <= 0.02)
        assertTrue("与真值旋转误差应 <= 0.02°，实际 $err", err <= 0.02)
    }

    @Test
    fun `T1 定位误差在 50 km 内`() {
        val run = runScene(0)
        val r = run.outcome.result
        assertTrue("应为 FIX，实际 ${r.status}/${r.reason}", r.isFix())
        val pos = r.position!!
        val dLatKm = (pos.latDeg - run.scene.latDeg) * 111.32
        val dLonKm = (pos.lonDeg - run.scene.lonDeg) * 111.32 * cos(Math.toRadians(run.scene.latDeg))
        val err = sqrt(dLatKm * dLatKm + dLonKm * dLonKm)
        println("T1 定位误差 = $err km，椭圆 = ${r.ellipse?.semiMajorM?.div(1000)} × ${r.ellipse?.semiMinorM?.div(1000)} km")
        assertTrue("定位误差应 <= 50 km，实际 $err", err <= 50.0)
        assertTrue("应给出 95% 椭圆", r.ellipse != null && r.ellipse!!.semiMajorM > 0)
        assertTrue("置信度应 >= 0.5", r.confidence >= 0.5)
    }

    @Test
    fun `南半球场景也能解算`() {
        val run = runScene(3)
        val r = run.outcome.result
        assertTrue("南纬场景应 FIX，实际 ${r.status}/${r.reason}", r.isFix())
        assertTrue("纬度应为负", r.position!!.latDeg < 0)
    }

    @Test
    fun `厚云必须 NO_FIX`() {
        val run = runScene(2)
        val r = run.outcome.result
        println("厚云用例 -> ${r.status} reason=${r.reason} cloud=${r.cloudCover}")
        assertTrue("厚云必须 NO_FIX", !r.isFix())
        assertTrue("必须给出坐标外的解释", r.reason != null)
        assertTrue("不得返回坐标", r.position == null)
    }

    @Test
    fun `时间陈旧必须 NO_FIX 且禁止 T1`() {
        val run = runScene(0, staleClock = true)
        val r = run.outcome.result
        println("陈旧时间用例 -> ${r.status} reason=${r.reason}")
        assertTrue("STALE_TIME 必须 NO_FIX", !r.isFix())
        assertTrue("应为 STALE_TIME，实际 ${r.reason}", r.reason == "STALE_TIME")
        assertTrue("不得返回坐标", r.position == null)
        assertTrue("T0 姿态仍应保留作为诊断", run.outcome.plate != null)
    }

    @Test
    fun `场地外移动（非静止）不得出解`() {
        val scene = DemoRunner.defaultScenes()[0]
        val frame = com.dsh.starpos.astro.SyntheticSky.render(Fixtures.catalog, scene)
        val det = com.dsh.starpos.astro.FrameAnalyzer.analyze(frame.image, frame.width, frame.height)
        // 手持抖动：accel 模值波动大、陀螺角速度大
        val accel = (0 until 24).map {
            doubleArrayOf(0.5 * (it % 5), 9.81, 0.3 * (it % 3))
        }
        val gyro = (0 until 24).map { 1.2 }
        val gravity = com.dsh.starpos.astro.GravityEstimator.estimate(accel, gyro)
        val pipeline = com.dsh.starpos.core.Pipeline(
            com.dsh.starpos.astro.PlateSolver(com.dsh.starpos.astro.SkyIndex(Fixtures.catalog), Fixtures.camera),
        )
        val out = pipeline.solve(
            com.dsh.starpos.core.Pipeline.Input(
                detections = det.detections, quality = det.quality, gravity = gravity,
                clock = syncedClockStatus(), context = frame.context,
            ),
        )
        assertTrue("运动时不得出解", !out.result.isFix())
        assertTrue("原因应为 NOT_STATIC，实际 ${out.result.reason}", out.result.reason == "NOT_STATIC")
        assertTrue("应提示请静止", !gravity.isStatic)
    }

    private fun rotationErrorDeg(recovered: Array<DoubleArray>, truth: Array<DoubleArray>): Double {
        // R_err = R_rec · R_truthᵀ，取转角
        val rt = Array(3) { i -> DoubleArray(3) { j -> truth[j][i] } }
        val r = Array(3) { i -> DoubleArray(3) { j ->
            (0..2).sumOf { k -> recovered[i][k] * rt[k][j] }
        } }
        val trace = r[0][0] + r[1][1] + r[2][2]
        return Math.toDegrees(kotlin.math.acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `JSON 输出包含需求字段`() {
        val run = runScene(0)
        val json = run.outcome.result.toJson()
        listOf(
            "\"status\"", "\"tier\"", "\"mode\"", "\"timestamp_utc\"", "\"time_uncertainty_ms\"",
            "\"last_sync_utc\"", "\"offline_hours\"", "\"ephemeris_age_days\"", "\"position\"",
            "\"position_ellipse_95\"", "\"attitude_icrs\"", "\"attitude_sigma_deg\"",
            "\"gravity_sigma_deg\"", "\"stars_used\"", "\"cloud_cover\"", "\"confidence\"", "\"warnings\"",
        ).forEach { key -> assertTrue("JSON 缺少 $key", json.contains(key)) }
        assertTrue("JSON 应为合法结构", json.trimEnd().endsWith("}"))
    }

    @Test
    fun `误差预算覆盖主要误差源`() {
        val run = runScene(0)
        val budget = run.outcome.budget
        assertTrue("FIX 时应给出误差预算", budget.isNotEmpty())
        assertTrue("应包含姿态与重力项", budget.any { it.source.contains("姿态") } && budget.any { it.source.contains("加速度计") })
        val total = com.dsh.starpos.core.ErrorBudget.totalSigmaMeters(budget)
        assertTrue("总误差量级应在 1–100 km，实际 ${total / 1000} km", total in 1000.0..100_000.0)
    }
}
