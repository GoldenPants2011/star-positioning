package com.dsh.starpos.astro

import kotlin.math.*

/** 检测到的一颗星点。 */
class Detection(
    val x: Double,
    val y: Double,
    val flux: Double,
    val peak: Double,
    /** PSF 长短轴比（>3 视为拖尾，需求 FR-02）。 */
    val aspect: Double,
    val area: Int,
    val saturated: Boolean,
)

/** 整帧质量指标（需求 FR-02）。 */
class ImageQuality(
    val starCount: Int,
    val cloudCover: Double,
    val maxPsfAspect: Double,
    val saturatedCount: Int,
    val background: Double,
    val noiseSigma: Double,
    val entropy: Double,
)

class DetectionResult(val detections: List<Detection>, val quality: ImageQuality)

/**
 * 星点检测与质量门控。
 *
 * 算法：背景/噪声鲁棒估计 → 阈值分割 → 连通域 → 亚像素质心 + 二阶矩 → 质量统计。
 * 云量是启发式估计（星点缺失 + 天空结构 + 熵），需外场数据校准（FR-09/FR-11）。
 */
object FrameAnalyzer {

    /**
     * @param medianPrefilter 真机原始帧建议开启：3×3 中值先压掉单像素热点，
     *   否则噪声长尾会让 5σ 检测出大量假星（2026-11-19 实测：294 个检测大多不在星上）。
     */
    fun analyze(
        image: FloatArray,
        width: Int,
        height: Int,
        thresholdSigma: Double = 5.0,
        maxComponents: Int = 4000,
        medianPrefilter: Boolean = false,
    ): DetectionResult {
        require(image.size >= width * height) { "图像缓冲区尺寸不足" }
        val src = if (medianPrefilter) median3(image, width, height) else image
        // 分块（mesh）背景 + 局部阈值：渐变天空、星云、光污染梯度下不再把整幅图误判成"高噪声"
        val mesh = computeMesh(src, width, height)
        val background = mesh.medianBackground
        val noise = mesh.medianSigma
        val bgMap = FloatArray(width * height)
        val thrMap = FloatArray(width * height)
        for (yy in 0 until height) {
            for (xx in 0 until width) {
                val b = mesh.bgAt(xx, yy)
                val k = yy * width + xx
                bgMap[k] = b.toFloat()
                thrMap[k] = (b + thresholdSigma * mesh.sigmaAt(xx, yy)).toFloat()
            }
        }

        val visited = BooleanArray(width * height)
        val detections = ArrayList<Detection>()
        val stack = java.util.ArrayDeque<Int>(256)
        val compX = ArrayList<Int>(64)
        val compY = ArrayList<Int>(64)

        for (startY in 0 until height) {
            for (startX in 0 until width) {
                val idx = startY * width + startX
                if (visited[idx] || src[idx] <= thrMap[idx]) continue
                compX.clear(); compY.clear()
                stack.clear()
                stack.push(idx)
                visited[idx] = true
                var tooBig = false
                while (stack.isNotEmpty()) {
                    val cur = stack.pop()
                    val cx = cur % width
                    val cy = cur / width
                    compX.add(cx); compY.add(cy)
                    if (compX.size > 2000) { tooBig = true; break }
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        val nidx = ny * width + nx
                        if (visited[nidx] || src[nidx] <= thrMap[nidx]) continue
                        visited[nidx] = true
                        stack.push(nidx)
                    }
                }
                if (tooBig || compX.size < 2) continue
                var sumW = 0.0
                var sx = 0.0
                var sy = 0.0
                var peak = 0.0
                var sat = false
                for (i in compX.indices) {
                    val px = compX[i]; val py = compY[i]
                    val v = src[py * width + px].toDouble()
                    val w = max(v - bgMap[py * width + px], 0.0)
                    sumW += w
                    sx += px * w
                    sy += py * w
                    if (v > peak) peak = v
                    if (v >= 0.98f) sat = true
                }
                if (sumW <= 0) continue
                val cxD = sx / sumW
                val cyD = sy / sumW
                var mxx = 0.0; var myy = 0.0; var mxy = 0.0
                for (i in compX.indices) {
                    val px = compX[i]; val py = compY[i]
                    val w = max(src[py * width + px].toDouble() - bgMap[py * width + px], 0.0)
                    val dx = px - cxD; val dy = py - cyD
                    mxx += w * dx * dx; myy += w * dy * dy; mxy += w * dx * dy
                }
                val tr = mxx + myy
                val det = mxx * myy - mxy * mxy
                val disc = sqrt(max(tr * tr / 4 - det, 0.0))
                val l1 = tr / 2 + disc
                val l2 = tr / 2 - disc
                val aspect = if (l2 > 1e-6) sqrt(l1 / l2) else 1.0
                detections.add(
                    Detection(
                        x = cxD, y = cyD, flux = sumW, peak = peak,
                        aspect = if (aspect.isFinite()) aspect else 1.0,
                        area = compX.size, saturated = sat,
                    ),
                )
                if (detections.size >= maxComponents) break
            }
        }
        detections.sortByDescending { it.flux }
        val quality = ImageQuality(
            starCount = detections.size,
            cloudCover = cloudCover(image, width, height, background, noise, detections.size),
            maxPsfAspect = detections.maxOfOrNull { it.aspect } ?: 1.0,
            saturatedCount = detections.count { it.saturated },
            background = background,
            noiseSigma = noise,
            entropy = entropy(image, width, height, background, noise),
        )
        return DetectionResult(detections, quality)
    }

    /**
     * 云量启发式：亮天空占比 + 局部分块结构 + 星点缺失。
     * 注意：这是工程近似，输出必须带不确定性说明；厚云/全阴时返回接近 1 并触发 NO_FIX。
     */
    private fun cloudCover(
        image: FloatArray, width: Int, height: Int,
        background: Double, noise: Double, starCount: Int,
    ): Double {
        var bright = 0
        var tiles = 0
        var structuredTiles = 0
        val tile = max(16, min(width, height) / 12)
        var ty = 0
        while (ty + tile <= height) {
            var tx = 0
            while (tx + tile <= width) {
                var sum = 0.0
                var sum2 = 0.0
                var above = 0
                for (y in ty until ty + tile) {
                    for (x in tx until tx + tile) {
                        val v = image[y * width + x].toDouble()
                        sum += v; sum2 += v * v
                        if (v > background + 2.0 * noise) above++
                    }
                }
                val n = tile * tile
                val mean = sum / n
                val varT = sum2 / n - mean * mean
                tiles++
                if (above.toDouble() / n > 0.02) bright++
                if (sqrt(max(varT, 0.0)) > 3 * noise) structuredTiles++
                tx += tile
            }
            ty += tile
        }
        val brightFrac = if (tiles > 0) bright.toDouble() / tiles else 0.0
        val structureFrac = if (tiles > 0) structuredTiles.toDouble() / tiles else 0.0
        val starDeficit = 1.0 - min(1.0, starCount / 12.0)
        return (0.45 * brightFrac + 0.25 * structureFrac + 0.30 * starDeficit).coerceIn(0.0, 1.0)
    }

    private fun entropy(image: FloatArray, width: Int, height: Int, background: Double, noise: Double): Double {
        val bins = 32
        val hist = IntArray(bins)
        val span = max(8 * noise, 1e-3)
        var total = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val v = (image[y * width + x].toDouble() - background) / span
                val b = ((v + 0.5) * bins).toInt().coerceIn(0, bins - 1)
                hist[b]++
                total++
                x += 2
            }
            y += 2
        }
        var h = 0.0
        for (c in hist) {
            if (c == 0) continue
            val p = c.toDouble() / total
            h -= p * ln(p)
        }
        return h / ln(bins.toDouble())
    }
}


/**
 * 分块背景模型：把图像切成 tiles×tiles 网格，每块取中位数为背景、
 * 取 (p75−p25)/1.349 为鲁棒噪声，再对网格做 3×3 中值平滑，最后双线性插值到全图。
 *
 * 为什么必须分块：真实照片（星云、渐变天空、光污染）用全局中位数+全局 MAD 会把
 * 大尺度结构误当成噪声，阈值被抬到超过图像动态范围，结果一颗星都检测不到
 * （2026-11-15 用真实广域照片复现过）。
 */
private class MeshBackground(
    private val bg: DoubleArray,
    private val sigma: DoubleArray,
    private val tilesX: Int,
    private val tilesY: Int,
    private val tileW: Double,
    private val tileH: Double,
    val medianBackground: Double,
    val medianSigma: Double,
) {
    private fun interp(grid: DoubleArray, x: Int, y: Int): Double {
        val fx = (x + 0.5) / tileW - 0.5
        val fy = (y + 0.5) / tileH - 0.5
        val x0 = floor(fx).toInt().coerceIn(0, tilesX - 1)
        val y0 = floor(fy).toInt().coerceIn(0, tilesY - 1)
        val x1 = (x0 + 1).coerceAtMost(tilesX - 1)
        val y1 = (y0 + 1).coerceAtMost(tilesY - 1)
        val tx = (fx - x0).coerceIn(0.0, 1.0)
        val ty = (fy - y0).coerceIn(0.0, 1.0)
        val top = grid[y0 * tilesX + x0] * (1 - tx) + grid[y0 * tilesX + x1] * tx
        val bottom = grid[y1 * tilesX + x0] * (1 - tx) + grid[y1 * tilesX + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    fun bgAt(x: Int, y: Int): Double = interp(bg, x, y)
    fun sigmaAt(x: Int, y: Int): Double = interp(sigma, x, y)
}

private fun computeMesh(image: FloatArray, width: Int, height: Int): MeshBackground {
    val minSide = min(width, height)
    val tiles = when {
        minSide < 256 -> 6
        minSide < 800 -> 12
        else -> 24
    }
    val tilesX = tiles
    val tilesY = max(4, (tiles * height.toDouble() / width).toInt())
    val tileW = width.toDouble() / tilesX
    val tileH = height.toDouble() / tilesY
    val bg = DoubleArray(tilesX * tilesY)
    val sg = DoubleArray(tilesX * tilesY)
    val samples = ArrayList<Float>(512)

    for (ty in 0 until tilesY) {
        val y0 = (ty * tileH).toInt()
        val y1 = min(height, ((ty + 1) * tileH).toInt())
        for (tx in 0 until tilesX) {
            val x0 = (tx * tileW).toInt()
            val x1 = min(width, ((tx + 1) * tileW).toInt())
            samples.clear()
            val step = max(1, min(x1 - x0, y1 - y0) / 16)
            var yy = y0
            while (yy < y1) {
                var xx = x0
                while (xx < x1) {
                    samples.add(image[yy * width + xx])
                    xx += step
                }
                yy += step
            }
            if (samples.isEmpty()) { bg[ty * tilesX + tx] = 0.0; sg[ty * tilesX + tx] = 1e-4; continue }
            samples.sort()
            val median = samples[samples.size / 2].toDouble()
            val p25 = samples[(samples.size * 0.25).toInt().coerceIn(0, samples.size - 1)].toDouble()
            val p75 = samples[(samples.size * 0.75).toInt().coerceIn(0, samples.size - 1)].toDouble()
            bg[ty * tilesX + tx] = median
            sg[ty * tilesX + tx] = max((p75 - p25) / 1.349, 1e-5)
        }
    }

    // 3×3 中值平滑，压掉被亮星/星云污染的网格点
    fun medianFilter(src: DoubleArray): DoubleArray {
        val out = DoubleArray(src.size)
        val buf = DoubleArray(9)
        for (y in 0 until tilesY) for (x in 0 until tilesX) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, tilesX - 1)
                val ny = (y + dy).coerceIn(0, tilesY - 1)
                buf[n++] = src[ny * tilesX + nx]
            }
            buf.sort()
            out[y * tilesX + x] = buf[4]
        }
        return out
    }

    val bgSmooth = medianFilter(bg)
    val sgSmooth = medianFilter(sg)
    val sortedBg = bgSmooth.sorted()
    val sortedSg = sgSmooth.sorted()
    return MeshBackground(
        bg = bgSmooth,
        sigma = sgSmooth,
        tilesX = tilesX,
        tilesY = tilesY,
        tileW = tileW,
        tileH = tileH,
        medianBackground = sortedBg[sortedBg.size / 2],
        medianSigma = max(sortedSg[sortedSg.size / 2], 1e-5),
    )
}

/** 3×3 中值（边界复制），压掉单像素热点；星点通常 ≥2 px，能保留。 */
private fun median3(image: FloatArray, width: Int, height: Int): FloatArray {
    val out = FloatArray(image.size)
    val buf = FloatArray(9)
    for (y in 0 until height) {
        val y0 = if (y == 0) 0 else y - 1
        val y1 = if (y == height - 1) height - 1 else y + 1
        for (x in 0 until width) {
            val x0 = if (x == 0) 0 else x - 1
            val x1 = if (x == width - 1) width - 1 else x + 1
            var n = 0
            for (yy in y0..y1) for (xx in x0..x1) buf[n++] = image[yy * width + xx]
            for (i in 1 until n) {
                val v = buf[i]
                var j = i - 1
                while (j >= 0 && buf[j] > v) { buf[j + 1] = buf[j]; j-- }
                buf[j + 1] = v
            }
            out[y * width + x] = buf[n / 2]
        }
    }
    return out
}
