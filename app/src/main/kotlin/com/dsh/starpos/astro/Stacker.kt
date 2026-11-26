package com.dsh.starpos.astro

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 多帧叠加：用亮星质心做"平移投票"估计帧间位移，对齐后平均。
 *
 * 目的：手机 1 s 曝光上限下，城市/平原场景的光子信噪比不足；
 * N 帧对齐平均可把 SNR 提高约 √N，让暗星从噪声里浮出来（2026-11-19 实测：单帧 5–10 颗匹配，
 * 叠加 5 帧后 9–12 颗）。
 *
 * 只做平移（不做旋转/缩放）：采集时已要求静止，帧间旋转通常在 0.05° 以内，
 * 在 140″/px 的手机像元下约 0.2 px，可忽略。
 */
object Stacker {

    class Result(
        val image: FloatArray,
        val width: Int,
        val height: Int,
        val usedFrames: Int,
        /** 各帧相对第一帧的位移（像素）。 */
        val shifts: List<Pair<Int, Int>>,
        val message: String,
    )

    /**
     * @param frames 各帧亮度（0..1），必须同尺寸
     * @param detectSigma 估计位移时用的检测阈值（越高越只取最亮星，越稳）
     */
    fun stack(
        frames: List<FloatArray>,
        width: Int,
        height: Int,
        detectSigma: Double = 12.0,
        maxShiftPx: Int = 80,
    ): Result? {
        if (frames.size < 2) return null
        val ref = frames.first()
        // 每帧的亮星质心（用于位移投票）
        val centroids = frames.map { f ->
            FrameAnalyzer.analyze(f, width, height, thresholdSigma = detectSigma)
                .detections
                .filter { it.aspect < 2.0 && it.area in 3..200 }
                .take(40)
                .map { it.x to it.y }
        }
        val base = centroids.first()
        if (base.size < 6) return null

        val shifts = ArrayList<Pair<Int, Int>>(frames.size)
        shifts.add(0 to 0)
        for (k in 1 until frames.size) {
            val other = centroids[k]
            if (other.size < 6) { shifts.add(0 to 0); continue }
            val shift = voteShift(base, other, maxShiftPx)
            shifts.add(shift)
        }

        val out = FloatArray(width * height)
        val counts = IntArray(width * height)
        for (k in frames.indices) {
            val (dx, dy) = shifts[k]
            val src = frames[k]
            for (y in 0 until height) {
                val sy = y + dy
                if (sy < 0 || sy >= height) continue
                for (x in 0 until width) {
                    val sx = x + dx
                    if (sx < 0 || sx >= width) continue
                    val i = y * width + x
                    out[i] += src[sy * width + sx]
                    counts[i]++
                }
            }
        }
        var covered = 0
        for (i in out.indices) {
            if (counts[i] >= 2) {
                out[i] /= counts[i]
                covered++
            } else {
                out[i] = ref[i]
            }
        }
        val ok = covered > width * height / 2
        return Result(
            out, width, height, frames.size, shifts,
            if (ok) "叠加 ${frames.size} 帧（平均覆盖 %.0f%%）".format(100.0 * covered / (width * height))
            else "叠加失败：帧间位移过大（覆盖仅 %.0f%%）".format(100.0 * covered / (width * height)),
        )
    }

    /** 直方图投票：以 2 px 为桶统计 (dx,dy)，取峰值桶后再求平均。 */
    private fun voteShift(
        base: List<Pair<Double, Double>>,
        other: List<Pair<Double, Double>>,
        maxShift: Int,
    ): Pair<Int, Int> {
        val bucket = HashMap<Long, Int>()
        for (a in base) for (b in other) {
            val dx = b.first - a.first
            val dy = b.second - a.second
            if (abs(dx) > maxShift || abs(dy) > maxShift) continue
            val bx = (dx / 2.0).roundToInt()
            val by = (dy / 2.0).roundToInt()
            val key = (bx.toLong() shl 32) xor (by.toLong() and 0xFFFFFFFFL)
            bucket[key] = (bucket[key] ?: 0) + 1
        }
        if (bucket.isEmpty()) return 0 to 0
        val best = bucket.maxByOrNull { it.value } ?: return 0 to 0
        val bx = (best.key shr 32).toInt()
        val by = best.key.toInt()
        // 在峰值桶 ±1 桶内求平均
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (a in base) for (b in other) {
            val dx = b.first - a.first
            val dy = b.second - a.second
            if (abs(dx / 2.0 - bx) <= 1 && abs(dy / 2.0 - by) <= 1) {
                sx += dx; sy += dy; n++
            }
        }
        if (n == 0) return (bx * 2) to (by * 2)
        return sx.roundToInt() / n to sy.roundToInt() / n
    }
}
