package com.dsh.starpos.astro

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 月亮模式（FR-10 moon）：星历 + 月面圆拟合 + **轨道解算**。
 *
 * 为什么需要"轨道解算"：单帧月亮只能给出一个高度角（一条位置线 LOP）。
 * 月亮在几小时内沿轨道移动约 15°/h 的时角，多次长时延观测的 LOP 才能交会出位置。
 *
 * 数据要求：同一地点、间隔 1–6 h 的 3–6 次观测；每次都能测到月亮高度角
 * （重力给天顶，月面圆拟合给光轴方向，二者夹角即高度角）与精确时间。
 */
object Moon {

    // ---------------- 星历（Meeus 47 章截断，精度约 0.02–0.05°） ----------------

    class Ephemeris(
        /** 地心赤经（弧度）。 */
        val raRad: Double,
        /** 地心赤纬（弧度）。 */
        val decRad: Double,
        /** 地月距离（km）。 */
        val distanceKm: Double,
        /** 相位角（rad，0=满月，π=新月）。 */
        val phaseAngleRad: Double,
    ) {
        /** 亮部占比（0..1）。 */
        val illuminatedFraction: Double get() = (1.0 + cos(phaseAngleRad)) / 2.0
        /** 视半径（弧度）。 */
        val angularRadiusRad: Double get() = asin((1737.4 / distanceKm).coerceIn(-1.0, 1.0))
    }

    /** 旧版（截断 Meeus）——仅用于精度对比。 */
    fun ephemerisAtLegacy(utcMillis: Long): Ephemeris {
        val jd = 2440587.5 + utcMillis / 86_400_000.0
        val t = (jd - 2451545.0) / 36525.0
        val d2r = Math.PI / 180.0
        // 平黄经与基本角
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t * t
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t
        fun rad(x: Double) = ((x % 360.0 + 360.0) % 360.0) * d2r
        val lpr = rad(lp); val dr = rad(d); val mr = rad(m); val mpr = rad(mp); val fr = rad(f)

        // 黄经主要项（度）
        val lonCorr =
            6.288774 * sin(mpr) + 1.274027 * sin(2 * dr - mpr) + 0.658314 * sin(2 * dr) +
                0.213618 * sin(2 * mpr) - 0.185116 * sin(mr) - 0.114332 * sin(2 * fr) +
                0.058793 * sin(2 * dr - 2 * mpr) + 0.057066 * sin(2 * dr - mr - mpr) +
                0.053322 * sin(2 * dr + mpr) + 0.045758 * sin(2 * dr - mr) -
                0.040923 * sin(mr - mpr) - 0.034720 * sin(dr) - 0.030383 * sin(mr + mpr) +
                0.015327 * sin(2 * dr - 2 * fr) - 0.012528 * sin(mpr + 2 * fr) +
                0.010980 * sin(mpr - 2 * fr) + 0.010675 * sin(4 * dr - mpr) +
                0.010034 * sin(3 * mpr) + 0.008548 * sin(4 * dr - 2 * mpr)
        // 黄纬主要项（度）
        val latCorr =
            5.128122 * sin(fr) + 0.280602 * sin(mpr + fr) + 0.277693 * sin(mpr - fr) +
                0.173237 * sin(2 * dr - fr) + 0.055413 * sin(2 * dr - mpr + fr) +
                0.046271 * sin(2 * dr - mpr - fr) + 0.032573 * sin(2 * dr + fr) +
                0.017198 * sin(2 * mpr + fr) + 0.009266 * sin(2 * dr + mpr - fr) +
                0.008822 * sin(2 * mpr - fr) + 0.008216 * sin(2 * dr - mr - fr)
        // 距离（km）
        val dist = 385000.56 -
            20905.355 * cos(mpr) - 3699.111 * cos(2 * dr - mpr) - 2955.968 * cos(2 * dr) -
            569.925 * cos(2 * mpr) + 246.158 * cos(2 * mpr - 2 * dr) - 152.138 * cos(2 * dr - mr - mpr) -
            170.733 * cos(2 * dr + mpr) - 204.586 * cos(2 * dr - mr) - 129.620 * cos(mr - mpr) +
            108.743 * cos(dr) + 104.755 * cos(mr + mpr) + 79.661 * cos(2 * mpr - 2 * fr) +
            48.888 * cos(mpr - mr) - 34.782 * cos(dr - fr) - 30.383 * cos(dr + fr)

        val lambda = rad(lp + lonCorr)
        val beta = latCorr * d2r
        // 黄道 → 赤道（用平黄赤交角，足够）
        val eps = (23.4392911 - 0.0130042 * t) * d2r
        val sinB = sin(beta)
        val dec = asin(sinB * cos(eps) + cos(beta) * sin(eps) * sin(lambda))
        val ra = atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda))
        // 相位角：太阳-月亮-地球夹角（用日地距离近似求太阳方向）
        val sunLon = rad(280.459 + 0.98564736 * (jd - 2451545.0))
        val elong = acos(cos(beta) * cos(lambda - sunLon))   // 月日角距（近似）
        val sunDist = 149_597_870.7
        val phase = atan2(sunDist * sin(elong), dist - sunDist * cos(elong))
        return Ephemeris(((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI), dec, dist, abs(phase))
    }

    /**
     * 太阳星历（Meeus 25 章；含 TT 改正、章动与年周光行差的**视位置**）。
     *
     * 2026-11-24 升级：旧版直接用 UTC 的平黄经（几何位置），少掉
     *   - TT−UTC（ΔT，~69 s → 黄经 ~0.0008°）
     *   - 光行差（−20.5″ ≈ 0.0057°）
     *   - 章动（黄经 ±17″、黄赤交角 ±9″，合计 ~0.006°）；
     * 新版给出视黄经/视黄赤交角（Meeus 例 25.b：1992-10-13.0 TD 视黄经 199.90895°），
     * 与 Meeus 结果差 <0.0002°，比旧版好约一个量级（真实 VSOP87 级 ~0.001° 需另引系数表）。
     *
     * 用途：白天"月亮+太阳同框"两体定位、太阳高度/方位引导、水镜直射像预测。
     */
    fun sunEphemerisAt(utcMillis: Long): Ephemeris {
        val jdTt = 2440587.5 + utcMillis / 86_400_000.0 + deltaTSeconds(utcMillis) / 86_400.0
        val t = (jdTt - 2451545.0) / 36525.0
        val d2r = Math.PI / 180.0
        // 几何平黄经、平近点角、偏心率（Meeus 25.2/25.3）
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val mr = m * d2r
        // 中心差（Meeus 25.4）
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr) +
            (0.019993 - 0.000101 * t) * sin(2 * mr) + 0.000289 * sin(3 * mr)
        val trueLong = l0 + c
        val trueAnom = mr + c * d2r
        // 日地距离（AU，Meeus 25.5）
        val au = 1.000001018 * (1 - e * e) / (1 + e * cos(trueAnom))
        // 视黄经：章动 + 光行差（Meeus 25.10；Ω = 升交点黄经）
        val om = (125.04 - 1934.136 * t) * d2r
        val lambdaApp = (trueLong - 0.00569 - 0.00478 * sin(om)) * d2r
        // 视黄赤交角（平黄赤交角 + 交角章动近似）
        val eps0 = Math.toDegrees(Nutation.meanObliquity(jdTt))
        val epsApp = (eps0 + 0.00256 * cos(om)) * d2r
        val ra = atan2(cos(epsApp) * sin(lambdaApp), cos(lambdaApp))
        val dec = asin(sin(epsApp) * sin(lambdaApp))
        return Ephemeris(
            ((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI), dec,
            au * 149_597_870.7, 0.004652,   // 太阳视半径 0.2666°（此前误填 π）
        )
    }

    // Meeus《Astronomical Algorithms》第 47 章表 47.A（60 项）
    private val TERMS_LR: Array<Pair<IntArray, DoubleArray>> = arrayOf(
        intArrayOf(0, 0, 1, 0) to doubleArrayOf(6288774.0, -20905355.0),
        intArrayOf(2, 0, -1, 0) to doubleArrayOf(1274027.0, -3699111.0),
        intArrayOf(2, 0, 0, 0) to doubleArrayOf(658314.0, -2955968.0),
        intArrayOf(0, 0, 2, 0) to doubleArrayOf(213618.0, -569925.0),
        intArrayOf(0, 1, 0, 0) to doubleArrayOf(-185116.0, 48888.0),
        intArrayOf(0, 0, 0, 2) to doubleArrayOf(-114332.0, -3149.0),
        intArrayOf(2, 0, -2, 0) to doubleArrayOf(58793.0, 246158.0),
        intArrayOf(2, -1, -1, 0) to doubleArrayOf(57066.0, -152138.0),
        intArrayOf(2, 0, 1, 0) to doubleArrayOf(53322.0, -170733.0),
        intArrayOf(2, -1, 0, 0) to doubleArrayOf(45758.0, -204586.0),
        intArrayOf(0, 1, -1, 0) to doubleArrayOf(-40923.0, -129620.0),
        intArrayOf(1, 0, 0, 0) to doubleArrayOf(-34720.0, 108743.0),
        intArrayOf(0, 1, 1, 0) to doubleArrayOf(-30383.0, 104755.0),
        intArrayOf(2, 0, 0, -2) to doubleArrayOf(15327.0, 10321.0),
        intArrayOf(0, 0, 1, 2) to doubleArrayOf(-12528.0, 0.0),
        intArrayOf(0, 0, 1, -2) to doubleArrayOf(10980.0, 79661.0),
        intArrayOf(4, 0, -1, 0) to doubleArrayOf(10675.0, -34782.0),
        intArrayOf(0, 0, 3, 0) to doubleArrayOf(10034.0, -23210.0),
        intArrayOf(4, 0, -2, 0) to doubleArrayOf(8548.0, -21636.0),
        intArrayOf(2, 1, -1, 0) to doubleArrayOf(-7888.0, 24208.0),
        intArrayOf(2, 1, 0, 0) to doubleArrayOf(-6766.0, 30824.0),
        intArrayOf(1, 0, -1, 0) to doubleArrayOf(-5163.0, -8379.0),
        intArrayOf(1, 1, 0, 0) to doubleArrayOf(4987.0, -16675.0),
        intArrayOf(2, -1, 1, 0) to doubleArrayOf(4036.0, -12831.0),
        intArrayOf(2, 0, 2, 0) to doubleArrayOf(3994.0, -10445.0),
        intArrayOf(4, 0, 0, 0) to doubleArrayOf(3861.0, -11650.0),
        intArrayOf(2, 0, -3, 0) to doubleArrayOf(3665.0, 14403.0),
        intArrayOf(0, 1, -2, 0) to doubleArrayOf(-2689.0, -7003.0),
        intArrayOf(2, 0, -1, 2) to doubleArrayOf(-2602.0, 0.0),
        intArrayOf(2, -1, -2, 0) to doubleArrayOf(2390.0, 10056.0),
        intArrayOf(1, 0, 1, 0) to doubleArrayOf(-2348.0, 6322.0),
        intArrayOf(2, -2, 0, 0) to doubleArrayOf(2236.0, -9884.0),
        intArrayOf(0, 1, 2, 0) to doubleArrayOf(-2120.0, 5751.0),
        intArrayOf(0, 2, 0, 0) to doubleArrayOf(-2069.0, 0.0),
        intArrayOf(2, -2, -1, 0) to doubleArrayOf(2048.0, -4950.0),
        intArrayOf(2, 0, 1, -2) to doubleArrayOf(-1773.0, 4130.0),
        intArrayOf(2, 0, 0, 2) to doubleArrayOf(-1595.0, 0.0),
        intArrayOf(4, -1, -1, 0) to doubleArrayOf(1215.0, -3958.0),
        intArrayOf(0, 0, 2, 2) to doubleArrayOf(-1110.0, 0.0),
        intArrayOf(3, 0, -1, 0) to doubleArrayOf(-892.0, 3258.0),
        intArrayOf(2, 1, 1, 0) to doubleArrayOf(-810.0, 2616.0),
        intArrayOf(4, -1, -2, 0) to doubleArrayOf(759.0, -1897.0),
        intArrayOf(0, 2, -1, 0) to doubleArrayOf(-713.0, -2117.0),
        intArrayOf(2, 2, -1, 0) to doubleArrayOf(-700.0, 2354.0),
        intArrayOf(2, 1, -2, 0) to doubleArrayOf(691.0, 0.0),
        intArrayOf(2, -1, 0, -2) to doubleArrayOf(596.0, 0.0),
        intArrayOf(4, 0, 1, 0) to doubleArrayOf(549.0, -1423.0),
        intArrayOf(0, 0, 4, 0) to doubleArrayOf(537.0, -1117.0),
        intArrayOf(4, -1, 0, 0) to doubleArrayOf(520.0, -1571.0),
        intArrayOf(1, 0, -2, 0) to doubleArrayOf(-487.0, -1739.0),
        intArrayOf(2, 1, 0, -2) to doubleArrayOf(-399.0, 0.0),
        intArrayOf(0, 0, 2, -2) to doubleArrayOf(-381.0, -4421.0),
        intArrayOf(1, 1, 1, 0) to doubleArrayOf(351.0, 0.0),
        intArrayOf(3, 0, -2, 0) to doubleArrayOf(-340.0, 0.0),
        intArrayOf(4, 0, -3, 0) to doubleArrayOf(330.0, 0.0),
        intArrayOf(2, -1, 2, 0) to doubleArrayOf(327.0, 0.0),
        intArrayOf(0, 2, 1, 0) to doubleArrayOf(-323.0, 1165.0),
        intArrayOf(1, 1, -1, 0) to doubleArrayOf(299.0, 0.0),
        intArrayOf(2, 0, 3, 0) to doubleArrayOf(294.0, 0.0),
        intArrayOf(2, 0, -1, -2) to doubleArrayOf(0.0, 8752.0),
    )
    // 表 47.B（60 项，黄纬）
    private val TERMS_B: Array<Pair<IntArray, DoubleArray>> = arrayOf(
        intArrayOf(0, 0, 0, 1) to doubleArrayOf(5128122.0),
        intArrayOf(0, 0, 1, 1) to doubleArrayOf(280602.0),
        intArrayOf(0, 0, 1, -1) to doubleArrayOf(277693.0),
        intArrayOf(2, 0, 0, -1) to doubleArrayOf(173237.0),
        intArrayOf(2, 0, -1, 1) to doubleArrayOf(55413.0),
        intArrayOf(2, 0, -1, -1) to doubleArrayOf(46271.0),
        intArrayOf(2, 0, 0, 1) to doubleArrayOf(32573.0),
        intArrayOf(0, 0, 2, 1) to doubleArrayOf(17198.0),
        intArrayOf(2, 0, 1, -1) to doubleArrayOf(9266.0),
        intArrayOf(0, 0, 2, -1) to doubleArrayOf(8822.0),
        intArrayOf(2, -1, 0, -1) to doubleArrayOf(8216.0),
        intArrayOf(2, 0, -2, -1) to doubleArrayOf(4324.0),
        intArrayOf(2, 0, 1, 1) to doubleArrayOf(4200.0),
        intArrayOf(2, 1, 0, -1) to doubleArrayOf(-3359.0),
        intArrayOf(2, -1, -1, 1) to doubleArrayOf(2463.0),
        intArrayOf(2, -1, 0, 1) to doubleArrayOf(2211.0),
        intArrayOf(2, -1, -1, -1) to doubleArrayOf(2065.0),
        intArrayOf(0, 1, -1, -1) to doubleArrayOf(-1870.0),
        intArrayOf(4, 0, -1, -1) to doubleArrayOf(1828.0),
        intArrayOf(0, 1, 0, 1) to doubleArrayOf(-1794.0),
        intArrayOf(0, 0, 0, 3) to doubleArrayOf(-1749.0),
        intArrayOf(0, 1, -1, 1) to doubleArrayOf(-1565.0),
        intArrayOf(1, 0, 0, 1) to doubleArrayOf(-1491.0),
        intArrayOf(0, 1, 1, 1) to doubleArrayOf(-1475.0),
        intArrayOf(0, 1, 1, -1) to doubleArrayOf(-1410.0),
        intArrayOf(0, 1, 0, -1) to doubleArrayOf(-1344.0),
        intArrayOf(1, 0, 0, -1) to doubleArrayOf(-1335.0),
        intArrayOf(0, 0, 3, 1) to doubleArrayOf(1107.0),
        intArrayOf(4, 0, 0, -1) to doubleArrayOf(1021.0),
        intArrayOf(4, 0, -1, 1) to doubleArrayOf(833.0),
        intArrayOf(0, 0, 1, -3) to doubleArrayOf(777.0),
        intArrayOf(4, 0, -2, 1) to doubleArrayOf(671.0),
        intArrayOf(2, 0, 0, -3) to doubleArrayOf(607.0),
        intArrayOf(2, 0, 2, -1) to doubleArrayOf(596.0),
        intArrayOf(2, -1, 1, -1) to doubleArrayOf(491.0),
        intArrayOf(2, 0, -2, 1) to doubleArrayOf(-451.0),
        intArrayOf(0, 0, 3, -1) to doubleArrayOf(439.0),
        intArrayOf(2, 0, 2, 1) to doubleArrayOf(422.0),
        intArrayOf(2, 0, -3, -1) to doubleArrayOf(421.0),
        intArrayOf(2, 1, -1, 1) to doubleArrayOf(-366.0),
        intArrayOf(2, 1, 0, 1) to doubleArrayOf(-351.0),
        intArrayOf(4, 0, 0, 1) to doubleArrayOf(331.0),
        intArrayOf(2, -1, 1, 1) to doubleArrayOf(315.0),
        intArrayOf(2, -2, 0, -1) to doubleArrayOf(302.0),
        intArrayOf(0, 0, 1, 3) to doubleArrayOf(-283.0),
        intArrayOf(2, 1, 1, -1) to doubleArrayOf(-229.0),
        intArrayOf(1, 1, 0, -1) to doubleArrayOf(223.0),
        intArrayOf(1, 1, 0, 1) to doubleArrayOf(223.0),
        intArrayOf(0, 1, -2, -1) to doubleArrayOf(-220.0),
        intArrayOf(2, 1, -1, -1) to doubleArrayOf(-220.0),
        intArrayOf(1, 0, 1, 1) to doubleArrayOf(-185.0),
        intArrayOf(2, -1, -2, -1) to doubleArrayOf(181.0),
        intArrayOf(0, 1, 2, 1) to doubleArrayOf(-177.0),
        intArrayOf(4, 0, -2, -1) to doubleArrayOf(176.0),
        intArrayOf(4, -1, -1, -1) to doubleArrayOf(166.0),
        intArrayOf(1, 0, 1, -1) to doubleArrayOf(-164.0),
        intArrayOf(4, 0, 1, -1) to doubleArrayOf(132.0),
        intArrayOf(1, 0, -1, -1) to doubleArrayOf(-119.0),
        intArrayOf(4, -1, 0, -1) to doubleArrayOf(115.0),
        intArrayOf(2, -2, 0, 1) to doubleArrayOf(107.0),
    )

    /**
     * TT − UTC（秒）。2015–2035 用观测/预测值 69.2 s（±1 s ≈ 0.0006°）；
     * 其他年份用 Espenak–Meeus 简化式（2005–2050 段）。
     */
    private fun deltaTSeconds(utcMillis: Long): Double {
        val y = 2000.0 + (2440587.5 + utcMillis / 86_400_000.0 - 2451545.0) / 365.25
        if (y in 2015.0..2035.0) return 69.2
        val t = y - 2000.0
        return 62.92 + 0.32217 * t + 0.005589 * t * t
    }

    private fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    /**
     * 月亮地心位置：完整 Meeus 第 47 章（60 项经度/距离 + 60 项黄纬），
     * 含 E 偏心率因子、A1/A2/A3 附加项、章动与真黄赤交角；TT = UTC + ΔT。
     * 精度约 10″（0.003°）量级，旧截断版约 0.02-0.05°。
     */
    fun ephemerisAt(utcMillis: Long): Ephemeris {
        val jdTt = 2440587.5 + utcMillis / 86_400_000.0 + deltaTSeconds(utcMillis) / 86_400.0
        val t = (jdTt - 2451545.0) / 36525.0
        val d2r = Math.PI / 180.0
        val lp = 218.3164477 + (481267.88123421 + (-0.0015786 + (1.0 / 538841.0 - t / 65194000.0) * t) * t) * t
        val d = 297.8501921 + (445267.1114034 + (-0.0018819 + (1.0 / 545868.0 - t / 113065000.0) * t) * t) * t
        val m = 357.5291092 + (35999.0502909 + (-0.0001536 + t / 24490000.0) * t) * t
        val mp = 134.9633964 + (477198.8675055 + (0.0087414 + (1.0 / 69699.0 - t / 14712000.0) * t) * t) * t
        val f = 93.2720950 + (483202.0175233 + (-0.0036539 + (-1.0 / 3526000.0 + t / 863310000.0) * t) * t) * t
        val a1 = norm360(119.75 + 131.849 * t) * d2r
        val a2 = norm360(53.09 + 479264.290 * t) * d2r
        val a3 = norm360(313.45 + 481266.484 * t) * d2r
        val e = 1.0 + (-0.002516 - 0.0000074 * t) * t
        val e2 = e * e
        val lpr = norm360(lp) * d2r
        val dr = norm360(d) * d2r
        val mr = norm360(m) * d2r
        val mpr = norm360(mp) * d2r
        val fr = norm360(f) * d2r
        var sl = 0.0
        var sr = 0.0
        for ((k, c) in TERMS_LR) {
            val arg = k[0] * dr + k[1] * mr + k[2] * mpr + k[3] * fr
            var cl = c[0]
            var cr = c[1]
            when (kotlin.math.abs(k[1])) {
                1 -> { cl *= e; cr *= e }
                2 -> { cl *= e2; cr *= e2 }
            }
            sl += cl * sin(arg)
            sr += cr * cos(arg)
        }
        sl += 3958.0 * sin(a1) + 1962.0 * sin(lpr - fr) + 318.0 * sin(a2)
        var sb = 0.0
        for ((k, c) in TERMS_B) {
            val arg = k[0] * dr + k[1] * mr + k[2] * mpr + k[3] * fr
            var cb = c[0]
            when (kotlin.math.abs(k[1])) {
                1 -> cb *= e
                2 -> cb *= e2
            }
            sb += cb * sin(arg)
        }
        sb += -2235.0 * sin(lpr) + 382.0 * sin(a3) + 175.0 * sin(a1 - fr) +
            175.0 * sin(a1 + fr) + 127.0 * sin(lpr - mpr) - 115.0 * sin(lpr + mpr)
        val lambda = (lp + sl / 1_000_000.0) * d2r + Nutation.deltaPsi(jdTt)
        val beta = (sb / 1_000_000.0) * d2r
        val dist = 385000.56 + sr / 1000.0
        val eps = Nutation.trueObliquity(jdTt)
        val sinB = sin(beta)
        val dec = asin(sinB * cos(eps) + cos(beta) * sin(eps) * sin(lambda))
        val ra = atan2(sin(lambda) * cos(eps) - tan(beta) * sin(eps), cos(lambda))
        val sunLon = norm360(280.459 + 0.98564736 * (jdTt - 2451545.0)) * d2r
        val elong = acos(cos(beta) * cos(lambda - sunLon))
        val sunDist = 149_597_870.7
        val phase = atan2(sunDist * sin(elong), dist - sunDist * cos(elong))
        return Ephemeris(((ra % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI), dec, dist, abs(phase))
    }

    /** 太阳半径（km）；太阳距离由 sunEphemerisAt 给出。 */
    const val SUN_RADIUS_KM = 696_340.0

    /** 测站坐标。 */
    class Site(val latRad: Double, val lonRad: Double)

    /**
     * 月亮的地平高度角（rad，含视差与大气折射）。
     * 观测者位置未知时就是待求量；解算时反复调用。
     */
    fun altitudeRad(site: Site, ephem: Ephemeris, utcMillis: Long, gast: Double): Double {
        // 地平坐标：时角 H = GAST + λ - α，高度 = asin(sinφ sinδ + cosφ cosδ cosH)
        val ha = normPi(gast + site.lonRad - ephem.raRad)
        val altGeo = asin(
            (sin(site.latRad) * sin(ephem.decRad) +
                cos(site.latRad) * cos(ephem.decRad) * cos(ha)).coerceIn(-1.0, 1.0),
        )
        // 地平视差（月亮约 57′）：alt' ≈ alt - HP·cos(alt)，HP = asin(6378.14/d)
        val hp = asin((6378.14 / ephem.distanceKm).coerceIn(-1.0, 1.0))
        val altTopo = altGeo - hp * cos(altGeo)
        return altTopo + refractionRad(altTopo)
    }

    /** 月亮方位角（rad，自北向东），供诊断与两体定位用。 */
    fun azimuthRad(site: Site, ephem: Ephemeris, gast: Double): Double {
        val ha = normPi(gast + site.lonRad - ephem.raRad)
        val sinAlt = sin(site.latRad) * sin(ephem.decRad) + cos(site.latRad) * cos(ephem.decRad) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val cosAz = (sin(ephem.decRad) - sin(site.latRad) * sin(alt)) / (cos(site.latRad) * cos(alt)).coerceAtLeast(1e-9)
        val az0 = acos(cosAz.coerceIn(-1.0, 1.0))
        return if (sin(ha) > 0) 2 * Math.PI - az0 else az0
    }

    /** Bennett 大气折射（rad）。 */
    fun refractionRad(altRad: Double): Double {
        val altDeg = Math.toDegrees(altRad)
        if (altDeg < -1.0) return 0.0
        val arcmin = 1.02 / tan(Math.toRadians(altDeg + 10.3 / (altDeg + 5.11)))
        return Math.toRadians(arcmin / 60.0)
    }

    // ---------------- 轨道解算 ----------------

    /** 一次月亮观测：UTC 时刻 + 测得的高度角 + 不确定度（度）。 */
    /**
     * 一次高度角观测。天体用自身赤经/赤纬/距离描述——同一个解算器既能做
     * "月亮多历元"（轨道解算），也能做"月亮+太阳/行星同帧双体定位"。
     */
    class Sight(
        val utcMillis: Long,
        val gast: Double,
        val altRad: Double,
        val sigmaDeg: Double,
        val raRad: Double,
        val decRad: Double,
        val distanceKm: Double = 384_400.0,
    )

    class Fix(val latDeg: Double, val lonDeg: Double, val rmsDeg: Double, val sights: Int, val warnings: List<String>)

    /**
     * 用多次长时延观测求位置（轨道解算）：粗网格 + 局部细化，最小化高度角残差。
     * 需要 3 次以上、间隔 1–6 h；间隔太短时 LOP 近乎平行，解会病态。
     */
    fun solveOrbit(sights: List<Sight>): Fix? {
        if (sights.size < 2) return null
        // 几何条件：单一天体需要足够的时间跨度（方位角变化）；多天体（不同赤经）可直接解
        var distinctBodies = false
        for (i in sights.indices) for (j in i + 1 until sights.size) {
            val sep = V.angle(
                V.fromRaDec(sights[i].raRad, sights[i].decRad),
                V.fromRaDec(sights[j].raRad, sights[j].decRad),
            )
            if (sep > Math.toRadians(10.0)) distinctBodies = true
        }
        if (!distinctBodies && sights.size < 3) return null
        val warnings = ArrayList<String>()
        val spanH = (sights.maxOf { it.utcMillis } - sights.minOf { it.utcMillis }) / 3_600_000.0
        if (!distinctBodies && spanH < 1.5) {
            warnings.add("单一天体观测跨度仅 %.1f h（方位角变化不足），位置线近乎平行，解不稳定".format(spanH))
        }
        if (distinctBodies) {
            if (spanH > 0.5) warnings.add("多天体观测时间跨度 %.1f h，需确认是同一地点".format(spanH))
            // 两体单次观测有"两圆交点二义性"（镜像解可能相距上万 km）；
            // 隔 15–20 分钟再测一次即可唯一化（实测 20 min → 3 km）。
            if (spanH < 0.25) warnings.add("两体只测一次存在二义性，建议隔 15–20 分钟再测一次")
        }

        fun cost(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
            val site = Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
            var sum = 0.0
            var wsum = 0.0
            for (s in sights) {
                val ey = Ephemeris(s.raRad, s.decRad, s.distanceKm, 0.0)
                val pred = altitudeRad(site, ey, s.utcMillis, s.gast)
                val d = (pred - s.altRad) / Math.toRadians(s.sigmaDeg)
                sum += d * d
                wsum += Math.toRadians(s.sigmaDeg)
            }
            return sum to wsum
        }

        // 粗网格 2°；在极区纬度上仍可行
        var best = Double.MAX_VALUE
        var bl = 0.0
        var bn = 0.0
        var lat = -80.0
        while (lat <= 80.0) {
            var lon = -180.0
            while (lon < 180.0) {
                val (c, _) = cost(lat, lon)
                if (c < best) { best = c; bl = lat; bn = lon }
                lon += 2.0
            }
            lat += 2.0
        }
        // 局部细化
        var step = 1.0
        repeat(25) {
            val (c0, _) = cost(bl, bn)
            var improved = false
            for (dl in doubleArrayOf(-step, 0.0, step)) {
                for (dn in doubleArrayOf(-step, 0.0, step)) {
                    if (dl == 0.0 && dn == 0.0) continue
                    val (c, _) = cost(bl + dl, bn + dn)
                    if (c < c0) { bl += dl; bn += dn; improved = true }
                }
            }
            if (!improved) step /= 2.0
        }
        val (cFinal, wsum) = cost(bl, bn)
        // 归一化 rms（按各观测的 σ）
        val dof = (sights.size - 2).coerceAtLeast(1)
        val rmsNorm = sqrt(cFinal / dof)
        val rmsDeg = rmsNorm * Math.toDegrees(wsum / sights.size)
        if (rmsNorm > 3.0) warnings.add("残差过大（%.1fσ），可能有云遮挡或时标错误".format(rmsNorm))
        return Fix(bl, bn, rmsDeg, sights.size, warnings)
    }


    // ---------------- 月面圆拟合（非满月也能定中心） ----------------

    class DiskFit(
        val cx: Double,
        val cy: Double,
        val radiusPx: Double,
        val inlierFraction: Double,
        /** 亮部占比（面积/圆面积）。 */
        val litFraction: Double,
        /** 明暗界线方向（rad，像面 x 轴为 0，逆时针）。 */
        val terminatorAngleRad: Double,
        /** 月缘处平均对比度（0..1）：云/雾/抖动会显著降低。 */
        val edgeScore: Double = 0.0,
        /** 凸包内点占比：部分遮挡（云穿过月面）会显著降低。 */
        val inlierFrac: Double = 0.0,
    )

    /**
     * 从亮度图拟合月面圆：阈值 → 最大连通域 → 凸包（=月缘弧，排除明暗界线）→ RANSAC 圆。
     * 2026-11-19 验证：半月/娥眉月下圆心误差 ≤0.003R；凸月必须 RANSAC，否则弦会把拟合拉偏。
     */
    fun fitDisk(
        lum: FloatArray,
        width: Int,
        height: Int,
        /** 最小连通域面积（px）；<=0 用默认的 w*h/500。月亮模式应传更小的值。 */
        minAreaPx: Int = 0,
        /** 最小拟合半径（px）。 */
        minRadiusPx: Double = 4.0,
    ): DiskFit? {
        // 阈值：Otsu（月亮远亮于天空）
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in lum) { if (v < min) min = v; if (v > max) max = v }
        if (max - min < 0.02f) return null
        // 阈值：Otsu 在"天空+暗面+亮面"三峰时会把暗面并进来，因此再要求至少是
        // 动态范围的 8%（真实图：天空~0.07、月面~0.9；合成图：0.02 / 0.03 / 0.9）
        val thr = maxOf(otsu(lum, min, max), min + 0.08f * (max - min))

        // 连通域：取面积最大的
        val visited = BooleanArray(width * height)
        var bestArea = 0
        var bestComp: IntArray = IntArray(0)
        val stack = java.util.ArrayDeque<Int>()
        val comp = ArrayList<Int>()
        for (start in 0 until width * height) {
            if (visited[start] || lum[start] <= thr) continue
            comp.clear()
            stack.push(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val cur = stack.pop()
                comp.add(cur)
                val cx = cur % width
                val cy = cur / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val ni = ny * width + nx
                    if (visited[ni] || lum[ni] <= thr) continue
                    visited[ni] = true
                    stack.push(ni)
                }
            }
            if (comp.size > bestArea) { bestArea = comp.size; bestComp = comp.toIntArray() }
        }
        val minArea = if (minAreaPx > 0) minAreaPx else width * height / 500
        if (bestArea < minArea) return null
        // 整片过曝（灯/白墙/室内）会形成百万像素级连通域：排序+凸包+RANSAC 会卡死，直接判为非月亮
        if (bestComp.size > 150_000) return null

        // 凸包（Andrew 单调链）
        val pts = bestComp.map { intArrayOf(it % width, it / width) }.sortedWith(compareBy({ it[0] }, { it[1] }))
        val hull = ArrayList<IntArray>()
        fun cross(o: IntArray, a: IntArray, b: IntArray) =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        for (p in pts) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        val lower = hull.size + 1
        for (i in pts.size - 2 downTo 0) {
            val p = pts[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        if (hull.size < 8) return null

        // RANSAC 圆拟合
        val xs = DoubleArray(hull.size) { hull[it][0].toDouble() }
        val ys = DoubleArray(hull.size) { hull[it][1].toDouble() }
        val rnd = java.util.Random(7)
        var bcx = 0.0; var bcy = 0.0; var br = 0.0; var bestIn = -1
        repeat(2000) {
            val i0 = rnd.nextInt(xs.size); val i1 = rnd.nextInt(xs.size); val i2 = rnd.nextInt(xs.size)
            if (i0 == i1 || i1 == i2 || i0 == i2) return@repeat
            val ax = xs[i0]; val ay = ys[i0]
            val bx = xs[i1]; val by = ys[i1]
            val cx0 = xs[i2]; val cy0 = ys[i2]
            val dd = 2 * (ax * (by - cy0) + bx * (cy0 - ay) + cx0 * (ay - by))
            if (abs(dd) < 1e-9) return@repeat
            val ux = ((ax * ax + ay * ay) * (by - cy0) + (bx * bx + by * by) * (cy0 - ay) + (cx0 * cx0 + cy0 * cy0) * (ay - by)) / dd
            val uy = ((ax * ax + ay * ay) * (cx0 - bx) + (bx * bx + by * by) * (ax - cx0) + (cx0 * cx0 + cy0 * cy0) * (bx - ax)) / dd
            val r = hypot(ax - ux, ay - uy)
            var inl = 0
            for (k in xs.indices) if (abs(hypot(xs[k] - ux, ys[k] - uy) - r) < 3.0) inl++
            if (inl > bestIn) { bestIn = inl; bcx = ux; bcy = uy; br = r }
        }
        if (br < minRadiusPx) return null

        // 用 RANSAC 内点做 Kasa 最小二乘精修（3 点圆的量化误差约 3 px；精修后可到 0.1 px 级）
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sx3 = 0.0; var sy3 = 0.0; var cnt = 0
        for (k in xs.indices) {
            if (abs(hypot(xs[k] - bcx, ys[k] - bcy) - br) < 3.0) {
                val xx = xs[k]; val yy = ys[k]
                sx += xx; sy += yy
                sxx += xx * xx; syy += yy * yy; sxy += xx * yy
                sx3 += xx * (xx * xx + yy * yy)
                sy3 += yy * (xx * xx + yy * yy)
                cnt++
            }
        }
        if (cnt >= 10) {
            // 解 [[sxx,sxy,sx],[sxy,syy,sy],[sx,sy,n]]·[2a,2b,c]ᵀ = [sx3,sy3,Σ(x²+y²)]
            var s2 = 0.0
            for (k in xs.indices) {
                if (abs(hypot(xs[k] - bcx, ys[k] - bcy) - br) < 3.0) s2 += xs[k] * xs[k] + ys[k] * ys[k]
            }
            val m = arrayOf(
                doubleArrayOf(sxx, sxy, sx.toDouble()),
                doubleArrayOf(sxy, syy, sy.toDouble()),
                doubleArrayOf(sx, sy, cnt.toDouble()),
            )
            val rhs = doubleArrayOf(sx3, sy3, s2)
            val det = det3x3(m)
            if (abs(det) > 1e-9) {
                val a2 = det3x3(replaceCol(m, 0, rhs)) / det
                val b2 = det3x3(replaceCol(m, 1, rhs)) / det
                val c = det3x3(replaceCol(m, 2, rhs)) / det
                val cx2 = a2 / 2.0
                val cy2 = b2 / 2.0
                val r2 = c + cx2 * cx2 + cy2 * cy2
                if (r2 > 16.0 && r2.isFinite()) {
                    bcx = cx2; bcy = cy2; br = Math.sqrt(r2)
                }
            }
        }

        // 亮部质心与明暗界线方向（质心相对圆心）
        var lx = 0.0; var ly = 0.0
        for (idx in bestComp) { lx += idx % width; ly += idx / width }
        lx /= bestComp.size; ly /= bestComp.size
        val termAng = atan2(ly - bcy, lx - bcx)
        val lit = bestArea / (Math.PI * br * br)
        var edgeSum = 0.0
        var edgeN = 0
        val samples = 72
        for (k in 0 until samples) {
            val a = 2 * Math.PI * k / samples
            val ca = cos(a); val sa = sin(a)
            val ix = (bcx + (br - 2.0) * ca).toInt()
            val iy = (bcy + (br - 2.0) * sa).toInt()
            val ox = (bcx + (br + 3.0) * ca).toInt()
            val oy = (bcy + (br + 3.0) * sa).toInt()
            if (ix < 1 || iy < 1 || ix >= width - 1 || iy >= height - 1) continue
            if (ox < 1 || oy < 1 || ox >= width - 1 || oy >= height - 1) continue
            val din = lum[iy * width + ix]
            val dout = lum[oy * width + ox]
            if (dout > thr * 3f) continue
            edgeSum += (din - dout).toDouble().coerceAtLeast(0.0)
            edgeN++
        }
        val edgeScore = if (edgeN >= 12) edgeSum / edgeN else 0.0
        return DiskFit(
            bcx, bcy, br, bestIn.toDouble() / xs.size, lit.toDouble(), termAng,
            edgeScore = edgeScore, inlierFrac = bestIn.toDouble() / xs.size,
        )
    }

    private fun otsu(lum: FloatArray, min: Float, max: Float): Float {
        val bins = 256
        val hist = IntArray(bins)
        val scale = (bins - 1) / (max - min).coerceAtLeast(1e-6f)
        for (v in lum) hist[((v - min) * scale).toInt().coerceIn(0, bins - 1)]++
        val total = lum.size
        var sum = 0.0
        for (i in 0 until bins) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var bestI = 0
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestI = i }
        }
        return min + bestI / scale
    }

    private fun normPi(x: Double): Double {
        var v = x % (2 * Math.PI)
        if (v > Math.PI) v -= 2 * Math.PI
        if (v < -Math.PI) v += 2 * Math.PI
        return v
    }
    private fun det3x3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    private fun replaceCol(m: Array<DoubleArray>, col: Int, v: DoubleArray): Array<DoubleArray> =
        Array(3) { r -> DoubleArray(3) { c -> if (c == col) v[r] else m[r][c] } }
}

/** 一次月亮观测（帧级）：时间、测站无关的高度角、月面拟合结果。 */
class MoonObservation(
    val utcMillis: Long,
    val gast: Double,
    /** 测得的高度角（rad）。 */
    val altRad: Double,
    val disk: Moon.DiskFit,
    /** 由星历视半径与焦距算出的期望像素半径。 */
    val expectedRadiusPx: Double,
    /** 星历给出的亮部占比。 */
    val expectedLitFraction: Double,
)

/**
 * 帧间质量门控：检测云、雾、遮挡。
 *
 * 判据（2026-11-19 设计）：
 *  1. 拟合半径与星历视半径偏差 >12% → 月面被切开（云/建筑遮挡）或拟合失败；
 *  2. 亮部占比与星历相位差 >0.20 → 有云遮住了部分亮面；
 *  3. 月缘对比度低于同组中位数的 35% → 薄云/雾/剧烈抖动（边缘变软）；
 *  4. 凸包内点占比 <0.55 → 部分遮挡；
 *  5. 视场裁切（月亮贴边）→ 半径不可靠。
 */
object MoonQuality {

    class Verdict(val accepted: Boolean, val reasons: List<String>, val sigmaDeg: Double)

    fun check(
        obs: MoonObservation,
        width: Int,
        height: Int,
        medianEdgeScore: Double,
    ): Verdict {
        val reasons = ArrayList<String>()
        val d = obs.disk
        // 贴边检查
        val margin = d.radiusPx * 1.02
        if (d.cx - margin < 0 || d.cy - margin < 0 || d.cx + margin > width || d.cy + margin > height) {
            reasons.add("月亮贴边（画面裁切），半径不可靠")
        }
        val rDev = (d.radiusPx - obs.expectedRadiusPx) / obs.expectedRadiusPx
        if (abs(rDev) > 0.12) {
            reasons.add("半径偏差 %.0f%%（疑遮挡/拟合失败）".format(rDev * 100))
        }
        val litDev = abs(d.litFraction - obs.expectedLitFraction)
        if (litDev > 0.20) {
            reasons.add("亮部占比偏差 %.2f（疑云遮）".format(litDev))
        }
        if (medianEdgeScore > 0.0 && d.edgeScore < 0.35 * medianEdgeScore) {
            reasons.add("月缘对比度仅中位数的 %.0f%%（疑薄云/雾/抖动）"
                .format(100 * d.edgeScore / medianEdgeScore))
        }
        if (d.inlierFrac < 0.55) {
            reasons.add("月缘内点仅 %.0f%%（疑部分遮挡）".format(d.inlierFrac * 100))
        }
        // σ：基础 0.05°，边缘越软越差
        val ratio = if (medianEdgeScore > 0.0) (d.edgeScore / medianEdgeScore).coerceIn(0.3, 1.5) else 1.0
        val sigma = 0.05 / ratio.coerceAtLeast(0.5)
        return Verdict(reasons.isEmpty(), reasons, sigma)
    }

    /** 过滤 + 解算：返回 (解, 被剔除的帧与原因)。 */
    fun solve(observations: List<MoonObservation>, width: Int, height: Int): Pair<Moon.Fix?, List<String>> {
        if (observations.isEmpty()) return null to listOf("没有观测帧")
        val edges = observations.map { it.disk.edgeScore }.sorted()
        val median = edges[edges.size / 2]
        val sights = ArrayList<Moon.Sight>()
        val dropped = ArrayList<String>()
        for (o in observations) {
            val v = check(o, width, height, median)
            if (v.accepted) {
                val ephem = Moon.ephemerisAt(o.utcMillis)
                sights.add(
                    Moon.Sight(
                        o.utcMillis, o.gast, o.altRad, v.sigmaDeg,
                        ephem.raRad, ephem.decRad, ephem.distanceKm,
                    ),
                )
            } else {
                dropped.add("%s: %s".format(
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(o.utcMillis)),
                    v.reasons.joinToString("；"),
                ))
            }
        }
        if (sights.size < 2) return null to (dropped + "有效观测不足（${sights.size}）")
        // 几何条件：单一天体需要足够的时间跨度（方位角变化）；多天体（不同赤经）可直接解
        var distinctBodies = false
        for (i in sights.indices) for (j in i + 1 until sights.size) {
            val sep = V.angle(
                V.fromRaDec(sights[i].raRad, sights[i].decRad),
                V.fromRaDec(sights[j].raRad, sights[j].decRad),
            )
            if (sep > Math.toRadians(10.0)) distinctBodies = true
        }
        if (!distinctBodies && sights.size < 3) return null to (dropped + "单一天体需 ≥3 次观测（或多天体同帧）")
        return Moon.solveOrbit(sights) to dropped
    }
}



