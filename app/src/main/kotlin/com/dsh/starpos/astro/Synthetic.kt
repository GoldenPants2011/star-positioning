package com.dsh.starpos.astro

import kotlin.math.*
import kotlin.random.Random

/**
 * 合成星空场景（需求 NFR-04：支持注入已知 R / 位置 / 时间 + 噪声）。
 * 这是 M1 的验收手段：真值完全已知，可做 χ² 与偏差检验。
 */
class SyntheticScene(
    val width: Int = 1080,
    val height: Int = 1440,
    val fovXDeg: Double = 30.0,
    /** WGS84 大地纬度/经度（度）。 */
    val latDeg: Double = 31.2304,
    val lonDeg: Double = 121.4737,
    val utcMillis: Long = 1763136000000L,
    val tiltXDeg: Double = 3.0,
    val tiltYDeg: Double = -2.0,
    val exposureMs: Double = 8000.0,
    val cloudCover: Double = 0.0,
    val magLimit: Double = 7.0,
    val seed: Long = 42L,
    val projection: Projection = Projection.PINHOLE,
)

class SyntheticFrame(
    val image: FloatArray,
    val width: Int,
    val height: Int,
    /** 真值：相机 → ICRS 旋转（列 = 相机轴在 ICRS 中的方向）。 */
    val truthRotation: Array<DoubleArray>,
    /** 真值：天顶在相机坐标系中的单位向量。 */
    val truthUpCam: DoubleArray,
    val context: SkyContext,
    val latDeg: Double,
    val lonDeg: Double,
    val camera: CameraIntrinsics,
    val scene: SyntheticScene,
    val starsRendered: Int,
)

object SyntheticSky {

    private const val WGS84_E2 = 6.69437999014e-3

    fun render(catalog: StarCatalog, scene: SyntheticScene): SyntheticFrame {
        val rnd = Random(scene.seed)
        val camera = CameraIntrinsics.fromFov(scene.width, scene.height, scene.fovXDeg, scene.projection)
        val context = SkyContext.at(scene.utcMillis)

        // 天顶方向（ICRS）：RA = GAST + 经度，Dec = 地心纬度
        val latRad = scene.latDeg * DEG
        val lonRad = scene.lonDeg * DEG
        val geocentricLat = atan((1 - WGS84_E2) * tan(latRad))
        val zenith = V.fromRaDec(norm2pi(context.gast + lonRad), geocentricLat)

        // 相机基：x=东, y=北, z=天顶，再叠加小角度倾斜
        val east = V.unit(V.cross(doubleArrayOf(0.0, 0.0, 1.0), zenith))
        val north = V.cross(zenith, east)
        val r0 = arrayOf(
            doubleArrayOf(east[0], north[0], zenith[0]),
            doubleArrayOf(east[1], north[1], zenith[1]),
            doubleArrayOf(east[2], north[2], zenith[2]),
        )
        val rotation = mul3(
            r0,
            rotX(-scene.tiltXDeg * DEG),
            rotY(-scene.tiltYDeg * DEG),
        )
        val upCam = apply(transpose(rotation), zenith)

        val image = FloatArray(scene.width * scene.height) { 0.03f }
        var rendered = 0
        for (i in 0 until catalog.size) {
            if (catalog.vmag(i) > scene.magLimit) continue
            val vIcr = catalog.icrs(i, context.jdTt)
            val vApp = Refraction.apparent(vIcr, zenith, 1010.0, 10.0)
            val u = apply(transpose(rotation), vApp)
            val px = camera.project(u) ?: continue
            val x = px[0]; val y = px[1]
            if (x < 0 || y < 0 || x >= scene.width || y >= scene.height) continue
            // 压缩的动态范围：合成测试关注几何精度，不追求真实测光；
            // 保证 1.5 等 ≈ 0.9 峰值、6.9 等 ≈ 0.1 峰值（≈5σ 阈值），避免大面积饱和与粘连。
            val peak = 0.9 * 10.0.pow(-0.18 * (catalog.vmag(i) - 1.5))
            if (peak < 0.10) continue
            addPsf(image, scene.width, scene.height, x, y, peak, sigmaPx = 1.15, cloud = scene.cloudCover)
            rendered++
        }
        // 背景噪声（含读出噪声与暗电流级别），云层额外抬高背景
        val bg = 0.03 + 0.05 * scene.cloudCover
        val sigma = 0.008 + 0.02 * scene.cloudCover
        for (k in image.indices) {
            val v = image[k] + bg * 0.0 + (rnd.nextDouble() * 2 - 1) * sigma * 1.7
            image[k] = v.coerceIn(0.0, 1.0).toFloat()
        }
        // 曝光噪声：亮星峰值限制在 1.0（饱和）
        for (k in image.indices) if (image[k] > 1f) image[k] = 1f

        return SyntheticFrame(
            image = image,
            width = scene.width,
            height = scene.height,
            truthRotation = rotation,
            truthUpCam = upCam,
            context = context,
            latDeg = scene.latDeg,
            lonDeg = scene.lonDeg,
            camera = camera,
            scene = scene,
            starsRendered = rendered,
        )
    }

    private fun addPsf(
        image: FloatArray, w: Int, h: Int,
        cx: Double, cy: Double, peak: Double, sigmaPx: Double, cloud: Double,
    ) {
        val attenuation = 1.0 - 0.85 * cloud
        val a = peak * attenuation
        if (a <= 0) return
        val radius = (3 * sigmaPx).toInt() + 1
        val x0 = max(0, floor(cx).toInt() - radius)
        val x1 = min(w - 1, ceil(cx).toInt() + radius)
        val y0 = max(0, floor(cy).toInt() - radius)
        val y1 = min(h - 1, ceil(cy).toInt() + radius)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val v = a * exp(-(dx * dx + dy * dy) / (2 * sigmaPx * sigmaPx))
                val k = y * w + x
                image[k] = (image[k] + v.toFloat()).coerceAtMost(1f)
            }
        }
    }
}
