// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.*

/** 单颗恒星（ICRS/J2000，自行单位 mas/yr）。 */
class Star(
    val hr: Int,
    val raRad: Double,
    val decRad: Double,
    val mag: Double,
    val pmRaMas: Double,
    val pmDecMas: Double,
)

/**
 * 内置亮星星表（资产 stars.bin，由 catalog/build_catalog.py 从 HYG v41 生成）。
 * 星等范围 -1.5 ~ 7.0；暗星（Gaia 子集）属于依赖项，未包含。
 */
class StarCatalog(
    val ra: DoubleArray,
    val dec: DoubleArray,
    val mag: DoubleArray,
    val pmRa: DoubleArray,
    val pmDec: DoubleArray,
    val hr: IntArray,
    /** 星表位置对应的参考历元（儒略日，TT）。默认 J2000；Gaia DR3 为 J2016.0（JD 2457389.0）。 */
    val refEpochJd: Double = AstroTime.J2000,
) {
    val size: Int get() = ra.size

    fun star(i: Int) = Star(hr[i], ra[i], dec[i], mag[i], pmRa[i], pmDec[i])

    /** J2000 单位向量（含自行改正到 jdTt 时刻）。 */
    fun icrs(i: Int, jdTt: Double): DoubleArray {
        val years = (jdTt - refEpochJd) / 365.25
        var raR = ra[i] + (pmRa[i] * years * 1e-3 / 3600.0) * DEG / cos(dec[i]).coerceAtLeast(1e-6)
        val decR = dec[i] + (pmDec[i] * years * 1e-3 / 3600.0) * DEG
        raR = norm2pi(raR)
        return V.fromRaDec(raR, decR)
    }

    fun vmag(i: Int) = mag[i]

    /** 以给定星等上限筛出的下标（按亮度升序）。 */
    fun brighterThan(limit: Double): IntArray {
        val idx = ArrayList<Int>()
        for (i in 0 until size) if (mag[i] <= limit) idx.add(i)
        idx.sortBy { mag[it] }
        return idx.toIntArray()
    }

    companion object {
        fun parse(input: InputStream, refEpochJd: Double = AstroTime.J2000): StarCatalog {
            DataInputStream(input.buffered()).use { dis ->
                val magic = ByteArray(4)
                dis.readFully(magic)
                require(String(magic) == "SPC1") { "星表魔数不匹配：${String(magic)}" }
                // 资产是小端字节序（Python struct '<II'）
                val version = readIntLE(dis)
                require(version == 1) { "不支持的星表版本 $version" }
                val n = readIntLE(dis)
                require(n in 1..1_000_000) { "星表大小异常：$n" }
                val ra = DoubleArray(n); val dec = DoubleArray(n); val mag = DoubleArray(n)
                val pmRa = DoubleArray(n); val pmDec = DoubleArray(n); val hr = IntArray(n)
                val rec = ByteArray(24)
                for (i in 0 until n) {
                    dis.readFully(rec)
                    var o = 0
                    ra[i] = leFloat(rec, o); o += 4
                    dec[i] = leFloat(rec, o); o += 4
                    mag[i] = leFloat(rec, o); o += 4
                    pmRa[i] = leFloat(rec, o); o += 4
                    pmDec[i] = leFloat(rec, o); o += 4
                    hr[i] = leFloat(rec, o).toInt()
                }
                return StarCatalog(ra, dec, mag, pmRa, pmDec, hr, refEpochJd)
            }
        }

        fun fromFile(file: File, refEpochJd: Double = AstroTime.J2000): StarCatalog = file.inputStream().use { parse(it, refEpochJd) }

        private fun readIntLE(dis: DataInputStream): Int {
            val b0 = dis.readUnsignedByte()
            val b1 = dis.readUnsignedByte()
            val b2 = dis.readUnsignedByte()
            val b3 = dis.readUnsignedByte()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun leFloat(buf: ByteArray, off: Int): Double {
            val bits = (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
            return java.lang.Float.intBitsToFloat(bits).toDouble()
        }
    }
}

/**
 * 天区分桶索引：最近的星、k 近邻、以及三角匹配用的三角形哈希表。
 * 说明：桶大小 2°，对 30° 视场足够；更极端的宽视场需要 HEALPix（列为后续优化）。
 */
class SkyIndex(
    private val catalog: StarCatalog,
    /** 参与匹配/索引的星等上限（要与检测能力匹配，否则图像里的暗邻居在星表里不存在）。 */
    val magLimit: Double = 6.5,
    /** 参与索引的恒星下标（亮度升序）。 */
    val ids: IntArray = catalog.brighterThan(magLimit),
    /** 索引向量对应的观测历元（儒略日）：正确做法是拍摄时刻，自行会按 refEpoch→该时刻传播。 */
    private val epochJd: Double = AstroTime.J2000,
) {
    private val binRa = 64          // 360° / 5.625°
    private val binDec = 32         // 180° / 5.625°
    private val cells: Array<ArrayList<Int>?> = arrayOfNulls(binRa * binDec)
    private val unitVectors: Array<DoubleArray>
    val count: Int get() = ids.size

    init {
        unitVectors = Array(ids.size) { catalog.icrs(ids[it], epochJd) }
        for (i in unitVectors.indices) {
            val key = keyOf(unitVectors[i])
            var list = cells[key]
            if (list == null) { list = ArrayList(16); cells[key] = list }
            list.add(i)
        }
    }

    private fun keyOf(v: DoubleArray): Int {
        val ra = norm2pi(atan2(v[1], v[0]))
        val dec = asin(v[2].coerceIn(-1.0, 1.0))
        val ir = ((ra / (2 * PI)) * binRa).toInt().coerceIn(0, binRa - 1)
        val id = (((dec + PI / 2) / PI) * binDec).toInt().coerceIn(0, binDec - 1)
        return id * binRa + ir
    }

    fun vectorOf(index: Int): DoubleArray = unitVectors[index]


    /** 索引内星等不超过 limit 的下标（按亮度升序）。 */
    fun indicesBrighterThan(limit: Double): IntArray {
        val out = ArrayList<Int>(1024)
        for (i in ids.indices) if (catalog.vmag(ids[i]) <= limit) out.add(i)
        return out.toIntArray()
    }

    /** 在 maxAngle（弧度）内找最近的索引内恒星；找不到返回 -1。 */
    fun nearest(v: DoubleArray, maxAngleRad: Double): Int {
        var best = -1
        var bestAng = maxAngleRad
        val centerRa = norm2pi(atan2(v[1], v[0]))
        val centerDec = asin(v[2].coerceIn(-1.0, 1.0))
        val dRa = maxAngleRad / max(cos(centerDec), 0.05)
        val stepsRa = ceil(dRa / (2 * PI / binRa)).toInt().coerceAtMost(binRa)
        val stepsDec = ceil(maxAngleRad / (PI / binDec)).toInt().coerceAtMost(binDec)
        val ir0 = ((centerRa / (2 * PI)) * binRa).toInt()
        val id0 = (((centerDec + PI / 2) / PI) * binDec).toInt()
        for (di in -stepsDec..stepsDec) {
            val id = id0 + di
            if (id < 0 || id >= binDec) continue
            for (dr in -stepsRa..stepsRa) {
                val ir = ((ir0 + dr) % binRa + binRa) % binRa
                val list = cells[id * binRa + ir] ?: continue
                for (cand in list) {
                    val a = V.angle(v, unitVectors[cand])
                    if (a < bestAng) { bestAng = a; best = cand }
                }
            }
        }
        return best
    }

}

/** 三角匹配用的三角形（边长用弦长比，具有尺度/投影不变性）。 */
class Triangle(
    val a: Int, val b: Int, val c: Int,
    val r1: Double,  // 短边/长边
    val r2: Double,  // 中边/长边
    /** 最长边的角距（弧度），用于按尺度排序与快速筛除。 */
    val maxSide: Double = 0.0,
) {
    /**
     * 桶键 = 尺度分档 × 比例网格。尺度分档很关键：星表的三角形索引是按近邻构造的，
     * 尺寸本来就集中；把尺寸并进键里能显著缓解比例空间中的聚簇（否则正确解会被挤掉）。
     */
    fun key(bucket: Double): Long {
        val maxIndex = (1.0 / bucket).toInt()
        val i = (r1 / bucket).toInt().coerceIn(0, maxIndex)
        val j = (r2 / bucket).toInt().coerceIn(0, maxIndex)
        val s = sizeBucket().toLong()
        return (s * 1_000_003L + i) * 1_000_003L + j
    }

    /** maxSide ∈ [0.3°, 60°] 按对数分 8 档。 */
    fun sizeBucket(): Int {
        val minRad = 0.3 * DEG
        val maxRad = 60.0 * DEG
        val x = maxSide.coerceIn(minRad, maxRad)
        val t = (kotlin.math.ln(x) - kotlin.math.ln(minRad)) / (kotlin.math.ln(maxRad) - kotlin.math.ln(minRad))
        return (t * 8).toInt().coerceIn(0, 7)
    }
}

object Triangles {
    /**
     * 规范化三角形：a-b 为最长边，c 为对角顶点，且 dist(a,c) <= dist(b,c)。
     * 规范化规则对图像侧与星表侧完全一致，因此匹配时可直接按 a→a, b→b, c→c 对应；
     * 另外还会尝试 a/b 交换以容忍镜像投影（如鱼眼/镜像前置相机）。
     *
     * r1 = 短边/长边，r2 = 中边/长边，均为纯比例量，具备尺度与投影不变性。
     */
    fun make(i: Int, j: Int, k: Int, vi: DoubleArray, vj: DoubleArray, vk: DoubleArray): Triangle {
        val dIJ = V.angle(vi, vj)
        val dIK = V.angle(vi, vk)
        val dJK = V.angle(vj, vk)

        var longA = i
        var longB = j
        var longD = dIJ
        var third = k
        if (dIK > longD) { longA = i; longB = k; longD = dIK; third = j }
        if (dJK > longD) { longA = j; longB = k; longD = dJK; third = i }

        val vA = when (longA) { i -> vi; j -> vj; else -> vk }
        val vB = when (longB) { i -> vi; j -> vj; else -> vk }
        val vC = when (third) { i -> vi; j -> vj; else -> vk }

        val dAC = V.angle(vA, vC)
        val dBC = V.angle(vB, vC)
        val a: Int; val b: Int; val dShort: Double; val dMid: Double
        if (dAC <= dBC) {
            a = longA; b = longB; dShort = dAC; dMid = dBC
        } else {
            a = longB; b = longA; dShort = dBC; dMid = dAC
        }
        val denom = longD.coerceAtLeast(1e-9)
        return Triangle(a = a, b = b, c = third, r1 = dShort / denom, r2 = dMid / denom, maxSide = longD)
    }
}


