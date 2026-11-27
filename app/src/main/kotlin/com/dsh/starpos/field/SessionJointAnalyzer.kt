// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import android.content.Context
import com.dsh.starpos.android.AccelBiasStore
import com.dsh.starpos.android.CatalogHolder
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import java.io.File
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * 从采集包提取联合解算观测（结果页用）：
 *  - sun-/ session-* 备注含 SUN → 太阳视高度（盒均值定心 + 重力天顶）；
 *  - moon-* → 月亮视高度（圆面拟合 + 重力天顶）；
 *  - 其它（star/ext/cal）暂不参与高度联合解（星光 FixObs 待接）。
 * 只做读包/取观测，不写文件、不联网。
 */
object SessionJointAnalyzer {

    data class MirrorFrame(
        val index: Int,
        val mirrorAltDeg: Double,
        val imuAltDeg: Double,
        val zenithDiffDeg: Double,
        val residualDeg: Double,
        val ephemDiffDeg: Double?,
    )

    data class MirrorReport(
        val frames: List<MirrorFrame>,
        val medianMirrorAltDeg: Double,
        val medianImuAltDeg: Double,
        val medianZenithDiffDeg: Double,
        val medianResidualDeg: Double,
        val medianEphemDiffDeg: Double?,
        val note: String,
    )

    data class SessionData(
        val file: File,
        val kind: String,
        val note: String,
        val truthLatDeg: Double?,
        val truthLonDeg: Double?,
        val truthAccuracyM: Double?,
        val observations: List<JointSolver.AltObs>,
        /** 星光/两体的 2D 位置观测（1σ 椭圆；每包最多 1 个）。 */
        val fixObs: JointSolver.FixObs? = null,
        val error: String? = null,
        /** 水镜采集标记与镜头高度（新包记录；旧包为 false/null）。 */
        val mirrorEnabled: Boolean = false,
        val mirrorMeta: Boolean = false,
        val mirrorHeightM: Double? = null,
        val mirrorReport: MirrorReport? = null,
    )

    fun kindOf(file: File): String = when {
        file.name.startsWith("sun-") -> "sun"
        file.name.startsWith("moon-") -> "moon"
        file.name.startsWith("star-") -> "star"
        file.name.startsWith("ext-") -> "ext"
        file.name.startsWith("cal-") -> "cal"
        else -> "legacy"
    }

    fun extract(context: Context, file: File, starVertical: StarVertical? = null, treatAsMirror: Boolean = false): SessionData {
        val kind = kindOf(file)
        if (kind == "ext" || kind == "cal") {
            return SessionData(file, kind, "", null, null, null, emptyList(), null, "该类型包不参与自动解算（标定包）")
        }
        if (kind == "star") {
            return runCatching {
                BundleReader.open(file).use { reader ->
                    val noteText = reader.manifest.note ?: ""
                    if (noteText.contains("EXTRINSIC", true) || noteText.contains("CALIBRATION", true) ||
                        noteText.contains("标定", true)
                    ) {
                        return SessionData(
                            file, kind, noteText, null, null, null, emptyList(), null,
                            "该包是标定包（${noteText}），不能当星光定位数据使用",
                        )
                    }
                    val truth = reader.readGnss().let { g ->
                        if (g.isEmpty()) null else Triple(g.sumOf { it.lat } / g.size, g.sumOf { it.lon } / g.size, g.sumOf { it.accuracyM } / g.size)
                    }
                    val catalog = CatalogHolder.catalog(context)
                    // 老包 manifest 没有记录加速度零偏/映射：必须把本机已标定的兜底值传进去，
                    // 否则重力天顶会系统偏 ~1.4°，星光单包可差 200 km 量级。
                    val bias = AccelBiasStore.load(context)?.bias
                    val map = AccelBiasStore.loadMap(context)
                    val analysis = FieldAnalyzer.analyze(
                        reader, catalog, ExtrinsicConfig.load(context), starVertical,
                        accelBiasOverride = bias, accelMapOverride = map,
                    )
                    val fixes = analysis.frames.mapNotNull { f ->
                        val fx = f.fix ?: return@mapNotNull null
                        val el = f.ellipse ?: return@mapNotNull null
                        Triple(fx, el, f.matched)
                    }
                    if (fixes.isEmpty()) {
                        SessionData(
                            file, kind, reader.manifest.note ?: "", truth?.first, truth?.second, truth?.third,
                            emptyList(), null,
                            "星图解算未得到位置（0 帧成功）" + (analysis.findings.firstOrNull()?.let { "：$it" } ?: ""),
                        )
                    } else {
                        fun median(v: List<Double>): Double = v.sorted()[v.size / 2]
                        val lat = median(fixes.map { Math.toRadians(it.first.latDeg) })
                        val lon = median(fixes.map { Math.toRadians(it.first.lonDeg) })
                        val major95 = median(fixes.map { it.second.semiMajorM })
                        val minor95 = median(fixes.map { it.second.semiMinorM })
                        val az = fixes.first().second.azimuthDeg
                        val fix = JointSolver.FixObs(
                            latRad = lat, lonRad = lon,
                            sigmaMajorM = (major95 / 2.4477).coerceAtLeast(1.0),
                            sigmaMinorM = (minor95 / 2.4477).coerceAtLeast(1.0),
                            azimuthDeg = az,
                            session = file.name.removeSuffix(".zip"),
                        )
                        SessionData(
                            file, kind, reader.manifest.note ?: "", truth?.first, truth?.second, truth?.third,
                            emptyList(), fix, null,
                        )
                    }
                }
            }.getOrElse {
                SessionData(file, kind, "", null, null, null, emptyList(), null, "星图解算异常：${it.javaClass.simpleName}: ${it.message}")
            }
        }
        return runCatching {
            BundleReader.open(file).use { reader ->
                val intr = reader.manifest.intrinsics
                    ?: return SessionData(file, kind, "", null, null, null, emptyList(), null, "包内没有内参")
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                val imu = reader.readImu()
                val extrinsic = ExtrinsicConfig.load(context)
                val cap = reader.manifest.capture
                val mirrorOn = (cap["mirror_enabled"] as? Boolean) ?: false
                val mirrorH = (cap["mirror_height_m"] as? Number)?.toDouble()
                val gnss = reader.readGnss()
                val truth = if (gnss.isNotEmpty()) {
                    val n = gnss.size
                    Triple(gnss.sumOf { it.lat } / n, gnss.sumOf { it.lon } / n, gnss.sumOf { it.accuracyM } / n)
                } else null
                val obs = ArrayList<JointSolver.AltObs>()
                val mirrorFrames = ArrayList<MirrorFrame>()
                val treatMirror = mirrorOn || treatAsMirror
                val site = truth?.let { Moon.Site(Math.toRadians(it.first), Math.toRadians(it.second)) }
                val isSun = kind == "sun" ||
                    (reader.manifest.note ?: "").contains("SUN", true) ||
                    (reader.manifest.note ?: "").contains("太阳")
                AccelBiasStore.load(context)?.let { FieldAnalyzer.accelBias = it.bias }
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val grav = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!grav.valid) continue
                    val up = V.unit(grav.upCam)
                    if (treatMirror) {
                        detectMirrorPair(f.luminance, f.width, f.height, cam, up)?.let { (pair, dpx) ->
                            val uD = V.unit(cam.unproject(dpx[0], dpx[1]))
                            val imuAlt = Math.toDegrees(
                                asin((uD[0] * up[0] + uD[1] * up[1] + uD[2] * up[2]).coerceIn(-1.0, 1.0)),
                            )
                            val zenith = Math.toDegrees(
                                Math.acos(
                                    (pair.upCam[0] * up[0] + pair.upCam[1] * up[1] + pair.upCam[2] * up[2])
                                        .coerceIn(-1.0, 1.0),
                                ),
                            )
                            val ephemAlt = site?.let { st ->
                                val ctx = SkyContext.at(meta.wallClockUtcMs)
                                val ep = if (isSun) Moon.sunEphemerisAt(meta.wallClockUtcMs) else Moon.ephemerisAt(meta.wallClockUtcMs)
                                Math.toDegrees(Moon.altitudeRad(st, ep, meta.wallClockUtcMs, ctx.gast))
                            }
                            mirrorFrames.add(
                                MirrorFrame(
                                    index = meta.index,
                                    mirrorAltDeg = Math.toDegrees(pair.apparentAltRad),
                                    imuAltDeg = imuAlt,
                                    zenithDiffDeg = zenith,
                                    residualDeg = pair.residualDeg,
                                    ephemDiffDeg = ephemAlt?.let { it - Math.toDegrees(pair.apparentAltRad) },
                                ),
                            )
                        }
                    }
                    val center: Pair<Double, Double>? = if (isSun) {
                        sunCenter(f.luminance, f.width, f.height)
                    } else {
                        val fit = Moon.fitDisk(f.luminance, f.width, f.height, minAreaPx = 32, minRadiusPx = 2.5)
                        fit?.let { it.cx to it.cy }
                    }
                    val c = center ?: continue
                    val dir = V.unit(cam.unproject(c.first, c.second))
                    val alt = asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0))
                    val sigma = Math.toRadians(sqrt(grav.sigmaDeg * grav.sigmaDeg + 0.05 * 0.05))
                    obs.add(
                        JointSolver.AltObs(
                            utcMs = meta.wallClockUtcMs,
                            body = if (isSun) JointSolver.Body.SUN else JointSolver.Body.MOON,
                            bodyName = if (isSun) "太阳" else "月亮",
                            apparentAltRad = alt,
                            sigmaRad = sigma,
                            session = file.name.removeSuffix(".zip"),
                        ),
                    )
                }
                fun med(v: List<Double>): Double = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]
                val report = if (treatMirror) {
                    MirrorReport(
                        frames = mirrorFrames,
                        medianMirrorAltDeg = med(mirrorFrames.map { it.mirrorAltDeg }),
                        medianImuAltDeg = med(mirrorFrames.map { it.imuAltDeg }),
                        medianZenithDiffDeg = med(mirrorFrames.map { it.zenithDiffDeg }),
                        medianResidualDeg = med(mirrorFrames.map { it.residualDeg }),
                        medianEphemDiffDeg = mirrorFrames.mapNotNull { it.ephemDiffDeg }.takeIf { it.isNotEmpty() }?.let { med(it) },
                        note = if (mirrorFrames.isEmpty()) "未检测到成对直射/反射像（需同框、反射足够亮）" else "",
                    )
                } else null
                SessionData(
                    file, kind, reader.manifest.note ?: "",
                    truth?.first, truth?.second, truth?.third,
                    obs, null, null, treatMirror, mirrorOn, mirrorH, report,
                )
            }
        }.getOrElse {
            SessionData(file, kind, "", null, null, null, emptyList(), null, "${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 反射检测：优先用 MirrorPair.findPair（星点检测配对）；失败时退化为
     * "最亮盒均值=直射、屏蔽直射附近后次亮=反射"（水镜太阳常用）。
     */
    private fun detectMirrorPair(
        lum: FloatArray,
        w: Int,
        h: Int,
        cam: com.dsh.starpos.astro.CameraIntrinsics,
        up: DoubleArray,
    ): Pair<MirrorPair.Result, DoubleArray>? {
        MirrorPair.findPair(lum, w, h, cam, thresholdSigma = 5.0, upHint = up)?.let {
            return it.first to it.second
        }
        val r = 5
        val step = 2
        fun peak(mask: (Int, Int) -> Boolean): Triple<Int, Int, Double>? {
            var best = Double.NEGATIVE_INFINITY
            var bx = -1; var by = -1
            var y = r
            while (y < h - r) {
                var x = r
                while (x < w - r) {
                    if (mask(x, y)) {
                        var s = 0.0
                        var dy = -r
                        while (dy <= r) { var dx = -r; while (dx <= r) { s += lum[(y + dy) * w + (x + dx)]; dx++ }; dy++ }
                        val m = s / ((2 * r + 1) * (2 * r + 1))
                        if (m > best) { best = m; bx = x; by = y }
                    }
                    x += step
                }
                y += step
            }
            return if (bx < 0) null else Triple(bx, by, best)
        }
        val direct = peak { _, _ -> true } ?: return null
        val (dx, dy, dMean) = direct
        val maskR = (Math.min(w, h) * 0.05).coerceAtLeast(30.0).toInt()
        val refl = peak { x, y -> (x - dx) * (x - dx) + (y - dy) * (y - dy) > maskR * maskR } ?: return null
        val (rx, ry, rMean) = refl
        if (rMean < 0.02 * dMean) return null
        val uD = V.unit(cam.unproject(dx.toDouble(), dy.toDouble()))
        val uR = V.unit(cam.unproject(rx.toDouble(), ry.toDouble()))
        val pair = MirrorPair.solve(uD, uR, upHint = up) ?: return null
        if (pair.residualDeg > 8.0) return null
        return pair to doubleArrayOf(dx.toDouble(), dy.toDouble())
    }

    /** 5×5 盒均值最大点（太阳圆面中心；城市/滤镜太阳成像很小）。 */
    fun sunCenter(lum: FloatArray, w: Int, h: Int): Pair<Double, Double>? {
        val r = 5
        var best = -1.0
        var bx = -1
        var by = -1
        var y = r
        while (y < h - r) {
            var x = r
            while (x < w - r) {
                var s = 0.0
                var dy = -r
                while (dy <= r) {
                    var dx = -r
                    val row = (y + dy) * w
                    while (dx <= r) {
                        s += lum[row + (x + dx)]
                        dx++
                    }
                    dy++
                }
                if (s > best) { best = s; bx = x; by = y }
                x += 3
            }
            y += 3
        }
        return if (bx < 0) null else bx.toDouble() to by.toDouble()
    }
}
