// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.android

import android.content.Context
import android.os.Build
import android.view.TextureView
import com.dsh.starpos.BuildConfig
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SyntheticScene
import com.dsh.starpos.core.ClockStatus
import com.dsh.starpos.field.BundleWriter
import com.dsh.starpos.field.CameraCaps
import com.dsh.starpos.field.FrameMeta
import com.dsh.starpos.astro.MirrorGeometry
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.Planets
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.ImuRow
import com.dsh.starpos.field.IntrinsicsRecord
import com.dsh.starpos.field.SessionManifest
import com.dsh.starpos.field.SyntheticSession
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 现场采集控制器：一次会话 = 相机手动曝光采若干帧 + IMU + GNSS + 时钟状态，
 * 打包成 zip 供带回离线分析（"异步调试"链路的手机端）。
 */
class FieldSessionController(private val context: Context) {

    /** 用户取消采集（不是错误，按"已取消"处理）。 */
    class CanceledCapture : Exception("已取消")

    /** 预览标记：亮星或月/日圆面。 */
    class PreviewMark(
        val nx: Float,
        val ny: Float,
        val label: String,
        val isDisk: Boolean = false,
        val radiusN: Float = 0f,
    )

    data class Spec(
        val frames: Int = 3,
        val exposureNs: Long = 8_000_000_000L,
        val iso: Int = 800,
        val note: String = "",
        /** 标定模式：AE/AF 都放开，近距离拍点阵用；不等待 GNSS。 */
        val autoMode: Boolean = false,
        /** HDR 第二段长曝光（ns；0 = 关闭）。短曝光拍月亮、长曝光拍土星/恒星，同一指向。 */
        val hdrLongNs: Long = 0L,
        val hdrLongIso: Int = 0,
        /** 采集完成后自动估计相机↔IMU 外参（星+陀螺手眼标定） */
        val estimateExtrinsic: Boolean = false,
        /** 手动逐帧标定：每帧等用户按"拍一帧"再拍（自动节奏会与转动窗口打架） */
        val manualCalibration: Boolean = false,
        /** 内参标定也改手动逐帧（AE/AF 自动，但何时拍由用户决定） */
        val manualIntrinsic: Boolean = false,
        /** 水镜采集：记录是否启用水镜辅助与镜头高度，供离线水镜分析。 */
        val mirrorEnabled: Boolean = false,
        val mirrorHeightM: Double = 0.4,
    )

    data class Quality(
        val index: Int,
        val detections: Int,
        val cloudCover: Double,
        val psfAspect: Double,
        val solved: Boolean,
        val matched: Int,
        val hint: String,
    )

    data class State(
        val phase: String = "IDLE",
        val message: String = "",
        val error: String? = null,
        val cameraLine: String = "",
        val caps: CameraCaps? = null,
        val intrinsics: IntrinsicsRecord? = null,
        val exposureRange: String = "",
        val isoRange: String = "",
        /** 设备上限（供 UI 显示与自动夹取） */
        val maxExposureMs: Double = 0.0,
        val maxIso: Int = 0,
        val rawSupported: Boolean = false,
        val preflightDone: Boolean = false,
        val clock: ClockStatus? = null,
        val gnssPermission: String = "未知",
        val gnssProviderEnabled: Boolean = false,
        val gnssSatellites: Int = 0,
        val gnssLastFixText: String = "尚未定位",
        val gnssReady: Boolean = false,
        val imuHz: Double = 0.0,
        val qualities: List<Quality> = emptyList(),
        val bundleDir: File? = null,
        val bundleZip: File? = null,
        val note: String = "",
        /** 相机/预览状态（未打开 / 打开中 / 预览中 / 失败原因） */
        val cameraStatus: String = "未打开",
        /** 预览模式：0=自动，1=找星，2=跟随拍摄参数 */
        val previewMode: Int = 0,
        /** 相机↔IMU 外参状态（未标定 / 已载入 X°） */
        val extrinsicText: String = "未标定",
        /** 是否处于手动标定（决定 UI 是否显示"拍一帧"） */
        val manualCalib: Boolean = false,
        /** 外参自标定结果（非 null 时 UI 弹结果框；null=无结果）。 */
        val calibrationResult: String? = null,
        /** 外参自标定是否通过（通过=已写入）。 */
        val calibrationOk: Boolean = false,
        /** 标定进行中：锁定帧数选择与开始按钮，只留快门/ISO */
        val calibLock: Boolean = false,
        /** 已拍帧数（标定进度） */
        val calibShot: Int = 0,
        /** 标定总帧数（锁定后显示进度用） */
        val calibTotal: Int = 0,
        /** 正在打包（最后一帧后到 zip 完成之间，禁止重复点击） */
        val packing: Boolean = false,
        /** 预览上要画的标记（归一化坐标 0..1）。 */
        val previewMarks: List<PreviewMark> = emptyList(),
        /** 增强预览画面（堆栈+拉伸后的位图）。 */
        val previewBitmap: android.graphics.Bitmap? = null,
    /** 预览 ROI 放大倍数（位图已是中心裁剪后的画面，标记需按此变换）。 */
    val previewZoom: Int = 1,
    /** ===== 预览调参状态 ===== */
    val enhanceEnabled: Boolean = true,
    val dispStack: Int = 4,
    val bgBlur: Int = 28,
    val starBoost: Float = 1.2f,
    val hpRadius: Int = 4,
    val targetLevel: Float = 0.42f,
    val gammaValue: Float = 0.72f,
    /** 找星预览曝光（ms），供调参界面显示当前值。 */
    val starExpMs: Int = 600,
    /** 预览重建代号：加 1 后 UI 会重建 TextureView（采集后修复卡帧）。 */
    val previewEpoch: Int = 0,
    // ---- 对准辅助（电子罗盘 + 多天体候选）----
    val headingDeg: Double = Double.NaN,
    val cameraPitchDeg: Double = Double.NaN,
    val compassAccuracy: Int = -1,
    val aimBodies: List<String> = emptyList(),
    val aimPairs: List<String> = emptyList(),
    // ---- 水镜辅助（预测直射像/反射像位置 + 托盘距离）----
    val mirrorEnabled: Boolean = false,
    val mirrorHeightM: Double = 0.4,
    val mirrorInfo: String = "",
    val mirrorMarks: List<PreviewMark> = emptyList(),
        /** 预览位图的旋转角（传感器朝向），UI 按它旋转标记。 */
        val previewRotationDeg: Int = 90,
        /** 实时稳定度（稳 / 轻晃 / 在动） */
        val shakeText: String = "稳定度：—",
        val shakeLevel: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var camera: StarCamera? = null

    /** 当前选择的相机 id（null=自动主摄；3=超广角，4=长焦，视设备是否允许直接打开）。 */
    val cameraId: String?
        get() = selectedCameraId

    /** 杭州附近磁差近似值（真方位 ≈ 磁方位 + 该值）。城市里磁干扰大，仅作粗对准。 */
    private val declinationDeg = -5.5
    private var compassAim: CompassAim? = null
    private var aimJob: Job? = null
    @Volatile private var selectedCameraId: String? = null
    @Volatile private var mirrorEnabled = false
    /** 手动逐帧标定时：帧与帧之间允许预览探测继续刷新（曝光瞬间会暂停） */
    @Volatile private var allowProbeDuringCapture = false
    @Volatile private var mirrorHeightM = 0.4

    /**
     * 切换相机（如主摄 0 → 超广角 3 → 长焦 4）。
     * 物理子相机能否直接 openCamera 取决于厂商；失败会走 openPreview 的错误提示。
     */
    fun setCameraId(id: String?) {
        if (selectedCameraId == id) return
        selectedCameraId = id
        runCatching { camera?.close() }
        camera = null
        _state.value = _state.value.copy(
            previewEpoch = _state.value.previewEpoch + 1,
            cameraStatus = "切换相机 ${id ?: "自动"}…",
        )
        refreshPreflight(_state.value.clock)
    }

    /** 水镜辅助开关（相机高度 = 镜头到水面的竖直距离，米）。 */
    fun setMirrorAssist(enabled: Boolean, heightM: Double) {
        mirrorEnabled = enabled
        mirrorHeightM = heightM.coerceIn(0.1, 3.0)
        val ca = compassAim
        if (ca != null) runCatching { updateAim(ca) }
        _state.value = _state.value.copy(
            mirrorEnabled = mirrorEnabled,
            mirrorHeightM = mirrorHeightM,
            mirrorInfo = if (mirrorEnabled) _state.value.mirrorInfo else "",
            mirrorMarks = if (mirrorEnabled) _state.value.mirrorMarks else emptyList(),
        )
    }

    private fun startAimMonitor() {
        if (compassAim != null) return
        val ca = runCatching { CompassAim(context) }.getOrNull() ?: return
        ca.start()
        compassAim = ca
        aimJob = scope.launch {
            while (isActive) {
                runCatching { updateAim(ca) }
                delay(2_000)
            }
        }
    }

    /** 更新罗盘朝向 + 多天体候选（按当前 GNSS 位置与时间）。 */
    private fun updateAim(ca: CompassAim) {
        val hdg = ca.headingDeg
        val pitch = ca.cameraPitchDeg
        val bodies = ArrayList<String>()
        val pairs = ArrayList<String>()
        val fix = runCatching { gnssRecorder.bestLive() }.getOrNull()
        if (fix != null) {
            val now = System.currentTimeMillis()
            val site = Moon.Site(Math.toRadians(fix.lat), Math.toRadians(fix.lon))
            val gast = SkyContext.at(now).gast
            data class Body(val name: String, val alt: Double, val az: Double, val ra: Double, val dec: Double)
            val list = ArrayList<Body>()
            runCatching {
                val m = Moon.ephemerisAt(now)
                list.add(Body("月亮", Math.toDegrees(Moon.altitudeRad(site, m, now, gast)),
                    Math.toDegrees(Moon.azimuthRad(site, m, gast)), m.raRad, m.decRad))
            }
            for (b in listOf(Planets.Body.VENUS, Planets.Body.JUPITER, Planets.Body.MARS, Planets.Body.SATURN)) {
                runCatching {
                    val e = Planets.geocentric(b, now)
                    list.add(Body(b.chinese, Math.toDegrees(Moon.altitudeRad(site, e, now, gast)),
                        Math.toDegrees(Moon.azimuthRad(site, e, gast)), e.raRad, e.decRad))
                }
            }
            runCatching {
                val sun = Moon.sunEphemerisAt(now)
                list.add(Body("太阳", Math.toDegrees(Moon.altitudeRad(site, sun, now, gast)),
                    Math.toDegrees(Moon.azimuthRad(site, sun, gast)), sun.raRad, sun.decRad))
            }
            val visible = list.filter { it.alt > 8.0 }.sortedByDescending { it.alt }
            for (b in visible) bodies.add("%s +%.0f° / 方位 %.0f°".format(b.name, b.alt, b.az))
            val pairList = ArrayList<Triple<Body, Body, Double>>()
            for (i in visible.indices) for (j in i + 1 until visible.size) {
                val sep = Math.toDegrees(
                    V.angle(V.fromRaDec(visible[i].ra, visible[i].dec), V.fromRaDec(visible[j].ra, visible[j].dec)),
                )
                if (sep in 3.0..38.0) pairList.add(Triple(visible[i], visible[j], sep))
            }
            pairList.sortBy { it.third }
            for ((a, b, sep) in pairList.take(3)) {
                pairs.add("%s + %s：角距 %.1f° ← 可同框".format(a.name, b.name, sep))
            }
        }
        var mirrorInfo = ""
        var mirrorMarks: List<PreviewMark> = emptyList()
        if (mirrorEnabled && fix != null) {
            val r = runCatching { computeMirrorMarks(hdg, pitch, fix.lat, fix.lon) }.getOrNull()
            if (r != null) {
                mirrorInfo = r.first
                mirrorMarks = r.second
            }
        }
        _state.value = _state.value.copy(
            headingDeg = hdg,
            cameraPitchDeg = pitch,
            compassAccuracy = ca.accuracy,
            aimBodies = bodies,
            aimPairs = pairs,
            mirrorEnabled = mirrorEnabled,
            mirrorHeightM = mirrorHeightM,
            mirrorInfo = mirrorInfo,
            mirrorMarks = mirrorMarks,
        )
    }

    /**
     * 水镜预测：假设水面水平，直射像与反射像（高度取反）在当前相机姿态下的像素位置，
     * 以及"镜头高 H 时，托盘应放在多远的水平距离 d = H / tan(h)"。
     * 只用于粗对准（罗盘方位有 5–15° 误差、假设无滚转）。
     */
    private fun computeMirrorMarks(
        magneticHeadingDeg: Double,
        pitchDeg: Double,
        latDeg: Double,
        lonDeg: Double,
    ): Pair<String, List<PreviewMark>>? {
        val intr = _state.value.intrinsics ?: return null
        val cam = intr.toCameraIntrinsics(2048, 1536)
        val now = System.currentTimeMillis()
        val site = Moon.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg))
        val gast = SkyContext.at(now).gast
        data class B(val name: String, val alt: Double, val az: Double, val radiusRad: Double)
        val cand = ArrayList<B>()
        runCatching {
            val m = Moon.ephemerisAt(now)
            cand.add(B("月亮", Math.toDegrees(Moon.altitudeRad(site, m, now, gast)),
                Math.toDegrees(Moon.azimuthRad(site, m, gast)), m.angularRadiusRad))
        }
        runCatching {
            val s = Moon.sunEphemerisAt(now)
            cand.add(B("太阳", Math.toDegrees(Moon.altitudeRad(site, s, now, gast)),
                Math.toDegrees(Moon.azimuthRad(site, s, gast)), 0.00465))
        }
        val target = cand.filter { it.alt > 2.0 }.maxByOrNull { it.radiusRad * 1000 } ?: return null
        val hTrue = (magneticHeadingDeg + declinationDeg + 360.0) % 360.0
        val marks = ArrayList<PreviewMark>()
        val rN = (Math.tan(target.radiusRad) * cam.fx / 2048.0).toFloat().coerceAtLeast(0.004f)
        fun addMark(altDeg: Double, label: String) {
            val nm = MirrorGeometry.mark(altDeg, target.az, hTrue, pitchDeg, cam.fx, cam.fy)
            if (nm != null) marks.add(PreviewMark(nm.first, nm.second, label, isDisk = true, radiusN = rN))
        }
        addMark(target.alt, target.name)
        addMark(-target.alt, "反射")
        val halfVfov = Math.toDegrees(Math.atan(768.0 / cam.fy))
        val dist = MirrorGeometry.trayDistanceM(mirrorHeightM, target.alt)
        val directVisible = target.alt < halfVfov
        val info = buildString {
            append("水镜：H=%.2f m，%s 高度 %+.1f° → 托盘距离 ".format(mirrorHeightM, target.name, target.alt))
            append(if (dist.isNaN()) "—（目标太贴近地平线）" else "**%.1f m**".format(dist))
            append("；竖直半视场 %.0f°：".format(halfVfov))
            append(if (directVisible) "直射像在画面内，反射像在下方" else "直射像超出画面，只能拍反射像")
        }
        return info to marks
    }
    private var starProbeJob: Job? = null
    private val shakeMonitor by lazy { ShakeMonitor(context) }
    private val calibTrigger = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastGoodMarksAtNs = 0L
    private var opening = false

    /** UI 按钮"拍一帧"：手动标定时调用，拍第 k+1 帧。 */
    fun triggerCalibrationFrame() {
        calibTrigger.incrementAndGet()
    }
    private var shakeJob: Job? = null
    private var imu: ImuRecorder? = null
    private val gnssRecorder: GnssRecorder by lazy { GnssRecorder(context) }
    private var gnssMonitor: Job? = null
    @Volatile private var capturing = false

    fun updateNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    /**
     * 预检阶段就开始监听 GNSS，UI 才能显示权限 / 卫星数 / 最近定位。
     * 采集时复用同一个 recorder（`clearLive()` 保证真值只来自本次会话）。
     */
    private fun startGnssMonitor() {
        val rec = gnssRecorder
        if (rec.permission == GnssRecorder.Permission.NONE) {
            _state.value = _state.value.copy(
                gnssPermission = rec.permissionLabel,
                gnssProviderEnabled = rec.providerEnabled,
                gnssReady = false,
                gnssLastFixText = "未授予定位权限",
            )
            return
        }
        rec.probeLastKnown()
        rec.start()
        if (gnssMonitor?.isActive == true) return
        gnssMonitor = scope.launch {
            while (isActive) {
                val age = rec.secondsSinceLastFix()
                val best = rec.bestLive()
                val text = when {
                    age < 0 -> rec.lastKnown?.let {
                        "无实时定位（系统最后已知：%.5f, %.5f）".format(it.lat, it.lon)
                    } ?: "尚未定位"
                    else -> "%.1f s 前 ±%.0f m（%s）".format(age, best?.accuracyM ?: -1.0, best?.provider ?: "-")
                }
                _state.value = _state.value.copy(
                    gnssPermission = rec.permissionLabel,
                    gnssProviderEnabled = rec.providerEnabled,
                    gnssSatellites = rec.satellitesUsed,
                    gnssLastFixText = text,
                    gnssReady = rec.hasRecentFix,
                )
                delay(1_000)
            }
        }
    }

    /** 预检：相机能力、内参照来源、时钟状态。必须在 UI 线程外调用或自行异步。 */
    /**
     * 进入采集页就打开相机预览（不等用户点"开始采集"）。
     * 之前只有点采集才 open()，且会话只建一次：TextureView 晚到就永远黑屏。
     */
    fun openPreview(textureView: TextureView?) {
        val cam = camera ?: runCatching {
            StarCamera(context).also {
                it.requestedCameraId = selectedCameraId
                camera = it
            }
        }.getOrNull() ?: return
        if (opening) return   // 并发打开会互相打断相机回调（"打开中…"卡死的根因之一）
        if (cam.isOpen) {
            cam.attachPreview(textureView)
            _state.value = _state.value.copy(cameraStatus = cam.previewStatus)
            return
        }
        opening = true
        scope.launch {
            _state.value = _state.value.copy(cameraStatus = "打开中…")
            // 看门狗：8 s 仍未就绪就关掉重开一次
            runCatching { cam.open(textureView) }
                .onSuccess {
                    var sinkCount = 0
                    cam.displaySink = { bmp ->
                        // 只有找星模式才显示增强位图；否则晚到的帧会在切模式后把旧画面"钉"在预览上
                        if (_state.value.previewMode == 1) {
                            sinkCount++
                            if (sinkCount % 20 == 1) {
                                DiagLog.i("Ctl", "displaySink #$sinkCount ${bmp.width}x${bmp.height}")
                            }
                            _state.value = _state.value.copy(
                                previewBitmap = bmp,
                                previewRotationDeg = cam.sensorOrientationDegrees,
                            )
                        } else {
                            DiagLog.w("Ctl", "displaySink dropped: mode=${_state.value.previewMode}")
                        }
                    }
                    applySavedTune(cam)
                    // 重新打开相机后必须把当前预览模式应用回去（否则找星/月亮探测目标不会加）
                    runCatching { cam.setPreviewMode(_state.value.previewMode) }
                    _state.value = _state.value.copy(cameraStatus = cam.previewStatus)
                    startShakeMonitor()
                    startAimMonitor()
                    DiagLog.i("Ctl", "aim monitor started: compass=${compassAim != null}")
                    if (_state.value.previewMode == 1 || _state.value.previewMode == 3 ||
                        _state.value.previewMode == 4 || _state.value.previewMode == 5
                    ) startStarProbe()
                }
                .onFailure { e ->
                    DiagLog.e("Ctl", "openPreview failed: ${e::class.java.simpleName}: ${e.message}")
                    _state.value = _state.value.copy(cameraStatus = "打开失败：${e.message}")
                }
            opening = false
        }
    }

    /**
     * 从后台/其他应用（如原生相机）回来时调用：
     * - 相机被抢占过（device/session 失效）→ 关掉重开，并按退避重试（对方可能还占着相机）；
     * - 相机仍可用 → 重装预览请求 + 重启找星探测。
     */
    fun onScreenResumed(textureView: TextureView?) {
        val cam = camera
        if (cam == null) {
            if (textureView != null) openPreview(textureView)
            return
        }
        if (!cam.isOpen) {
            opening = false
            runCatching { cam.close() }
            if (textureView != null) openPreview(textureView)
            scope.launch {
                for (wait in longArrayOf(1_500L, 4_000L, 10_000L)) {
                    delay(wait)
                    val c = camera ?: return@launch
                    if (c.isOpen) return@launch
                    opening = false
                    runCatching { c.close() }
                    openPreview(textureView)
                }
            }
            return
        }
        runCatching { cam.resetProbe() }
        runCatching { cam.setPreviewMode(_state.value.previewMode) }
        if (_state.value.previewMode == 1 && !capturing) {
            starProbeJob?.cancel()
            startStarProbe()
        }
        _state.value = _state.value.copy(cameraStatus = cam.previewStatus)
    }

    /** 预览调参：把现场可调参数推到相机并回写到状态。 */
    fun setTunables(
        enhance: Boolean? = null,
        stack: Int? = null,
        bgBlur: Int? = null,
        starBoost: Float? = null,
        hpRadius: Int? = null,
        targetLevel: Float? = null,
        gamma: Float? = null,
        exposureMs: Int? = null,
    ) {
        val cam = camera ?: return
        enhance?.let { cam.enhanceEnabled = it }
        stack?.let { cam.dispStackFrames = it.coerceIn(2, 6) }
        bgBlur?.let { cam.bgBlurRadius = it.coerceIn(4, 96) }
        starBoost?.let { cam.starBoost = it.coerceIn(0f, 4f) }
        hpRadius?.let { cam.starHpRadius = it.coerceIn(1, 16) }
        targetLevel?.let { cam.targetLevel = it.coerceIn(0.1f, 0.8f) }
        gamma?.let { cam.gammaValue = it.coerceIn(0.3f, 1.5f) }
        var needReset = false
        enhance?.let { needReset = true }
        stack?.let { needReset = true }
        var newExpMs = _state.value.starExpMs
        exposureMs?.let {
            cam.starPreviewExpNs = (it * 1_000_000L).coerceIn(50_000_000L, 1_000_000_000L)
            newExpMs = (cam.starPreviewExpNs / 1_000_000L).toInt()
            runCatching { cam.setPreviewMode(_state.value.previewMode) }
            needReset = true
        }
        if (needReset) cam.resetProbe()
        _state.value = _state.value.copy(
            enhanceEnabled = cam.enhanceEnabled,
            dispStack = cam.dispStackFrames,
            bgBlur = cam.bgBlurRadius,
            starBoost = cam.starBoost,
            hpRadius = cam.starHpRadius,
            targetLevel = cam.targetLevel,
            gammaValue = cam.gammaValue,
            starExpMs = newExpMs,
            previewBitmap = if (cam.enhanceEnabled) _state.value.previewBitmap else null,
        )
        saveTunePrefs()
    }

    /** 调参持久化（重启后仍生效）。 */
    private fun saveTunePrefs() {
        val s = _state.value
        context.getSharedPreferences("preview_tune", Context.MODE_PRIVATE).edit()
            .putBoolean("enhance", s.enhanceEnabled)
            .putInt("stack", s.dispStack)
            .putInt("bgBlur", s.bgBlur)
            .putFloat("starBoost", s.starBoost)
            .putFloat("target", s.targetLevel)
            .putFloat("gamma", s.gammaValue)
            .putInt("expMs", s.starExpMs)
            .apply()
    }

    /** 相机打开后应用已保存的调参。 */
    private fun applySavedTune(cam: StarCamera) {
        val sp = context.getSharedPreferences("preview_tune", Context.MODE_PRIVATE)
        if (!sp.contains("expMs")) return
        cam.enhanceEnabled = sp.getBoolean("enhance", true)
        cam.dispStackFrames = sp.getInt("stack", 4)
        cam.bgBlurRadius = sp.getInt("bgBlur", 28)
        cam.starBoost = sp.getFloat("starBoost", 1.2f)
        cam.targetLevel = sp.getFloat("target", 0.42f)
        cam.gammaValue = sp.getFloat("gamma", 0.72f)
        cam.starPreviewExpNs = sp.getInt("expMs", 600) * 1_000_000L
        _state.value = _state.value.copy(
            enhanceEnabled = cam.enhanceEnabled, dispStack = cam.dispStackFrames,
            bgBlur = cam.bgBlurRadius, starBoost = cam.starBoost,
            targetLevel = cam.targetLevel, gammaValue = cam.gammaValue,
            starExpMs = (cam.starPreviewExpNs / 1_000_000L).toInt(),
        )
    }

    /** 预览 ROI 放大：1/2/3/4 倍（只显示中心区域，星点更大更醒目）。 */
    fun setPreviewZoom(z: Int) {
        val zz = z.coerceIn(1, 4)
        camera?.previewZoom = zz
        camera?.resetProbe()
        _state.value = _state.value.copy(previewZoom = zz)
    }

    /** 预览模式：0=自动，1=找星，2=跟随拍摄参数。 */
    fun setPreviewMode(mode: Int, exposureNs: Long, iso: Int) {
        DiagLog.i("Ctl", "setPreviewMode mode=$mode (prev=${_state.value.previewMode}) exp=$exposureNs iso=$iso")
        camera?.setPreviewParams(exposureNs, iso)
        camera?.setPreviewMode(mode)
        _state.value = _state.value.copy(
            previewMode = mode,
            cameraStatus = camera?.previewStatus ?: "未打开",
            // 非找星模式不再产生增强画面：清掉旧位图，否则会"卡在上一帧"
            previewBitmap = if (mode == 1) _state.value.previewBitmap else null,
            previewMarks = if (mode == 1) _state.value.previewMarks else emptyList(),
            starExpMs = camera?.let { (it.starPreviewExpNs / 1_000_000L).toInt() } ?: _state.value.starExpMs,
        )
        starProbeJob?.cancel()
        if (mode != 1) runCatching { camera?.resetProbe() }
        if (mode == 1 || mode == 3 || mode == 4 || mode == 5) startStarProbe()
    }

    /** 月亮模式提示：月亮高度/方位（需 GNSS 位置）+ 多历元采集提醒。 */
    private fun moonGuidance(detected: Int): String {
        val now = System.currentTimeMillis()
        val ephem = runCatching { Moon.ephemerisAt(now) }.getOrNull()
            ?: return "月亮：星历不可用"
        val fix = runCatching { gnssRecorder.bestLive() }.getOrNull()
        val head = if (fix != null) {
            val site = Moon.Site(Math.toRadians(fix.lat), Math.toRadians(fix.lon))
            val gast = SkyContext.at(now).gast
            "月亮：高度 %.0f° 方位 %.0f°".format(
                Math.toDegrees(Moon.altitudeRad(site, ephem, now, gast)),
                Math.toDegrees(Moon.azimuthRad(site, ephem, gast)),
            )
        } else {
            "月亮：等待 GNSS 定位（才能算高度/方位）"
        }
        val exp = camera?.moonPreviewExpNs ?: 2_000_000L
        val iso = camera?.moonPreviewIso ?: 800
        return "$head · ${exp / 1_000_000.0} ms / ISO $iso · 视场内 $detected 个亮点 · 隔 20–30 min 再拍一组"
    }

    /** 找星模式：周期检测，更新识别框与增强预览。 */
    private fun startStarProbe() {
        val cam = camera ?: return
        // 先取消旧循环（取消是协作式的，旧循环可能还在迭代 probeTracks）
        starProbeJob?.cancel()
        val gen = ++probeGeneration
        starProbeJob = scope.launch {
            while (isActive) {
                delay(500)   // 刷新间隔：1.5 s → 0.5 s
                if (gen != probeGeneration) break
                if (capturing && !allowProbeDuringCapture) {
                    // 采集进行中（且不是手动标定的帧间等待）：识别框暂停并清空
                    synchronized(probeLock) { probeTracks.clear() }
                    _state.value = _state.value.copy(
                        cameraStatus = "采集中：识别框暂停",
                        previewMarks = emptyList(),
                    )
                    continue
                }
                val t0 = System.currentTimeMillis()
                val probe = cam.probeStars(200_000_000L, cam.maxIso)
                val ms = System.currentTimeMillis() - t0
                val marks = ArrayList<PreviewMark>()
                if (probe.error.isEmpty()) {
                    // 时间一致性：只有连续 2 次检测到的天体才显示
                    // （三脚架上仍会因大气/云衰减抖动，单帧亮斑不可信）
                    synchronized(probeLock) {
                    for (b in probe.blobs) {
                        val hit = probeTracks.minByOrNull {
                            (it.nx - b.nx) * (it.nx - b.nx) + (it.ny - b.ny) * (it.ny - b.ny)
                        }
                        if (hit != null && Math.hypot((hit.nx - b.nx).toDouble(), (hit.ny - b.ny).toDouble()) < 0.03) {
                            hit.nx = 0.6f * b.nx + 0.4f * hit.nx
                            hit.ny = 0.6f * b.ny + 0.4f * hit.ny
                            hit.peak = b.peak
                            hit.hits++
                            hit.misses = 0
                        } else if (probeTracks.size < 12) {
                            probeTracks.add(Track(b.nx, b.ny, 1, b.peak, 1, 0))
                        }
                    }
                    probeTracks.removeAll { ++it.misses >= 3 || it.hits <= 0 }
                    // 只标画面里最亮的前 6 颗，编号固定 1..6（按亮度排序，不再无限增长）
                    val brightest = probeTracks.filter { it.hits >= 2 }
                        .sortedByDescending { it.peak }
                        .take(6)
                    brightest.forEachIndexed { idx, t ->
                        marks.add(PreviewMark(t.nx, t.ny, "${idx + 1}"))
                    }
                    }
                    probe.disk?.let { d ->
                        diskStreak = if (diskStreak > 0) diskStreak + 1 else 1
                        if (diskStreak >= 2) {
                            val modeNow = _state.value.previewMode
        val label = when {
            modeNow == 4 -> "太阳"
            modeNow == 5 -> "月亮"
            probe.frameMean > 0.25f -> "太阳"
            else -> "月亮"
        }
                            marks.add(PreviewMark(d.nx, d.ny, label, isDisk = true, radiusN = d.radiusN))
                        }
                    } ?: run { diskStreak = 0 }

                    // 水镜辅助：跟踪框里指示反射像（优先用检测到的亮斑，检测不到就给预测位）
                    if (mirrorEnabled) {
                        val fix = runCatching { gnssRecorder.bestLive() }.getOrNull()
                        val cam = _state.value.intrinsics?.toCameraIntrinsics(2048, 1536)
                        if (fix != null && cam != null) {
                            val now = System.currentTimeMillis()
                            val site = Moon.Site(Math.toRadians(fix.lat), Math.toRadians(fix.lon))
                            val gast = SkyContext.at(now).gast
                            val isSun = _state.value.previewMode == 4 || probe.frameMean > 0.25f
                            val body = runCatching {
                                if (isSun) Moon.sunEphemerisAt(now) else Moon.ephemerisAt(now)
                            }.getOrNull()
                            if (body != null) {
                                val altDeg = Math.toDegrees(Moon.altitudeRad(site, body, now, gast))
                                val azDeg = Math.toDegrees(Moon.azimuthRad(site, body, gast))
                                val rN = (Math.tan(body.angularRadiusRad) * cam.fx / 2048.0)
                                    .toFloat().coerceAtLeast(0.004f)
                                var detected: PreviewMark? = null
                                val d = probe.disk
                                if (d != null) {
                                    val uD = V.unit(cam.unproject(d.nx * 2048.0, d.ny * 1536.0))
                                    var bestErr = Double.MAX_VALUE
                                    for (b in probe.blobs) {
                                        val sep = Math.hypot((b.nx - d.nx).toDouble(), (b.ny - d.ny).toDouble())
                                        if (sep < 0.03) continue
                                        val uB = V.unit(cam.unproject(b.nx * 2048.0, b.ny * 1536.0))
                                        val nx3 = uD[0] - uB[0]
                                        val ny3 = uD[1] - uB[1]
                                        val nz3 = uD[2] - uB[2]
                                        val nn = Math.sqrt(nx3 * nx3 + ny3 * ny3 + nz3 * nz3)
                                        if (nn < 1e-6) continue
                                        val alt = Math.toDegrees(
                                            Math.asin(((uD[0] * nx3 + uD[1] * ny3 + uD[2] * nz3) / nn).coerceIn(-1.0, 1.0)),
                                        )
                                        // 镜像自洽：直射−反射 得到的天顶，反算高度应≈星历高度
                                        val err = Math.abs(alt - altDeg)
                                        if (alt > 1.0 && err < bestErr && err < 1.2) {
                                            bestErr = err
                                            detected = PreviewMark(b.nx, b.ny, "反射", isDisk = false)
                                        }
                                    }
                                }
                                if (detected != null) {
                                    marks.add(detected!!)
                                } else {
                                    val hTrue = (_state.value.headingDeg + declinationDeg + 360.0) % 360.0
                                    val nm = MirrorGeometry.mark(
                                        -altDeg, azDeg, hTrue, _state.value.cameraPitchDeg, cam.fx, cam.fy,
                                    )
                                    if (nm != null) {
                                        marks.add(PreviewMark(nm.first, nm.second, "反射?", isDisk = true, radiusN = rN))
                                    }
                                }
                            }
                        }
                    }
                }
                // 没检测到东西时保留上一批框最多 6 s，避免闪烁
                val keep = marks.isEmpty() && lastGoodMarksAtNs > 0 &&
                    (System.nanoTime() - lastGoodMarksAtNs) < 6_000_000_000L
                if (marks.isNotEmpty()) lastGoodMarksAtNs = System.nanoTime()
                _state.value = _state.value.copy(
                    cameraStatus = if (probe.error.isNotEmpty())
                        "找星探测失败：${probe.error}"
                    else if (_state.value.previewMode == 3)
                        moonGuidance(probe.count)
                    else if (_state.value.previewMode == 4)
                        "太阳：%s · 拍前确认已装滤镜".format(
                            if (probe.disk != null) "已检测到日面" else "未检测到日面（对准/调曝光）",
                        )
                    else
                        "找星：视场内 ${probe.count} 颗 · 预览 ${_state.value.starExpMs} ms" +
                            "（取帧 ${probe.captureMs} ms + 检测 ${probe.analyzeMs} ms）",
                    previewMarks = if (keep) _state.value.previewMarks else marks,
                )
            }
        }
    }

    private class Track(var nx: Float, var ny: Float, val id: Int, var peak: Float, var hits: Int, var misses: Int)
    private val probeTracks = ArrayList<Track>()
    /** 保护 probeTracks：多个探测协程/采集状态可能并发访问（2026-11-21 闪退根因）。 */
    private val probeLock = Any()
    /** 探测循环代号：新循环启动后旧循环立即退出，避免双循环并发。 */
    private var probeGeneration = 0
    private var nextTrackId = 1
    private var diskStreak = 0

    /**
     * 等到"稳住"再返回：陀螺等级 0 连续保持 [needMs]。
     * 标定采集用它替代固定间隔——手持晃动时不再盲拍。
     */
    private suspend fun waitUntilStable(timeoutMs: Long = 20_000, needMs: Long = 800): Boolean {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var stableSince = -1L
        while (android.os.SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val lv = shakeMonitor.level()
            _state.value = _state.value.copy(shakeText = shakeMonitor.text(), shakeLevel = lv)
            val now = android.os.SystemClock.elapsedRealtime()
            if (lv == 0) {
                if (stableSince < 0) stableSince = now
                if (now - stableSince >= needMs) return true
            } else {
                stableSince = -1L
            }
            delay(100)
        }
        return false
    }

    /** 实时稳定度：每 0.5 s 刷新一次，告诉用户"现在能不能拍"。 */
    private fun startShakeMonitor() {
        shakeMonitor.start()
        if (shakeJob?.isActive == true) return
        shakeJob = scope.launch {
            while (isActive) {
                val lv = shakeMonitor.level()
                _state.value = _state.value.copy(
                    shakeText = shakeMonitor.text(),
                    shakeLevel = lv,
                )
                delay(500)
            }
        }
    }

    /** 用户改曝光/ISO 时同步给预览（仅手动模式下生效）。 */
    fun updatePreviewParams(exposureNs: Long, iso: Int) {
        camera?.setPreviewParams(exposureNs, iso)
        _state.value = _state.value.copy(cameraStatus = camera?.previewStatus ?: "未打开")
    }

    /** TextureView 可用/销毁时由 UI 通知，用于补建预览会话。 */
    fun onPreviewSurface(textureView: TextureView?) {
        camera?.attachPreview(textureView)
        _state.value = _state.value.copy(cameraStatus = camera?.previewStatus ?: "未打开")
    }

    fun refreshPreflight(clock: ClockStatus?) {
        startGnssMonitor()
        scope.launch {
            runCatching {
                val cm = CameraProbe.cameraManager(context)
                val id = selectedCameraId ?: CameraProbe.pickBackCamera(cm)
                if (id == null) {
                    _state.value = _state.value.copy(phase = "ERROR", error = "没有可用的后置相机")
                    return@launch
                }
                val caps = CameraProbe.describe(cm, id) ?: error("读取相机能力失败")
                // 预检用 active array 估算内参（真正采集时会用实际输出尺寸重算）
                val w = if (caps.activeArrayW > 0) caps.activeArrayW else 4000
                val h = if (caps.activeArrayH > 0) caps.activeArrayH else 3000
                val intr = CameraProbe.resolveIntrinsics(caps, w, h, CameraProbe.loadCalibrationFile(context, id))
                val expText = caps.exposureRangeNs.takeIf { it.size == 2 && it[1] > 0 }
                    ?.let { "%.1f µs – %.2f s".format(it[0] / 1000.0, it[1] / 1e9) } ?: "未知"
                val isoText = caps.isoRange.takeIf { it.size == 2 && it[1] > 0 }
                    ?.let { "${it[0]} – ${it[1]}" } ?: "未知"
                _state.value = _state.value.copy(
                    phase = "IDLE",
                    cameraLine = "camera=$id ${caps.lensFacing} ${caps.hardwareLevel} " +
                        "active=${caps.activeArrayW}×${caps.activeArrayH} focal=${caps.focalLengthsMm.firstOrNull() ?: 0.0}mm",
                    caps = caps,
                    intrinsics = intr,
                    exposureRange = expText,
                    isoRange = isoText,
                    rawSupported = caps.rawSupported,
                    preflightDone = true,
                    clock = clock,
                    error = null,
                    extrinsicText = com.dsh.starpos.field.ExtrinsicConfig.load(context)?.let {
                        val how = when (it.source) {
                            "asset-default" -> "APK 内置"
                            "app-handeye" -> "App 自标定"
                            else -> it.source
                        }
                        "已载入（%.2f°，%s）".format(it.angleDeg, how)
                    } ?: "未标定（单历元位置误差可达数百 km）",
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(phase = "ERROR", error = e.message ?: e.toString())
            }
        }
    }

    private fun imuShake(rows: List<ImuRow>, meta: FrameMeta): com.dsh.starpos.field.ImuShake.Info? {
        val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
        return com.dsh.starpos.field.ImuShake.analyze(rows, anchor)
    }

    /** 开始采集。会阻塞在 IO 协程里，逐帧更新 state。 */
    fun startCapture(textureView: TextureView?, spec: Spec, clock: ClockStatus?) {
        if (capturing) return
        capturing = true
        _state.value = _state.value.copy(calibrationResult = null, calibrationOk = false)
        // 手动逐帧标定：帧与帧之间要保持预览探测刷新（否则识别框会卡在第一帧位置）。
        // 自动/多帧采集仍停掉探测，避免并发抢图像队列。
        if (!(spec.manualCalibration || spec.manualIntrinsic)) starProbeJob?.cancel()
        _state.value = _state.value.copy(previewMarks = emptyList())
        if (spec.estimateExtrinsic) startShakeMonitor()   // 标定依赖稳定度，确保监视在跑
        if (spec.manualCalibration || spec.manualIntrinsic) {
            calibTrigger.set(0)
            _state.value = _state.value.copy(
                manualCalib = true, calibLock = true, calibShot = 0,
                calibTotal = spec.frames.coerceIn(1, 12),
            )
        } else {
            _state.value = _state.value.copy(calibLock = false)
        }
        scope.launch {
            var cam: StarCamera? = null
            var imuRec: ImuRecorder? = null
            try {
                _state.value = _state.value.copy(phase = "OPENING", message = "打开相机…", error = null, qualities = emptyList())
                cam = camera ?: StarCamera(context).also { camera = it }
                if (cam.isOpen) {
                    cam.attachPreview(textureView)   // 复用预览，避免重开相机造成黑屏
                } else {
                    cam.open(textureView)
                }
                _state.value = _state.value.copy(cameraStatus = cam.previewStatus)
                val caps = (CameraProbe.describe(CameraProbe.cameraManager(context), cam.cameraId) ?: error("读取相机能力失败"))
                val w = cam.outputWidth
                val h = cam.outputHeight
                val intrinsics = CameraProbe.resolveIntrinsics(caps, w, h, CameraProbe.loadCalibrationFile(context, cam.cameraId))

                // 设备可能不支持长曝光：夹到硬件范围；曝光被压时把 ISO 拉满补偿
                val devMaxExpNs = caps.exposureRangeNs.getOrNull(1) ?: spec.exposureNs
                val devMinExpNs = caps.exposureRangeNs.getOrNull(0) ?: 1_000L
                val devMaxIso = caps.isoRange.getOrNull(1) ?: spec.iso
                val devMinIso = caps.isoRange.firstOrNull() ?: 100
                val expNs = spec.exposureNs.coerceIn(devMinExpNs, devMaxExpNs)
                var isoUse = spec.iso.coerceIn(devMinIso, devMaxIso)
                if (expNs < spec.exposureNs && isoUse < devMaxIso) isoUse = devMaxIso
                val clampNote = if (expNs < spec.exposureNs || isoUse != spec.iso) {
                    "⚠ 设备上限：曝光 %.0f ms / ISO %d → 自动采用 %.0f ms / ISO %d".format(
                        spec.exposureNs / 1e6, spec.iso, expNs / 1e6, isoUse,
                    )
                } else ""
                if (clampNote.isNotEmpty()) {
                    _state.value = _state.value.copy(message = clampNote)
                }

                imuRec = ImuRecorder(context)
                val imuOk = imuRec.start()
                if (!imuOk) {
                    _state.value = _state.value.copy(
                        message = "⚠ IMU 启动失败（${imuRec.failureReason ?: "未知原因"}）：仍可采集，但本次无法解出位置",
                    )
                }
                val gnssRec = gnssRecorder
                gnssRec.clearLive()       // 真值只认本次会话的实时定位
                gnssRec.probeLastKnown()  // 仅作参考，单独落盘
                val gnssOk = gnssRec.start()
                imu = imuRec

                val sessionsRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                // 数据前缀：sun/moon/star/ext/cal，便于联合解算按类型识别（旧包仍是 session-*）
                val kind = when {
                    spec.note.contains("SUN", true) || spec.note.contains("太阳") -> "sun"
                    spec.note.contains("EXTRINSIC", true) -> "ext"
                    spec.note.contains("CALIBRATION", true) -> "cal"
                    _state.value.previewMode == 3 || _state.value.previewMode == 5 -> "moon"
                    _state.value.previewMode == 4 -> "sun"
                    else -> "star"
                }
                val camTag = "cam" + (selectedCameraId ?: "0")
                val dir = File(sessionsRoot, "$kind-$camTag-session-$stamp")
                dir.mkdirs()
                val writer = BundleWriter(dir)

                // 等首个实时定位（最多 15 s），避免采集到的包没有真值；标定模式不需要
                var waitedMs = 0L
                if (spec.autoMode) {
                    _state.value = _state.value.copy(
                        phase = "CAPTURING",
                        message = "标定采集（自动对焦/曝光），共 ${spec.frames} 张…",
                        caps = caps,
                        intrinsics = intrinsics,
                        cameraLine = "camera=${cam.cameraId} ${caps.lensFacing} ${caps.hardwareLevel} ${w}×${h}",
                    )
                } else {
                    val waitStart = android.os.SystemClock.elapsedRealtime()
                    while (gnssRec.liveFixCount == 0 &&
                        android.os.SystemClock.elapsedRealtime() - waitStart < 15_000L
                    ) {
                        waitedMs = android.os.SystemClock.elapsedRealtime() - waitStart
                        _state.value = _state.value.copy(
                            phase = "WAITING_GNSS",
                            message = "等待 GNSS 定位… %d s / 15 s（无定位也可采集，但无法验证精度）"
                                .format(waitedMs / 1000),
                        )
                        delay(500)
                    }
                    waitedMs = android.os.SystemClock.elapsedRealtime() - waitStart

                    _state.value = _state.value.copy(
                        phase = "CAPTURING",
                        message = if (gnssRec.liveFixCount > 0) {
                            "已获得 GNSS 定位（±%.0f m），稳定 2 秒后开始曝光…".format(gnssRec.bestLive()?.accuracyM ?: -1.0)
                        } else {
                            "⚠ 暂无 GNSS 定位（仍继续采集），稳定 2 秒后开始曝光…"
                        },
                        caps = caps,
                        intrinsics = intrinsics,
                        cameraLine = "camera=${cam.cameraId} ${caps.lensFacing} ${caps.hardwareLevel} ${w}×${h}",
                    )
                }
                delay(2_000) // 让 IMU 有足够静止样本，也避免刚开机的抖动

                val index = CatalogHolder.index(context)
                var liveSolver: PlateSolver? = null
                // 与 Analyzer 一致：残差门限按像素尺度（广视场 1 px 可达 100″+）
                val metas = ArrayList<FrameMeta>()
                val qualities = ArrayList<Quality>()
                var shakyFrames = 0
                var maxDriftDeg = 0.0

                // HDR：先拍 frames 张短曝光，再拍 frames 张长曝光（同一指向）
                val hdrLongExp = spec.hdrLongNs.coerceIn(devMinExpNs, devMaxExpNs)
                val hdrLongIso = spec.hdrLongIso.coerceIn(devMinIso, devMaxIso)
                val framePlan = ArrayList<Pair<Long, Int>>()
                repeat(spec.frames.coerceIn(1, 12)) { framePlan.add(expNs to isoUse) }
                if (spec.hdrLongNs > 0L) {
                    repeat(spec.frames.coerceIn(1, 12)) { framePlan.add(hdrLongExp to hdrLongIso) }
                }
                val frameCount = framePlan.size
                if (spec.hdrLongNs > 0L) {
                    DiagLog.i("Ctl", "HDR capture: ${spec.frames}×${expNs/1_000_000}ms + ${spec.frames}×${hdrLongExp/1_000_000}ms")
                }
                for (i in 0 until frameCount) {
                    val (frameExpNs, frameIso) = framePlan[i]
                    _state.value = _state.value.copy(
                        message = "曝光 %d/%d%s…".format(i + 1, frameCount, if (spec.hdrLongNs > 0L && i >= spec.frames) "（长曝光）" else ""),
                    )
                    if (spec.autoMode && i > 0 && !spec.manualIntrinsic) delay(1_200)   // 手动模式由用户控制节奏
                    if (!capturing) throw CanceledCapture()
                    // 手动逐帧（外参/内参通用）：等用户按"拍一帧"
                    if (spec.manualCalibration || spec.manualIntrinsic) {
                        // 帧间恢复预览并重新启动识别框刷新（否则框会卡在上一帧）
                        runCatching { cam.setPreviewMode(_state.value.previewMode) }
                        allowProbeDuringCapture = true
                        startStarProbe()
                        _state.value = _state.value.copy(
                            message = "等待按『拍一帧』：已拍 ${i}/$frameCount（换好角度再按，${shakeMonitor.text()}）",
                        )
                        val deadline = System.currentTimeMillis() + 120_000
                        while (calibTrigger.get() <= i && capturing && System.currentTimeMillis() < deadline) {
                            delay(150)
                        }
                        if (!capturing) throw CanceledCapture()
                        if (calibTrigger.get() <= i) {
                            throw IllegalStateException("等待手动触发超时（2 分钟没按『拍一帧』）")
                        }
                        // 曝光瞬间暂停探测，避免和 still capture 抢图像队列
                        allowProbeDuringCapture = false
                        starProbeJob?.cancel()
                        runCatching { cam.resetProbe() }
                        _state.value = _state.value.copy(calibShot = i + 1)
                        if (i + 1 >= frameCount) {
                            _state.value = _state.value.copy(
                                message = "第 ${i + 1}/${spec.frames} 帧已拍：正在打包，请勿重复点击",
                                packing = true,
                            )
                        }
                    }
                    // 外参标定：等"稳住"再拍（不再用固定间隔盲拍）
                    if (spec.estimateExtrinsic && !spec.manualCalibration) {
                        _state.value = _state.value.copy(
                            message = "外参标定：等待稳定后拍第 ${i + 1}/${spec.frames} 帧（${shakeMonitor.text()}）",
                        )
                        if (!waitUntilStable()) {
                            _state.value = _state.value.copy(
                                message = "第 ${i + 1} 帧：20 s 内未等到稳定，仍尝试拍摄（该帧可能被后续剔除）",
                            )
                        }
                    }
                    val frame = cam.capture(frameExpNs, frameIso, 20_000L, spec.autoMode)
                    // 用真实帧尺寸建解算器：RAW 帧是 4096/2=2048 宽，内参必须跟着缩放
                    val solverForFrame = liveSolver ?: run {
                        val lc = intrinsics.toCameraIntrinsics(frame.width, frame.height)
                        PlateSolver(
                            index, lc,
                            triangleMagLimit = com.dsh.starpos.astro.StarSolveParams.triangleMag(lc),
                            maxResidualArcsec = max(60.0, 2.0 * 206264.8 / lc.fx),
                        ).also { liveSolver = it }
                    }
                    val meta = FrameMeta(
                        index = i,
                        sensorTimestampNs = frame.sensorTimestampNs,
                        elapsedRealtimeNs = android.os.SystemClock.elapsedRealtimeNanos(),
                        wallClockUtcMs = System.currentTimeMillis(),
                        exposureNs = frame.exposureNs,
                        iso = frame.iso,
                        focalLengthMm = frame.focalLengthMm,
                        focusDistanceDiopters = frame.focusDistanceDiopters,
                        zoomRatio = frame.zoomRatio,
                        width = frame.width,
                        height = frame.height,
                        bitDepth = frame.bitDepth,
                        source = frame.source,
                        oisEnabled = frame.oisEnabled,
                        awbMode = frame.awbMode,
                        frameDurationNs = frame.frameDurationNs,
                    )
                    writer.writeFrame(meta, frame.pixels16, frame.pixels8)
                    metas.add(meta)

                    // 现场即时反馈：能不能认出星、够不够解算
                    val lum = frame.pixels16?.let { p -> FloatArray(p.size) { (p[it].toInt() and 0xFFFF) / 65535f } }
                        ?: frame.pixels8?.let { p -> FloatArray(p.size) { (p[it].toInt() and 0xFF) / 255f } }
                    var quality = Quality(i, 0, 1.0, 0.0, false, 0, "无像素")
                    if (lum != null) {
                        // 内参标定（近距点阵）：点阵不是恒星，不做星图解算，也不显示"匹配失败"
                        if (spec.autoMode && !spec.estimateExtrinsic) {
                            val det2 = FrameAnalyzer.analyze(
                                lum, meta.width, meta.height,
                                thresholdSigma = com.dsh.starpos.field.FieldAnalyzer.REAL_THRESHOLD_SIGMA,
                                medianPrefilter = false,
                            )
                            quality = Quality(
                                index = i,
                                detections = det2.detections.size,
                                cloudCover = det2.quality.cloudCover,
                                psfAspect = 1.0,
                                solved = false,
                                matched = 0,
                                hint = "内参帧（点阵）：不参与星图解算，共 ${det2.detections.size} 个点状结构",
                            )
                            qualities.add(quality)
                            _state.value = _state.value.copy(qualities = qualities.toList())
                            continue
                        }
                        // 自适应阈值阶梯：主摄 7σ→5.5σ→9σ；超广角 7σ→5.5σ→5.0σ（星点 1–2 px、更暗）
                        val camQ = intrinsics.toCameraIntrinsics(meta.width, meta.height)
                        val solveParams = com.dsh.starpos.astro.StarSolveParams
                        val minMatched = if (solveParams.isWide(camQ)) 4 else 5
                        var det = FrameAnalyzer.analyze(
                            lum, meta.width, meta.height,
                            thresholdSigma = com.dsh.starpos.field.FieldAnalyzer.REAL_THRESHOLD_SIGMA,
                            medianPrefilter = false,
                        )
                        var starLike = det.detections.filter { solveParams.accept(it, camQ) }
                        var sol = runCatching {
                            solverForFrame.solve(
                                starLike,
                                maxImageStars = solveParams.maxImageStars(camQ),
                                matchToleranceDeg = solveParams.matchToleranceDeg(camQ),
                            )
                        }.getOrNull()
                        for (fallbackSigma in solveParams.thresholds(camQ).drop(1)) {
                            if (sol != null && sol.matchedStars >= minMatched) break
                            val d2 = FrameAnalyzer.analyze(
                                lum, meta.width, meta.height,
                                thresholdSigma = fallbackSigma, medianPrefilter = false,
                            )
                            val s2 = d2.detections.filter { solveParams.accept(it, camQ) }
                            val p2 = runCatching {
                                solverForFrame.solve(
                                    s2,
                                    maxImageStars = solveParams.maxImageStars(camQ),
                                    matchToleranceDeg = solveParams.matchToleranceDeg(camQ),
                                )
                            }.getOrNull()
                            val cur = sol
                            if (p2 != null && (cur == null || p2.matchedStars > cur.matchedStars)) {
                                det = d2; starLike = s2; sol = p2
                            }
                        }
                        val bright = det.detections.take(20).map { it.aspect }.sorted()
                        val medAspect = if (bright.isEmpty()) 1.0 else bright[bright.size / 2]
                        val elongFrac = if (det.detections.isEmpty()) 0.0
                        else det.detections.count { it.aspect > 2.5 }.toDouble() / det.detections.size
                        // 曝光是否真的生效：设备可能把长曝光压回帧时长（这是"只能看到 1 颗星"的最常见原因）
                        val expRequestedMs = expNs / 1e6
                        val expActualMs = frame.exposureNs / 1e6
                        val clamped = frame.exposureNs < expNs * 0.5
                        val stat = luminanceStats(lum)
                        val meanLum = stat?.first ?: 0.0
                        val p99 = stat?.second ?: 0.0
                        val hint = when {
                            clamped -> "⚠ 设备只给了 %.0f ms 曝光（请求 %.0f ms）：长曝光被限制，画面必然过暗".format(
                                expActualMs, expRequestedMs,
                            )
                            p99 < 0.02 -> "画面太暗（亮部 p99=%.3f）：提高 ISO 或延长曝光".format(p99)
                            meanLum > 0.6 -> "过曝（平均亮度 %.2f）：降低 ISO 或缩短曝光".format(meanLum)
                            det.detections.size < 8 -> "星太少：延长曝光或换个更开阔的方向"
                            det.quality.cloudCover > 0.7 -> "云太多：等云过去"
                            elongFrac > 0.35 -> "拖尾/边缘太多（%.0f%%）：换个更稳的支撑或避开建筑".format(elongFrac * 100)
                            sol == null -> "认出 ${det.detections.size} 颗像星但未匹配（多为拖尾/视场过小/曝光不足）：先试 0.5 s 曝光或换方向"
                            sol.matchedStars < minMatched -> "匹配星数不足：多拍几张"
                            else -> "可解算（${sol.matchedStars} 颗）"
                        }
                        val shake = imuShake(imuRec.rows(), meta)
                        val hintWithShake = if (shake != null && shake.text.isNotEmpty()) "$hint；${shake.text}" else hint
                        if (shake != null) {
                            if (shake.bad) shakyFrames++
                            if (shake.driftDeg > maxDriftDeg) maxDriftDeg = shake.driftDeg
                        }
                        quality = Quality(
                            index = i,
                            detections = det.detections.size,
                            cloudCover = det.quality.cloudCover,
                            psfAspect = medAspect,
                            solved = sol != null && sol.matchedStars >= minMatched,
                            matched = sol?.matchedStars ?: 0,
                            hint = hintWithShake,
                        )
                        qualities.add(quality)
                        _state.value = _state.value.copy(qualities = qualities.toList())
                    }
                }

                imuRec.stop()
                writer.writeImu(imuRec.rows())
                // 只写本次会话的实时定位；系统"最后已知位置"单独落盘，绝不混进真值
                writer.writeGnss(gnssRec.rows())
                writer.writeLastKnown(gnssRec.lastKnown)
                writer.writeNotes(spec.note)

                val truthRow = gnssRec.bestLive()
                val manifest = SessionManifest(
                    sessionId = dir.name,
                    createdAtUtcMs = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    device = deviceInfo(),
                    camera = caps,
                    intrinsics = intrinsics,
                    capture = linkedMapOf(
                        "frames" to metas.size,
                        "auto_mode" to spec.autoMode,
                        "requested_exposure_ns" to expNs,
                        "exposure_clamped" to metas.any { it.exposureNs < spec.exposureNs / 2 },
                        "applied_exposure_ns" to (metas.minOfOrNull { it.exposureNs } ?: 0L),
                        "requested_iso" to isoUse,
                        "mode" to (if (caps.rawSupported) "raw16" else "yuv8"),
                        "imu_ok" to imuOk,
                        "mirror_enabled" to spec.mirrorEnabled,
                        "mirror_height_m" to spec.mirrorHeightM,
                        "imu_hz" to imuRec.sampleRateHz(),
                        "imu" to linkedMapOf(
                            "ok" to imuOk,
                            "hz" to imuRec.sampleRateHz(),
                            "requested_delay_us" to imuRec.requestedDelayUs,
                            "effective_delay_us" to imuRec.effectiveDelayUs,
                            "error" to (imuRec.failureReason ?: ""),
                        ),
                        "raw_supported" to caps.rawSupported,
                        "device_to_camera" to "diag(1,-1,-1) rear camera（未做逐台外参标定）",
                        "lens_correction" to "off",
                        "note" to "M2 现场采集；相机路径已真机验证（2026-11-19）",
                        "gnss" to linkedMapOf(
                            "ok" to gnssOk,
                            "permission" to gnssRec.permissionLabel,
                            "provider_enabled" to gnssRec.providerEnabled,
                            "fix_count" to gnssRec.liveFixCount,
                            "wait_ms" to waitedMs,
                            "satellites_used" to gnssRec.satellitesUsed,
                            "accuracy_m" to (truthRow?.accuracyM ?: -1.0),
                            "provider" to (truthRow?.provider ?: ""),
                            "has_truth" to (truthRow != null),
                        ),
                    ),
                    clock = linkedMapOf(
                        "auto_time" to (clock?.autoTimeEnabled ?: false),
                        "network" to (clock?.networkAvailable ?: false),
                        "uncertainty_ms" to (clock?.clockUncertaintyMs ?: -1.0),
                        "offline_hours" to (clock?.offlineHours ?: -1.0),
                        "last_sync_utc_ms" to (clock?.lastSyncUtcMs ?: 0L),
                        "stale" to (clock?.stale ?: true),
                        "snapshot_at_utc_ms" to System.currentTimeMillis(),
                    ),
                    note = spec.note,
                    frames = metas,
                    // 记录采集时实际生效的 IMU 零偏/映射：离线分析若不知道这两项，
                    // 重力天顶会偏 ~1.4°，星位误差凭空多出 ~175 km（2026-11-24 查实）
                    accelBiasMg = runCatching {
                        AccelBiasStore.load(context)?.bias?.let { b ->
                            doubleArrayOf(b[0] / 9.80665e-3, b[1] / 9.80665e-3, b[2] / 9.80665e-3)
                        }
                    }.getOrNull(),
                    accelMap = runCatching { AccelBiasStore.loadMap(context) }.getOrNull(),
                    // 真值只认本次会话的实时定位；没有就是 null，不用 last-known 冒充
                    gnssTruth = truthRow,
                )
                writer.writeManifest(manifest)
                // 诊断日志一并入包（最后 1500 行），离线排查卡帧/闪退不用再要 logcat
                DiagLog.writeIntoBundle(dir)

                val zip = File(sessionsRoot, "${dir.name}.zip")
                writer.zipTo(zip)
                _state.value = _state.value.copy(packing = false)

                // 需要时立刻用这份包自标定外参（结果并入最终提示，不能被"已打包"覆盖）
                var extrinsicMsg: String? = null
                if (spec.estimateExtrinsic) {
                    val est = runCatching {
                        com.dsh.starpos.field.BundleReader.open(zip).use { rd ->
                            com.dsh.starpos.field.ExtrinsicEstimator.estimate(
                                rd, CatalogHolder.catalog(context),
                            )
                        }
                    }.getOrNull()
                    if (est?.config != null) {
                        // 安全闸：与现有外参差异 >3° 时**不写入**。
                        // 2026-11-20 实测：几何退化的包解出 7.98°，若直接写入会让定位误差从 8–65 km 恶化到 500+ km。
                        val cfg = est.config
                        val cur = com.dsh.starpos.field.ExtrinsicConfig.load(context)
                        val diffDeg = if (cur != null) {
                            val d = com.dsh.starpos.astro.HandEye.rotationVector(
                                com.dsh.starpos.astro.Rodrigues.exp(
                                    cfg.rx - cur.rx, cfg.ry - cur.ry, cfg.rz - cur.rz,
                                ),
                            )
                            Math.toDegrees(com.dsh.starpos.astro.V.norm(d))
                        } else 0.0
                        if (cur != null && diffDeg > 3.0) {
                            extrinsicMsg = ("解出 %.2f°，与现有 %.2f° 相差 %.1f° → **未写入**（差异过大，需人工核对）")
                                .format(cfg.angleDeg, cur.angleDeg, diffDeg)
                            _state.value = _state.value.copy(
                                calibrationResult = extrinsicMsg + "\n\n下一步：确认三脚架稳定、每帧转 5–15°、8 帧覆盖左右/俯仰/滚转；" +
                                    "如仍有系统差，导出本包人工分析。",
                                calibrationOk = false,
                            )
                        } else {
                            val f = File(context.filesDir, com.dsh.starpos.field.ExtrinsicConfig.FILE)
                            runCatching { f.writeText(cfg.toJson()) }
                            _state.value = _state.value.copy(
                                extrinsicText = "已标定（%.2f°，%s）".format(cfg.angleDeg, cfg.source),
                            )
                            extrinsicMsg = est.message
                            _state.value = _state.value.copy(
                                calibrationResult = ("外参标定通过：%.2f°，%d 个帧对，已写入（离线计算，无需网络/GNSS）。")
                                    .format(cfg.angleDeg, est.pairsUsed),
                                calibrationOk = true,
                            )
                        }
                    } else {
                        extrinsicMsg = est?.message ?: "外参标定失败（解算异常）"
                        _state.value = _state.value.copy(
                            calibrationResult = (extrinsicMsg ?: "外参标定未通过") +
                                "\n\n下一步：确认三脚架固定、每帧停稳再拍、每帧转 5–15°、8 帧覆盖左右/俯仰/滚转、" +
                                "画面里星足够多；本次采集包已保存，可导出人工分析。",
                            calibrationOk = false,
                        )
                    }
                }
                _state.value = _state.value.copy(
                    phase = "PACKAGED",
                    message = buildString {
                        append("已打包：${zip.name}（${zip.length() / 1024} KB）")
                        append(if (truthRow != null) " · GNSS 真值 ±%.0f m".format(truthRow.accuracyM) else " · ⚠ 无实时 GNSS 真值")
                        if (!imuOk) append(" · ⚠ IMU 未启动，只能验证成像")
                        if (shakyFrames > 0) append(" · ⚠ 有 $shakyFrames 帧明显晃动（最大漂移 %.2f°），建议重拍".format(maxDriftDeg))
                        extrinsicMsg?.let {
                            append(" · ").append(it)
                            append("（离线自标定：只需星空+IMU，无需网络/GNSS 真值）")
                        }
                    },
                    bundleDir = dir,
                    bundleZip = zip,
                    imuHz = imuRec.sampleRateHz(),
                )
            } catch (t: CanceledCapture) {
                _state.value = _state.value.copy(
                    phase = "IDLE",
                    message = "已取消（已拍 ${_state.value.calibShot} 帧，未打包）",
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    phase = "ERROR",
                    error = "${t::class.java.simpleName}: ${t.message}",
                    message = "采集中断",
                )
            } finally {
                runCatching { imuRec?.stop() }
                imu = null
                capturing = false
                // 相机保持打开：采集完继续显示预览，离开页面时由 close() 关闭
                DiagLog.i("Ctl", "capture finished; rebuild preview epoch=${_state.value.previewEpoch + 1}")
                _state.value = _state.value.copy(
                    manualCalib = false, calibLock = false,
                    cameraStatus = camera?.previewStatus ?: "未打开",
                )
                // 采集后：部分机型（本机实测）仍会冻结 TextureView；最可靠的做法是
                // **彻底关掉相机并重建 TextureView**（UI 依据 previewEpoch 重建）
                starProbeJob?.cancel()
                runCatching { camera?.close() }
                camera = null
                _state.value = _state.value.copy(
                    previewBitmap = null,
                    previewMarks = emptyList(),
                    previewEpoch = _state.value.previewEpoch + 1,
                    cameraStatus = "重开预览…",
                )
            }
        }
    }

    /** 采集过程中用户取消。 */
    fun cancelCapture() {
        runCatching { imu?.stop() }
        capturing = false
        _state.value = _state.value.copy(
            phase = "IDLE", message = "已取消", error = null,
            cameraStatus = camera?.previewStatus ?: "未打开",
        )
    }

    /** 粗估亮度：返回 (平均, 99 分位)，抽样避免大图开销；空图返回 null。 */
    private fun luminanceStats(lum: FloatArray): Pair<Double, Double>? {
        if (lum.isEmpty()) return null
        val step = max(1, lum.size / 20_000)
        var sum = 0.0
        var n = 0
        val sample = ArrayList<Float>(20_000)
        var i = 0
        while (i < lum.size) {
            val v = lum[i]
            sum += v
            n++
            sample.add(v)
            i += step
        }
        if (n == 0) return null
        sample.sort()
        return (sum / n) to sample[(sample.size * 0.99).toInt().coerceIn(0, sample.size - 1)].toDouble()
    }

    /** 生成一个合成采集包（无真机时验证"导出 → 分析"链路）。 */
    fun buildSyntheticBundle(): File? {
        return runCatching {
            val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val dir = File(root, "synthetic-$stamp")
            dir.mkdirs()
            val catalog = CatalogHolder.catalog(context)
            val scene = SyntheticScene(
                width = 1080,
                height = 1440,
                fovXDeg = 30.0,
                latDeg = 31.2304,
                lonDeg = 121.4737,
                utcMillis = SyntheticSession.DEMO_UTC_MS,
                cloudCover = 0.0,
                seed = 7L,
            )
            val zip = SyntheticSession.build(dir, catalog, scene, frameCount = 2)
            _state.value = _state.value.copy(
                phase = "PACKAGED",
                message = "已生成合成采集包：${zip.name}（用于验证导出与分析链路）",
                bundleDir = dir,
                bundleZip = zip,
            )
            zip
        }.getOrElse { e ->
            _state.value = _state.value.copy(phase = "ERROR", error = "生成合成包失败：${e.message}")
            null
        }
    }

    private fun deviceInfo(): Map<String, Any?> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "android_sdk" to Build.VERSION.SDK_INT,
        "android_release" to Build.VERSION.RELEASE,
        "fingerprint" to Build.FINGERPRINT,
    )

    /** 用户点掉"标定结果"弹窗后调用。 */
    fun clearCalibrationResult() {
        _state.value = _state.value.copy(calibrationResult = null)
    }

    /** 调试用：模拟一次"外参标定未通过"结果（只走 UI 流程，不拍照）。 */
    fun debugCalibrationFailure() {
        _state.value = _state.value.copy(
            phase = "PACKAGED",
            calibrationOk = false,
            calibrationResult = "有效帧对不足（2，需 ≥4）：每帧前要转 5–15°，再停稳拍。\n\n" +
                "下一步：确认三脚架固定、每帧停稳再拍、8 帧覆盖左右/俯仰/滚转、画面里星足够多；" +
                "本次采集包已保存，可导出人工分析。",
        )
    }

    fun close() {
        runCatching { camera?.close() }
        camera = null
        runCatching { imu?.stop() }
        runCatching { gnssRecorder.stop() }
        gnssMonitor?.cancel()
        starProbeJob?.cancel()
        shakeJob?.cancel()
        aimJob?.cancel()
        runCatching { compassAim?.stop() }
        compassAim = null
        runCatching { shakeMonitor.stop() }
        scope.cancel()
    }
}


























































