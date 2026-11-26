package com.dsh.starpos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 的单帧采集/预览封装（M1 自检与 M2 现场采集共用）。
 *
 * 关键点（都是踩坑换来的）：
 * - 会话只在**预览表面可用**时建立，TextureView 晚到时靠 [attachPreview] 补建；
 * - 找星模式下预览流同时输出到小尺寸 YUV（[probeReader]），检测无需另拍静帧；
 * - 所有曝光/ISO 都**夹到设备范围**，否则 HAL 会丢弃请求，表现为"预览卡死"；
 * - 增强预览：短曝光帧 → 多帧堆栈 → 去噪/高通/asinh 拉伸 → [displaySink]。
 */
class StarCamera(private val context: Context) {

    /** 一帧原始图像（RAW16 已按 2×2 合并到 2048×1536，或 YUV8）。 */
    class CapturedFrame(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val pixels16: ShortArray?,
        val pixels8: ByteArray?,
        val source: String,
        val sensorTimestampNs: Long,
        val exposureNs: Long,
        val iso: Int,
        val focalLengthMm: Double,
        val focusDistanceDiopters: Double,
        val zoomRatio: Double,
        val oisEnabled: Boolean,
        val awbMode: Int,
        val frameDurationNs: Long,
    )

    /** 预览识别框（归一化坐标）。 */
    data class PreviewBlob(
        val nx: Float,
        val ny: Float,
        val peak: Float,
        val isDisk: Boolean = false,
        val radiusN: Float = 0f,
    )

    /** 找星预览的检测结果。 */
    data class PreviewProbe(
        val count: Int = 0,
        val blobs: List<PreviewBlob> = emptyList(),
        val disk: PreviewBlob? = null,
        val frameMean: Float = 0f,
        val captureMs: Long = 0L,
        val analyzeMs: Long = 0L,
        val error: String = "",
    )

    private val manager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var probeReader: ImageReader? = null
    private var probeWidth = 0
    private var probeHeight = 0

    @Volatile private var probeLum: FloatArray? = null
    @Volatile private var probeFrameAt = 0L
    @Volatile private var probeDims = 0
    @Volatile private var probeTargetAdded = false

    /** 月/日圆面在画面宽度上的期望半径（归一化），用于判定"这是不是天上的圆盘"。 */
    @Volatile private var expectedDiskRadiusN = 0f

    /** 增强预览的显示回调（Bitmap 已按传感器方向旋转）。 */
    @Volatile var displaySink: ((Bitmap) -> Unit)? = null

    @Volatile private var sensorOrientationDeg = 90

    /** 传感器图像需顺时针旋转多少度为正立。 */
    val sensorOrientationDegrees: Int get() = sensorOrientationDeg

    /** 显示处理线程：与相机回调解耦，避免"堆栈+拉伸"阻塞预览。 */
    private val dispExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "star-display")
    }
    @Volatile private var dispBusy = false
    /** γ 查找表（按 gammaValue 惰性重建）。 */
    private fun gammaLut(): IntArray {
        if (Math.abs(gammaLutFor - gammaValue) > 1e-6) {
            gammaLutFor = gammaValue.toDouble()
            val g = gammaValue.coerceIn(0.2f, 2.0f).toDouble()
            for (i in 0..4096) {
                gammaLut[i] = (Math.pow(i / 4096.0, g) * 255.0).toInt().coerceIn(0, 255)
            }
        }
        return gammaLut
    }

    /** σ 裁剪堆叠用的环形帧缓冲（4 帧）。 */
    private var dispFrames: Array<FloatArray?> = arrayOfNulls(4)
    private var dispCount = 0
    /** 预览 ROI 放大倍数（1=全幅，2/3/4=中心裁剪）。 */
    @Volatile var previewZoom = 1
    @Volatile private var dispFrameMs = 0L

    private var previewSurface: Surface? = null

    /** 已打开的后置相机 id。 */
    var cameraId: String = ""
        private set

    /** 指定要打开的相机 id（null = 自动选主摄）；切换后需要重建 starCamera 实例。 */
    @Volatile var requestedCameraId: String? = null

    var characteristics: CameraCharacteristics? = null
        private set

    var outputWidth = 0
        private set
    var outputHeight = 0
        private set
    var useRaw = false
        private set
    private var whiteLevel = 65535

    /** 预览状态（给 UI 显示）：未打开 / 打开中 / 预览中 / 无预览表面 / 会话失败… */
    @Volatile var previewStatus: String = "未打开"
        private set

    private var previewView: TextureView? = null
    @Volatile private var sessionCreating = false

    /** 预览模式：0=自动曝光，1=找星（长曝光+最大 ISO），2=跟随拍摄参数。 */
    @Volatile private var previewMode = 0
    @Volatile private var previewExpNs = 800_000_000L
    @Volatile private var previewIso = 3200

    /** 找星预览的预览流曝光。 */
    @Volatile var starPreviewExpNs = 600_000_000L
    /** ===== 预览调参（现场可调） ===== */
    /** 是否启用增强预览（关掉就直接看 TextureView 原始画面）。 */
    @Volatile var enhanceEnabled = true
    /** 堆栈帧数（2–6）。 */
    @Volatile var dispStackFrames = 4
    /** 局部背景模糊半径（去渐晕/光污染梯度）。 */
    @Volatile var bgBlurRadius = 28
    /** 星点增强强度。 */
    @Volatile var starBoost = 1.2f
    /** 高通（局部细节）半径：越小越突出点状星。 */
    @Volatile var starHpRadius = 4
    /** 夜间背景目标亮度。 */
    @Volatile var targetLevel = 0.42f
    /** γ 值。 */
    @Volatile var gammaValue = 0.72f
    @Volatile private var gammaLutFor = -1.0
    private var gammaLut = IntArray(4097)
    /** 月亮预览：短曝光，避免圆面饱和（实测 1 s/ISO3188 会全白）。 */
    @Volatile var moonPreviewExpNs = 2_000_000L
    @Volatile var moonPreviewIso = 800
    /** 太阳预览：实测 ND6 滤镜下 2 ms/ISO400 圆面约 60% 满阱（100 µs/ISO50 太暗）。必须装太阳滤镜。 */
    @Volatile var sunPreviewExpNs = 2_000_000L
    @Volatile var sunPreviewIso = 400
    /** 白天月亮预览：白天天空比夜间月亮场景亮 3–4 个数量级，2 ms/ISO800 会全白。目标天空亮度 0.4–0.6。 */
    @Volatile var dayMoonPreviewExpNs = 1_000_000L
    @Volatile var dayMoonPreviewIso = 200

    /** 是否被其他应用抢占过相机（onDisconnected）。 */
    @Volatile var disconnected = false
        private set
    @Volatile private var inCapture = false
    private val cameraLock = Any()
    private val captureLock = Any()

    /** "打开"必须包含会话就绪：只有 device 没有 session 时预览是黑的。 */
    val isOpen: Boolean get() = device != null && session != null

    /** 设备支持的最大 ISO（找星预览用）。 */
    val maxIso: Int
        get() = characteristics
            ?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 12800

    /** 打开相机并在 [textureView] 上启动预览（可为 null：无预览也要能采集）。幂等。 */
    @Synchronized
    fun open(textureView: TextureView?) {
        if (device != null) {
            // 相机已开：补挂预览 + 补建会话 + 重置可能卡住的"打开中…"状态
            attachPreview(textureView)
            if (session == null) runCatching { ensureSession() }
            if (session != null) {
                runCatching { applyPreviewRequest() }
                if (previewStatus == "打开中…") previewStatus = "预览中（已恢复）"
            } else {
                previewStatus = "会话未就绪（可重试）"
            }
            return
        }
        previewView = textureView
        previewStatus = "打开中…"
        val id = requestedCameraId ?: CameraProbe.pickBackCamera(manager)
            ?: throw IllegalStateException("找不到可用的后置相机")
        cameraId = id
        val ch = manager.getCameraCharacteristics(id)
        characteristics = ch
        useRaw = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        whiteLevel = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 65535

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("相机没有 stream configuration map")
        val format = if (useRaw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val sizes = map.getOutputSizes(format)
            ?: throw IllegalStateException("相机不支持格式 $format")
        // 选最大输出（保留全部像素；分析端按内参缩放）
        val size = sizes.maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
        outputWidth = size.width
        outputHeight = size.height

        runCatching {
            val physW = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            if (physW != null && focal != null && physW > 0.1f) {
                // 视半径约 0.0046 rad（月 0.259°、日 0.266°），按画面宽度归一化
                expectedDiskRadiusN = 0.0046f * focal / physW
                sensorOrientationDeg = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            }
        }
        thread = HandlerThread("star-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(outputWidth, outputHeight, format, 3)

        runCatching {
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            if (yuvSizes != null && yuvSizes.isNotEmpty()) {
                // 选最接近 1024×768 的尺寸：检测够用，传输量只有全分辨率的 1/16
                val pick = yuvSizes.minByOrNull {
                    abs(it.width - 1024) + abs(it.height - 768)
                }!!
                probeWidth = pick.width
                probeHeight = pick.height
                probeReader = ImageReader.newInstance(probeWidth, probeHeight, ImageFormat.YUV_420_888, 3)
                probeReader?.setOnImageAvailableListener({ r ->
                    runCatching {
                        val img = r.acquireNextImage() ?: return@runCatching
                        try {
                            val w = img.width
                            val h = img.height
                            val plane = img.planes[0]
                            val buf = plane.buffer
                            val rowStride = plane.rowStride
                            val lum = FloatArray(w * h)
                            val row = ByteArray(rowStride)
                            for (y in 0 until h) {
                                buf.position(y * rowStride)
                                val n = min(rowStride, buf.remaining())
                                if (n <= 0) break
                                buf.get(row, 0, n)
                                val base = y * w
                                for (x in 0 until w) lum[base + x] = (row[x].toInt() and 0xFF) / 255f
                            }
                            probeLum = lum
                            probeDims = (w shl 16) or h
                            probeFrameAt = System.currentTimeMillis()
                            if (!dispBusy) {
                                dispBusy = true
                                dispExec.execute {
                                    try {
                                        accumulateAndEmit(lum, w, h)
                                    } catch (ignore: Throwable) {
                                    } finally {
                                        dispBusy = false
                                    }
                                }
                            }
                        } finally {
                            img.close()
                        }
                    }
                }, handler)
            }
        }

        val latch = CountDownLatch(1)
        var openError: Throwable? = null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                disconnected = false
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
        DiagLog.w("Cam", "onDisconnected")
                camera.close()
                device = null
                session = null
                disconnected = true
                previewStatus = "相机被其他应用占用（返回后自动重连）"
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, e: Int) {
                openError = IllegalStateException("打开相机失败：error=$e")
                camera.close()
                device = null
                latch.countDown()
            }
        }, handler)
        if (!latch.await(5, TimeUnit.SECONDS)) {
            previewStatus = "打开相机超时"
            throw IllegalStateException("打开相机超时")
        }
        openError?.let {
            previewStatus = "打开相机失败"
            throw it
        }
        ensureSession()
        if (previewStatus == "打开中…") {
            previewStatus = if (session != null) "预览中（已就绪）" else "会话未就绪（可重试）"
        }
    }

    /** 预览表面晚到时补建会话。 */
    fun attachPreview(textureView: TextureView?) {
        previewView = textureView
        if (inCapture) return   // 采集进行中不重建会话
        if (device == null || textureView == null || !textureView.isAvailable) return
        if (previewSurface == null) {
            runCatching { session?.close() }
            session = null
            ensureSession()
        } else if (session != null) {
            applyPreviewRequest()
        }
    }

    fun setPreviewMode(mode: Int) {
        DiagLog.i("Cam", "setPreviewMode $mode (prev=$previewMode)")
        previewMode = mode
        applyPreviewRequest()
    }

    fun setPreviewParams(exposureNs: Long, iso: Int) {
        previewExpNs = exposureNs
        previewIso = iso
        if (previewMode == 2) applyPreviewRequest()
    }

    /** 预览请求：所有曝光/ISO 都夹到设备范围（超范围会让 HAL 丢弃请求→画面卡死）。 */
    private fun applyPreviewRequest() {
        val dev = device ?: return
        val s = session ?: return
        val preview = previewSurface ?: return
        runCatching {
            DiagLog.i("Cam", "applyPreview mode=$previewMode exp=${if (previewMode == 1) starPreviewExpNs else if (previewMode == 3) moonPreviewExpNs else if (previewMode == 4) sunPreviewExpNs else if (previewMode == 5) dayMoonPreviewExpNs else -1} probeTarget=${probeReader != null}")
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(preview)
            probeTargetAdded = (previewMode == 1 || previewMode == 3 || previewMode == 4 || previewMode == 5) && probeReader != null
            if (probeTargetAdded) b.addTarget(probeReader!!.surface)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val isoLo = isoRange?.lower ?: 50
            val isoHi = isoRange?.upper ?: 12800
            when (previewMode) {
                1 -> {
                    val sens = max(isoHi, previewIso).coerceIn(isoLo, isoHi)
                    val exp = starPreviewExpNs.coerceAtLeast(1_000_000L)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    previewStatus = "找星预览（%.0f ms / ISO %d）".format(exp / 1e6, sens)
                }
                4 -> {
                    val sens = sunPreviewIso.coerceIn(isoLo, isoHi)
                    val exp = sunPreviewExpNs.coerceIn(34_700L, 8_000_000L)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    previewStatus = if (exp >= 1_000_000L)
                        "太阳预览（%.1f ms / ISO %d）· 必须装太阳滤镜".format(exp / 1e6, sens)
                    else
                        "太阳预览（%.0f µs / ISO %d）· 必须装太阳滤镜".format(exp / 1000.0, sens)
                }
                5 -> {
                    // 白天月亮：短曝光避免天空饱和（1 ms / ISO200，目标天空亮度 0.4–0.6）
                    val sens = dayMoonPreviewIso.coerceIn(isoLo, isoHi)
                    val exp = dayMoonPreviewExpNs.coerceIn(34_700L, 8_000_000L)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    previewStatus = "白天月亮预览（%.2f ms / ISO %d）· 目标天空亮度 0.4–0.6".format(exp / 1e6, sens)
                }
                3 -> {
                    val sens = moonPreviewIso.coerceIn(isoLo, isoHi)
                    val exp = moonPreviewExpNs.coerceAtLeast(100_000L)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    previewStatus = "月亮预览（%.1f ms / ISO %d）".format(exp / 1e6, sens)
                }
                2 -> {
                    val sens = previewIso.coerceIn(isoLo, isoHi)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, previewExpNs)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(previewExpNs, 33_333_333L))
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    previewStatus = "预览中（跟随拍摄：%.0f ms / ISO %d）".format(previewExpNs / 1e6, sens)
                }
                else -> {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    previewStatus = "预览中（自动曝光）"
                }
            }
            s.setRepeatingRequest(b.build(), null, handler)
        }.onFailure { previewStatus = "预览请求失败：${it.message}" }
    }

    // ===================== 增强预览 =====================

    /** 可分离盒式模糊（局部均值），用于星点高通增强。 */
    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum * norm
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)]
                sum -= src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum * norm
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                sum -= tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    /**
     * σ 裁剪堆叠（去掉最亮/最暗各一帧）+ **局部背景归一化**（同时压掉渐晕/光污染梯度）
     * + 星点高通增强 + γ 查找表。全部在线程池里跑，不阻塞相机回调。
     */
    private fun accumulateAndEmit(lum: FloatArray, w: Int, h: Int) {
        val sink = displaySink ?: return
        if (!enhanceEnabled) return   // 关闭增强：让下层 TextureView 显示原始画面
        // 月亮/太阳模式用短曝光：增强管线会把噪点放大成"雪花"，直接让 TextureView 显示原始画面
        if (previewMode == 3 || previewMode == 4 || previewMode == 5) return
        val now = System.currentTimeMillis()
        val wantFrames = dispStackFrames.coerceIn(2, 6)
        if (dispFrames.size != wantFrames) {
            dispFrames = arrayOfNulls(wantFrames)
            dispCount = 0
        }
        if (now - dispFrameMs > 1200) {
            dispFrames = arrayOfNulls(wantFrames)
            dispCount = 0
        }
        if (dispCount >= dispFrames.size) dispCount = 0
        var slot = dispFrames[dispCount]
        if (slot == null || slot.size != lum.size) {
            slot = FloatArray(lum.size)
            dispFrames[dispCount] = slot
        }
        System.arraycopy(lum, 0, slot, 0, lum.size)
        dispCount = (dispCount + 1).coerceAtMost(dispFrames.size)
        val used = dispCount
        val frames = dispFrames
        val n0 = lum.size
        val mean = FloatArray(n0)
        for (i in 0 until n0) {
            var s = 0f
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (k in 0 until used) {
                val v = frames[k]!![i]
                s += v
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            mean[i] = if (used >= 3) (s - mn - mx) / (used - 2) else s / used
        }
        dispFrameMs = now
        // ROI 放大
        val z = previewZoom.coerceIn(1, 4)
        var ww = w
        var hh = h
        var data = mean
        if (z > 1) {
            ww = w / z
            hh = h / z
            val ox = (w - ww) / 2
            val oy = (h - hh) / 2
            data = FloatArray(ww * hh)
            for (y in 0 until hh) System.arraycopy(mean, (y + oy) * w + ox, data, y * ww, ww)
        }
        val n = data.size
        // 背景：大半径盒式模糊（含渐晕与光污染梯度），归一化后统一亮度
        val bg = boxBlur(data, ww, hh, bgBlurRadius.coerceIn(4, 96))
        val step = 17
        var k = 0
        val samples = FloatArray(n / step + 2)
        var i = 0
        while (i < n) {
            samples[k++] = data[i]
            i += step
        }
        java.util.Arrays.sort(samples, 0, k)
        val p50 = samples[(k * 0.5).toInt().coerceIn(0, k - 1)]
        val px = IntArray(n)
        if (p50 > 0.15f) {
            // 亮场景直出（不做背景归一化，避免把均匀墙面画成灰阶噪声）
            for (idx in 0 until n) {
                val g = (data[idx].coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
                px[idx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        } else {
            // 夜空：ratio = 原始/局部背景 → 背景恒为 1，渐晕被除掉；星点 ratio>1
            val smallBlur = boxBlur(data, ww, hh, starHpRadius.coerceIn(1, 16))
            val lut = gammaLut()
            for (idx in 0 until n) {
                val b = bg[idx].coerceAtLeast(1e-5f)
                val ratio = data[idx] / b
                // 高通：局部细结构（星点）再强化一点
                val hp = (data[idx] - smallBlur[idx]) / b
                var v = targetLevel + (ratio - 1f) * 0.55f + hp * starBoost
                v = v.coerceIn(0f, 1f)
                val li = (v * 4096f).toInt().coerceIn(0, 4096)
                val g = lut[li]
                px[idx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        var bmp = Bitmap.createBitmap(px, ww, hh, Bitmap.Config.ARGB_8888)
        if (sensorOrientationDeg != 0) {
            val m = Matrix().apply { postRotate(sensorOrientationDeg.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, ww, hh, m, true)
        }
        sink(bmp)
    }

    // ===================== 找星探测 =====================

    /** 丢掉旧的探测帧状态（切换预览模式/采集前后调用，避免"帧过期"）。 */
    fun resetProbe() {
        DiagLog.i("Cam", "resetProbe")
        probeLum = null
        probeFrameAt = 0L
        probeDims = 0
        dispFrames = arrayOfNulls(4)
        dispCount = 0
    }

    /** 预览探测：返回亮星位置（归一化）与可能的月/日圆面，供预览上画框。 */
    fun probeStars(exposureNs: Long, iso: Int): PreviewProbe {
        if (inCapture) return PreviewProbe()
        val lum = probeLum
            ?: return PreviewProbe(error = "预览流尚未出帧（探测目标=${if (probeTargetAdded) "已加" else "未加"}）")
        if (probeFrameAt == 0L) {
            runCatching { applyPreviewRequest() }
            return PreviewProbe(error = "预览流尚未出帧（已重装请求）")
        }
        val age = System.currentTimeMillis() - probeFrameAt
        if (age > 1500) {
            resetProbe()
            runCatching { applyPreviewRequest() }
            return PreviewProbe(error = "预览帧过期 $age ms（已重装请求）")
        }
        val w = probeDims ushr 16
        val h = probeDims and 0xFFFF
        if (w <= 0 || h <= 0 || lum.size < w * h) return PreviewProbe(error = "探测帧尺寸异常")
        val t0 = System.currentTimeMillis()
        // 2×2 合并到一半分辨率：检测耗时降 ~4 倍，不影响增强预览（预览用全量帧）
        val w2 = w / 2
        val h2 = h / 2
        val small = FloatArray(w2 * h2)
        for (y in 0 until h2) {
            val r0 = y * 2 * w
            val r1 = r0 + w
            for (x in 0 until w2) {
                val i0 = r0 + x * 2
                small[y * w2 + x] =
                    (lum[i0] + lum[i0 + 1] + lum[r1 + x * 2] + lum[r1 + x * 2 + 1]) / 4f
            }
        }
        val probe = analyzeProbe(small, w2, h2)
        return probe.copy(captureMs = age, analyzeMs = System.currentTimeMillis() - t0)
    }

    private var probeTick = 0

    private fun analyzeProbe(lum: FloatArray, w: Int, h: Int): PreviewProbe {
        val det = FrameAnalyzer.analyze(lum, w, h, thresholdSigma = 6.0, maxComponents = 4000)
        var sum = 0.0
        for (v in lum) sum += v
        val mean = (sum / lum.size).toFloat()
        val blobs = det.detections
            .filter { it.aspect < 2.5 && it.area >= 2 && it.area < 401 && !it.saturated }
            .sortedByDescending { it.peak }
            .take(6)
            .map { PreviewBlob((it.x / w).toFloat(), (it.y / h).toFloat(), it.peak.toFloat()) }
        var diskMark: PreviewBlob? = null
        probeTick++
        // 圆面拟合较贵：月亮模式每次都做，找星模式隔次做
        var disk: Moon.DiskFit? = null
        if (previewMode == 4) {
            // 太阳：日面更小（1024 宽预览约 3–4 px），用很小的面积/半径下限
            disk = Moon.fitDisk(lum, w, h, minAreaPx = 16, minRadiusPx = 1.5)
        } else if (previewMode == 3 || previewMode == 5 || probeTick % 2 == 0) {
            disk = Moon.fitDisk(lum, w, h)
        }
        if (disk == null && (previewMode == 3 || previewMode == 5)) {
            // 月亮模式：月面小（内参下约 3–15 px），用放宽的面积/半径下限再拟合一次
            disk = Moon.fitDisk(lum, w, h, minAreaPx = 24, minRadiusPx = 1.8)
        }
        if (disk != null && disk.radiusPx > 1.5) {
            val rN = disk.radiusPx / w
            val ratio = if (expectedDiskRadiusN > 0f) rN / expectedDiskRadiusN else -1.0
            val sizeOk = if (previewMode == 4) {
                disk.radiusPx in 1.5..40.0
            } else if (previewMode == 5) {
                disk.radiusPx in 1.5..60.0
            } else if (expectedDiskRadiusN > 0f) {
                ratio in 0.25..4.0 && disk.radiusPx >= 1.8
            } else {
                disk.radiusPx > min(w, h) * 0.06
            }
            val qualityOk = if (previewMode == 4) {
                disk.edgeScore > 0.02 && disk.litFraction > 0.5
            } else if (previewMode == 5) {
                disk.edgeScore > 0.015 && disk.litFraction > 0.2
            } else {
                disk.edgeScore > 0.02 && disk.inlierFrac > 0.6 && disk.litFraction > 0.25
            }
            if (sizeOk && qualityOk) {
                diskMark = PreviewBlob(
                    (disk.cx / w).toFloat(), (disk.cy / h).toFloat(),
                    1f, true, rN.toFloat(),
                )
            }
        }
        return PreviewProbe(
            count = det.detections.size,
            blobs = blobs,
            disk = diskMark,
            frameMean = mean,
        )
    }

    // ===================== 会话 =====================

    @Synchronized
    private fun ensureSession() {
        val dev = device ?: throw IllegalStateException("相机未打开")
        if (session != null || sessionCreating) return
        sessionCreating = true
        try {
            val tv = previewView
            if (tv != null && tv.isAvailable && previewSurface == null) {
                previewSurface = Surface(tv.surfaceTexture)
            }
            tryCreateSession(dev, previewSurface, listOfNotNull(reader!!.surface, probeReader?.surface))
            if (session == null && useRaw) {
                // 部分机型不支持 RAW + 预览同时配置：退回 YUV 再试一次
                previewStatus = "RAW 会话失败，退回 YUV"
                runCatching { reader?.close() }
                val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val yuvSize = map?.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                if (yuvSize != null) {
                    outputWidth = yuvSize.width
                    outputHeight = yuvSize.height
                }
                reader = ImageReader.newInstance(outputWidth, outputHeight, ImageFormat.YUV_420_888, 3)
                useRaw = false
                tryCreateSession(dev, previewSurface, listOfNotNull(reader!!.surface, probeReader?.surface))
            }
            if (session == null) {
                previewStatus = "会话创建失败"
                throw IllegalStateException("创建预览会话失败")
            }
        } finally {
            sessionCreating = false
        }
    }

    private fun tryCreateSession(dev: CameraDevice, preview: Surface?, extraSurfaces: List<Surface>) {
        val surfaces = ArrayList<Surface>(3)
        if (preview != null) surfaces.add(preview)
        surfaces.addAll(extraSurfaces)
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        dev.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                if (preview != null) {
                    applyPreviewRequest()
                } else {
                    previewStatus = "无预览表面"
                }
                latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                err = IllegalStateException("创建会话失败")
                latch.countDown()
            }
        }, handler)
        if (!latch.await(8, TimeUnit.SECONDS)) {
            previewStatus = "创建会话超时"
        } else if (err != null) {
            previewStatus = "会话配置失败"
        }
    }

    // ===================== 采集 =====================

    /** 拍一帧（RAW16 或 YUV，按设备能力）。 */
    fun capture(
        exposureNs: Long,
        iso: Int,
        timeoutMs: Long = 20_000L,
        auto: Boolean = false,
    ): CapturedFrame = synchronized(captureLock) {
        DiagLog.i("Cam", "capture start exp=${exposureNs}ns iso=$iso auto=$auto")
        inCapture = true
        try {
            // 清掉管道里的旧帧，避免拿到上一张
            while (true) {
                val img = runCatching { reader?.acquireNextImage() }.getOrNull() ?: break
                img.close()
            }
            captureInternal(exposureNs, iso, timeoutMs, auto).also {
                DiagLog.i("Cam", "capture ok exp=${it.exposureNs}ns iso=${it.iso}")
            }
        } finally {
            inCapture = false
            // 采集后恢复预览重复请求（否则部分机型预览会停在静帧请求/旧帧上）
            resetProbe()
            runCatching { applyPreviewRequest() }
        }
    }

    private fun captureInternal(
        exposureNs: Long,
        iso: Int,
        timeoutMs: Long,
        auto: Boolean,
    ): CapturedFrame {
        val dev = device ?: throw IllegalStateException("相机未打开")
        if (session == null) ensureSession()
        val s = session ?: throw IllegalStateException("会话未就绪")
        val ch = characteristics ?: throw IllegalStateException("缺少相机能力")
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = if (expRange != null) {
            exposureNs.coerceIn(expRange.lower, expRange.upper)
        } else {
            exposureNs.coerceAtLeast(1_000L)
        }
        val sens = if (isoRange != null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso

        val imageLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val imageBox = arrayOfNulls<Image>(1)
        val resultBox = arrayOfNulls<TotalCaptureResult>(1)
        val reader0 = reader ?: throw IllegalStateException("缺少 ImageReader")
        reader0.setOnImageAvailableListener({ r ->
            runCatching {
                val img = r.acquireNextImage()
                if (img != null) {
                    imageBox[0] = img
                    imageLatch.countDown()
                }
            }
        }, handler)

        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(reader0.surface)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (auto) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, sens)
            val maxFrameNs = ch.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: exp
            b.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                max(exp, 33_333_333L).coerceAtMost(max(maxFrameNs, exp)),
            )
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            b.set(CaptureRequest.SCALER_CROP_REGION, it)
        }
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                r: TotalCaptureResult,
            ) {
                resultBox[0] = r
                resultLatch.countDown()
            }
        }

        // 提交失败（会话已死）→ 重建会话后重试一次
        var submitError: Throwable? = null
        runCatching { s.capture(b.build(), callback, handler) }
            .onFailure { submitError = it }
        if (submitError != null) {
            synchronized(cameraLock) {
                runCatching { session?.close() }
                session = null
                ensureSession()
            }
            val s2 = session
                ?: throw IllegalStateException("相机会话已被关闭且重建失败：${submitError!!.message}")
            s2.capture(b.build(), callback, handler)
        }

        if (!imageLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("等待图像超时")
        }
        resultLatch.await(2, TimeUnit.SECONDS)
        val img = imageBox[0] ?: throw IllegalStateException("没有取到图像")
        try {
            val w = img.width
            val h = img.height
            val isRaw = img.format == ImageFormat.RAW_SENSOR
            val pixels16 = if (isRaw) rawToLuminance16(img) else null
            val pixels8 = if (isRaw) null else yuvYPlane(img)
            val outW = if (isRaw) w / 2 else w
            val outH = if (isRaw) h / 2 else h
            val meta = resultBox[0]
            val ts = meta?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val metaExp = meta?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exp
            val metaIso = meta?.get(CaptureResult.SENSOR_SENSITIVITY) ?: sens
            val focal = meta?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: 0f
            val focus = meta?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val ois = (meta?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ?: 0) != 0
            val awb = meta?.get(CaptureResult.CONTROL_AWB_MODE) ?: -1
            val frameDuration = meta?.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
            return CapturedFrame(
                width = outW,
                height = outH,
                bitDepth = if (isRaw) 16 else 8,
                pixels16 = pixels16,
                pixels8 = pixels8,
                source = if (isRaw) "raw16" else "yuv8",
                sensorTimestampNs = ts,
                exposureNs = metaExp,
                iso = metaIso,
                focalLengthMm = focal.toDouble(),
                focusDistanceDiopters = focus.toDouble(),
                zoomRatio = 1.0,
                oisEnabled = ois,
                awbMode = awb,
                frameDurationNs = frameDuration,
            )
        } finally {
            runCatching { img.close() }
        }
    }

    /** RAW16 → 2×2 合并的 16 位亮度（2048×1536），按白电平归一化。 */
    private fun rawToLuminance16(image: Image): ShortArray {
        val plane = image.planes[0]
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val outW = w / 2
        val outH = h / 2
        val out = ShortArray(outW * outH)
        val shift = (65535.0 / whiteLevel.coerceAtLeast(1)).coerceAtMost(64.0)
        val row = ShortArray(w)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            val shortBuf = buf.asShortBuffer()
            val n = min(w, shortBuf.remaining())
            for (x in 0 until n) row[x] = shortBuf.get(x)
            if (y % 2 == 1) {
                val oy = y / 2
                for (x2 in 0 until outW) {
                    val sum = (row[2 * x2].toInt() and 0xFFFF) + (row[2 * x2 + 1].toInt() and 0xFFFF)
                    val v = (sum * 0.5 * shift).toInt().coerceIn(0, 65535)
                    out[oy * outW + x2] = v.toShort()
                }
            }
        }
        return out
    }

    /** YUV_420_888 的 Y 平面。 */
    private fun yuvYPlane(image: Image): ByteArray {
        val plane = image.planes[0]
        val buf: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h)
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            val n = min(rowStride, buf.remaining())
            buf.get(row, 0, n)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, out, y * w, min(w, n))
            } else {
                for (x in 0 until w) {
                    val idx = x * pixelStride
                    out[y * w + x] = if (idx < n) row[idx] else 0
                }
            }
        }
        return out
    }

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        reader = null
        previewSurface = null
        previewView = null
        previewStatus = "已关闭"
    }
}
