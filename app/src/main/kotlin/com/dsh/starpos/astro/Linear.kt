// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.abs
import kotlin.math.sqrt
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.RealMatrix

/** 三维向量与小矩阵工具。所有角度单位为弧度，内部矩阵一律 3x3 行主序 DoubleArray。 */
object V {
    fun of(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun norm(a: DoubleArray) = sqrt(dot(a, a))

    fun unit(a: DoubleArray): DoubleArray {
        val n = norm(a)
        require(n > 1e-12) { "零向量无法归一化" }
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    fun scale(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)

    fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    /** 由赤经赤纬（弧度）得到 ICRS 单位向量。 */
    fun fromRaDec(ra: Double, dec: Double): DoubleArray {
        val cd = kotlin.math.cos(dec)
        return doubleArrayOf(cd * kotlin.math.cos(ra), cd * kotlin.math.sin(ra), kotlin.math.sin(dec))
    }

    fun raOf(v: DoubleArray): Double = kotlin.math.atan2(v[1], v[0])

    fun decOf(v: DoubleArray): Double = kotlin.math.asin((v[2] / norm(v)).coerceIn(-1.0, 1.0))

    /** 两单位向量夹角（弧度）。 */
    fun angle(a: DoubleArray, b: DoubleArray): Double =
        kotlin.math.atan2(norm(cross(a, b)), dot(a, b))
}

fun identity3(): Array<DoubleArray> = arrayOf(
    doubleArrayOf(1.0, 0.0, 0.0),
    doubleArrayOf(0.0, 1.0, 0.0),
    doubleArrayOf(0.0, 0.0, 1.0),
)

fun rotX(a: Double): Array<DoubleArray> {
    val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
    return arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0),
        doubleArrayOf(0.0, c, s),
        doubleArrayOf(0.0, -s, c),
    )
}

fun rotY(a: Double): Array<DoubleArray> {
    val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
    return arrayOf(
        doubleArrayOf(c, 0.0, -s),
        doubleArrayOf(0.0, 1.0, 0.0),
        doubleArrayOf(s, 0.0, c),
    )
}

fun rotZ(a: Double): Array<DoubleArray> {
    val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
    return arrayOf(
        doubleArrayOf(c, s, 0.0),
        doubleArrayOf(-s, c, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0),
    )
}

fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val out = Array(3) { DoubleArray(3) }
    for (i in 0..2) for (j in 0..2) {
        out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j]
    }
    return out
}

fun mul3(vararg m: Array<DoubleArray>): Array<DoubleArray> = m.reduce { acc, x -> mul(acc, x) }

fun apply(m: Array<DoubleArray>, v: DoubleArray) = doubleArrayOf(
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
)

fun transpose(m: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> m[j][i] } }

fun toRealMatrix(m: Array<DoubleArray>): RealMatrix =
    Array2DRowRealMatrix(m, false)

fun fromRealMatrix(m: RealMatrix): Array<DoubleArray> =
    Array(3) { i -> DoubleArray(3) { j -> m.getEntry(i, j) } }

fun rotAngleDeg(r: Array<DoubleArray>): Double {
    // 旋转矩阵的转角：arccos((tr(R)-1)/2)
    val tr = r[0][0] + r[1][1] + r[2][2]
    return Math.toDegrees(kotlin.math.acos(((tr - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
}

const val ARCSEC = 1.0 / 3600.0
const val DEG = Math.PI / 180.0

fun Double.arcsecToRad() = this * ARCSEC * DEG
fun radToArcsec(r: Double) = r / DEG / ARCSEC
fun norm2pi(x: Double): Double {
    val t = x % (2 * Math.PI)
    return if (t < 0) t + 2 * Math.PI else t
}

fun normPi(x: Double): Double {
    var t = norm2pi(x)
    if (t > Math.PI) t -= 2 * Math.PI
    return t
}

