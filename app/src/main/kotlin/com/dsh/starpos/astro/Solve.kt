package com.dsh.starpos.astro

import kotlin.math.*
import org.apache.commons.math3.linear.SingularValueDecomposition

/** Wahba 问题的 SVD 解（Davenport / Kabsch）。 */
object Attitude {

    fun det3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    /**
     * 求旋转 R 使 sky ≈ R·cam（最小二乘）。
     * A = Σ w·v·uᵀ，SVD A = U S Vᵀ，R = U·diag(1,1,det(U·Vᵀ))·Vᵀ。
     */
    fun wahba(cam: List<DoubleArray>, sky: List<DoubleArray>, weights: DoubleArray? = null): Array<DoubleArray> {
        require(cam.size == sky.size) { "cam/sky 数量不一致" }
        require(cam.size >= 2) { "至少需要 2 对匹配才能定姿态" }
        val a = Array(3) { DoubleArray(3) }
        for (k in cam.indices) {
            val w = weights?.get(k) ?: 1.0
            val u = cam[k]
            val v = sky[k]
            for (i in 0..2) for (j in 0..2) a[i][j] += w * v[i] * u[j]
        }
        val svd = SingularValueDecomposition(toRealMatrix(a))
        val uu = fromRealMatrix(svd.u)
        val vv = fromRealMatrix(svd.v)
        val d = if (det3(uu) * det3(vv) < 0) -1.0 else 1.0
        val r = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) {
                val dd = if (k == 2) d else 1.0
                s += uu[i][k] * dd * vv[j][k]
            }
            r[i][j] = s
        }
        return r
    }

    fun residualsRad(r: Array<DoubleArray>, cam: List<DoubleArray>, sky: List<DoubleArray>): DoubleArray =
        DoubleArray(cam.size) { i -> V.angle(apply(r, cam[i]), sky[i]) }

    fun rmsDeg(r: Array<DoubleArray>, cam: List<DoubleArray>, sky: List<DoubleArray>): Double {
        val res = residualsRad(r, cam, sky)
        val meanSq = res.sumOf { it * it } / res.size
        return Math.toDegrees(sqrt(meanSq))
    }

    /** 姿态 1σ 不确定度（度）：sqrt(Σr²/(n−3))。 */
    fun sigmaDeg(r: Array<DoubleArray>, cam: List<DoubleArray>, sky: List<DoubleArray>): Double {
        if (cam.size <= 3) return 0.05
        val res = residualsRad(r, cam, sky)
        val dof = (cam.size - 3).coerceAtLeast(1)
        val s = sqrt(res.sumOf { it * it } / dof)
        return Math.toDegrees(s)
    }
}

class StarMatch(val detectionIndex: Int, val catalogIndex: Int, val residualArcsec: Double)

class PlateSolution(
    /** 相机 → 真赤道（观测方向，已含折射改正）旋转。 */
    val rotation: Array<DoubleArray>,
    val matchedStars: Int,
    val rmsArcsec: Double,
    val attitudeSigmaDeg: Double,
    val matches: List<StarMatch>,
)

/**
 * 星图识别：三角哈希 + RANSAC 式假设检验（需求 FR-04）。
 *
 * 要点：
 *  - 三角形用弦长比例（r1,r2）索引，对尺度/投影/旋转/平移不变；
 *  - 图像侧与星表侧使用同一规范化规则，可直接对应，另测 a/b 交换以容忍镜像；
 *  - 每个假设用全部检测星验证内点数（星表星不重复计数），取最优并做 3 轮最小二乘精化。
 */
class PlateSolver(
    private val index: SkyIndex,
    private val camera: CameraIntrinsics,
    private val bucket: Double = 0.02,
    /**
     * 参与三角形索引的星等上限。默认 5.0（宽视场、靠最亮星定位）；
     * 窄视场（<10°）里 mag≤5 的星可能只有几颗，需要调到 7.0 让更多星参与。
     */
    private val triangleMagLimit: Double = 5.0,
    /**
     * 假设检验的残差上限（角秒）：超过它的匹配视为假星群。
     * 真实窄视场照片里，假匹配的残差常在 60–100″，而真解在 3–10″，这个判据比"内点数"更可靠。
     */
    private val maxResidualArcsec: Double = 60.0,
) {
    private val skyTriangles: HashMap<Long, MutableList<Triangle>> = HashMap(1 shl 14)

    /** 诊断统计（UI/测试可读）。 */
    val stats = LinkedHashMap<String, Int>()

    init {
        buildSkyIndex()
    }

    /**
     * 星表侧三角形只在“亮星子集”上构造（mag ≤ 5.0）：
     * 图像里能稳定取到的最亮若干颗，对应的正是这些亮星；若在全部星上做近邻，
     * 构造出的三角形会大量由暗星构成，和图像侧根本对不上（这是宽视场匹配失败的主因）。
     */
    private fun buildSkyIndex() {
        val brightIds = index.indicesBrighterThan(triangleMagLimit)
        val vectors = brightIds.map { index.vectorOf(it) }
        for (a in brightIds.indices) {
            // 亮星子集很小（约 1.5k），直接暴力取 4 近邻，避免网格索引在小子集上的边界问题
            val dists = ArrayList<Pair<Double, Int>>(vectors.size)
            for (b in brightIds.indices) {
                if (a == b) continue
                dists.add(Pair(V.angle(vectors[a], vectors[b]), b))
            }
            dists.sortBy { it.first }
            val nn = dists.take(4).map { it.second }
            if (nn.size < 3) continue
            for (x in 0 until nn.size - 2) {
                for (y in x + 1 until nn.size - 1) {
                    for (z in y + 1 until nn.size) {
                        val tri = Triangles.make(
                            brightIds[nn[x]], brightIds[nn[y]], brightIds[nn[z]],
                            vectors[nn[x]], vectors[nn[y]], vectors[nn[z]],
                        )
                        if (tri.r1 < 0.12 || tri.maxSide < 0.3 * DEG) continue
                        val list = skyTriangles.getOrPut(tri.key(bucket)) { ArrayList(4) }
                        if (list.size < 64) list.add(tri)
                    }
                }
            }
        }
    }

    fun solve(
        detections: List<Detection>,
        maxImageStars: Int = 30,
        maxHypotheses: Int = 20000,
        matchToleranceDeg: Double = 0.08,
        /** 若提供天顶方向（相机系），则先反解大气折射，把观测方向变回真方向（FR-05）。 */
        upCam: DoubleArray? = null,
        pressureHpa: Double = 1010.0,
        tempC: Double = 10.0,
    ): PlateSolution? {
        if (detections.size < 4) return null
        stats.clear()
        val usable = detections.take(maxImageStars)
        val fluxOf = DoubleArray(usable.size) { usable[it].flux.coerceAtLeast(1e-3) }
        val camVecsRaw = usable.map { camera.unproject(it.x, it.y) }
        val camVecs = if (upCam == null) camVecsRaw else camVecsRaw.map {
            Refraction.trueDirection(it, upCam, pressureHpa, tempC)
        }

        // 图像侧三角形：
        //  (a) 最亮的若干颗星两两组合 —— 它们几乎必然在星表索引内，最可靠；
        //  (b) 每颗星与最近邻的组合 —— 增加覆盖率，但在稀疏/暗星场里可能落空。
        val imageTriangles = ArrayList<Triangle>(512)
        val brightCount = min(12, usable.size)
        for (i in 0 until brightCount - 2) {
            for (j in i + 1 until brightCount - 1) {
                for (k in j + 1 until brightCount) {
                    imageTriangles.add(Triangles.make(i, j, k, camVecs[i], camVecs[j], camVecs[k]))
                }
            }
        }
        for (i in usable.indices) {
            val order = (0 until usable.size)
                .filter { it != i }
                .sortedBy { k -> V.angle(camVecs[i], camVecs[k]) }
            if (order.size < 3) continue
            val k1 = order[0]; val k2 = order[1]; val k3 = order[2]
            imageTriangles.add(Triangles.make(i, k1, k2, camVecs[i], camVecs[k1], camVecs[k2]))
            imageTriangles.add(Triangles.make(i, k2, k3, camVecs[i], camVecs[k2], camVecs[k3]))
            imageTriangles.add(Triangles.make(i, k1, k3, camVecs[i], camVecs[k1], camVecs[k3]))
        }
        // 大三角形几何约束更强、比例更有辨识度，优先尝试
        imageTriangles.removeAll { it.r1 < 0.10 || it.maxSide < 0.3 * DEG }
        imageTriangles.sortByDescending { it.maxSide }

        val tol = matchToleranceDeg * DEG
        var best: Array<DoubleArray>? = null
        var bestInliers: IntArray = IntArray(0)
        var hypotheses = 0
        var candidatesTotal = 0
        var consistentTotal = 0

        outer@ for (tri in imageTriangles) {
            val candidates = ArrayList<Triangle>(8)
            val i0 = (tri.r1 / bucket).toInt()
            val j0 = (tri.r2 / bucket).toInt()
            val s0 = tri.sizeBucket()
            for (ds in -1..1) {
                val s = s0 + ds
                if (s < 0 || s > 7) continue
                for (di in -1..1) for (dj in -1..1) {
                    val key = (s.toLong() * 1_000_003L + (i0 + di)) * 1_000_003L + (j0 + dj)
                    skyTriangles[key]?.let { candidates.addAll(it) }
                }
            }
            candidatesTotal += candidates.size
            // 尺度预筛：相机焦距已知，三角形最长边的角距必须对得上，先砍掉绝大多数候选
            val scaleTol = 2.5 * tol + 0.15 * tri.maxSide
            for (cand in candidates) {
                if (abs(cand.maxSide - tri.maxSide) > scaleTol) continue
                for (swap in booleanArrayOf(false, true)) {
                    hypotheses++
                    if (hypotheses > maxHypotheses) break@outer
                    val imgIdx = intArrayOf(tri.a, tri.b, tri.c)
                    val skyIdx = if (swap) intArrayOf(cand.b, cand.a, cand.c)
                    else intArrayOf(cand.a, cand.b, cand.c)
                    var consistent = true
                    for (p in 0 until 3) for (q in p + 1 until 3) {
                        val dImg = V.angle(camVecs[imgIdx[p]], camVecs[imgIdx[q]])
                        val dSky = V.angle(index.vectorOf(skyIdx[p]), index.vectorOf(skyIdx[q]))
                        if (abs(dImg - dSky) > 2.5 * tol) consistent = false
                    }
                    if (!consistent) continue
                    consistentTotal++
                    val cam3 = listOf(camVecs[imgIdx[0]], camVecs[imgIdx[1]], camVecs[imgIdx[2]])
                    val sky3 = listOf(
                        index.vectorOf(skyIdx[0]), index.vectorOf(skyIdx[1]), index.vectorOf(skyIdx[2]),
                    )
                    val r = try { Attitude.wahba(cam3, sky3) } catch (e: Exception) { continue }
                    val (inliers, rmsRad) = matchInliersWithRms(r, camVecs, tol)
                    // 残差检验：假星群的内点也能凑够 4–10 颗，但残差会很大
                    if (inliers.size < 4 || rmsRad * 3600 / DEG > maxResidualArcsec) continue
                    if (inliers.size > bestInliers.size) {
                        best = r
                        bestInliers = inliers
                        if (inliers.size >= 25) break@outer
                    }
                }
            }
        }
        stats["imageTriangles"] = imageTriangles.size
        stats["skyTriangles"] = skyTriangles.size
        stats["buckets"] = skyTriangles.size
        stats["candidates"] = candidatesTotal
        stats["consistent"] = consistentTotal
        stats["hypotheses"] = hypotheses
        stats["bestInliers"] = bestInliers.size

        var rot = best ?: return null
        var inliers = bestInliers
        if (inliers.size < 4) return null

        // 精化：先按当前 R 把检测星配到星表星，再用这些配对做最小二乘（配对必须成对存在）
        fun correspondences(r: Array<DoubleArray>, tolRad: Double): List<Pair<Int, Int>> {
            val used = HashSet<Int>()
            val out = ArrayList<Pair<Int, Int>>()
            for (d in camVecs.indices) {
                val hit = index.nearest(apply(r, camVecs[d]), tolRad)
                if (hit >= 0 && used.add(hit)) out.add(Pair(d, hit))
            }
            return out
        }

        repeat(3) {
            val pairs = correspondences(rot, tol)
            if (pairs.size < 4) return@repeat
            val w = pairs.map { fluxOf[it.first] }.toDoubleArray()
            rot = Attitude.wahba(
                pairs.map { camVecs[it.first] },
                pairs.map { index.vectorOf(it.second) },
                w,
            )
            inliers = pairs.map { it.first }.toIntArray()
        }

        // 粗差剔除：按残差中位数 3 倍截断后用亮度加权重解（亮星质心更准，FR-09 置信度也依赖于此）
        var pairs = correspondences(rot, tol * 0.5)
        if (pairs.size >= 4) {
            val residuals = pairs.map { (d, c) ->
                V.angle(apply(rot, camVecs[d]), index.vectorOf(c))
            }.sorted()
            val median = residuals[residuals.size / 2]
            val limit = (3.0 * median).coerceAtLeast(1e-6)
            val clipped = pairs.filterIndexed { k, _ ->
                V.angle(apply(rot, camVecs[pairs[k].first]), index.vectorOf(pairs[k].second)) <= limit
            }
            if (clipped.size >= 4) pairs = clipped
        }
        if (pairs.size < 4) return null
        val camList = pairs.map { camVecs[it.first] }
        val skyList = pairs.map { index.vectorOf(it.second) }
        val weights = pairs.map { fluxOf[it.first] }.toDoubleArray()
        rot = Attitude.wahba(camList, skyList, weights)
        val rmsDeg = Attitude.rmsDeg(rot, camList, skyList)
        val sigmaDeg = Attitude.sigmaDeg(rot, camList, skyList)
        val matches = pairs.map { (detIdx, catIdx) ->
            StarMatch(
                detectionIndex = detIdx,
                catalogIndex = index.ids[catIdx],
                residualArcsec = radToArcsec(V.angle(apply(rot, camVecs[detIdx]), index.vectorOf(catIdx))),
            )
        }
        return PlateSolution(
            rotation = rot,
            matchedStars = pairs.size,
            rmsArcsec = rmsDeg * 3600.0,
            attitudeSigmaDeg = sigmaDeg,
            matches = matches,
        )
    }

    /** 找内点并返回残差 RMS（弧度）：用于剔除"内点够多但残差很大"的假星群。 */
    private fun matchInliersWithRms(
        r: Array<DoubleArray>,
        camVecs: List<DoubleArray>,
        tolRad: Double,
    ): Pair<IntArray, Double> {
        val out = ArrayList<Int>(camVecs.size)
        val used = HashSet<Int>(camVecs.size)
        val residuals = ArrayList<Double>(camVecs.size)
        for (i in camVecs.indices) {
            val v = apply(r, camVecs[i])
            val hit = index.nearest(v, tolRad)
            if (hit >= 0 && used.add(hit)) {
                out.add(i)
                residuals.add(V.angle(v, index.vectorOf(hit)))
            }
        }
        if (residuals.isEmpty()) return IntArray(0) to Double.MAX_VALUE
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size)
        return out.toIntArray() to rms
    }

}






