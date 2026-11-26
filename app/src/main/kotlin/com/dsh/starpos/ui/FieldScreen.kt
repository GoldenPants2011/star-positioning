package com.dsh.starpos.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.os.Build
import android.net.Uri
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.ui.text.font.FontFamily
import com.dsh.starpos.BuildConfig
import java.io.File
import com.dsh.starpos.android.AndroidTimeSource
import com.dsh.starpos.android.DiagLog
import com.dsh.starpos.android.FieldSessionController
import com.dsh.starpos.android.SharedPreferencesStore
import com.dsh.starpos.core.ClockMonitor
import com.dsh.starpos.core.ClockStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dsh.starpos.android.ImuBiasCalibrator
import com.dsh.starpos.android.AccelBiasStore
import com.dsh.starpos.field.AccelBiasCalibration

// 语义色跟随全 App 主题/色觉辅助（getter 每次读取当前调色板）
private val FieldAccent: Color get() = AppAccent
private val FieldWarn: Color get() = AppWarn
private val FieldOk: Color get() = AppOk
private val FieldBad: Color get() = AppBad
private val FieldCardBg: Color get() = AppCard

/**
 * 现场采集页：预检 → 手动曝光采集 → 即时质量反馈 → 打包 zip → 导出。
 * 导出的 zip 就是"异步调试"的输入物；分析侧用 FieldInboxReportTest / FieldAnalyzer。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldScreen(onOpenCleanup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val controller = remember { FieldSessionController(context) }
    val clockMonitor = remember { ClockMonitor(AndroidTimeSource(context), SharedPreferencesStore(context)) }
    val state by controller.state.collectAsState()
    var clock by remember { mutableStateOf<ClockStatus?>(null) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var frames by remember { mutableStateOf(3) }
    var exposureNs by remember { mutableStateOf(8_000_000_000L) }
    var iso by remember { mutableStateOf(800) }
    val prefs = remember { context.getSharedPreferences("field_ui", Context.MODE_PRIVATE) }
    var permsGranted by remember { mutableStateOf(fieldPermissionsGranted(context)) }
    var pendingStart by remember { mutableStateOf(false) }
    val previewTicks = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var showTuning by remember { mutableStateOf(false) }
    var showSunConfirm by remember { mutableStateOf(false) }
    var showCaptureConfirm by remember { mutableStateOf(false) }
    var showDiagLog by remember { mutableStateOf(false) }
    var hdrEnabled by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<String?>(null) }
    val presets = remember { ExposurePresets.load(context) }
    val appPrefs = remember { context.getSharedPreferences("starpos", Context.MODE_PRIVATE) }
    val currentCam = Regex("camera=([0-9])").find(state.cameraLine)?.groupValues?.getOrNull(1)
        ?: (controller.cameraId ?: "0")
    val defaultCam = appPrefs.getString("default_camera", "0") ?: "0"
    val bortle = appPrefs.getInt("bortle", 5)
    var pendingSunCamera by remember { mutableStateOf<String?>(null) }
    var showLowSpace by remember { mutableStateOf(false) }
    var showCameraInv by remember { mutableStateOf(false) }
    var mirrorAssist by remember { mutableStateOf(false) }
    var mirrorHeight by remember { mutableStateOf(0.4f) }
    // ---- IMU 零偏标定（六位置法）----
    var showBiasCal by remember { mutableStateOf(false) }
    var biasStep by remember { mutableStateOf(0) }
    var biasRunning by remember { mutableStateOf(false) }
    var biasSeconds by remember { mutableStateOf(15) }
    var biasPhase by remember { mutableStateOf("place") }
    var biasMsg by remember { mutableStateOf("") }
    var biasResult by remember { mutableStateOf<AccelBiasCalibration.Result?>(null) }
    val biasMeans = remember { mutableStateListOf<DoubleArray>() }
    val biasCalibrator = remember { ImuBiasCalibrator(context) }
    val biasScope = rememberCoroutineScope()
    val biasPoses = listOf(
        "屏幕朝上（平放桌面，别碰）",
        "屏幕朝下（屏幕贴桌面）",
        "左侧边朝下（横着立起来）",
        "右侧边朝下",
        "顶边朝下（竖着立起来）",
        "底边朝下",
    )

    // 设备上限已知时，把 ISO 状态夹回可用范围（避免 12800 这类非法值残留在状态里）
    androidx.compose.runtime.LaunchedEffect(state.maxIso) {
        if (state.maxIso > 0 && iso > state.maxIso) iso = state.maxIso
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permsGranted = fieldPermissionsGranted(context)
                // 其他应用（原生相机）可能抢过相机：回来时重连/重装预览
                controller.onScreenResumed(textureView)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        controller.refreshPreflight(clockMonitor.status())
        clock = clockMonitor.status()
        onDispose {
            controller.close()
            (context as? android.app.Activity)?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    // 进入页面就开预览：之前只有点"开始采集"才开相机，且会话只建一次，界面里看不到画面
    LaunchedEffect(textureView, permsGranted) {
        val tv = textureView ?: return@LaunchedEffect
        if (permsGranted) controller.openPreview(tv)
    }

    // 采集期间保持屏幕常亮：锁屏会让定位更新与相机预览被限流
    LaunchedEffect(state.phase) {
        val busy = state.phase == "OPENING" || state.phase == "WAITING_GNSS" || state.phase == "CAPTURING"
        val window = (context as? android.app.Activity)?.window
        if (busy) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        val zip = state.bundleZip
        if (uri != null && zip != null) {
            val ok = copyTo(context, zip, uri)
            Toast.makeText(context, if (ok) "已导出到所选位置" else "导出失败", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val camOk = result[Manifest.permission.CAMERA] == true
        val locOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permsGranted = camOk && locOk
        if (!locOk) {
            Toast.makeText(context, "定位权限未授予：仍可采集，但包里不会有 GNSS 真值", Toast.LENGTH_LONG).show()
        }
        clock = clockMonitor.status()
        controller.refreshPreflight(clock)
        if (pendingStart) {
            pendingStart = false
            if (camOk) {
                controller.startCapture(
                    textureView,
                    FieldSessionController.Spec(
                        frames,
                        // 采集前把参数夹到设备范围，避免 UI 残留非法值（如 ISO 12800）
                        exposureNs = (if (state.maxExposureMs > 0)
                            exposureNs.coerceAtMost((state.maxExposureMs * 1_000_000.0).toLong()) else exposureNs)
                            // 太阳模式：曝光硬限制 1 ms（安全上限）
                            .let { if (state.previewMode == 4) it.coerceAtMost(8_000_000L) else it },
                        iso = if (state.previewMode == 4)
                            iso.coerceAtMost(200)
                        else if (state.maxIso > 0) iso.coerceAtMost(state.maxIso) else iso,
                        note = if (state.previewMode == 4) (state.note + " SUN(已装滤镜)").trim() else state.note,
                        hdrLongNs = if (hdrEnabled && state.previewMode != 4 && state.previewMode != 5) 1_000_000_000L else 0L,
                        hdrLongIso = if (hdrEnabled && state.previewMode != 4 && state.previewMode != 5) 3200 else 0,
                    ),
                    clock,
                )
            } else {
                Toast.makeText(context, "没有相机权限，无法采集", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 分享诊断日志文件（FileProvider）
    val shareDiagLog: () -> Unit = {
        val f = DiagLog.file()
        if (f == null || !f.exists()) {
            Toast.makeText(context, "日志文件不存在", Toast.LENGTH_SHORT).show()
        } else {
            runCatching {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "星光定位诊断日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(i, "分享诊断日志"))
            }.onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // 采集确认后的实际启动逻辑（曝光/ISO 夹到设备范围；太阳模式硬限制 1 ms）
    val startCaptureNow: () -> Unit = {
        clock = clockMonitor.status()
        controller.refreshPreflight(clock)
        if (permsGranted) {
            val sunMode = state.previewMode == 4
            val expNs = (if (state.maxExposureMs > 0)
                exposureNs.coerceAtMost((state.maxExposureMs * 1_000_000.0).toLong()) else exposureNs)
                .let { if (sunMode) it.coerceAtMost(1_000_000L) else it }
            val isoUse = if (sunMode) iso.coerceAtMost(200)
            else if (state.maxIso > 0) iso.coerceAtMost(state.maxIso) else iso
            controller.startCapture(
                textureView,
                FieldSessionController.Spec(
                    frames, expNs, isoUse,
                    if (sunMode) (state.note + " SUN(已装滤镜)").trim() else state.note,
                    hdrLongNs = if (hdrEnabled && !sunMode) 1_000_000_000L else 0L,
                    hdrLongIso = if (hdrEnabled && !sunMode) 3200 else 0,
                    mirrorEnabled = mirrorAssist,
                    mirrorHeightM = mirrorHeight.toDouble(),
                ),
                clock,
            )
        } else {
            pendingStart = true
            permissionLauncher.launch(PERMISSIONS)
        }
    }

    // 首次进入现场采集页就自动申请权限（不再等用户点"开始采集"才弹窗）
    LaunchedEffect(Unit) {
        val defCam = appPrefs.getString("default_camera", "0") ?: "0"
        if (defCam != "0") controller.setCameraId(defCam)
        if (Storage.stats(context).freeBytes < Storage.DANGER_FREE_BYTES) showLowSpace = true
        if (!permsGranted) {
            val asked = prefs.getBoolean("perm_asked", false)
            if (!asked) {
                prefs.edit().putBoolean("perm_asked", true).apply()
                permissionLauncher.launch(PERMISSIONS)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("现场采集（M2 异步调试）", fontSize = 20.sp, color = Color.White)
        Text(
            "采完导出 zip，带回后放到 star-positioning/field-inbox/ 即可离线分析。",
            fontSize = 12.sp, color = Color(0xFF8A93A6), modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        // ===== 顶部标签（点开为底部弹出面板）=====
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "预检" to (state.preflightDone && state.cameraLine.isNotBlank()),
                "GNSS 真值" to state.gnssReady,
                "标定" to (state.extrinsicText.startsWith("已载入") && AccelBiasStore.load(context) != null),
            ).forEach { (label, ok) ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(FieldCardBg)
                        .clickable { openSheet = label }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (ok) FieldOk else FieldWarn),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = Color(0xFFBFD0E8), fontSize = 12.sp)
                    Text(" ▾", color = Color(0xFF8A93A6), fontSize = 11.sp)
                }
            }
        }

        if (state.manualCalib) {
            val isIntr = state.calibTotal == 12
            Card(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2310)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        (if (isIntr) "内参标定（手动 12 帧）" else "外参标定（手动 8 帧）") +
                            " · 第 ${state.calibShot}/${state.calibTotal} 帧",
                        color = FieldWarn, fontSize = 13.sp,
                    )
                    Text(
                        if (isIntr) {
                            "把点阵板放在 0.5~1.5 m 处；每帧换一个角度/位置，画面里点阵要占 1/3 以上。拍完 12 帧自动打包。"
                        } else {
                            "三脚架固定：每帧换角度（左右转 3、俯仰 3、绕光轴滚转 2），停稳再按「拍一帧」。拍完 8 帧自动打包。"
                        },
                        color = Color(0xFFCFC6B0), fontSize = 11.sp,
                    )
                    if (state.message.isNotBlank()) {
                        Text(state.message, color = Color(0xFFBFD0E8), fontSize = 11.sp)
                    }
                    state.error?.let { Text("错误：$it", color = FieldBad, fontSize = 11.sp) }
                    Row {
                        Button(
                            enabled = !state.packing,
                            onClick = { controller.triggerCalibrationFrame() },
                            colors = ButtonDefaults.buttonColors(containerColor = FieldAccent, contentColor = Color(0xFF0A0E17)),
                        ) {
                            Text(if (state.packing) "正在打包…" else "拍一帧", fontSize = 13.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { controller.cancelCapture() },
                            colors = ButtonDefaults.buttonColors(containerColor = FieldCardBg, contentColor = FieldWarn),
                        ) { Text("取消标定") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }


        if (!permsGranted) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2310))) {
                Column(Modifier.padding(14.dp)) {
                    Text("需要相机与定位权限", color = FieldWarn, fontSize = 12.sp)
                    Text(
                        "相机：拍摄星图；定位：记录 GNSS 真值（不授权也能采集，但包里没有真值、无法验证精度）。",
                        color = Color(0xFFCFC6B0), fontSize = 11.sp,
                    )
                    Row {
                        TextButton(onClick = { permissionLauncher.launch(PERMISSIONS) }) {
                            Text("重新申请", color = FieldAccent, fontSize = 12.sp)
                        }
                        TextButton(onClick = { openAppSettings(context) }) {
                            Text("去系统设置", color = FieldAccent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))


        // 对准辅助：电子罗盘朝向/俯仰 + 多天体候选（省得肉眼找行星）
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FieldCardBg)) {
            Column(Modifier.padding(14.dp)) {
                Text("对准辅助（磁罗盘 · 粗对准）", color = Color(0xFF8A93A6), fontSize = 12.sp)
                val hdg = state.headingDeg
                val hdgText = if (hdg.isNaN()) "方位 —"
                    else "方位 %.0f°（真方位≈%.0f°）".format(hdg, (hdg - 5.5 + 360.0) % 360.0)
                val pitch = state.cameraPitchDeg
                val pitchText = if (pitch.isNaN()) "俯仰 —" else "俯仰 %+.0f°".format(pitch)
                val acc = when (state.compassAccuracy) {
                    3 -> "高"; 2 -> "中"; 1 -> "低"; else -> "未标定"
                }
                Text("$hdgText · $pitchText · 罗盘精度：$acc", color = Color(0xFFBFD0E8), fontSize = 12.sp)
                if (state.aimPairs.isNotEmpty()) {
                    state.aimPairs.forEach { p -> Text("★ $p", color = FieldOk, fontSize = 12.sp) }
                }
                state.aimBodies.forEach { b -> Text("· $b", color = Color(0xFF9AA5B8), fontSize = 11.sp) }
                run {
                    val pitchDeg = state.cameraPitchDeg
                    val intr = state.intrinsics
                    val res = Regex("active=([0-9]+)[^0-9]+([0-9]+)").find(state.cameraLine)
                    val w = res?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    val h = res?.groupValues?.getOrNull(2)?.toDoubleOrNull()
                    if (!pitchDeg.isNaN() && intr != null && w != null && h != null) {
                        val hFov = 2 * Math.toDegrees(kotlin.math.atan(w / (2 * intr.fx)))
                        val vFov = 2 * Math.toDegrees(kotlin.math.atan(h / (2 * intr.fy)))
                        val count = AimStars.visibleStars(hFov, vFov, pitchDeg, bortle)
                        if (count <= 0) {
                            Text("俯仰在地平面之下：看不到星", color = Color(0xFF9AA5B8), fontSize = 10.sp)
                        } else {
                            Text(
                                "肉眼可见星约 %d 颗（Bortle %d，极限星等 ~%.1f，视场 %.0f°×%.0f°）".format(
                                    count, bortle, AimStars.limitingMag(bortle), hFov, vFov,
                                ),
                                color = FieldAccent, fontSize = 11.sp,
                            )
                            Text(
                                "按全天星等分布模型粗略估计；光污染等级可在 设置→高级 修改。",
                                color = Color(0xFF7E889C), fontSize = 9.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = mirrorAssist,
                        onCheckedChange = {
                            mirrorAssist = it
                            controller.setMirrorAssist(it, mirrorHeight.toDouble())
                        },
                    )
                    Text("水镜辅助（预测直射/反射像 + 托盘距离）", color = Color(0xFFBFD0E8), fontSize = 12.sp)
                }
                if (mirrorAssist) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("镜头高 H", color = Color(0xFF9AA5B8), fontSize = 11.sp)
                        androidx.compose.material3.Slider(
                            value = mirrorHeight,
                            onValueChange = { mirrorHeight = it },
                            onValueChangeFinished = { controller.setMirrorAssist(true, mirrorHeight.toDouble()) },
                            valueRange = 0.2f..1.0f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text("%.2f m".format(mirrorHeight), color = Color(0xFFBFD0E8), fontSize = 11.sp)
                    }
                    if (state.mirrorInfo.isNotEmpty()) {
                        Text(state.mirrorInfo, color = FieldOk, fontSize = 11.sp)
                    }
                    Text(
                        "说明：托盘距离 d = H / tan(月亮高度)；画面上橙色圈=预测直射像，绿色圈=预测反射像（假设水面水平、罗盘无滚转，仅粗对准）。",
                        color = Color(0xFF7E889C), fontSize = 10.sp,
                    )
                }
                Text(
                    "注：城市磁干扰可差 5–15°，真方位按磁差 −5.5° 估算；双天体同框时不需要水平基准。",
                    color = Color(0xFF7E889C), fontSize = 10.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 预览
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF05070D))) {
            Box {
            androidx.compose.runtime.key(state.previewEpoch) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                DiagLog.i("UI", "TextureView available ${w}x$h")
                                textureView = tv
                                controller.onPreviewSurface(tv)
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                                previewTicks.intValue++
                                if (previewTicks.intValue % 300 == 0) {
                                    DiagLog.i("UI", "preview ticks=${previewTicks.intValue} mode=${controller.state.value.previewMode}")
                                }
                            }
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                DiagLog.i("UI", "TextureView destroyed")
                                textureView = null
                                controller.onPreviewSurface(null)
                                return true
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
            }
            // 增强预览：自己渲染"堆栈+拉伸"后的画面（覆盖在 TextureView 上），再叠加识别框
            val previewImg = remember(state.previewBitmap) { state.previewBitmap?.asImageBitmap() }
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = 28f
                    color = android.graphics.Color.rgb(120, 230, 145)
                }
                val img = previewImg
                // 标记坐标：位图已按传感器朝向旋转，标记要跟着转
                fun rotNorm(nx: Float, ny: Float): Pair<Float, Float> = when (state.previewRotationDeg) {
                    90 -> (1f - ny) to nx
                    180 -> (1f - nx) to (1f - ny)
                    270 -> ny to (1f - nx)
                    else -> nx to ny
                }
                var dx = 0f
                var dy = 0f
                var dw = size.width
                var dh = size.height
                if (img != null) {
                    // 居中裁切填满卡片（不再留黑边）
                    val s = maxOf(size.width / img.width, size.height / img.height)
                    dw = img.width * s
                    dh = img.height * s
                    dx = (size.width - dw) / 2f
                    dy = (size.height - dh) / 2f
                    clipRect(
                        left = 0f, top = 0f, right = size.width, bottom = size.height,
                    ) {
                        drawImage(
                            image = img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                        )
                    }
                }
                val zoom = state.previewZoom.coerceAtLeast(1).toFloat()
                for (m in state.previewMarks + state.mirrorMarks) {
                    val zx = if (zoom > 1f) (m.nx - 0.5f) * zoom + 0.5f else m.nx
                    val zy = if (zoom > 1f) (m.ny - 0.5f) * zoom + 0.5f else m.ny
                    if (zoom > 1f && (zx < 0f || zx > 1f || zy < 0f || zy > 1f)) continue
                    val (rx, ry) = rotNorm(zx, zy)
                    val x = dx + rx * dw
                    val y = dy + ry * dh
                    if (m.isDisk) {
                        drawCircle(
                            color = Color(0xFFFFC24B),
                            radius = m.radiusN * dw * zoom,
                            center = Offset(x, y),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                        )
                        paint.color = android.graphics.Color.rgb(255, 194, 75)
                    } else {
                        drawCircle(
                            color = FieldOk,
                            radius = dw * 0.012f * zoom,
                            center = Offset(x, y),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                        )
                        drawCircle(color = FieldOk, radius = 2f, center = Offset(x, y))
                        paint.color = FieldOk.toArgb()
                    }
                    drawIntoCanvas { c ->
                        c.nativeCanvas.drawText(m.label, x + 14f, y - 14f, paint)
                    }
                }
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text(
                    "相机：${state.cameraStatus}  · 刷新 ${previewTicks.intValue}",
                    color = if (state.cameraStatus.contains("预览中")) FieldOk else FieldWarn,
                    fontSize = 11.sp,
                )
                Text(
                    "拍摄：%s / ISO %d%s".format(
                        when {
                            exposureNs >= 1_000_000_000L -> "${exposureNs / 1_000_000_000}s"
                            exposureNs >= 1_000_000L -> "${exposureNs / 1_000_000}ms"
                            else -> "${exposureNs / 1_000}µs"
                        },
                        iso,
                        if (state.maxIso > 0 && iso > state.maxIso) "（设备上限 ${state.maxIso}）" else "",
                    ),
                    color = Color(0xFFBFD0E8),
                    fontSize = 11.sp,
                )
                Text(
                    state.shakeText,
                    color = when (state.shakeLevel) {
                        0 -> FieldOk
                        1 -> FieldWarn
                        else -> Color(0xFFFF6B6B)
                    },
                    fontSize = 11.sp,
                )
            }
            }
        }

        if (state.manualCalib) {
            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !state.packing && state.calibShot < state.calibTotal,
                onClick = { controller.triggerCalibrationFrame() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FieldAccent, contentColor = Color(0xFF0A0E17),
                ),
            ) {
                Text(
                    if (state.packing) "正在打包…"
                    else "拍一帧（${state.calibShot}/${state.calibTotal}）· 就在预览窗下方",
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row {
            Button(
                onClick = { showCaptureConfirm = true },
                enabled = state.phase != "CAPTURING" && state.phase != "OPENING",
                colors = ButtonDefaults.buttonColors(containerColor = FieldAccent, contentColor = Color(0xFF0A0E17)),
            ) { Text(if (state.phase == "CAPTURING" || state.phase == "OPENING") "采集中…" else "开始采集") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { controller.cancelCapture() },
                enabled = state.phase == "CAPTURING" || state.phase == "OPENING",
                colors = ButtonDefaults.buttonColors(containerColor = FieldCardBg, contentColor = FieldWarn),
            ) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Column {
                Button(
                    onClick = { controller.buildSyntheticBundle() },
                    enabled = state.phase != "CAPTURING" && state.phase != "OPENING",
                    colors = ButtonDefaults.buttonColors(containerColor = FieldCardBg, contentColor = FieldAccent),
                ) { Text("合成样例") }
                Text(
                    "说明：不拍照，直接生成一份带合成星点/IMU/GNSS 的完整采集包 ZIP，用于验证打包与离线解算链路。" +
                        "点后等状态提示\"已打包\"，到「会话」页即可分享/导出。",
                    color = Color(0xFF7E889C), fontSize = 9.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (state.message.isNotBlank()) {
            Text(state.message, color = Color(0xFFBFD0E8), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        state.error?.let {
            Text("错误：$it", color = FieldBad, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        if (state.qualities.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FieldCardBg)) {
                Column(Modifier.padding(14.dp)) {
                    Text("逐帧质量", color = Color(0xFF8A93A6), fontSize = 12.sp)
                    state.qualities.forEach { q ->
                        Text(
                            "帧${q.index}: 检测 ${q.detections} 颗 · 云量 %.2f · PSF %.2f · 匹配 %d".format(
                                q.cloudCover, q.psfAspect, q.matched,
                            ),
                            color = Color(0xFFBFD0E8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                        Text("   ${q.hint}", color = if (q.solved) FieldOk else FieldWarn, fontSize = 11.sp)
                    }
                    if (state.imuHz > 0) {
                        Text("IMU 实测 %.0f Hz".format(state.imuHz), color = Color(0xFF7E889C), fontSize = 11.sp)
                    }
                }
            }
        }

        // 参数
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FieldCardBg)) {
            Column(Modifier.padding(14.dp)) {
                Text("采集参数与预览（按任务选预设，细节可再调）", color = Color(0xFF8A93A6), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    presets.forEach { p ->
                        FilterChip(
                            selected = state.previewMode == p.mode &&
                                exposureNs == p.exposureNs && iso == p.iso &&
                                (p.cameraId == null || p.cameraId == currentCam),
                            onClick = {
                                if (p.mode == 4) {
                                    // 太阳预设先确认，不在取消前改曝光/ISO（避免按钮假亮）
                                    pendingSunCamera = p.cameraId
                                    showSunConfirm = true
                                } else {
                                    exposureNs = p.exposureNs
                                    iso = p.iso
                                    controller.updatePreviewParams(p.exposureNs, p.iso)
                                    controller.setPreviewMode(p.mode, p.exposureNs, p.iso)
                                    p.cameraId?.let { controller.setCameraId(if (it == "0") null else it) }
                                }
                            },
                            label = { Text(p.name, fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                val camMismatch = presets.firstOrNull {
                    it.mode == state.previewMode && it.exposureNs == exposureNs && it.iso == iso &&
                        it.cameraId != null && it.cameraId != currentCam
                }
                Text(
                    when {
                        camMismatch != null -> "⚠ 预设「${camMismatch.name}」要求 cam${camMismatch.cameraId}，当前 cam$currentCam；点该预设会自动切换"
                        defaultCam != currentCam -> "当前镜头 cam$currentCam（默认相机 cam$defaultCam，可在设置→高级修改）"
                        else -> "当前镜头 cam$currentCam · 预设/默认相机一致"
                    },
                    color = if (camMismatch != null) FieldWarn else Color(0xFF7E889C),
                    fontSize = 9.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(1, 3, 5).forEach { n ->
                        FilterChip(
                            selected = frames == n, enabled = !state.calibLock,
                            onClick = { frames = n },
                            label = { Text("$n 帧", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    if (state.calibLock) {
                        Text(
                            "标定中：帧数已锁定（已拍 ${state.calibShot}），仅可调快门/ISO",
                            color = FieldWarn, fontSize = 10.sp,
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    // 加入亚秒档：手持/标定时必须用短曝光，1 s 手持必糊
                    listOf(
                        100_000L, 500_000L, 1_000_000L,
                        2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L,
                        100_000_000L, 250_000_000L, 500_000_000L,
                        1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L,
                    ).forEach { ns ->
                        FilterChip(selected = exposureNs == ns,
                            onClick = {
                                exposureNs = ns
                                controller.updatePreviewParams(exposureNs, iso)
                            },
                            label = {
                                Text(
                                    when {
                                        ns >= 1_000_000_000L -> "${ns / 1_000_000_000}s"
                                        ns >= 1_000_000L -> "${ns / 1_000_000}ms"
                                        else -> "${ns / 1_000}µs"
                                    },
                                    fontSize = 12.sp,
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(400, 800, 1600, 3200, 6400, 12800).forEach { v ->
                        FilterChip(selected = iso == v,
                            enabled = state.maxIso <= 0 || v <= state.maxIso,
                            onClick = {
                            iso = v
                            controller.updatePreviewParams(exposureNs, iso)
                        },
                            label = { Text("ISO $v", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Text("预览模式（对准星空用「找星」）", color = Color(0xFF8A93A6), fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("自动" to 0, "找星" to 1, "跟随拍摄" to 2, "月亮" to 3, "太阳" to 4, "白天月亮" to 5).forEach { (label, mode) ->
                        FilterChip(
                            selected = state.previewMode == mode,
                            onClick = {
                                DiagLog.i("UI", "tap preview mode: $label ($mode), current=${state.previewMode}")
                                if (mode == 3) { exposureNs = 2_000_000L; iso = 800 }
                                if (mode == 5) { exposureNs = 1_000_000L; iso = 400 }
                                if (mode == 4) {
                                    showSunConfirm = true
                                } else {
                                    controller.setPreviewMode(mode, exposureNs, iso)
                                }
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("放大", color = Color(0xFF8A93A6), fontSize = 12.sp,
                        modifier = Modifier.padding(end = 6.dp))
                    listOf(1, 2, 3, 4).forEach { z ->
                        FilterChip(
                            selected = state.previewZoom == z,
                            onClick = { controller.setPreviewZoom(z) },
                            label = { Text(if (z == 1) "1×" else if (z == 2) "2×" else if (z == 3) "3×" else "4×", fontSize = 12.sp) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(
                        checked = hdrEnabled,
                        enabled = state.previewMode != 4 && state.previewMode != 5,
                        onCheckedChange = { hdrEnabled = it },
                    )
                    Text(
                        "HDR 双曝光：短曝光拍月亮 + 1 s / ISO 3200 拍土星（帧数 ×2，需三脚架不动）",
                        color = if (state.previewMode == 4) Color(0xFF7E889C) else Color(0xFFBFD0E8),
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = {
                    exposureNs = 2_000_000L
                    iso = 800
                    controller.setPreviewMode(3, exposureNs, iso)
                }) { Text("月亮参数（2 ms / ISO 800）→ 再点「开始采集」拍照", fontSize = 12.sp) }
                TextButton(onClick = {
                    // 白天月亮：天空亮度高 3–4 个数量级，1 ms/ISO400 起步；预览切"白天月亮"
                    exposureNs = 1_000_000L
                    iso = 400
                    controller.updatePreviewParams(exposureNs, iso)
                    controller.setPreviewMode(5, exposureNs, iso)
                }) {
                    Text("白天月亮（1 ms / ISO400）→ 预览切「白天月亮」；过曝就再降 ISO/曝光", fontSize = 12.sp)
                }
                TextButton(onClick = { showTuning = true }) {
                    Text("预览调参（曝光/堆栈/模糊/增强/γ）…", fontSize = 12.sp)
                }
                if (showBiasCal) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { if (!biasRunning) showBiasCal = false },
                        title = { Text("加速度计零偏标定（六位置法）") },
                        text = {
                            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    "把手机按下面顺序静置，每个姿态点一次\"采集\"（15 秒）。\n" +
                                        "不需要天空/户外，室内桌面即可；垫块歪一点没关系（算法对倾角不敏感）。",
                                    color = Color(0xFF9AA5B8), fontSize = 11.sp,
                                )
                                Spacer(Modifier.height(6.dp))
                                if (biasMeans.size < 6) {
                                    Text(
                                        "第 ${biasStep + 1}/6 步：${biasPoses[biasStep]}",
                                        color = Color(0xFFBFD0E8), fontSize = 13.sp,
                                    )
                                    if (biasRunning) {
                                        Text(
                                            if (biasPhase == "place") "请摆好姿态：$biasSeconds s 后开始采集（屏幕朝下时不用点屏幕）"
                                            else "采集中… 剩余 $biasSeconds s（别碰手机）",
                                            color = FieldWarn, fontSize = 12.sp,
                                        )
                                    }
                                    Button(
                                        enabled = !biasRunning,
                                        onClick = {
                                            biasMsg = ""
                                            biasRunning = true
                                            biasPhase = "place"
                                            biasSeconds = 8
                                            biasScope.launch {
                                                repeat(8) {
                                                    delay(1000)
                                                    biasSeconds--
                                                }
                                                biasCalibrator.startPose()
                                                biasPhase = "collect"
                                                biasSeconds = 15
                                                repeat(15) {
                                                    delay(1000)
                                                    biasSeconds--
                                                }
                                                val r = biasCalibrator.endPose()
                                                biasRunning = false
                                                if (r == null) {
                                                    biasMsg = "采样太少，本姿态重来"
                                                } else {
                                                    biasMeans.add(r.first)
                                                    biasMsg = "本姿态抖动 %.2f m/s²".format(r.second)
                                                    if (biasMeans.size >= 6) {
                                                        biasResult = AccelBiasCalibration.solve(biasMeans.toList())
                                                    } else {
                                                        biasStep = biasMeans.size
                                                    }
                                                }
                                            }
                                        },
                                    ) { Text("采集（15 s）") }
                                    Text(biasMsg, color = Color(0xFF7E889C), fontSize = 10.sp)
                                } else {
                                    val res = biasResult
                                    if (res == null) {
                                        Text("标定失败：姿态不足或某轴缺失，请重来。", color = FieldBad, fontSize = 12.sp)
                                    } else {
                                        Text("结果：g = %.3f m/s²，残差 %.3f m/s²".format(res.g, res.rmsMps2), fontSize = 12.sp, color = Color(0xFFBFD0E8))
                                        Text("零偏 = (%.1f, %.1f, %.1f) mg".format(res.bias[0] / 9.80665 * 1000, res.bias[1] / 9.80665 * 1000, res.bias[2] / 9.80665 * 1000), fontSize = 12.sp, color = Color(0xFFBFD0E8))
                                        Text("等效倾斜误差 %.2f°（越小越好）".format(res.tiltErrorDeg), fontSize = 12.sp, color = FieldOk)
                                        res.warnings.forEach { Text("警告：$it", color = FieldWarn, fontSize = 11.sp) }
                                    }
                                    Row {
                                        Button(onClick = {
                                            val r = biasResult
                                            if (r != null) {
                                                val ok = AccelBiasStore.save(context, r.bias, r.g, r.rmsMps2)
                                                biasMsg = if (ok) "已保存并生效" else "保存失败"
                                            }
                                        }) { Text("保存并生效") }
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            biasMeans.clear(); biasStep = 0; biasResult = null; biasMsg = ""
                                        }) { Text("重新标定") }
                                    }
                                }
                                Text(biasMsg, color = FieldOk, fontSize = 11.sp)
                                Spacer(Modifier.height(6.dp))
                                val stored = remember(showBiasCal) { AccelBiasStore.load(context) }
                                stored?.let {
                                    Text("已存零偏：%.1f, %.1f, %.1f mg（保存于 %s）".format(
                                        it.bias[0] / 9.80665 * 1000, it.bias[1] / 9.80665 * 1000, it.bias[2] / 9.80665 * 1000,
                                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.savedAtUtcMs)),
                                    ), color = Color(0xFF7E889C), fontSize = 10.sp)
                                }
                                TextButton(onClick = {
                                    AccelBiasStore.clear(context)
                                    biasMsg = "已清除零偏"
                                }) { Text("清除已存零偏", color = FieldWarn, fontSize = 11.sp) }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { showBiasCal = false }) { Text("关闭") }
                        },
                    )
                }
                if (showCameraInv) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showCameraInv = false },
                        title = { Text("相机能力一览") },
                        text = {
                            Box(Modifier.height(420.dp)) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    com.dsh.starpos.android.CameraInventory.describe(context).forEach { line ->
                                        Text(
                                            line + "\n",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            color = Color(0xFFBFD0E8),
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { showCameraInv = false }) { Text("关闭") }
                        },
                    )
                }
                if (showLowSpace) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showLowSpace = false },
                        title = { Text("存储空间不足") },
                        text = {
                            Text(
                                "可用空间低于 1 GB，采集/打包可能失败。\n" +
                                    "建议先去「会话 → 清理」删除原始帧目录（保留 ZIP）或导出旧包。",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showLowSpace = false
                                onOpenCleanup?.invoke()
                            }) { Text("去清理") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showLowSpace = false }) { Text("仍要拍摄") }
                        },
                    )
                }
                if (showSunConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showSunConfirm = false },
                        title = { Text("太阳模式安全确认") },
                        text = {
                            Text(
                                "拍摄太阳前必须：\n" +
                                    "1. 已安装专用太阳滤镜（ND5/ND6 级，带 IR 截止更好）；\n" +
                                    "2. 用屏幕看画面，禁止肉眼/光学取景窗直视太阳；\n" +
                                    "3. 曝光被硬限制在 8 ms 以内（实测 2 ms / ISO 400 合适）；\n" +
                                    "4. 不要长时间连续对着太阳预览。",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                exposureNs = 2_000_000L   // 实测：ND6 下 2 ms/ISO400 圆面 ~60% 满阱
                                iso = 400
                                controller.setPreviewMode(4, exposureNs, iso)
                                pendingSunCamera?.let { controller.setCameraId(if (it == "0") null else it) }
                                pendingSunCamera = null
                                showSunConfirm = false
                            }) { Text("已装滤镜，继续") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showSunConfirm = false
                                pendingSunCamera = null
                            }) { Text("取消") }
                        },
                    )
                }
                if (showCaptureConfirm) {
                    val modeName = when (state.previewMode) {
                        1 -> "找星"; 2 -> "跟随拍摄"; 3 -> "月亮"; 4 -> "太阳"; else -> "自动"
                    }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showCaptureConfirm = false },
                        title = { Text("确认采集？") },
                        text = {
                            Text(
                                "模式：$modeName\n帧数：$frames\n曝光：${
                                    when {
                                        exposureNs >= 1_000_000_000L -> "${exposureNs / 1_000_000_000}s"
                                        exposureNs >= 1_000_000L -> "${exposureNs / 1_000_000}ms"
                                        else -> "${exposureNs / 1_000}µs"
                                    }
                                }\nISO：${if (state.maxIso > 0) iso.coerceAtMost(if (state.previewMode == 4) 200 else state.maxIso) else iso}" +
                                    (if (hdrEnabled) "\nHDR：再加 $frames 帧 1 s / ISO 3200（共 ${frames * 2} 帧）" else "") +
                                    "\n\n" +
                                    "采集后预览会自动恢复；如需修改参数请取消。",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showCaptureConfirm = false
                                startCaptureNow()
                            }) { Text("确认采集") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showCaptureConfirm = false }) { Text("取消") }
                        },
                    )
                }
                if (showDiagLog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDiagLog = false },
                        title = { Text("诊断日志（最近 200 行）") },
                        text = {
                            Box(Modifier.height(400.dp)) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    Text(
                                        DiagLog.tailText(200),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = Color(0xFFBFD0E8),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { shareDiagLog() }) { Text("分享日志文件") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showDiagLog = false }) { Text("关闭") }
                        },
                    )
                }
                if (showTuning) {
                    PreviewTuningDialog(
                        state = state,
                        controller = controller,
                        onClose = { showTuning = false },
                    )
                }
                Text(
                    when (state.previewMode) {
                        1 -> "找星：预览 600 ms / 4 帧 σ裁剪堆叠；每 0.5 s 检测；只标最亮前 6 颗（编号 1–6，绿圈=连续 2 次检测到），黄圈=月/日"
                        2 -> "跟随拍摄参数：所见即所得，但 1 s 曝光会掉到约 1 fps"
                        3 -> "月亮预览：短曝光（2 ms / ISO 800）避免圆面过曝；对准月亮，黄圈=检测到的圆面"
                        4 -> "太阳预览：实测 2 ms / ISO 400 合适；必须装太阳滤镜，黄圈=检测到的日面"
                        else -> "自动曝光：白天/景物用；夜空里楼和灯光会把天空压黑"
                    },
                    color = Color(0xFF8A93A6), fontSize = 10.sp,
                )
                if (state.maxExposureMs > 0.0) {
                    Text(
                        "设备上限：曝光 %.0f ms · ISO %d；超出的请求会被自动压到上限（曝光被压时 ISO 自动拉满）".format(
                            state.maxExposureMs, state.maxIso,
                        ),
                        color = FieldWarn, fontSize = 11.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 诊断日志入口（现场排查"卡帧/闪退"用，不需要 adb）
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { showDiagLog = true }) {
                Text("诊断日志…", color = Color(0xFF8A93A6), fontSize = 12.sp)
            }
            TextButton(onClick = { shareDiagLog() }) {
                Text("分享日志文件", color = Color(0xFF8A93A6), fontSize = 12.sp)
            }
        }

        // 采集包
        state.bundleZip?.let { zip ->
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2A))) {
                Column(Modifier.padding(14.dp)) {
                    Text("采集包", color = Color(0xFF8A93A6), fontSize = 12.sp)
                    Text(zip.name, color = Color.White, fontSize = 13.sp)
                    Text(
                        "%d KB · 目录 %s".format(zip.length() / 1024, state.bundleDir?.absolutePath ?: ""),
                        color = Color(0xFF7E889C), fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { exportLauncher.launch(zip.name) },
                        colors = ButtonDefaults.buttonColors(containerColor = FieldAccent, contentColor = Color(0xFF0A0E17)),
                    ) { Text("导出 ZIP（保存到下载/网盘）") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2310))) {
            Column(Modifier.padding(14.dp)) {
                Text("现场注意", color = FieldWarn, fontSize = 12.sp)
                Text(
                    "1) 手机固定（靠墙/三脚架/放地上），别手持长曝光；\n" +
                        "2) 对准天顶附近，避开路灯直射；\n" +
                        "3) 每个方位拍一组，转 60° 以上再拍下一组；\n" +
                        "4) 采集时别碰手机（IMU 会判定为运动）。",
                    color = Color(0xFFE3D3A8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // ===== 底部弹出面板：预检 / GNSS 真值 / 标定 =====
        openSheet?.let { sheet ->
            ModalBottomSheet(
                onDismissRequest = { openSheet = null },
                containerColor = FieldCardBg,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    when (sheet) {
                        "预检" -> {
                            Text("预检", color = FieldAccent, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            if (!state.preflightDone) {
                                Text("正在探测相机…", color = Color.White, fontSize = 13.sp)
                            } else {
                                Text(state.cameraLine.ifBlank { "—" }, color = Color.White, fontSize = 12.sp)
                                Text(
                                    "RAW ${if (state.rawSupported) "支持" else "不支持（将用 YUV）"} · 曝光 ${state.exposureRange} · ISO ${state.isoRange}",
                                    color = Color(0xFF9AA5B8), fontSize = 12.sp,
                                )
                                state.intrinsics?.let {
                                    Text(
                                        "内参 fx=%.1f fy=%.1f cx=%.1f cy=%.1f（来源 %s）".format(it.fx, it.fy, it.cx, it.cy, it.source),
                                        color = if (it.source == "calibration") FieldOk else FieldWarn, fontSize = 11.sp,
                                    )
                                }
                                Text("外参：${state.extrinsicText}", color = if (state.extrinsicText.startsWith("已载入")) FieldOk else FieldWarn, fontSize = 11.sp)
                                val biasEntry = runCatching { AccelBiasStore.load(context) }.getOrNull()
                                Text(
                                    "IMU 零偏：" + (biasEntry?.let { b ->
                                        val mg = doubleArrayOf(
                                            b.bias[0] * 101.97, b.bias[1] * 101.97, b.bias[2] * 101.97,
                                        )
                                        val tilt = Math.toDegrees(
                                            Math.atan(Math.sqrt(mg[0] * mg[0] + mg[1] * mg[1] + mg[2] * mg[2]) / 1019.7),
                                        )
                                        "(%.1f, %.1f, %.1f) mg · 等效倾斜 %.2f°".format(mg[0], mg[1], mg[2], tilt)
                                    } ?: "未标定（去「标定」标签做六位置法）"),
                                    color = if (biasEntry != null) FieldOk else FieldWarn,
                                    fontSize = 11.sp,
                                )
                                clock?.let {
                                    Text(
                                        "时钟：脱网 ${if (it.offlineHours.isInfinite()) "从未对时" else "%.1f 小时".format(it.offlineHours)}" +
                                            " · ±%.0f ms%s".format(it.clockUncertaintyMs, if (it.stale) "（陈旧，禁止 T1）" else ""),
                                        color = if (it.stale) FieldBad else FieldOk, fontSize = 12.sp,
                                    )
                                }
                                Row {
                                    TextButton(onClick = {
                                        clock = clockMonitor.status()
                                        controller.refreshPreflight(clock)
                                    }) { Text("重新预检", color = FieldAccent, fontSize = 12.sp) }
                                    TextButton(onClick = { showCameraInv = true }) {
                                        Text("相机能力一览…", color = FieldAccent, fontSize = 12.sp)
                                    }
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    Text("相机：", color = Color(0xFF8A93A6), fontSize = 11.sp)
                                    listOf("主摄 0" to "0", "超广角 3" to "3", "长焦 4" to "4").forEach { (label, id) ->
                                        val sel = (controller.cameraId ?: "0") == id
                                        FilterChip(
                                            selected = sel,
                                            onClick = { controller.setCameraId(if (id == "0") null else id) },
                                            label = { Text(label, fontSize = 11.sp) },
                                        )
                                    }
                                }
                            }
                        }
                        "GNSS 真值" -> {
                            Text("GNSS 真值", color = FieldAccent, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "真值只来自本次会话的实时定位：采集中会同步记录 GNSS 点，离线分析用它检验星光定位误差。" +
                                    "没有实时定位时，包里不会写假真值。",
                                color = Color(0xFF9AA5B8), fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (state.gnssReady) "● 已定位" else "○ 等待定位", color = if (state.gnssReady) FieldOk else FieldWarn, fontSize = 12.sp)
                            }
                            Text(
                                "权限 ${state.gnssPermission} · GPS ${if (state.gnssProviderEnabled) "开启" else "关闭"} · 卫星 ${state.gnssSatellites} 颗在用",
                                color = Color(0xFF9AA5B8), fontSize = 12.sp,
                            )
                            Text(state.gnssLastFixText, color = Color(0xFFBFD0E8), fontSize = 11.sp)
                            if (state.gnssPermission == "未授予") {
                                TextButton(onClick = {
                                    permissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                    )
                                }) { Text("授予定位权限", color = FieldAccent, fontSize = 12.sp) }
                            } else if (!state.gnssReady) {
                                Text(
                                    "还没有实时定位：采集会等一下（最多 15 s）；始终拿不到也能采集，但包里不会有真值。",
                                    color = FieldWarn, fontSize = 10.sp,
                                )
                            }
                        }
                        "标定" -> {
                            Text("标定（三件事，按顺序做一次即可）", color = FieldAccent, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "① IMU 零偏（室内六位置）→ ② 外参（三脚架星空）→ ③ 内参（点阵，最后做）",
                                color = Color(0xFFBFD0E8), fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                            CalibEntry(
                                title = "① IMU 零偏标定（六位置法）",
                                desc = "手机按 6 个姿态各静置 15 s；室内桌面即可，不需要天空。标定后等效倾斜误差通常 <0.05°。",
                                action = "开始零偏标定",
                            ) { openSheet = null; showBiasCal = true }
                            CalibEntry(
                                title = "② 外参标定（星空手眼，需三脚架）",
                                desc = "三脚架固定；每帧换角度（左右 3、俯仰 3、绕光轴滚转 2），停稳再拍；拍满 8 帧后本机离线估计，通过则自动写入。",
                                action = "开始外参标定",
                            ) {
                                openSheet = null
                                if (permsGranted) {
                                    controller.startCapture(
                                        textureView,
                                        FieldSessionController.Spec(
                                            frames = 8,
                                            exposureNs = 1_000_000_000L,
                                            iso = if (state.maxIso > 0) state.maxIso else 3200,
                                            note = "EXTRINSIC_CALIBRATION",
                                            estimateExtrinsic = true,
                                            manualCalibration = true,
                                        ),
                                        clock,
                                    )
                                } else { pendingStart = true; permissionLauncher.launch(PERMISSIONS) }
                            }
                            CalibEntry(
                                title = "③ 内参标定（点阵，手动 12 帧；最后做）",
                                desc = "适用：相机模组维修/更换、对焦异常、内参来源不是 calibration（会直接影响星点位置与解算精度）。" +
                                    "点阵板放 0.5~1.5 m，画面占 1/3 以上，每帧换角度/位置，拍满 12 帧自动打包并写入内参。",
                                action = "开始内参标定",
                            ) {
                                openSheet = null
                                if (permsGranted) {
                                    controller.startCapture(
                                        textureView,
                                        FieldSessionController.Spec(
                                            frames = 12, exposureNs = 1_000_000_000L, iso = 3200,
                                            note = "CALIBRATION", autoMode = true, manualIntrinsic = true,
                                        ),
                                        clock,
                                    )
                                } else { pendingStart = true; permissionLauncher.launch(PERMISSIONS) }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("点阵图案（打印 A4，20 mm 间距，9×13 点）", color = FieldAccent, fontSize = 12.sp)
                            Text(
                                "源码目录：star-positioning/calibration-pattern/（dot-grid-9x13-20mm-A4.pdf/.png）；" +
                                    "App 已内置同一份资产。点下面导出到「下载/StarPosition」，打印时选 A4、100% 比例、不要缩放。",
                                color = Color(0xFF9AA5B8), fontSize = 10.sp, lineHeight = 14.sp,
                            )
                            Row {
                                TextButton(onClick = { exportCalibrationPattern(context, "dot-grid-9x13-20mm-A4.pdf") }) {
                                    Text("导出 PDF", color = FieldAccent, fontSize = 12.sp)
                                }
                                TextButton(onClick = { exportCalibrationPattern(context, "dot-grid-9x13-20mm-A4.png") }) {
                                    Text("导出 PNG", color = FieldAccent, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        // 外参自标定结果（通过/未通过都要能返回采集页）
        state.calibrationResult?.let { msg ->
            AlertDialog(
                onDismissRequest = { controller.clearCalibrationResult() },
                title = { Text(if (state.calibrationOk) "外参标定完成" else "外参标定未通过", color = Color.White) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(msg, color = Color(0xFFBFD0E8), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { controller.clearCalibrationResult() }) {
                        Text("返回采集页", color = FieldAccent)
                    }
                },
                dismissButton = if (!state.calibrationOk) {
                    {
                        TextButton(onClick = {
                            controller.clearCalibrationResult()
                            openSheet = "标定"
                        }) { Text("查看标定说明", color = FieldAccent) }
                    }
                } else null,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private val PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun fieldPermissionsGranted(context: Context): Boolean {
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val loc = ok(Manifest.permission.ACCESS_FINE_LOCATION) || ok(Manifest.permission.ACCESS_COARSE_LOCATION)
    return ok(Manifest.permission.CAMERA) && loc
}

private fun openAppSettings(context: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun copyTo(context: Context, file: java.io.File, uri: Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)

/** 预览调参弹窗：现场手动试参数（曝光/堆栈/背景模糊/星点增强/目标亮度/γ）。 */
@androidx.compose.runtime.Composable
private fun PreviewTuningDialog(
    state: com.dsh.starpos.android.FieldSessionController.State,
    controller: com.dsh.starpos.android.FieldSessionController,
    onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        androidx.compose.material3.Surface(
            color = Color(0xFF141A26),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(14.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text("预览调参（实时生效）", color = Color(0xFF7FB2FF), fontSize = 16.sp)
                state.previewBitmap?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp),
                    )
                }
                Text("增强预览", color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Switch(
                    checked = state.enhanceEnabled,
                    onCheckedChange = { controller.setTunables(enhance = it) },
                )
                var expMs by androidx.compose.runtime.remember(state.starExpMs) {
                    androidx.compose.runtime.mutableStateOf(state.starExpMs.toFloat())
                }
                Text("预览曝光：${expMs.toInt()} ms", color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = expMs,
                    onValueChange = { expMs = it },
                    onValueChangeFinished = {
                        controller.setTunables(exposureMs = expMs.toInt())
                        expMs = (controller.state.value.starExpMs).toFloat()
                    },
                    valueRange = 100f..1500f,
                    steps = 27,
                )
                Text("堆栈帧数：${state.dispStack}", color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.dispStack.toFloat(),
                    onValueChange = { controller.setTunables(stack = it.toInt()) },
                    valueRange = 2f..6f,
                    steps = 3,
                )
                Text("背景模糊半径：${state.bgBlur}（越大越压光污染/渐晕）", color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.bgBlur.toFloat(),
                    onValueChange = { controller.setTunables(bgBlur = it.toInt()) },
                    valueRange = 4f..96f,
                )
                Text("高通半径：${state.hpRadius} px（越小越突出点状星）", color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.hpRadius.toFloat(),
                    onValueChange = { controller.setTunables(hpRadius = it.toInt()) },
                    valueRange = 1f..16f,
                )
                Text("星点增强：%.1f".format(state.starBoost), color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.starBoost,
                    onValueChange = { controller.setTunables(starBoost = it) },
                    valueRange = 0f..4f,
                )
                Text("背景目标亮度：%.2f".format(state.targetLevel), color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.targetLevel,
                    onValueChange = { controller.setTunables(targetLevel = it) },
                    valueRange = 0.1f..0.8f,
                )
                Text("γ：%.2f".format(state.gammaValue), color = Color(0xFF8A93A6), fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = state.gammaValue,
                    onValueChange = { controller.setTunables(gamma = it) },
                    valueRange = 0.3f..1.2f,
                )
                Row {
                    TextButton(onClick = {
                        controller.setTunables(
                            enhance = true, stack = 4, bgBlur = 28, starBoost = 1.2f, hpRadius = 4,
                            targetLevel = 0.42f, gamma = 0.72f, exposureMs = 600,
                        )
                        expMs = 600f
                    }) { Text("恢复默认") }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
        }
    }
}


@Composable
private fun CalibEntry(title: String, desc: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2A))) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = FieldAccent, fontSize = 13.sp)
            Text(desc, color = Color(0xFF9AA5B8), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = FieldAccent, contentColor = Color(0xFF0A0E17)),
            ) { Text(action, fontSize = 12.sp) }
        }
    }
}


/** 把内置点阵图案导出到「下载/StarPosition」（API29+）或应用外部目录并分享。 */
private fun exportCalibrationPattern(context: Context, name: String) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".pdf")) "application/pdf" else "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StarPosition")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    context.assets.open("calibration-pattern/$name").use { it.copyTo(out) }
                }
                Toast.makeText(context, "已导出到 下载/StarPosition/$name", Toast.LENGTH_LONG).show()
                return
            }
        }
        // 旧系统/失败兜底：应用外部目录 + 分享
        val dir = File(context.getExternalFilesDir(null), "calibration-pattern").apply { mkdirs() }
        val f = File(dir, name)
        context.assets.open("calibration-pattern/$name").use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (name.endsWith(".pdf")) "application/pdf" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "保存/分享点阵图案"))
    }.onFailure {
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}
