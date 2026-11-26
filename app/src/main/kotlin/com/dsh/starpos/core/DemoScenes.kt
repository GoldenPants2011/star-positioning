package com.dsh.starpos.core

import com.dsh.starpos.astro.DetectionResult
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.GravityEstimate
import com.dsh.starpos.astro.GravityEstimator
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.SyntheticFrame
import com.dsh.starpos.astro.SyntheticScene
import com.dsh.starpos.astro.SyntheticSky
import com.dsh.starpos.astro.DEG
import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.V
import kotlin.math.*
import kotlin.random.Random

class DemoRun(
    val scene: SyntheticScene,
    val frame: SyntheticFrame,
    val detections: DetectionResult,
    val gravity: GravityEstimate,
    val clock: ClockStatus,
    val outcome: Pipeline.Output,
)

/**
 * 合成数据自检（NFR-04）：真值已知，可验证偏差与门控。
 * 每次运行都重新渲染图像，走完整流水线（检测 → 识别 → 姿态 → 位置）。
 */
class DemoRunner(
    private val catalog: StarCatalog,
    val camera: CameraIntrinsics,
) {
    private val index = SkyIndex(catalog)
    private val pipeline = Pipeline(PlateSolver(index, camera))

    fun run(scene: SyntheticScene, clock: ClockStatus, gravitySigmaDeg: Double = 0.1): DemoRun {
        val frame = SyntheticSky.render(catalog, scene)
        val detections = FrameAnalyzer.analyze(frame.image, frame.width, frame.height)
        val gravity = simulateGravity(frame.truthUpCam, gravitySigmaDeg, seed = scene.seed)
        val outcome = pipeline.solve(
            Pipeline.Input(
                detections = detections.detections,
                quality = detections.quality,
                gravity = gravity,
                clock = clock,
                context = frame.context,
                mode = SolveMode.STAR,
                ephemerisAgeDays = 0.0,
            ),
        )
        return DemoRun(scene, frame, detections, gravity, clock, outcome)
    }

    companion object {
        /** 默认四个用例：两个正常、一个厚云、一个时间陈旧（对应 §9.5 新增用例）。 */
        fun defaultScenes(): List<SyntheticScene> = listOf(
            SyntheticScene(
                latDeg = 31.2304, lonDeg = 121.4737, utcMillis = 1763136000000L,
                tiltXDeg = 3.0, tiltYDeg = -2.0, seed = 42L,
            ),
            SyntheticScene(
                latDeg = 45.7500, lonDeg = 126.6300, utcMillis = 1763136000000L,
                tiltXDeg = -6.0, tiltYDeg = 4.0, seed = 77L,
            ),
            SyntheticScene(
                latDeg = 31.2304, lonDeg = 121.4737, utcMillis = 1763136000000L,
                tiltXDeg = 1.0, tiltYDeg = 1.0, cloudCover = 0.9, seed = 99L,
            ),
            SyntheticScene(
                latDeg = -33.8688, lonDeg = 151.2093, utcMillis = 1763136000000L,
                tiltXDeg = 2.0, tiltYDeg = 3.0, seed = 123L,
            ),
        )

        /** 按需求 §3 的高斯噪声模型注入重力倾角误差。 */
        fun simulateGravity(upCam: DoubleArray, sigmaDeg: Double, seed: Long, samples: Int = 24): GravityEstimate {
            val rnd = Random(seed)
            val sigmaRad = sigmaDeg * DEG
            // 构造与 up 垂直的基
            val helper = if (abs(upCam[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
            val e1 = V.unit(V.cross(upCam, helper))
            val e2 = V.cross(upCam, e1)
            val g = 9.81
            val accel = ArrayList<DoubleArray>(samples)
            val gyro = ArrayList<Double>(samples)
            repeat(samples) {
                val a = gauss(rnd) * sigmaRad
                val b = gauss(rnd) * sigmaRad
                val tilted = V.unit(V.add(upCam, V.add(V.scale(e1, a), V.scale(e2, b))))
                accel.add(V.scale(tilted, g))   // 比力：静止时 f ≈ +g·up（Android 约定）
                gyro.add(abs(gauss(rnd)) * 0.01)
            }
            return GravityEstimator.estimate(accel, gyro)
        }

        private fun gauss(rnd: Random): Double {
            var u = rnd.nextDouble()
            if (u < 1e-12) u = 1e-12
            val v = rnd.nextDouble()
            return sqrt(-2.0 * ln(u)) * cos(2 * PI * v)
        }
    }
}
