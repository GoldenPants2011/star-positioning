// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.ErrorEllipse
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.GeoFix
import com.dsh.starpos.astro.GravityEstimate
import com.dsh.starpos.astro.GravityEstimator
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.Precession
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.mul
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import java.io.File
import kotlin.math.*

class FrameAnalysis(
    val index: Int,
    val detections: Int,
    val matched: Int,
    val rmsArcsec: Double,
    val attitudeSigmaDeg: Double,
    val starsUsed: Int,
    val cloudCover: Double,
    val maxPsfAspect: Double,
    val gravitySigmaDeg: Double,
    val gravityStatic: Boolean,
    val imuSamples: Int,
    val fix: GeoFix?,
    val ellipse: ErrorEllipse?,
    /** 与 GNSS 真值的地面距离（km），无真值时 null。 */
    val truthErrorKm: Double?,
    val reason: String?,
    val warnings: List<String>,
    val elapsedMs: Long,
)

class SessionAnalysis(
    val sessionId: String,
    val createdAtUtcMs: Long,
    val intrinsics: IntrinsicsRecord,
    val cameraLine: String,
    val frames: List<FrameAnalysis>,
    val findings: List<String>,
    val truth: GnssRow?,
    val imuRows: Int,
    val gnssRows: Int,
    val notes: String,
)

/**
 * 离线分析：输入一个采集包 + 星表，输出逐帧结果与结论。
 * 纯 JVM，可在单测/命令行里跑，不需要 Android 设备。
 */
object FieldAnalyzer {

    /**
     * 加速度计零偏（设备系，m/s²）；由六位置标定写入，gravityAt 里先从原始加速度扣掉。
     * null = 未标定（不做任何修正——错误的确定性比错误的数值更危险）。
     */
    @Volatile
    var accelBias: DoubleArray? = null

    /**
     * 加速度计刻度/轴间串扰矩阵（行优先 9 个数）：accel_corrected = S · (accel - bias)。
     * 由星空天顶自标定（JointCalibTest），六位置法只能标零偏、标不了刻度。
     */
    @Volatile
    var accelMap: DoubleArray? = null

    /**
     * 真机原始帧的默认检测阈值（合成用例仍用 5σ）。
     * 依据 2026-11-19 对照实验（阈值 × 中值预滤波，三组真机数据）：
     * 5σ 检测被噪声长尾支配（294 个点）；7σ 检测降到 55–82 且三组都能解出；
     * 中值预滤波会把 1–2 px 的暗星糊掉，012017 在开了中值后全部解不出 → **不开**。
     */
    const val REAL_THRESHOLD_SIGMA = 7.0

    fun analyze(
        reader: BundleReader,
        catalog: StarCatalog,
        extrinsic: ExtrinsicConfig? = null,
        /**
         * 仅星光定位分支的垂直修正（设备系小倾角；不影响日月/两体/水镜分支）。
         * 2026-11-24 用旧星光会话 + GNSS 真值拟合，留一会话交叉验证中位 ~25 km。
         */
        starVertical: StarVertical? = StarVertical.DEFAULT,
        /** 老会话没有 manifest 零偏时的外部兜底（m/s²，设备系）。 */
        accelBiasOverride: DoubleArray? = null,
        accelMapOverride: DoubleArray? = null,
        /** 星解 R 是否需要 ICRS→TOD 变换（真实数据 true；合成数据自洽约定可关）。 */
        applyPrecession: Boolean = true,
    ): SessionAnalysis {
        val manifest = reader.manifest
        val intrinsics = manifest.intrinsics ?: estimateIntrinsics(manifest.camera)
            ?: throw IllegalArgumentException("采集包既没有内参，也没有可用于推算的相机能力信息")

        // 零偏优先级：会话内记录（新包）> 调用方兜底（老包）> 全局静态（App 内已加载）
        val accelBiasEff: DoubleArray? = manifest.accelBiasMg?.let { a ->
            doubleArrayOf(a[0] * 9.80665e-3, a[1] * 9.80665e-3, a[2] * 9.80665e-3)
        } ?: accelBiasOverride
        val accelMapEff: DoubleArray? = manifest.accelMap ?: accelMapOverride

        val index = SkyIndex(catalog)
        val solverCache = HashMap<String, PlateSolver>()
        fun solverFor(width: Int, height: Int): PlateSolver {
            val cam = intrinsics.toCameraIntrinsics(width, height)
            val key = "${width}x$height:${"%.4f".format(cam.fx)}:${"%.4f".format(cam.cx)}"
            // 残差门限必须按像素尺度给：广视场手机 1 px 就可能 100″+，固定 60″ 会把正确解全挡掉
            val pixelScaleArcsec = 206264.8 / cam.fx
            val residualCap = max(60.0, 2.0 * pixelScaleArcsec)
            return solverCache.getOrPut(key) {
                PlateSolver(
                    index, cam,
                    triangleMagLimit = com.dsh.starpos.astro.StarSolveParams.triangleMag(cam),
                    maxResidualArcsec = residualCap,
                )
            }
        }

        val imu = reader.readImu()
        val gnss = reader.readGnss()
        val lastKnown = reader.readLastKnown()
        val truth = pickTruth(manifest.gnssTruth, gnss)
        val findings = ArrayList<String>()
        val frames = ArrayList<FrameAnalysis>()

        // 预加载所有帧，并额外生成一帧"叠加帧"（对齐平均，SNR×√N）
        val entries = ArrayList<Triple<FrameMeta, LoadedFrame, Boolean>>()
        val stackFrames = ArrayList<FloatArray>()
        var sw = 0
        var sh = 0
        for (m in reader.frameMetas()) {
            val lf = runCatching { reader.loadFrame(m.index) }.getOrElse { e ->
                findings.add("帧 ${m.index}: 像素数据无法读取（${e.message}）")
                continue
            }
            entries.add(Triple(m, lf, false))
            if (sw == 0) { sw = lf.width; sh = lf.height }
            if (lf.width == sw && lf.height == sh) stackFrames.add(lf.luminance)
        }
        if (stackFrames.size >= 3) {
            val st = com.dsh.starpos.astro.Stacker.stack(stackFrames, sw, sh)
            if (st != null) {
                findings.add("叠加帧：" + st.message + "（位移 " + st.shifts.joinToString { "${it.first}/${it.second}" } + "）")
                entries.add(Triple(entries.first().first, LoadedFrame(entries.first().first, sw, sh, 16, st.image), true))
            }
        }

        for ((meta, loaded, isStack) in entries) {
            val t0 = System.nanoTime()
            val camera = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
            val gravity = gravityAt(imu, meta, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
            // 自动阈值阶梯：默认 7σ；星太少就放宽到 5.5σ，检测过多（噪声主导）再收紧到 9σ。
            // 这样不同光污染/不同图像类型不用人工调阈值（2026-11-19 对照实验：7σ 对原始帧与
            // 处理过的天文照片都优于 5σ）。
            val solver = solverFor(loaded.width, loaded.height)
            val starParams = com.dsh.starpos.astro.StarSolveParams
            val starMinMatched = if (starParams.isWide(camera)) 4 else 5
            fun starCandidates(dets: List<com.dsh.starpos.astro.Detection>) =
                dets.filter { starParams.accept(it, camera) }
            val context = SkyContext.at(meta.wallClockUtcMs)
            var detection = FrameAnalyzer.analyze(
                loaded.luminance, loaded.width, loaded.height,
                thresholdSigma = REAL_THRESHOLD_SIGMA, medianPrefilter = false,
            )
            var usedSigma = REAL_THRESHOLD_SIGMA
            var plate = runCatching {
                solver.solve(
                    starCandidates(detection.detections),
                    maxImageStars = starParams.maxImageStars(camera),
                    matchToleranceDeg = starParams.matchToleranceDeg(camera),
                    upCam = if (gravity.valid) gravity.upCam else null,
                )
            }.getOrNull()
            val fallbackSigmas = if (starParams.isWide(camera)) doubleArrayOf(5.5, 4.0, 5.0, 9.0)
            else doubleArrayOf(5.5, 4.0, 9.0)
            for (fallback in fallbackSigmas) {
                if (plate != null && plate!!.matchedStars >= starMinMatched) break
                val d2 = FrameAnalyzer.analyze(
                    loaded.luminance, loaded.width, loaded.height,
                    thresholdSigma = fallback, medianPrefilter = false,
                )
                val p2 = runCatching {
                    solver.solve(
                        starCandidates(d2.detections),
                        maxImageStars = starParams.maxImageStars(camera),
                        matchToleranceDeg = starParams.matchToleranceDeg(camera),
                        upCam = if (gravity.valid) gravity.upCam else null,
                    )
                }.getOrNull()
                if (p2 != null && (plate == null || p2.matchedStars > plate!!.matchedStars)) {
                    detection = d2
                    plate = p2
                    usedSigma = fallback
                }
            }

            var fix: GeoFix? = null
            var ellipse: ErrorEllipse? = null
            var reason: String? = null
            val warnings = ArrayList<String>()
            if (usedSigma != REAL_THRESHOLD_SIGMA) {
                warnings.add("自动阈值阶梯：默认 %.1fσ 未解出，改用 %.1fσ".format(REAL_THRESHOLD_SIGMA, usedSigma))
            }

            // 拖尾判据用"长条检测的占比"，不用单个最大值：
            // 一颗近距离双星/饱和团块就能把 maxPsfAspect 拉到几十，误杀整帧（2026-11-19 复现）。
            val brightDets = detection.detections.take(60)
            val elongatedFrac = if (brightDets.isEmpty()) 0.0
            else brightDets.count { it.aspect > 2.5 }.toDouble() / brightDets.size
            if (detection.detections.size < 8) reason = "TOO_FEW_STARS"
            else if (detection.quality.cloudCover > 0.7) reason = "CLOUD_COVER_TOO_HIGH"
            else if (elongatedFrac > 0.35) reason = "TRAILING_DETECTED"
            else if (plate == null) reason = "PLATE_SOLVE_FAILED"
            else if (plate.matchedStars < starMinMatched) reason = "TOO_FEW_MATCHED_STARS"
            else if (3 * plate.attitudeSigmaDeg > 0.30) reason = "ATTITUDE_COVARIANCE_EXCEEDED"
            else if (!gravity.valid) reason = "NOT_STATIC"
            else if (gravity.sigmaDeg > 0.5) reason = "NOT_STATIC"   // 晃动导致重力方向不确定，单历元定位不可信
            else {
                // 仅星光分支的垂直修正（设备系小倾角，标定值；不改变重力/日月分支）
                val upForPos = if (starVertical != null) {
                    starVertical.correctUp(gravity.upCam, extrinsic, manifest.camera?.sensorOrientation ?: 90)
                } else gravity.upCam
                // 星解 R: camera→ICRS(J2000)；PositionSolver 的 lon = RA − GAST 需要 TOD（真赤道真春分点）
                val rotTod = if (applyPrecession) mul(Precession.icrsToTod(context.jdTt), plate.rotation) else plate.rotation
                val pos = PositionSolver.solve(
                    rotation = rotTod,
                    upCam = upForPos,
                    context = context,
                    gravitySigmaDeg = gravity.sigmaDeg,
                    attitudeSigmaDeg = plate.attitudeSigmaDeg,
                    timeUncertaintySec = 0.5,
                )
                fix = pos.fix
                ellipse = pos.ellipse
                warnings.addAll(pos.warnings)
            }

            val truthErrorKm = if (fix != null && truth != null && truth.lat != 0.0) {
                haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
            } else null

            val ms = (System.nanoTime() - t0) / 1_000_000
            frames.add(
                FrameAnalysis(
                    index = if (isStack) -1 else meta.index,
                    detections = detection.detections.size,
                    matched = plate?.matchedStars ?: 0,
                    rmsArcsec = plate?.rmsArcsec ?: 0.0,
                    attitudeSigmaDeg = plate?.attitudeSigmaDeg ?: 0.0,
                    starsUsed = detection.detections.size,
                    cloudCover = detection.quality.cloudCover,
                    maxPsfAspect = detection.quality.maxPsfAspect,
                    gravitySigmaDeg = gravity.sigmaDeg,
                    gravityStatic = gravity.isStatic,
                    imuSamples = gravity.samples,
                    fix = fix,
                    ellipse = ellipse,
                    truthErrorKm = truthErrorKm,
                    reason = reason,
                    warnings = warnings,
                    elapsedMs = ms,
                ),
            )
        }

        // ---- 结论 ----
        if (imu.isEmpty()) {
            val imuMeta = manifest.capture.sub("imu")
            findings.add(
                buildString {
                    append("本次采集**没有任何 IMU 数据**")
                    if (imuMeta.isNotEmpty()) {
                        val err = imuMeta.str("error")
                        append("（采样周期 ${imuMeta.int("requested_delay_us", -1)} µs")
                        if (err.isNotBlank()) append("，$err")
                        append("）")
                    }
                    append("：拿不到重力天顶，只能检查成像与星图匹配，**无法出位置**。")
                },
            )
        }
        val fixes = frames.filter { it.fix != null }
        if (frames.isEmpty()) findings.add("采集包里没有任何帧，无法分析。")
        if (frames.isNotEmpty() && fixes.isEmpty()) {
            findings.add("所有帧都没有解出位置。优先排查：内参是否可用（当前来源=${intrinsics.source}）、曝光是否足够、是否静止。")
        }
        when (intrinsics.source) {
            "estimated" -> findings.add(
                "内参来自「焦距+像元」推算，主点按图像中心假设；焦距误差 1% 会引起约 0.5° 量级的姿态/位置偏差。",
            )
            "assumed" -> findings.add("内参为兜底猜测值，结果只能用于链路检查，不能用于精度评估。")
        }
        if (frames.any { !it.gravityStatic }) findings.add("有帧在采集时不是静止状态（加速度计波动或陀螺超阈值），对应结果不可信。")
        if (frames.any { it.cloudCover > 0.5 }) findings.add("云量估计偏高（>0.5），星点识别可能不完整。")
        if (frames.any { it.maxPsfAspect > 3.0 }) findings.add("检测到拖尾（PSF 长宽比 >3）：曝光期间手机晃动，需要更稳的支撑或更短曝光。")
        val truthErr = fixes.mapNotNull { it.truthErrorKm }
        if (truthErr.isNotEmpty()) {
            val median = truthErr.sorted()[truthErr.size / 2]
            findings.add("与 GNSS 真值中位偏差 %.1f km（T1 验收线 ≤50 km）。".format(median))
            if (median > 50) findings.add("中位偏差超出 T1 验收线：需要检查标定/内参/时间同步，而不是继续调算法。")
        } else if (truth == null) {
            findings.add(
                "采集包里**没有本次会话的实时 GNSS 真值**" +
                    if (lastKnown != null) "（只有系统最后已知位置，已忽略：可能来自别的时间/地点）" else "（也没有系统最后已知位置）" +
                    "：只能检查链路能否解出结果，无法做精度验收。",
            )
        } else {
            findings.add("有 GNSS 真值但没有任何一帧出解，先看上面的逐帧原因。")
        }
        if (truth != null) {
            findings.add("真值来源：%s，水平精度 ±%.0f m。".format(truth.provider, truth.accuracyM))
        }
        val gnssMeta = manifest.capture.sub("gnss")
        if (gnssMeta.isNotEmpty() && gnssMeta.int("fix_count", -1) == 0) {
            findings.add(
                "本次采集没有收到实时定位（权限=%s，GPS 开启=%s，等待 %.1f s）。".format(
                    gnssMeta.str("permission", "?"),
                    gnssMeta.bool("provider_enabled"),
                    gnssMeta.long("wait_ms") / 1000.0,
                ),
            )
        }
        // ===== 多帧姿态融合：把本会话所有"解算成功"的帧姿态加权平均，压随机误差（≈√N） =====
        run {
            val ok = frames.filter { it.reason == null && it.matched >= 5 }
            if (ok.size >= 2) {
                // 逐帧需要姿态矩阵：重新求解一次（复用同样的检测/解算参数）
                val rots = ArrayList<Pair<Array<DoubleArray>, Double>>()
                val usedMetas = ArrayList<FrameMeta>()
                for (meta in reader.frameMetas()) {
                    val loaded = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    if (frames.none { it.index == meta.index && it.reason == null && it.matched >= 5 }) continue
                    val camera = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                    val solver = solverFor(loaded.width, loaded.height)
                    val det = FrameAnalyzer.analyze(
                        loaded.luminance, loaded.width, loaded.height,
                        thresholdSigma = REAL_THRESHOLD_SIGMA, medianPrefilter = false,
                    )
                    val sol = runCatching {
                        solver.solve(
                            det.detections.filter { com.dsh.starpos.astro.StarSolveParams.accept(it, camera) },
                            maxImageStars = com.dsh.starpos.astro.StarSolveParams.maxImageStars(camera),
                            matchToleranceDeg = com.dsh.starpos.astro.StarSolveParams.matchToleranceDeg(camera),
                        )
                    }.getOrNull() ?: continue
                    val minMatched = if (com.dsh.starpos.astro.StarSolveParams.isWide(camera)) 4 else 5
                    if (sol.matchedStars < minMatched) continue
                    rots.add(sol.rotation to sol.matchedStars.toDouble())
                    usedMetas.add(meta)
                }
                if (rots.size >= 2) {
                    val fused = com.dsh.starpos.astro.QuaternionAverage.average(
                        rots.map { it.first }, rots.map { it.second },
                    )
                    if (fused != null) {
                        val firstMeta = usedMetas.first()
                        val g = gravityAt(
                            imu, firstMeta, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic,
                        )
                        val ctx = SkyContext.at(firstMeta.wallClockUtcMs)
                        if (g.valid) {
                            val pos = PositionSolver.solve(
                                fused, g.upCam, ctx, g.sigmaDeg,
                                Math.toRadians(0.05), 0.005,
                            )
                            val fx = pos.fix
                            val errKm = if (fx != null && truth != null) {
                                haversineKm(fx.latDeg, fx.lonDeg, truth.lat, truth.lon)
                            } else null
                            findings.add(
                                ("多帧融合：%d 帧姿态加权平均 → %.5f, %.5f（95%% 半长轴 %.1f km）%s").format(
                                    rots.size,
                                    fx?.latDeg ?: Double.NaN, fx?.lonDeg ?: Double.NaN,
                                    (pos.ellipse?.semiMajorM ?: Double.NaN) / 1000.0,
                                    if (errKm != null) "，与真值差 %.1f km".format(errKm) else "",
                                ),
                            )
                        }
                    }
                }
            }
        }
        // ===== 太阳模式：帧内日心 → 高度角 → 多历元联合定位 =====
        // 说明：太阳与月亮共用"圆面拟合 + IMU 重力定高度角"链路；
        // 太阳距离约 1 AU，视差可忽略；方位未知（无航向），故只用高度角。
        val sunFrames = HashSet<Int>()
        run {
            val sunSights = ArrayList<com.dsh.starpos.astro.Moon.Sight>()
            var sunCount = 0
            val sunResid = ArrayList<Double>()
            if (truth == null) {
                findings.add("太阳：本包没有 GNSS 真值，跳过太阳识别（避免把月亮误认成太阳）。")
            }
            for (meta in if (truth != null) reader.frameMetas() else emptyList()) {
                val loaded = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                val sun = runCatching { com.dsh.starpos.astro.Moon.sunEphemerisAt(meta.wallClockUtcMs) }.getOrNull() ?: continue
                val peak = detectSunPeak(loaded.luminance, loaded.width, loaded.height) ?: continue
                val cam = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                // 太阳半径 696,340 km；不能用 Ephemeris.angularRadiusRad（那是按月球半径算的）
                val sunAngRad = com.dsh.starpos.astro.Moon.SUN_RADIUS_KM / sun.distanceKm
                val expectedPx = Math.tan(sunAngRad) * cam.fx
                // 面积法半径：过曝/PSF 会膨胀，暗弱时只剩几个像素；放宽到 0.15–40×
                val radiusPx = Math.sqrt(peak.areaPx / Math.PI)
                if (radiusPx < 0.05 * expectedPx || radiusPx > 40.0 * expectedPx) continue
                val g = gravityAt(imu, meta, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
                // 太阳/液面实验常是手持：不强制"静止"，但重力不确定度太大就不用
                if (!g.valid && g.sigmaDeg > 5.0) continue
                if (g.sigmaDeg > 90.0) continue
                val dir = cam.unproject(peak.cx, peak.cy)
                val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                    .coerceIn(-1.0, 1.0)
                val altApp = Math.PI / 2 - Math.acos(cosZ)
                val altTrue = altApp - com.dsh.starpos.astro.Moon.refractionRad(altApp)
                val gast = SkyContext.at(meta.wallClockUtcMs).gast
                val truthRow = truth ?: continue
                val expAlt = com.dsh.starpos.astro.Moon.altitudeRad(
                    com.dsh.starpos.astro.Moon.Site(Math.toRadians(truthRow.lat), Math.toRadians(truthRow.lon)),
                    sun, meta.wallClockUtcMs, gast,
                )
                if (Math.toDegrees(expAlt) < 0.5) continue   // 太阳在地平线以下：不可能是太阳
                val rd = Math.toDegrees(altTrue - expAlt)
                // 合理性检查：真太阳的高度残差不会超过 10°（否则是月亮/灯/误检）
                if (Math.abs(rd) > 10.0) continue
                sunFrames.add(meta.index)
                sunCount++
                sunResid.add(rd)
                findings.add(
                    ("帧 %d: 太阳 峰值 SNR %.0fσ 半径 %.1f px（期望 %.1f）高度 %.2f°%s").format(
                        meta.index, peak.snr, radiusPx, expectedPx, Math.toDegrees(altTrue),
                        if (rd.isNaN()) "" else "（期望 %.2f°，残差 %+.2f°）".format(Math.toDegrees(expAlt), rd),
                    ),
                )
                sunSights.add(
                    com.dsh.starpos.astro.Moon.Sight(
                        utcMillis = meta.wallClockUtcMs, gast = gast, altRad = altTrue,
                        sigmaDeg = Math.max(0.15, g.sigmaDeg),
                        raRad = sun.raRad, decRad = sun.decRad, distanceKm = sun.distanceKm,
                    ),
                )
            }
            if (sunCount == 0 && manifest.note.contains("SUN", ignoreCase = true)) {
                findings.add("太阳：本包标注了 SUN，但没有拟合到日面圆盘（可能太暗/不在画面内/被遮挡）；建议提高曝光或 ISO。")
            }
            if (sunCount > 0) {
                if (sunResid.isNotEmpty()) {
                    val mean = sunResid.average()
                    val rms = Math.sqrt(sunResid.sumOf { it * it } / sunResid.size)
                    findings.add("太阳：识别 $sunCount 帧，高度残差 均值 %+.3f°、RMS %.3f°（对 GNSS 真值）。".format(mean, rms))
                } else {
                    findings.add("太阳：识别 $sunCount 帧（无 GNSS 真值，只记录高度）。")
                }
                if (sunSights.size >= 2) {
                    val fixS = com.dsh.starpos.astro.Moon.solveOrbit(sunSights)
                    if (fixS != null) {
                        val errKm = if (truth != null) haversineKm(fixS.latDeg, fixS.lonDeg, truth.lat, truth.lon) else Double.NaN
                        val spanMin = (sunSights.maxOf { it.utcMillis } - sunSights.minOf { it.utcMillis }) / 60_000.0
                        findings.add(
                            ("太阳联合定位：%.4f, %.4f（rms %.3f°，跨度 %.0f min，%d 次观测）%s").format(
                                fixS.latDeg, fixS.lonDeg, fixS.rmsDeg, spanMin, sunSights.size,
                                if (errKm.isNaN()) "" else "，与真值差 %.1f km".format(errKm),
                            ),
                        )
                        fixS.warnings.forEach { findings.add("太阳解算警告：$it") }
                    } else {
                        findings.add("太阳联合定位失败：观测次数/几何不足。")
                    }
                } else {
                    findings.add("太阳：观测不足 2 次，无法联合定位（单一天体需 ≥1.5 h、3 次以上）。")
                }
            }
        }

        // ===== 月亮模式：帧内月心 → 高度角 → 轨道解算 =====
        run {
            val moonSights = ArrayList<com.dsh.starpos.astro.Moon.Sight>()
            var moonFrames = 0
            val residDeg = ArrayList<Double>()
            for (meta in reader.frameMetas()) {
                if (meta.index in sunFrames) continue   // 该帧拍到的是太阳，不做月面拟合
                val loaded = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                val ephem = runCatching {
                    com.dsh.starpos.astro.Moon.ephemerisAt(meta.wallClockUtcMs)
                }.getOrNull() ?: continue
                val fit = com.dsh.starpos.astro.Moon.fitDisk(
                    loaded.luminance, loaded.width, loaded.height,
                    minAreaPx = Math.max(32, loaded.width * loaded.height / 20000),
                    minRadiusPx = 2.5,
                ) ?: continue
                val cam = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                val expectedPx = Math.tan(ephem.angularRadiusRad) * cam.fx
                // 饱和帧会把月面撑到 10–20×（质心偏亮面，高度残差可差 1–2°）：过曝帧不用于高度
                if (fit.radiusPx > 10.0 * expectedPx) {
                    findings.add(
                        "帧 %d: 月亮过曝（光斑半径 %.0f px ≈ %.1f× 期望），跳过高度：请用更短曝光/更低 ISO".format(
                            meta.index, fit.radiusPx, fit.radiusPx / expectedPx,
                        ),
                    )
                    continue
                }
                if (fit.radiusPx < 0.3 * expectedPx) continue
                val g = gravityAt(imu, meta, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
                if (!g.valid) continue
                val dir = cam.unproject(fit.cx, fit.cy)
                val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                    .coerceIn(-1.0, 1.0)
                val altApp = Math.PI / 2 - Math.acos(cosZ)
                val altTrue = altApp - com.dsh.starpos.astro.Moon.refractionRad(altApp)
                moonFrames++
                val gast = SkyContext.at(meta.wallClockUtcMs).gast
                val expAlt = if (truth != null) {
                    com.dsh.starpos.astro.Moon.altitudeRad(
                        com.dsh.starpos.astro.Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)),
                        ephem, meta.wallClockUtcMs, gast,
                    )
                } else Double.NaN
                val rd = Math.toDegrees(altTrue - expAlt)
                if (!rd.isNaN()) residDeg.add(rd)
                findings.add(
                    ("帧 %d: 月亮 拟合半径 %.0f px（期望 %.0f）高度 %.2f°%s").format(
                        meta.index, fit.radiusPx, expectedPx, Math.toDegrees(altTrue),
                        if (rd.isNaN()) "" else "（期望 %.2f°，残差 %+.2f°）".format(Math.toDegrees(expAlt), rd),
                    ),
                )
                moonSights.add(
                    com.dsh.starpos.astro.Moon.Sight(
                        utcMillis = meta.wallClockUtcMs, gast = gast, altRad = altTrue,
                        sigmaDeg = Math.max(0.15, g.sigmaDeg),
                        raRad = ephem.raRad, decRad = ephem.decRad, distanceKm = ephem.distanceKm,
                    ),
                )
            }
            if (moonFrames > 0) {
                if (residDeg.isNotEmpty()) {
                    val mean = residDeg.average()
                    val rms = Math.sqrt(residDeg.sumOf { it * it } / residDeg.size)
                    findings.add("月亮：识别 $moonFrames 帧，高度残差 均值 %+.3f°、RMS %.3f°（对 GNSS 真值）。".format(mean, rms))
                } else {
                    findings.add("月亮：识别 $moonFrames 帧（无 GNSS 真值，只记录高度）。")
                }
                if (moonSights.size >= 2) {
                    val fixM = com.dsh.starpos.astro.Moon.solveOrbit(moonSights)
                    if (fixM != null) {
                        val errKm = if (truth != null) haversineKm(fixM.latDeg, fixM.lonDeg, truth.lat, truth.lon) else Double.NaN
                        val spanMin = (moonSights.maxOf { it.utcMillis } - moonSights.minOf { it.utcMillis }) / 60_000.0
                        findings.add(
                            ("月亮轨道解算：%.4f, %.4f（rms %.3f°，跨度 %.0f min，%d 次观测）%s").format(
                                fixM.latDeg, fixM.lonDeg, fixM.rmsDeg, spanMin, moonSights.size,
                                if (errKm.isNaN()) "" else "，与真值差 %.1f km".format(errKm),
                            ),
                        )
                        fixM.warnings.forEach { findings.add("月亮解算警告：$it") }
                    } else {
                        findings.add("月亮轨道解算失败：观测次数/几何不足。")
                    }
                } else {
                    findings.add("月亮：观测不足 2 次，无法轨道解算（单一天体需 ≥1.5 h、3 次以上）。")
                }
            }
        }
        // ===== 两体姿态定位：月亮 + 亮行星（土星/木星/金星）同框 =====
        // 用姿态（Wahba）而不是重力：两个天体方向定出完整姿态，再联合定位。
        // 单次两体观测是退化的（只剩 1 个约束），需要固定相机隔 ≥5 分钟多次拍摄。
        run {
            val obsList = ArrayList<com.dsh.starpos.astro.TwoBody.Obs>()
            var pairFrames = 0
            val planets = listOf(
                com.dsh.starpos.astro.Planets.Body.SATURN,
                com.dsh.starpos.astro.Planets.Body.JUPITER,
                com.dsh.starpos.astro.Planets.Body.VENUS,
            )
            for (meta in reader.frameMetas()) {
                val loaded = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                val cam = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                val moon = runCatching { com.dsh.starpos.astro.Moon.ephemerisAt(meta.wallClockUtcMs) }.getOrNull() ?: continue
                val fit = com.dsh.starpos.astro.Moon.fitDisk(
                    loaded.luminance, loaded.width, loaded.height,
                    minAreaPx = Math.max(32, loaded.width * loaded.height / 20000), minRadiusPx = 2.5,
                ) ?: continue
                val g = gravityAt(imu, meta, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
                if (g.sigmaDeg > 5.0) continue
                val up = com.dsh.starpos.astro.V.unit(g.upCam)
                val moonDir = com.dsh.starpos.astro.V.unit(cam.unproject(fit.cx, fit.cy))
                val gast = SkyContext.at(meta.wallClockUtcMs).gast
                // 画面中值/噪声（用中位绝对偏差）
                val sortedPix = loaded.luminance.copyOf(); sortedPix.sort()
                val med = sortedPix[sortedPix.size / 2]
                var madSum = 0.0
                for (v in sortedPix) madSum += Math.abs(v - med)
                val pixSigma = (madSum / sortedPix.size * 1.4826).toFloat()
                for (body in if (truth != null) planets else emptyList()) {
                    val ep = runCatching { com.dsh.starpos.astro.Planets.geocentric(body, meta.wallClockUtcMs) }.getOrNull() ?: continue
                    val sepDeg = Math.toDegrees(
                        com.dsh.starpos.astro.V.angle(
                            com.dsh.starpos.astro.V.fromRaDec(moon.raRad, moon.decRad),
                            com.dsh.starpos.astro.V.fromRaDec(ep.raRad, ep.decRad),
                        ),
                    )
                    if (sepDeg > 38.0) continue
                    // 用重力把行星可能位置限制在"角距=sep + 高度=某值"的两条镜像解上
                    val moonAlt = Math.toDegrees(
                        Math.asin((moonDir[0] * up[0] + moonDir[1] * up[1] + moonDir[2] * up[2]).coerceIn(-1.0, 1.0)),
                    )
                    val site = com.dsh.starpos.astro.Moon.Site(
                        Math.toRadians(truth!!.lat), Math.toRadians(truth!!.lon),
                    )
                    val planetAlt = Math.toDegrees(
                        com.dsh.starpos.astro.Moon.altitudeRad(site, ep, meta.wallClockUtcMs, gast),
                    )
                    if (planetAlt < 3.0) continue
                    // IMU 抬升/倾斜偏差：用"实测月亮高度 − 星历月亮高度"修正行星预测高度
                    val moonExpAlt = Math.toDegrees(
                        com.dsh.starpos.astro.Moon.altitudeRad(site, moon, meta.wallClockUtcMs, gast),
                    )
                    val satAltMeas = Math.toRadians(planetAlt) + Math.toRadians(moonAlt - moonExpAlt)
                    val sinA = Math.sin(satAltMeas)
                    val cs = Math.toRadians(sepDeg)
                    val moonDotUp = moonDir[0] * up[0] + moonDir[1] * up[1] + moonDir[2] * up[2]
                    var e1 = doubleArrayOf(
                        up[0] - moonDotUp * moonDir[0], up[1] - moonDotUp * moonDir[1], up[2] - moonDotUp * moonDir[2],
                    )
                    val n1 = Math.sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2])
                    if (n1 < 1e-9) continue
                    e1 = doubleArrayOf(e1[0] / n1, e1[1] / n1, e1[2] / n1)
                    val e2 = doubleArrayOf(
                        moonDir[1] * e1[2] - moonDir[2] * e1[1],
                        moonDir[2] * e1[0] - moonDir[0] * e1[2],
                        moonDir[0] * e1[1] - moonDir[1] * e1[0],
                    )
                    val e1dotup = e1[0] * up[0] + e1[1] * up[1] + e1[2] * up[2]
                    val cosPhi = (sinA - Math.cos(cs) * moonDotUp) / (Math.sin(cs) * e1dotup)
                    if (Math.abs(cosPhi) > 1.0) continue
                    val phi0 = Math.acos(cosPhi)
                    var bestSnr = 0.0
                    var bestDir: DoubleArray? = null
                    for (sgn in doubleArrayOf(1.0, -1.0)) {
                        val phi = sgn * phi0
                        val u = com.dsh.starpos.astro.V.unit(
                            doubleArrayOf(
                                Math.cos(cs) * moonDir[0] + Math.sin(cs) * (Math.cos(phi) * e1[0] + Math.sin(phi) * e2[0]),
                                Math.cos(cs) * moonDir[1] + Math.sin(cs) * (Math.cos(phi) * e1[1] + Math.sin(phi) * e2[1]),
                                Math.cos(cs) * moonDir[2] + Math.sin(cs) * (Math.cos(phi) * e1[2] + Math.sin(phi) * e2[2]),
                            ),
                        )
                        if (u[2] <= 0.05) continue
                        val px = (cam.fx * u[0] / u[2] + cam.cx).toInt()
                        val py = (cam.fy * u[1] / u[2] + cam.cy).toInt()
                        if (px < 4 || py < 4 || px > loaded.width - 5 || py > loaded.height - 5) continue
                        var peak = 0f
                        for (dy in -3..3) for (dx in -3..3) {
                            val v = loaded.luminance[(py + dy) * loaded.width + (px + dx)]
                            if (v > peak) peak = v
                        }
                        val snr = if (pixSigma > 0f) ((peak - med) / pixSigma).toDouble() else 0.0
                        if (snr > bestSnr) { bestSnr = snr; bestDir = u }
                    }
                    if (bestSnr > 12.0 && bestDir != null) {
                        pairFrames++
                        obsList.add(
                            com.dsh.starpos.astro.TwoBody.Obs(
                                meta.wallClockUtcMs, gast, moon.raRad, moon.decRad, moon.distanceKm, moonDir, 0.15,
                            ),
                        )
                        obsList.add(
                            com.dsh.starpos.astro.TwoBody.Obs(
                                meta.wallClockUtcMs, gast, ep.raRad, ep.decRad, ep.distanceKm, bestDir, 0.3,
                            ),
                        )
                        findings.add(
                            "帧 %d: 月亮+%s 同框（角距 %.1f°，星点 SNR %.0fσ）".format(
                                meta.index, body.chinese, sepDeg, bestSnr,
                            ),
                        )
                    }
                }
            }
            if (truth == null) findings.add("两体姿态：本包没有 GNSS 真值，跳过（行星预测需要近似位置）。")
            if (obsList.isNotEmpty()) {
                val spanMin = (obsList.maxOf { it.utcMillis } - obsList.minOf { it.utcMillis }) / 60_000.0
                val fix = com.dsh.starpos.astro.TwoBody.solve(obsList)
                if (fix != null) {
                    val errKm = if (truth != null) haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon) else Double.NaN
                    findings.add(
                        ("两体姿态定位：%.4f, %.4f（rms %.2f°，跨度 %.0f min，%d 个天体观测）%s").format(
                            fix.latDeg, fix.lonDeg, fix.rmsDeg, spanMin, obsList.size,
                            if (errKm.isNaN()) "" else "，与真值差 %.1f km".format(errKm),
                        ),
                    )
                    fix.warnings.forEach { findings.add("两体解算警告：$it") }
                } else {
                    findings.add(
                        "两体姿态：识别到 %d 次双天体观测（跨度 %.1f min），但几何不足——"
                            .format(pairFrames, spanMin) +
                            "两体单次观测只剩 1 个约束；请固定相机隔 ≥5 分钟多次拍摄，或一帧里装 ≥3 个天体。",
                    )
                }
            }
        }
        // ===== 跨曝光两体（HDR）：短帧定月亮、长帧找行星，按同一三脚架配对 =====
        // 解决"短帧拍月亮（2 ms）时行星太暗、长帧（1 s）时月亮过曝"的矛盾。
        run {
            if (truth != null) {
                val metasAll = reader.frameMetas()
                val shorts = metasAll.filter { it.exposureNs in 1L..100_000_000L }
                val longs = metasAll.filter { it.exposureNs >= 500_000_000L }
                if (shorts.isNotEmpty() && longs.isNotEmpty()) {
                    val site = com.dsh.starpos.astro.Moon.Site(
                        Math.toRadians(truth.lat), Math.toRadians(truth.lon),
                    )
                    class MoonRef(
                        val utc: Long, val gast: Double, val dir: DoubleArray,
                        val ep: com.dsh.starpos.astro.Moon.Ephemeris,
                    )
                    val moons = ArrayList<MoonRef>()
                    for (m in shorts) {
                        val loaded = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                        val cam = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                        val ep = runCatching { com.dsh.starpos.astro.Moon.ephemerisAt(m.wallClockUtcMs) }.getOrNull() ?: continue
                        val expectedPx = Math.tan(ep.angularRadiusRad) * cam.fx
                        val fit = com.dsh.starpos.astro.Moon.fitDisk(
                            loaded.luminance, loaded.width, loaded.height, minAreaPx = 32, minRadiusPx = 2.5,
                        ) ?: continue
                        if (fit.radiusPx < 0.3 * expectedPx || fit.radiusPx > 40.0 * expectedPx) continue
                        val g = gravityAt(imu, m, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
                        if (g.sigmaDeg > 5.0) continue
                        moons.add(
                            MoonRef(
                                m.wallClockUtcMs, SkyContext.at(m.wallClockUtcMs).gast,
                                com.dsh.starpos.astro.V.unit(cam.unproject(fit.cx, fit.cy)), ep,
                            ),
                        )
                    }
                    if (moons.isNotEmpty()) {
                        val planets = listOf(
                            com.dsh.starpos.astro.Planets.Body.SATURN,
                            com.dsh.starpos.astro.Planets.Body.JUPITER,
                            com.dsh.starpos.astro.Planets.Body.VENUS,
                        )
                        val obsX = ArrayList<com.dsh.starpos.astro.TwoBody.Obs>()
                        var crossPairs = 0
                        for (m in longs) {
                            val near = moons.minByOrNull { kotlin.math.abs(it.utc - m.wallClockUtcMs) } ?: continue
                            if (kotlin.math.abs(near.utc - m.wallClockUtcMs) > 15_000L) continue
                            val loaded = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                            val cam = intrinsics.toCameraIntrinsics(loaded.width, loaded.height)
                            val g = gravityAt(imu, m, manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic, accelBias = accelBiasEff, accelMap = accelMapEff)
                            if (g.sigmaDeg > 5.0) continue
                            val up = com.dsh.starpos.astro.V.unit(g.upCam)
                            val gastL = SkyContext.at(m.wallClockUtcMs).gast
                            val det = FrameAnalyzer.analyze(
                                loaded.luminance, loaded.width, loaded.height, thresholdSigma = 6.0,
                            )
                            for (body in planets) {
                                val ep = runCatching { com.dsh.starpos.astro.Planets.geocentric(body, m.wallClockUtcMs) }.getOrNull() ?: continue
                                val sepDeg = Math.toDegrees(
                                    com.dsh.starpos.astro.V.angle(
                                        com.dsh.starpos.astro.V.fromRaDec(near.ep.raRad, near.ep.decRad),
                                        com.dsh.starpos.astro.V.fromRaDec(ep.raRad, ep.decRad),
                                    ),
                                )
                                if (sepDeg > 38.0) continue
                                val planetAlt = Math.toDegrees(
                                    com.dsh.starpos.astro.Moon.altitudeRad(site, ep, m.wallClockUtcMs, gastL),
                                )
                                if (planetAlt < 3.0) continue
                                var bestDir: DoubleArray? = null
                                var bestScore = 2.5
                                for (d in det.detections) {
                                    if (d.peak < 0.45f) continue
                                    val dir = com.dsh.starpos.astro.V.unit(cam.unproject(d.x.toDouble(), d.y.toDouble()))
                                    val sepMeas = Math.toDegrees(com.dsh.starpos.astro.V.angle(near.dir, dir))
                                    val altMeas = Math.toDegrees(
                                        Math.asin((dir[0] * up[0] + dir[1] * up[1] + dir[2] * up[2]).coerceIn(-1.0, 1.0)),
                                    )
                                    val score = kotlin.math.abs(sepMeas - sepDeg) + kotlin.math.abs(altMeas - planetAlt)
                                    if (score < bestScore) { bestScore = score; bestDir = dir }
                                }
                                if (bestDir != null) {
                                    crossPairs++
                                    obsX.add(
                                        com.dsh.starpos.astro.TwoBody.Obs(
                                            near.utc, near.gast, near.ep.raRad, near.ep.decRad, near.ep.distanceKm, near.dir, 0.15,
                                        ),
                                    )
                                    obsX.add(
                                        com.dsh.starpos.astro.TwoBody.Obs(
                                            m.wallClockUtcMs, gastL, ep.raRad, ep.decRad, ep.distanceKm, bestDir, 0.3,
                                        ),
                                    )
                                    findings.add(
                                        "帧 %d: 跨曝光 月亮+%s（长帧检测，角距 %.1f°）".format(
                                            m.index, body.chinese, sepDeg,
                                        ),
                                    )
                                    break
                                }
                            }
                        }
                        if (crossPairs > 0) {
                            val spanMin = (obsX.maxOf { it.utcMillis } - obsX.minOf { it.utcMillis }) / 60_000.0
                            val fix = com.dsh.starpos.astro.TwoBody.solve(obsX)
                            if (fix != null) {
                                val errKm = haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
                                findings.add(
                                    ("两体姿态定位：%.4f, %.4f（rms %.2f°，跨度 %.0f min，%d 个天体观测）%s").format(
                                        fix.latDeg, fix.lonDeg, fix.rmsDeg, spanMin, obsX.size,
                                        if (errKm.isNaN()) "" else "，与真值差 %.1f km".format(errKm),
                                    ),
                                )
                                findings.add("两体（跨曝光）：%d 次短帧月亮+长帧行星配对".format(crossPairs))
                                fix.warnings.forEach { findings.add("两体解算警告：$it") }
                            } else {
                                findings.add(
                                    "两体姿态：识别到 %d 次双天体观测（跨度 %.1f min），但几何不足——".format(crossPairs, spanMin) +
                                        "两体单次观测只剩 1 个约束；请固定相机隔 ≥5 分钟多次拍摄，或一帧里装 ≥3 个天体。",
                                )
                            }
                        }
                    }
                }
            }
        }

        findings.add("模型：${com.dsh.starpos.core.SolveResult.MODEL_VERSIONS}")

        return SessionAnalysis(
            sessionId = manifest.sessionId,
            createdAtUtcMs = manifest.createdAtUtcMs,
            intrinsics = intrinsics,
            cameraLine = describeCamera(manifest.camera),
            frames = frames,
            findings = findings,
            truth = truth,
            imuRows = imu.size,
            gnssRows = gnss.size,
            notes = reader.readNotes(),
        )
    }

    /** 太阳亮斑检测结果。 */
    private class SunPeak(val cx: Double, val cy: Double, val snr: Double, val areaPx: Double)

    /**
     * 检测太阳（小、可能很暗）：盒式平滑找峰值 → 阈值面积 → 加权质心。
     * 比圆面拟合更适合"日落时只剩几个像素"的太阳；也用于亮太阳。
     */
    private fun detectSunPeak(lum: FloatArray, w: Int, h: Int, r: Int = 6): SunPeak? {
        if (w < 4 * r || h < 4 * r) return null
        // 积分图
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += lum[y * w + x]
                ii[(y + 1) * (w + 1) + (x + 1)] = ii[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        fun boxSum(x0: Int, y0: Int, x1: Int, y1: Int): Double =
            ii[(y1 + 1) * (w + 1) + (x1 + 1)] - ii[y0 * (w + 1) + (x1 + 1)] -
                ii[(y1 + 1) * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
        val sm = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = Math.max(0, x - r); val y0 = Math.max(0, y - r)
            val x1 = Math.min(w - 1, x + r); val y1 = Math.min(h - 1, y + r)
            sm[y * w + x] = (boxSum(x0, y0, x1, y1) / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
        }
        val sorted = sm.copyOf(); sorted.sort()
        val med = sorted[sorted.size / 2]
        var varSum = 0.0
        for (v in sm) { val d = (v - med).toDouble(); varSum += d * d }
        val sigma = Math.sqrt(varSum / sm.size)
        if (sigma < 1e-9) return null
        var bi = 0; var bv = -Float.MAX_VALUE
        for (i in sm.indices) if (sm[i] > bv) { bv = sm[i]; bi = i }
        val snr = (bv - med) / sigma
        if (snr < 6.0) return null
        val bx = bi % w; val by = bi / w
        val win = 12
        val thr = med + 8f * sigma.toFloat()
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var area = 0
        for (y in Math.max(0, by - win)..Math.min(h - 1, by + win)) {
            for (x in Math.max(0, bx - win)..Math.min(w - 1, bx + win)) {
                val v = sm[y * w + x]
                if (v > thr) area++
                val wt = (v - med).toDouble().coerceAtLeast(0.0)
                sw += wt; sx += wt * x; sy += wt * y
            }
        }
        if (sw <= 0.0) return null
        return SunPeak(sx / sw, sy / sw, snr, area.toDouble())
    }

    /** 内参兜底：用焦距和像元尺寸推算 fx=fy，主点取中心。 */
    fun estimateIntrinsics(caps: CameraCaps?): IntrinsicsRecord? {
        if (caps == null) return null
        val focal = caps.focalLengthsMm.firstOrNull() ?: return null
        if (caps.sensorPhysicalWmm <= 0.0 || caps.pixelArrayW <= 0) return null
        val pixelSizeMm = caps.sensorPhysicalWmm / caps.pixelArrayW
        val fPx = focal / pixelSizeMm
        return IntrinsicsRecord(
            width = caps.pixelArrayW,
            height = caps.pixelArrayH,
            fx = fPx,
            fy = fPx,
            cx = caps.pixelArrayW / 2.0,
            cy = caps.pixelArrayH / 2.0,
            source = "estimated",
        )
    }

    /**
     * 真值选取：**绝不使用系统"最后已知位置"**（provider 以 `:last-known` 结尾）。
     * 优先 manifest 里的实时定位，其次本次会话里最好的实时定位（GPS 优先、精度更优）。
     */
    fun pickTruth(manifestTruth: GnssRow?, rows: List<GnssRow>): GnssRow? {
        fun isSeed(r: GnssRow) = r.provider.endsWith(":last-known")
        manifestTruth?.let { if (!isSeed(it)) return it }
        val live = rows.filter { !isSeed(it) }
        if (live.isEmpty()) return null
        return live.sortedWith(
            compareByDescending<GnssRow> { it.provider.startsWith("gps") }
                .thenBy { if (it.accuracyM >= 0) it.accuracyM else Double.MAX_VALUE },
        ).first()
    }

    /** 取帧时刻附近的 IMU 样本（±0.75 s），静止判决 + 平均得到天顶。 */
    fun gravityAt(
        imu: List<ImuRow>,
        meta: FrameMeta,
        sensorOrientation: Int = 90,
        anchorNs: Long = 0L,
        extrinsic: ExtrinsicConfig? = null,
        accelBias: DoubleArray? = null,
        accelMap: DoubleArray? = null,
    ): GravityEstimate {
        // 优先用曝光开始时刻（SENSOR_TIMESTAMP，REALTIME 基准）；用"拍完的时刻"会引入约 1 s 手持晃动误差
        val anchor = when {
            anchorNs > 0L -> anchorNs
            meta.sensorTimestampNs > 0L -> meta.sensorTimestampNs
            else -> meta.elapsedRealtimeNs
        }
        val windowNs = 750_000_000L
        val near = imu.filter { abs(it.tNs - anchor) <= windowNs }
        if (near.size < 5) return GravityEstimate(doubleArrayOf(0.0, 0.0, 1.0), 90.0, false, near.size)
        val b = accelBias ?: FieldAnalyzer.accelBias
        val s = accelMap ?: FieldAnalyzer.accelMap
        val accel = near.map {
            var x = it.ax; var y = it.ay; var z = it.az
            if (b != null) { x -= b[0]; y -= b[1]; z -= b[2] }
            if (s != null) {
                val nx = s[0] * x + s[1] * y + s[2] * z
                val ny = s[3] * x + s[4] * y + s[5] * z
                val nz = s[6] * x + s[7] * y + s[8] * z
                x = nx; y = ny; z = nz
            }
            doubleArrayOf(x, y, z)
        }
        val gyro = near.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }
        val est = GravityEstimator.estimate(accel, gyro, DeviceFrame.deviceToCameraRear(sensorOrientation))
        return if (extrinsic == null) est
        else GravityEstimate(extrinsic.applyTo(est.upCam), est.sigmaDeg, est.isStatic, est.samples)
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    private fun describeCamera(caps: CameraCaps?): String {
        if (caps == null) return "（采集包未记录相机能力）"
        return "camera=${caps.usedCameraId} facing=${caps.lensFacing} level=${caps.hardwareLevel} " +
            "raw=${caps.rawSupported} active=${caps.activeArrayW}x${caps.activeArrayH} " +
            "focal=${caps.focalLengthsMm.firstOrNull() ?: 0.0}mm ts=${caps.timestampSource}"
    }

    // ---- 报告输出 ----

    fun writeMarkdown(a: SessionAnalysis, out: File) {
        out.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append("# 采集包分析报告\n\n")
        sb.append("- session: `").append(a.sessionId).append("`\n")
        sb.append("- 采集时间: ").append(com.dsh.starpos.core.SolveResult.isoUtc(a.createdAtUtcMs)).append("\n")
        sb.append("- 相机: ").append(a.cameraLine).append("\n")
        sb.append(
            "- 内参: fx=%.1f fy=%.1f cx=%.1f cy=%.1f（来源 %s）\n".format(
                a.intrinsics.fx, a.intrinsics.fy, a.intrinsics.cx, a.intrinsics.cy, a.intrinsics.source,
            ),
        )
        sb.append("- IMU 样本: ").append(a.imuRows).append(" · GNSS 样本: ").append(a.gnssRows).append("\n")
        a.truth?.let {
            sb.append("- GNSS 真值: %.6f, %.6f (±%.1f m, %s)\n".format(it.lat, it.lon, it.accuracyM, it.provider))
        }
        if (a.notes.isNotBlank()) sb.append("- 现场备注: ").append(a.notes.replace("\n", " / ")).append("\n")
        sb.append("\n## 逐帧结果\n\n")
        sb.append("| 帧 | 检测星 | 匹配 | RMS(″) | 姿态3σ(°) | 重力σ(°) | 静止 | 云量 | 纬度 | 经度 | 椭圆(m) | 真值偏差(km) | 结论 | 耗时(ms) |\n")
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (f in a.frames) {
            sb.append("| ").append(f.index)
                .append(" | ").append(f.detections)
                .append(" | ").append(f.matched)
                .append(" | ").append("%.1f".format(f.rmsArcsec))
                .append(" | ").append("%.3f".format(3 * f.attitudeSigmaDeg))
                .append(" | ").append("%.3f".format(f.gravitySigmaDeg))
                .append(" | ").append(if (f.gravityStatic) "是" else "否")
                .append(" | ").append("%.2f".format(f.cloudCover))
                .append(" | ").append(f.fix?.let { "%.5f".format(it.latDeg) } ?: "—")
                .append(" | ").append(f.fix?.let { "%.5f".format(it.lonDeg) } ?: "—")
                .append(" | ").append(f.ellipse?.let { "%.0f×%.0f".format(it.semiMajorM, it.semiMinorM) } ?: "—")
                .append(" | ").append(f.truthErrorKm?.let { "%.1f".format(it) } ?: "—")
                .append(" | ").append(f.fix?.let { "FIX" } ?: (f.reason ?: "—"))
                .append(" | ").append(f.elapsedMs)
                .append(" |\n")
        }
        sb.append("\n## 结论与建议\n\n")
        a.findings.forEach { sb.append("- ").append(it).append("\n") }
        out.writeText(sb.toString(), Charsets.UTF_8)
    }

    fun writeJson(a: SessionAnalysis, out: File) {
        out.parentFile?.mkdirs()
        val map = linkedMapOf<String, Any?>(
            "session_id" to a.sessionId,
            "created_at_utc_ms" to a.createdAtUtcMs,
            "camera" to a.cameraLine,
            "intrinsics" to a.intrinsics.toMap(),
            "imu_rows" to a.imuRows,
            "gnss_rows" to a.gnssRows,
            "truth" to a.truth?.toMap(),
            "frames" to a.frames.map { f ->
                linkedMapOf(
                    "index" to f.index,
                    "detections" to f.detections,
                    "matched" to f.matched,
                    "rms_arcsec" to f.rmsArcsec,
                    "attitude_sigma_deg" to f.attitudeSigmaDeg,
                    "gravity_sigma_deg" to f.gravitySigmaDeg,
                    "gravity_static" to f.gravityStatic,
                    "imu_samples" to f.imuSamples,
                    "cloud_cover" to f.cloudCover,
                    "psf_aspect" to f.maxPsfAspect,
                    "fix" to f.fix?.let { linkedMapOf("lat" to it.latDeg, "lon" to it.lonDeg) },
                    "ellipse_95" to f.ellipse?.let {
                        linkedMapOf("semi_major_m" to it.semiMajorM, "semi_minor_m" to it.semiMinorM, "azimuth_deg" to it.azimuthDeg)
                    },
                    "truth_error_km" to f.truthErrorKm,
                    "reason" to f.reason,
                    "warnings" to f.warnings,
                    "elapsed_ms" to f.elapsedMs,
                )
            },
            "findings" to a.findings,
        )
        out.writeText(JsonText.write(map), Charsets.UTF_8)
    }
}












