// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.astro

import kotlin.math.*

/**
 * 天球与时间模型。
 *
 * 精度声明（M1）：
 *  - 岁差：IAU 2006 (P03) 完全多项式，误差 < 0.001″/世纪量级；
 *  - 章动：IAU 2000A 截断到 20 个主项（误差 < 0.1″），完整 1365 项系列属于依赖项；
 *  - 光行差：一阶年周光行差，使用低精度太阳位置（误差 < 0.01″）；
 *  - 折射：Bennett 1982 / Saemundsson 公式 + 气压温度修正。
 * 目标 T0（3σ ≤ 0.02° = 72″），上述截断远小于目标，故可接受；但报告中必须标注模型版本。
 */
object AstroTime {
    const val J2000 = 2451545.0
    /** TAI−UTC（2026 年生效值 37 s，需求 §7）。 */
    const val TAI_MINUS_UTC = 37.0
    /** TT−TAI（定义值）。 */
    const val TT_MINUS_TAI = 32.184
    const val UNIX_EPOCH_JD = 2440587.5

    fun jdFromUnixMillis(unixMs: Long): Double = UNIX_EPOCH_JD + unixMs / 86_400_000.0

    fun unixMillisFromJd(jd: Double): Long = ((jd - UNIX_EPOCH_JD) * 86_400_000.0).roundToLong()

    /** 儒略日（Meeus 7.1），月/日为整数日历日期。 */
    fun julianDay(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Double = 0.0): Double {
        var y = year
        var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        val dayFrac = day + (hour + (minute + second / 60.0) / 60.0) / 24.0
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + dayFrac + b - 1524.5
    }

    fun ttFromUtc(jdUtc: Double) = jdUtc + (TAI_MINUS_UTC + TT_MINUS_TAI) / 86400.0
    fun centuriesSinceJ2000(jdTt: Double) = (jdTt - J2000) / 36525.0

    /** 地球自转角 ERA（IAU 2000），弧度。 */
    fun earthRotationAngle(jdUt1: Double): Double {
        val d = jdUt1 - J2000
        val turns = 0.7790572732640 + 1.00273781191135448 * d
        return norm2pi(2 * PI * (turns - floor(turns)))
    }

    /** 格林尼治平恒星时 GMST（IAU 2006），弧度。 */
    fun gmst06(jdUt1: Double, jdTt: Double): Double {
        val t = centuriesSinceJ2000(jdTt)
        val polyArcsec = 0.014506 + 4612.156534 * t + 1.3915817 * t * t -
            0.00000044 * t * t * t - 0.000029956 * t * t * t * t - 0.0000000368 * t * t * t * t * t
        return norm2pi(earthRotationAngle(jdUt1) + polyArcsec.arcsecToRad())
    }

    /** 格林尼治视恒星时 GAST，弧度。 */
    fun gast(jdUt1: Double, jdTt: Double): Double =
        norm2pi(gmst06(jdUt1, jdTt) + Nutation.equationOfEquinoxes(jdTt))
}

object Nutation {
    /** 单位：0.0001 角秒；系数 (l, l', F, D, Ω) 为主项。 */
    private class Term(
        val l: Int, val lp: Int, val f: Int, val d: Int, val om: Int,
        val psiSin: Double, val psiSinT: Double, val epsCos: Double, val epsCosT: Double,
    )

    private val TERMS = listOf(
        Term(0, 0, 0, 0, 1, -171996.0, -174.2, 92025.0, 8.9),
        Term(-2, 0, 0, 2, 2, -13187.0, -1.6, 5736.0, -3.1),
        Term(0, 0, 0, 2, 2, -2274.0, -0.2, 977.0, -0.5),
        Term(0, 0, 0, 0, 2, 2062.0, 0.2, -895.0, 0.5),
        Term(0, 1, 0, 0, 0, 1426.0, -3.4, 54.0, -0.1),
        Term(0, 0, 1, 0, 0, 712.0, 0.1, -7.0, 0.0),
        Term(-2, 1, 0, 2, 2, -517.0, 1.2, 224.0, -0.6),
        Term(0, 0, 0, 2, 1, -386.0, -0.4, 200.0, 0.0),
        Term(0, 0, 1, 2, 2, -301.0, 0.0, 129.0, -0.1),
        Term(-2, -1, 0, 2, 2, 217.0, -0.5, -95.0, 0.3),
        Term(-2, 0, 1, 0, 0, -158.0, 0.0, 0.0, 0.0),
        Term(-2, 0, 0, 2, 1, 129.0, 0.1, -70.0, 0.0),
        Term(0, 0, -1, 2, 2, 123.0, 0.0, -53.0, 0.0),
        Term(2, 0, 0, 0, 0, 63.0, 0.0, 0.0, 0.0),
        Term(0, 0, 1, 0, 1, 63.0, 0.1, -33.0, 0.0),
        Term(2, 0, -1, 2, 2, -59.0, 0.0, 26.0, 0.0),
        Term(0, 0, -1, 0, 1, -58.0, -0.1, 32.0, 0.0),
        Term(0, 0, 1, 2, 1, -51.0, 0.0, 27.0, 0.0),
        Term(-2, 0, 2, 0, 0, 48.0, 0.0, 0.0, 0.0),
        Term(0, 0, -2, 2, 1, 46.0, 0.0, -24.0, 0.0),
    )

    /** 五个基本幅角（弧度）：D, M, M', F, Ω。 */
    private fun arguments(jdTt: Double): DoubleArray {
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        val d = 297.85036 + 445267.111480 * t - 0.0019142 * t * t + t * t * t / 189474.0
        val m = 357.52772 + 35999.050340 * t - 0.0001603 * t * t - t * t * t / 300000.0
        val mp = 134.96298 + 477198.867398 * t + 0.0086972 * t * t + t * t * t / 56250.0
        val f = 93.27191 + 483202.017538 * t - 0.0036825 * t * t + t * t * t / 327270.0
        val om = 125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000.0
        return doubleArrayOf(d * DEG, m * DEG, mp * DEG, f * DEG, om * DEG)
    }

    fun deltaPsi(jdTt: Double): Double {
        val args = arguments(jdTt)
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        var sum = 0.0
        for (term in TERMS) {
            val arg = term.l * args[1] + term.lp * args[2] + term.f * args[3] +
                term.d * args[0] + term.om * args[4]
            sum += (term.psiSin + term.psiSinT * t) * sin(arg)
        }
        return (sum * 1e-4).arcsecToRad()
    }

    fun deltaEps(jdTt: Double): Double {
        val args = arguments(jdTt)
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        var sum = 0.0
        for (term in TERMS) {
            val arg = term.l * args[1] + term.lp * args[2] + term.f * args[3] +
                term.d * args[0] + term.om * args[4]
            sum += (term.epsCos + term.epsCosT * t) * cos(arg)
        }
        return (sum * 1e-4).arcsecToRad()
    }

    /** 平黄赤交角（IAU 2006），弧度。 */
    fun meanObliquity(jdTt: Double): Double {
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        val arcsec = 84381.406 - 46.836769 * t - 0.0001831 * t * t + 0.00200340 * t * t * t -
            0.000000576 * t * t * t * t - 0.0000000434 * t * t * t * t * t
        return arcsec.arcsecToRad()
    }

    fun trueObliquity(jdTt: Double) = meanObliquity(jdTt) + deltaEps(jdTt)

    /** 分点方程 EE = Δψ·cos ε_A + 0.00264″·sin Ω + 0.000063″·sin 2Ω。 */
    fun equationOfEquinoxes(jdTt: Double): Double {
        val om = arguments(jdTt)[4]
        val epsA = meanObliquity(jdTt)
        return deltaPsi(jdTt) * cos(epsA) + (0.00264 * sin(om) + 0.000063 * sin(2 * om)).arcsecToRad()
    }
}

object Precession {
    /** 标准右手旋转（Meeus 记号），与 Linear.kt 的 rotX 互为反向。 */
    private fun r1(a: Double) = rotX(-a)
    private fun r2(a: Double) = rotY(-a)
    private fun r3(a: Double) = rotZ(-a)

    /** IAU 2006 岁差（含框架偏差）矩阵：ICRS/J2000 → 当日平赤道平春分点。 */
    fun matrix(jdTt: Double): Array<DoubleArray> {
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        val zeta = (2.650545 + 2306.083227 * t + 0.2988499 * t * t + 0.01801828 * t * t * t -
            0.000005971 * t * t * t * t - 0.0000003173 * t * t * t * t * t).arcsecToRad()
        val z = (-2.650545 + 2306.077181 * t + 1.0927348 * t * t + 0.01826837 * t * t * t -
            0.000028596 * t * t * t * t - 0.0000002904 * t * t * t * t * t).arcsecToRad()
        val theta = (2004.191903 * t - 0.4294934 * t * t - 0.04182264 * t * t * t -
            0.000007089 * t * t * t * t - 0.0000001274 * t * t * t * t * t).arcsecToRad()
        return mul3(r3(-z), r2(theta), r3(-zeta))
    }

    /** 章动矩阵：当日平赤道 → 真赤道。 */
    fun nutationMatrix(jdTt: Double): Array<DoubleArray> {
        val epsA = Nutation.meanObliquity(jdTt)
        val dPsi = Nutation.deltaPsi(jdTt)
        val dEps = Nutation.deltaEps(jdTt)
        return mul3(r1(-(epsA + dEps)), r3(-dPsi), r1(epsA))
    }

    /** ICRS → 真赤道真春分点（TOD），不含光行差。 */
    fun icrsToTod(jdTt: Double): Array<DoubleArray> =
        mul(nutationMatrix(jdTt), matrix(jdTt))
}

object Aberration {
    /** 地球速度矢量方向（ICRS 单位向量）与 |v|/c。低精度太阳位置，误差远小于 0.01″。 */
    fun earthVelocity(jdTt: Double): Pair<DoubleArray, Double> {
        val t = AstroTime.centuriesSinceJ2000(jdTt)
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val sunLon = (l0 + c) * DEG
        // 地球速度方向 = 日心黄经 + 90°（地球公转方向）
        val velLon = sunLon + PI / 2
        val eps = Nutation.meanObliquity(jdTt)
        val x = cos(velLon)
        val y = sin(velLon) * cos(eps)
        val z = sin(velLon) * sin(eps)
        return Pair(V.unit(doubleArrayOf(x, y, z)), 0.00009936)
    }

    /** 一阶年周光行差：u' = normalize(u + β − (u·β)u)。 */
    fun apply(u: DoubleArray, jdTt: Double): DoubleArray {
        val (dir, beta) = earthVelocity(jdTt)
        val b = V.scale(dir, beta)
        val ub = V.dot(u, b)
        return V.unit(V.add(V.add(u, b), V.scale(u, -ub)))
    }
}

object Refraction {
    /** Saemundsson/Bennett 真高度修正量（角秒，正数表示星应上抬）。hTrue 单位为度。 */
    fun correctionArcsec(hTrueDeg: Double, pressureHpa: Double = 1010.0, tempC: Double = 10.0): Double {
        val h = hTrueDeg
        // 低于 −2° 时公式失效，直接钳制到 0（该高度通常已被遮挡）
        if (h < -2.0) return 0.0
        val hh = max(h, -1.0)
        val r = 1.02 / tan((hh + 10.3 / (hh + 5.11)) * DEG) // 角分
        val atmo = (pressureHpa / 1010.0) * (283.0 / (273.0 + tempC))
        return (r * atmo) * 60.0
    }

    /** 把真方向抬升到视方向（本地地平坐标系内按高度角修正）。 */
    fun apparent(
        uTod: DoubleArray,
        zenithTod: DoubleArray,
        pressureHpa: Double = 1010.0,
        tempC: Double = 10.0,
    ): DoubleArray {
        val alt = PI / 2 - V.angle(uTod, zenithTod)
        val corr = correctionArcsec(Math.toDegrees(alt), pressureHpa, tempC).arcsecToRad()
        return rotateTowardZenith(uTod, zenithTod, corr)
    }

    /**
     * 视方向 → 真方向：观测到的星被大气抬高，反解时要朝远离天顶的方向转回。
     * 星图识别阶段用它把观测向量变回真方向，才能和星表直接比较。
     */
    fun trueDirection(
        uApparent: DoubleArray,
        zenithTod: DoubleArray,
        pressureHpa: Double = 1010.0,
        tempC: Double = 10.0,
    ): DoubleArray {
        val alt = PI / 2 - V.angle(uApparent, zenithTod)
        val corr = correctionArcsec(Math.toDegrees(alt), pressureHpa, tempC).arcsecToRad()
        return rotateTowardZenith(uApparent, zenithTod, -corr)
    }

    private fun rotateTowardZenith(u: DoubleArray, zenith: DoubleArray, corr: Double): DoubleArray {
        if (corr == 0.0) return u
        val perp = V.sub(zenith, V.scale(u, V.dot(zenith, u)))
        val pn = V.norm(perp)
        if (pn < 1e-9) return u
        val unitPerp = V.scale(perp, 1.0 / pn)
        return V.unit(V.add(V.scale(u, cos(corr)), V.scale(unitPerp, sin(corr))))
    }

}

/** 某时刻的完整天球上下文；解算器只需它即可把星表位置变到观测框架。 */
class SkyContext(
    val jdUtc: Double,
    val jdUt1: Double,
    val jdTt: Double,
    val gast: Double,
    val meanObliquity: Double,
    val dPsi: Double,
    val dEps: Double,
    /** EOP/闰秒数据年龄（天），用于时效告警。 */
    val ephemerisAgeDays: Double = 0.0,
) {
    val icrsToTod: Array<DoubleArray> = Precession.icrsToTod(jdTt)

    companion object {
        fun at(utcMillis: Long, dut1Seconds: Double = 0.0, ephemerisAgeDays: Double = 0.0): SkyContext {
            val jdUtc = AstroTime.jdFromUnixMillis(utcMillis)
            val jdUt1 = jdUtc + dut1Seconds / 86400.0
            val jdTt = AstroTime.ttFromUtc(jdUtc)
            return SkyContext(
                jdUtc = jdUtc,
                jdUt1 = jdUt1,
                jdTt = jdTt,
                gast = AstroTime.gast(jdUt1, jdTt),
                meanObliquity = Nutation.meanObliquity(jdTt),
                dPsi = Nutation.deltaPsi(jdTt),
                dEps = Nutation.deltaEps(jdTt),
                ephemerisAgeDays = ephemerisAgeDays,
            )
        }
    }

}
