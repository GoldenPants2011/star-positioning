// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import android.content.Context
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.starpos.BuildConfig
import com.dsh.starpos.R
import com.dsh.starpos.android.AccelBiasStore
import com.dsh.starpos.android.AndroidTimeSource
import com.dsh.starpos.android.FieldSessionController
import com.dsh.starpos.android.SharedPreferencesStore
import com.dsh.starpos.core.ClockMonitor
import kotlinx.coroutines.launch
import java.io.File
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sqrt
import kotlin.math.abs

/** 首页底部的 4 个可展开标签。 */
private enum class HomePanel(val title: String, val glyph: String) {
    PRINCIPLE("原理说明", "∎"),
    USAGE("使用说明", "☰"),
    STATUS("状态聚合", "●"),
    ABOUT("关于", "ⓘ"),
}

/**
 * 首页：上半部分大幅留空（Logo + 名称/版本 + powered by DSH 徽章），
 * 下方 4 个标签可上滑展开、下滑收起。
 */
@Composable
fun HomeScreen(onOpenCapture: () -> Unit = {}, onOpenSessions: () -> Unit = {}) {
    val context = LocalContext.current
    val controller = remember { FieldSessionController(context) }
    val clockMonitor = remember { ClockMonitor(AndroidTimeSource(context), SharedPreferencesStore(context)) }
    val state by controller.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("starpos", android.content.Context.MODE_PRIVATE) }
    var expanded by remember { mutableStateOf<HomePanel?>(null) }
    var lastRefreshedPanel by remember { mutableStateOf<HomePanel?>(null) }
    val scope = rememberCoroutineScope()
    var clock by remember { mutableStateOf(clockMonitor.status()) }
    var syncing by remember { mutableStateOf(false) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var autoSyncTried by remember { mutableStateOf(false) }

    val doSync: () -> Unit = {
        if (!syncing) {
            syncing = true
            syncMsg = "对时中…"
            scope.launch {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val host = prefs.getString("ntp_host", "ntp.aliyun.com") ?: "ntp.aliyun.com"
                    runCatching { com.dsh.starpos.core.NtpClient.query(host) }.getOrNull()
                }
                if (res != null) {
                    clockMonitor.recordSync(res.offsetMs)
                    clock = clockMonitor.status()
                    syncMsg = "对时成功（${res.server}）：偏差 ${res.offsetMs} ms，往返 ${res.rttMs} ms"
                } else {
                    syncMsg = "对时失败：无网络或 NTP 不可达（离线时请手动确认 UTC）"
                }
                syncing = false
            }
        }
    }

    // 打开"状态聚合"时做一次预检；若时钟陈旧且网络可用，自动 NTP 对时一次
    LaunchedEffect(expanded) {
        val p = expanded
        if (p == HomePanel.STATUS && lastRefreshedPanel != HomePanel.STATUS) {
            lastRefreshedPanel = HomePanel.STATUS
            clock = clockMonitor.status()
            runCatching { controller.refreshPreflight(clock) }
            if (!autoSyncTried && clock.stale && clock.networkAvailable && prefs.getBoolean("auto_ntp", true)) {
                autoSyncTried = true
                doSync()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { controller.close() } }
    }

    val imuLive = rememberImuLive(active = expanded == HomePanel.STATUS)
    BoxWithConstraints(Modifier.fillMaxSize().background(AppBg)) {
        val panelHeight = maxHeight * 0.56f
        Column(Modifier.fillMaxSize()) {
            // ===== 上半部分：大幅留空 =====
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_starpos_logo),
                        contentDescription = "星光定位 Logo",
                        modifier = Modifier.size(128.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "星 光 定 位",
                        color = AppText,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 6.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "STAR POSITION",
                        color = AppAccent.copy(alpha = 0.65f),
                        fontSize = 10.sp,
                        letterSpacing = 5.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "v${BuildConfig.VERSION_NAME} · 开源版 · GPL-3.0",
                        color = AppDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    PoweredByDshBadge()
                }
            }

            // ===== 可展开面板 =====
            AnimatedVisibility(
                visible = expanded != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val panel = expanded
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .border(
                            1.dp, AppAccent.copy(alpha = 0.18f),
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        ),
                    color = AppCard,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // 拖拽条 + 收起
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(panel) {
                                    var dy = 0f
                                    detectVerticalDragGestures(
                                        onDragEnd = { if (dy > 40f) expanded = null; dy = 0f },
                                        onVerticalDrag = { _, d -> dy += d },
                                    )
                                },
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .width(42.dp).height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(AppDim.copy(alpha = 0.5f)),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    (panel?.title ?: "") + " · 下滑收起",
                                    color = AppDim, fontSize = 10.sp,
                                )
                            }
                        }
                        if (panel != null) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                                when (panel) {
                                    HomePanel.PRINCIPLE -> PrincipleContent()
                                    HomePanel.USAGE -> UsageContent()
                                    HomePanel.STATUS -> StatusContent(
                                        context = context,
                                        state = state,
                                        clockText = if (clock.offlineHours.isInfinite()) "从未对时"
                                        else "脱网 %.1f 小时 · ±%d ms".format(clock.offlineHours, clock.clockUncertaintyMs.toInt()),
                                        clockOk = !clock.stale && !clock.offlineHours.isInfinite(),
                                        syncMsg = syncMsg,
                                        syncing = syncing,
                                        onSync = doSync,
                                        imuLive = imuLive,
                                        batteryPct = batteryPercent(context),
                                        onRefresh = {
                                            clock = clockMonitor.status()
                                            runCatching { controller.refreshPreflight(clock) }
                                        },
                                        onOpenCapture = onOpenCapture,
                                        onOpenSessions = onOpenSessions,
                                    )
                                    HomePanel.ABOUT -> AboutContent()
                                }
                            }
                        }
                    }
                }
            }

            // ===== 4 个标签（上滑展开 / 下滑收起）=====
            HomeTagRow(
                expanded = expanded,
                onToggle = { tapped ->
                    expanded = if (expanded == tapped) null else tapped
                },
                onSwipeUp = {
                    if (expanded == null) expanded = HomePanel.PRINCIPLE
                },
                onSwipeDown = { expanded = null },
            )
        }
    }
}

@Composable
private fun HomeTagRow(
    expanded: HomePanel?,
    onToggle: (HomePanel) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    var drag = 0f
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppCard2)
            .border(1.dp, AppAccent.copy(alpha = 0.12f))
            .pointerInput(expanded) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag < -30f) onSwipeUp()
                        else if (drag > 30f) onSwipeDown()
                        drag = 0f
                    },
                    onVerticalDrag = { _, d -> drag += d },
                )
            }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomePanel.entries.forEach { p ->
            val active = expanded == p
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) AppAccent.copy(alpha = 0.16f) else AppCard)
                    .border(
                        1.dp,
                        if (active) AppAccent.copy(alpha = 0.6f) else AppAccent.copy(alpha = 0.12f),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onToggle(p) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(p.glyph, color = if (active) AppAccent else AppDim, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(p.title, color = if (active) AppText else AppDim, fontSize = 12.sp)
            }
        }
    }
}

private class ImuCounter {
    @Volatile var samples = 0
    @Volatile var accelMag = 0.0
    @Volatile var gyroMag = 0.0
    @Volatile var lastNs = 0L
    fun reset() { samples = 0 }
}

private class ImuLive(val hz: Double, val accel: Double, val gyro: Double, val static: Boolean)

/** 状态聚合面板打开时，实时采样 IMU（1 Hz 刷新），不写文件、不影响采集。 */
@Composable
private fun rememberImuLive(active: Boolean): ImuLive? {
    val context = LocalContext.current
    var info by remember { mutableStateOf<ImuLive?>(null) }
    val counter = remember { ImuCounter() }
    LaunchedEffect(active) {
        if (!active) { info = null; return@LaunchedEffect }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (acc == null) { info = null; return@LaunchedEffect }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    counter.samples++
                    counter.accelMag = sqrt(
                        (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble(),
                    )
                } else {
                    counter.gyroMag = sqrt(
                        (e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble(),
                    )
                }
                counter.lastNs = e.timestamp
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
        if (gyr != null) sm.registerListener(listener, gyr, SensorManager.SENSOR_DELAY_GAME)
        try {
            while (isActive) {
                delay(1000)
                val n = counter.samples
                val g = counter.gyroMag
                info = ImuLive(
                    hz = n.toDouble(),
                    accel = counter.accelMag,
                    gyro = g,
                    static = g < 0.015 && counter.accelMag > 9.4 && counter.accelMag < 10.2,
                )
                counter.reset()
            }
        } finally {
            sm.unregisterListener(listener)
        }
    }
    return info
}

@Composable
private fun PoweredByDshBadge() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.dsh_badge),
            contentDescription = "powered by dsh",
            modifier = Modifier.height(20.dp).width(121.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "由 DeepSeek Harness 构建",
            color = AppDim.copy(alpha = 0.75f),
            fontSize = 9.sp,
        )
    }
}

// ==================== 原理说明 ====================

@Composable
private fun PrincipleContent() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        ContentSection("一句话原理") {
            Text(
                "拍一张星图 → 认出是哪几颗星 → 反推手机姿态 → 用重力方向当地平基准 → " +
                    "由\"天顶指向\"算出经纬度。不需要卫星信号，只要有星、有时钟、有重力。",
                color = AppText, fontSize = 12.sp, lineHeight = 19.sp,
            )
        }
        ContentSection("核心计算链") {
            FormulaLine("① 星表方向（ICRS/J2000）→ 岁差+章动 → 当日真赤道（TOD）")
            FormulaLine("   u_tod = P_icrsToTod(t) · u_icrs")
            FormulaLine("② 星图匹配：相机方向 u_cam 与星表方向配对，求旋转矩阵 R")
            FormulaLine("   min_R  Σ wᵢ · |R·u_cam,ᵢ − u_tod,ᵢ|²   （Wahba 问题，SVD 闭式解）")
            FormulaLine("③ 重力方向：加速度计 → 设备系 → 外参 → 相机系天顶 n")
            FormulaLine("④ 天顶指向：z_tod = R · n")
            FormulaLine("⑤ 经纬度：dec = asin(z_z)，ra = atan2(z_y, z_x)")
            FormulaLine("   lon = ra − GAST(t)，lat = geodetic(dec)")
            FormulaLine("⑥ 误差：星点残差 RMS、高度残差、CEP95、T0/T1/T2 分级")
        }
        ContentSection("参考系与时间") {
            Text(
                "恒星位置来自星表（ICRS/J2000）。拍到的星看起来在\"今天的天空\"，所以要先做" +
                    "岁差（地球自转轴缓慢进动）与章动（月球/太阳引起的小抖动）改正；" +
                    "GAST 是格林尼治视恒星时，把\"天球经度\"换算成\"地面经度\"。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("光轴是什么（入门）") {
            Text(
                "光轴 = 镜头/相机镜头中心、垂直于传感器平面、指向被摄景物的那条线；" +
                    "画面正中心就在光轴上，光轴指向哪里，相机就\"看\"向哪里。",
                color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "两个常见误解：① 光轴不是\"手机边缘/屏幕朝向\"；② 绕光轴滚转（roll）时，画面中心指向不变，" +
                    "只是画面在原地转圈——所以外参标定必须专门拍\"绕光轴滚转\"的帧，否则几何退化、解不出来。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "和星图的关系：星解算的是\"光轴指向 + 画面滚转\"（三自由度姿态），" +
                    "重力给的是\"哪边是下\"；两者合起来才能把天顶方向换成经纬度。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("联合解算与结果怎么读") {
            Text(
                "联合解算：把多个包/多帧的太阳、月亮高度（以及星光/两体的 2D 位置）放在一个最小二乘里，" +
                    "同时解纬度和经度；必要时把钟差 δt、垂直偏差 δv 也当未知量。前提是同一地点、同一设备/标定。",
                color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "结果页先给 WGS84 经纬度（主），再给局部 ENU（相对参考原点，米）和 UTM/高斯投影；" +
                    "概率椭圆同时给 1σ 与 95%（半轴，不是直径），方位自北顺时针。无 GNSS 真值时只显示椭圆，不显示误差、不给 T 级。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "系统偏差迁移：用有真值的参考包估出高度系统偏差 δv（鲁棒中位数），扣到无真值的包上再解；" +
                    "必须手动确认同地点/同设备/同天体，并剔除异常参考包。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("适用地区与高纬度影响") {
            Text(
                "星光模式：全球有夜空、能看到足够亮星的地方都能用（星表覆盖全天，南天星也可用）；" +
                    "前提是镜头能拍到星、设备已完成标定。室内、强光污染、极昼/极夜无星的时段不可用。",
                color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "高纬度（约 |纬度| > 60–70°）的主要影响：① 同样的水平角误差换算成经度误差会放大（约 1/cosφ）；" +
                    "② 磁罗盘磁差大、靠极区方向不可靠，对准辅助只能粗用；③ 拱极星多、星图绕天极转，" +
                    "部分方向的亮星分布与天光背景更不利；④ 极昼/极夜时没有夜空，星光模式直接不可用。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "日月模式：需要太阳/月亮在地平线以上，全球可用；但高纬极昼时太阳可能整日不落（或多日不升），" +
                    "极夜时太阳不升起；低高度太阳高度变化小、折射与地平线遮挡误差大，单点几何差。" +
                    "月亮虽可全天可见，但方位变化慢、圆面大，高纬同样建议多历元。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "建议：高纬地区多拍几组（不同时间/方位）、严格标定，并用 GNSS 真值校验；结果页的 T 级只对可信真值有效。",
                color = AppWarn, fontSize = 10.sp, lineHeight = 15.sp,
            )
        }
        ContentSection("高级设置怎么影响结果") {
            Text(
                "坐标格式：只改结果的显示（十进制度或度分秒），不改解算与坐标值；导出/计算仍用十进制度。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "星光垂直修正（StarVertical）：只作用于星光分支；修正量来自**标定**——用同一地点星光会话 + GNSS 真值拟合，" +
                    "内置 dt≈(2.97,−0.03,−4.84)°，剔除单包交叉验证中位误差约 25 km；换机/换标定需重新拟合。关掉可做对照。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "联合解算参数：系统误差底噪会把每个观测的 σ 抬高，椭圆更保守；单包剔除复核点数量决定误差图里画多少" +
                    "“去掉一包再解”的一致性点（0=关闭，点数越多越慢）。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("其他分支") {
            Text("• 月亮/太阳：圆面拟合 + 星历高度 → 单天体或多历元定位（T2 不依赖重力/外参）", color = AppDim, fontSize = 11.sp, lineHeight = 17.sp)
            Text("• 两体（月亮+行星同框）：两颗天体同时定向，几何退化时仍可约束方位", color = AppDim, fontSize = 11.sp, lineHeight = 17.sp)
            Text("• 水镜：直射像与水面反射像关于水平面对称 → 两个方向之差 = 天顶，不依赖 IMU", color = AppDim, fontSize = 11.sp, lineHeight = 17.sp)
            Text("• 折射：星越靠近地平线被大气抬得越高（Bennett 公式），需按视高度反解", color = AppDim, fontSize = 11.sp, lineHeight = 17.sp)
        }
        ContentSection("GNSS 真值怎么用（本机定位误差校验）") {
            Text(
                "采集时会同步记录 GNSS 定位点。离线分析在**真值可信**时，把星光/天体解算的位置与 GNSS 位置比，" +
                    "得到本机定位误差（km）——这是评价算法最直接的指标，不参与解算本身。",
                color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "可信条件（同时满足才采用）：① 有本次会话的实时定位（不是“最后已知位置”）；" +
                    "② 水平精度 ≤ 约 20 m；③ 定位来源为 GPS/北斗等卫星（network 定位只作粗略参考）；" +
                    "④ 定位时间与拍摄时间相差不大（同一次会话内）。否则该会话不计算“误差”，只做绝对位置输出。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "在校准场景（外参/零偏/垂直修正）里，GNSS 真值还作为拟合目标：用多帧已知位置反求外参/天顶修正。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("星表的适用年限") {
            Text(
                "内置星表来自 HYG v41（J2000/ICRS 历元，星等 −1.5~7，含自行）。解算时按拍摄时刻把每颗星从 J2000 " +
                    "传播到当时位置，所以不需要每年换表；对 7 等以内亮星，自行传播在 2000–2050 年区间误差很小（远小于像素尺度）。",
                color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 17.sp,
            )
            Text(
                "建议适用区间：约 2000–2050 年（本机 2026 年使用完全没问题）。超过 ~2050 年，或要 0.1″ 级天体测量，" +
                    "应换用更现代的星表（如 Gaia DR3 的 J2016.0 历元数据）；换表只需替换 stars.bin，解算代码不变。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
        }
        ContentSection("专业术语表（正式含义 + 大白话）") {
            TERMS.forEach { TermRow(it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private class Term(val name: String, val formal: String, val plain: String)

private val TERMS = listOf(
    Term("星历（Ephemeris）", "天体在给定时刻的位置/距离/视半径等数值，由天文理论或数据表计算。", "星星/月亮/太阳在某分某秒\"在天上的哪儿\"的答案。"),
    Term("星表（Catalog）", "恒星位置、自行、星等的清单（本项目为 HYG v41 亮星表，-1.5~7 等）。", "一张\"星星点名册\"，写着每颗星在天上的固定地址。"),
    Term("ICRS / J2000", "国际天球参考系，恒星坐标的标准历元（2000 年）。", "全人类共用的\"天上地图坐标系\"，以 2000 年为基准。"),
    Term("赤经 / 赤纬（RA/Dec）", "天球上的经度与纬度（赤经常用小时/度，赤纬用度）。", "在天上地图上的\"横坐标 / 纵坐标\"。"),
    Term("天顶（Zenith）", "观察者正上方的方向（重力方向的反方向）。", "你抬头正对头顶的那一点。"),
    Term("高度角（Altitude）", "天体方向与地平面的夹角，0°=地平线，90°=头顶。", "那颗星离地平线\"多高\"。"),
    Term("方位角（Azimuth）", "天体方向在地平面上的投影相对正北的顺时针角度。", "那颗星在\"东南西北\"的哪个方向。"),
    Term("视高度 / 真高度", "视高度是眼睛/相机看到的高度（含折射）；真高度是几何位置。", "看到的和实际的位置差一点，因为大气像透镜。"),
    Term("大气折射（Refraction）", "大气密度随高度变化使光线弯曲，天体视位置比真位置偏高（地平附近约 34′）。", "看水里的筷子会弯——看低空的星也会\"被抬起来\"。"),
    Term("光行差（Aberration）", "地球公转速度造成星光方向的小偏移（约 20.5″）。", "你跑着接雨，雨看起来是斜的——地球也在跑。"),
    Term("岁差（Precession）", "地球自转轴像陀螺一样缓慢摆动，天极位置逐年移动（约 50″/年）。", "天上的\"北极\"在慢慢搬家，所以坐标要用年份改正。"),
    Term("章动（Nutation）", "月球/太阳引力叠加在岁差上的短周期小抖动。", "北极搬家路上的\"小颤抖\"。"),
    Term("GAST", "格林尼治视恒星时：春分点相对格林尼治子午线的角度，把天球经度转成地面经度。", "天上地图的\"旋转角度\"，用来换算你在地球上的经度。"),
    Term("光轴（Optical axis）", "过镜头中心、垂直于传感器平面、指向被摄景物的直线；画面主点（cx,cy）在光轴上。", "镜头\"正对着看\"的那条线，画面正中心就是它指着的地方。"),
    Term("滚转（Roll）", "绕光轴旋转的姿态角：画面中心指向不变，只改变画面里\"上\"的方向。", "手机像方向盘一样转圈，镜头还是看着同一个方向，只是画面歪了。"),
    Term("内参（Intrinsics）", "相机内部几何参数：焦距 fx/fy、主点 cx/cy、畸变 k1/k2/k3/p1/p2。", "相机的\"眼睛度数\"：多广的视角、哪里是画面中心、边缘怎么歪。"),
    Term("外参（Extrinsics）", "相机坐标系与设备/IMU 坐标系之间的旋转关系。", "相机装在手机上的\"歪斜角度\"，必须逐台标定。"),
    Term("零偏（Bias）", "传感器在\"没有输入\"时输出的固定偏置（加速度计 mg 级，陀螺 mdps 级）。", "秤还没放东西就显示的\"皮重\"，要扣掉。"),
    Term("加速度计 / 陀螺仪", "分别测比力（可推重力方向）与角速度（可积分转角）。", "一个感觉\"哪边是下\"，一个感觉\"转得多快\"。"),
    Term("姿态矩阵 / 四元数", "描述相机相对天球（或设备）三维旋转的数学对象。", "用一组数表示\"相机朝向哪、歪了多少\"。"),
    Term("Wahba 问题", "给两组对应向量，求最优旋转矩阵使加权误差最小；可用 SVD 求闭式解。", "已知几张照片里的星和星表对上了，求\"相机怎么转才能对上\"的最优答案。"),
    Term("PSF / 质心", "星点在图像上的亮度分布（点扩散函数）及其亮度加权中心。", "一颗星在照片里不是一个像素，而是一小团光；那团光的中心就是星的位置。"),
    Term("星等（Magnitude）", "天体亮度单位，数值越小越亮（每差 5 等亮度差 100 倍）。", "星星的\"亮度等级\"，数字越小越亮。"),
    Term("匹配 / 内点（Inlier）", "图像星点与星表星成功配对；被判定为正确匹配的点叫内点。", "认出来的星是哪些；认对的那几颗叫\"靠谱的\"。"),
    Term("RMS / 残差", "残差是观测与模型之差；RMS 是残差的均方根，衡量拟合好坏（角秒或像素）。", "算出来的位置和实际差多少，越小越准。"),
    Term("视差（Parallax）", "有限距离天体（月亮/行星）从不同地点看方向不同，需归算到地心。", "月亮离得近，你站的位置不同，看到它偏的角度也不同。"),
    Term("地心纬度 / 大地纬度", "地心纬度是几何指向，大地纬度是 WGS84 椭球定义的纬度（两者差约 0.19°）。", "地球不是正球，\"纬度\"要按椭球形换算一下。"),
    Term("CEP95", "误差圆概率 95%：有 95% 概率落在以解为中心的圆内（由误差椭圆换算）。", "给你的位置画个圈，95% 的可能性你在圈里。"),
    Term("T0 / T1 / T2", "需求里的精度分级：T0 最高（≤1 km）、T1 ≤50 km、T2 双历元兜底。", "定位精度的三档目标。"),
    Term("两体（Two-body）", "同一画面里两颗天体（如月亮+土星）的角距与方位联合约束姿态/位置。", "一次拍到两个天体，用它们之间的\"张角\"来定位。"),
    Term("水镜 / 反射像", "水平液面反射天体形成镜像，直射像与反射像关于水平面对称。", "对着水面拍，天上一个太阳/月亮、水里还有一个，两个一起用可当地平基准。"),
    Term("托盘距离（d = H/tan h）", "镜头高于水面 H 时，要在水平距离 d 处放水盘才能同时拍到直射与反射像。", "水面要放多远，才能让两个像同框。"),
    Term("LOP（位置线）", "一次观测给出的等可能位置曲线（高度角对应一条位置圆，方位角对应一条直线）。", "只测一个高度，你只知道自己在一个圈上；多测几个圈交会才定位置。"),
    Term("十进制度 / DMS", "经纬度的两种写法：十进制度（30.8189°）或度分秒（30°49′08″）；只是显示格式。", "同一个位置，用小数度数或“度分秒”两种写法表示。"),
    Term("星垂修正（StarVertical）", "星光解算里按设备系小倾角对天顶方向做的固定修正；修正量由**星光会话 + GNSS 真值标定拟合**得到（当前内置 dt≈(2.97,−0.03,−4.84)°，剔除单包交叉验证中位误差约 25 km）。", "把相机“以为的天顶”再掰正一点点；这个“掰多少”是拿有 GPS 的星光数据标定出来的，只对星光路线用。"),
    Term("逐包剔除校验（LOO）", "每次去掉一个采集包、用其余包重新解算，比较这些解的位置离散度来检查一致性。", "抽掉一包看看结果还稳不稳；这些小点画在误差图里。"),
    Term("系统误差底噪", "给每个观测人为加的最小不确定度，防止椭圆把系统误差漏掉后显得过于乐观。", "给误差圈设一个“保底大小”，别让它小得不真实。"),
    Term("联合解算", "把多包/多帧观测放进同一个最小二乘，同时估计位置（可选钟差/垂直偏差）。", "把几次拍摄的数据合在一起算一个位置，而不是每张单独算。"),
    Term("FixObs（2D 位置观测）", "星光/两体已经解出的位置点，带椭圆协方差，作为联合解里的二维约束。", "已经算好的一个位置，连同它的误差圈，一起交给联合解参考。"),
    Term("系统偏差迁移", "用有真值的参考包估计共同的高度偏差 δv，再应用到无真值的目标包。", "先在有 GPS 的地方量出“系统偏了多少”，再到没 GPS 的地方扣掉。"),
    Term("1σ / 95% 椭圆", "由协方差得到的误差椭圆：1σ 约 39% 概率、95% 约 95% 概率（二维）。", "误差圈的大小；95% 圈更大，表示更有把握把真位置包住。"),
    Term("UTM / 高斯投影", "把经纬度投影到平面米制坐标（6° 带、含假东/假北）；本项目只用它显示，不参与解算。", "把地球表面摊平成一张米格纸的坐标，方便看“多少米”。"),
    Term("参考原点 / ENU", "局部东-北-天坐标的原点；ENU 即东(E)/北(N)/天(U) 三轴，单位米。", "以某个点为原点，往东/往北各多少米。"),
    Term("GNSS 真值", "采集时同步记录的卫星定位结果，用作离线分析与精度评估的参考。", "手机 GPS 给的位置，用来检查星光算得准不准。"),
    Term("自行（Proper motion）", "恒星相对天球背景的角运动（mas/年），需按时间从星表历元传播到观测时刻。", "每颗星都在天上缓慢移动，隔几十年就要按速度修正一下坐标。"),
    Term("参考历元（Epoch / Equinox）", "星表坐标对应的时刻与春分点定义（如 J2000.0、J2016.0）；不同历元数据需统一后再比较。", "星表坐标是“哪一年”的地址；不同年份的表要换算到同一年才能用。"),
)

@Composable
private fun TermRow(t: Term) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(t.name, color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(t.formal, color = AppDim, fontSize = 11.sp, lineHeight = 16.sp)
        Text("大白话：${t.plain}", color = AppText.copy(alpha = 0.85f), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

// ==================== 使用说明 ====================

@Composable
private fun UsageContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ContentSection("首次使用") {
            UsageStep("1", "授予相机/定位/传感器权限（采集页会提示）。")
            UsageStep("2", "校准时间：Wi-Fi/NTP 正常时自动；随后在首页状态里确认\"时钟\"为绿色。")
            UsageStep("3", "IMU 零偏标定：室内六位置法（15 s×6），一次即可，之后温漂很小。")
            UsageStep("4", "外参标定：夜间星空，三脚架，手动 8 帧（每帧换角度、停稳再拍）。")
        }
        ContentSection("现场采集（通用）") {
            UsageStep("1", "把手机固定：三脚架/靠墙/放地上，长曝光时绝对不能手持。")
            UsageStep("2", "打开采集页 → 预检：相机、GNSS、IMU 都正常再开始。")
            UsageStep("3", "选模式：星星→「找星」；拍月亮→「月亮」；白天带滤镜拍太阳→「太阳」；水面倒影→「水镜」；两颗天体同框→「两体」。")
            UsageStep("4", "先看预览：绿圈/编号是识别到的星，黄圈是日/月面，\"反射\"框是水面倒影。")
            UsageStep("5", "参数先不用动：每个模式有推荐曝光/ISO/帧数；不够亮再手动加曝光。")
            UsageStep("6", "按快门：自动模式等它拍完；手动标定模式按「拍一帧」换角度继续。")
            UsageStep("7", "看逐帧质量：匹配数不足/晃动/过曝的帧会被标出，必要时重拍。")
            UsageStep("8", "保存后到「会话」页：可以分享/导出 ZIP，交给我离线分析。")
        }
        ContentSection("找星预览怎么用") {
            UsageStep("1", "采集页下滑到「采集参数与预览」→ 预览模式选「找星」；镜头对准天空，等 1–2 秒增强画面出来。")
            UsageStep("2", "找星预览会把画面堆栈+拉伸，标出最亮 6 颗星（编号 1–6）；绿圈=连续两次检测到（更稳），黄圈=检测到的月/日圆面。")
            UsageStep("3", "看预览下方的检测星数：至少 3 颗、且分布不要挤成一条线，再开始采集；星太少就换方向/避开灯光。")
            UsageStep("4", "「自动」适合白天构图；夜间自动曝光容易把天空压黑。「找星」只影响预览，不会改变正式采集的曝光/ISO。")
            UsageStep("5", "对焦/确认星点可用 2×/3×/4× 放大；松开后仍按当前放大倍率显示。")
        }
        ContentSection("预览调参怎么调") {
            UsageStep("入口", "同一区域点「预览调参（曝光/堆栈/模糊/增强/γ）…」，参数实时生效，可随时「恢复默认」。")
            UsageStep("预览曝光", "100–1500 ms：越大星点越多，但刷新变慢；找星一般 400–800 ms。")
            UsageStep("堆栈帧数", "2–6 帧：压噪点；手持或风大时帧数太高会拖影。")
            UsageStep("背景模糊", "4–96：越大越能压城市光污染/镜头渐晕；星点也会略糊。")
            UsageStep("高通半径", "1–16 px：越小越突出点状星，太大星点会变暗。")
            UsageStep("星点增强", "0–4：越高星越显眼；太高会把噪点也放大。")
            UsageStep("目标亮度 / γ", "背景目标 0.1–0.8、γ 0.3–1.2：让天空背景不黑死也不灰白；先调背景亮度，再微调 γ。")
            UsageStep("建议", "城市里：先加大背景模糊、降低目标亮度；星太少：提高预览曝光/星点增强、降 γ；画面糊/拖影：降堆栈帧数。调参只影响预览，不影响 RAW 采集与解算结果。")
        }
        ContentSection("常见问题") {
            UsageStep("Q", "找不到星 / 0 匹配：确认在「找星」模式、手机没对着路灯、曝光 500 ms~1 s、ISO 3200 左右；星太少就转个方向。")
            UsageStep("Q", "月亮过曝：降 ISO 或把曝光降到 1~2 ms；圆面边缘发白就是过曝。")
            UsageStep("Q", "太阳模式必须装 ND 滤镜：不装会烧相机；App 有确认弹窗，不要绕过。")
            UsageStep("Q", "水镜看不到反射：太阳太低/水面不平静/曝光太短；把曝光加到 20~500 ms，反射像会在直射像正下方。")
            UsageStep("Q", "预览卡住/黑屏：切到别的 Tab 再切回来，或点「重新预检」。")
            UsageStep("Q", "校准时定位误差大：先确认外参/零偏已标定、时钟已同步、GNSS 真值可信；单帧误差大正常，用多组/多地点复核。")
        }
        ContentSection("结果页怎么用") {
            UsageStep("1", "会话页右上「结果页」→ 勾选采集包；同地点、同一设备/标定是前提，镜头不一致会默认拒绝。")
            UsageStep("2", "只勾 1 个包 →「单包解算」（按钮变蓝）；勾 ≥2 个同地点包 →「联合解算」。")
            UsageStep("3", "有 GNSS 真值才显示误差与 T 级；无真值只显示 1σ/95% 椭圆，并标「未验证」。")
            UsageStep("4", "误差图：中心估计、叉为真值、点为剔除单包复核；比例尺随范围自适应；参考原点可切解算点/真值。")
            UsageStep("5", "系统偏差迁移：用有真值的参考包估 δv，勾选同地点/同设备/同天体后应用；异常参考包可取消勾选。")
            UsageStep("6", "结果可「复制结果」或「导出结果 JSON」；水镜包会显示「水镜采集（诊断项）」，当前只用直射像高度。")
        }
        ContentSection("镜头怎么选（主摄 / 超广角 / 长焦）") {
            UsageStep("路径", "采集页 →「预检」标签 → 相机：主摄 0 / 超广角 3 / 长焦 4（点一下即切换，预览会重启）。")
            UsageStep("默认", "设置 → 高级 → 默认相机（主摄 0 / 超广角 3 / 长焦 4）：进入采集页自动切到该镜头。")
            UsageStep("预设", "设置 → 高级 → 曝光/ISO 预设… → 编辑预设 → 镜头（保持/主摄/超广角/长焦）；采集页点该预设会自动切镜头。")
            UsageStep("识别", "新采集包名会带 camX（如 sun-cam0-session-...）；结果页联合解算会显示各镜头数量，镜头不一致默认拒绝。")
            UsageStep("注意", "不同镜头内参/外参不同：联合解算、偏差迁移请用同一镜头；换镜头后需要各自标定，不能拿主摄外参套广角。")
        }
        ContentSection("水镜采集（测高度 / 无 IMU 垂直基准用）") {
            UsageStep("1", "原理：直射像 + 水面反射像关于水平面对称；两方向之差 = 天顶，不依赖 IMU/GNSS 姿态。")
            UsageStep("2", "准备：找平静水面（水盆/水塘，无风）；量镜头到水面的竖直高度 H（0.2~1 m）。")
            UsageStep("3", "构图：太阳/月亮靠近画面顶部，反射像靠近底部，两者同框；主摄竖直视场约 55°，目标高度 <27° 才容易同框。")
            UsageStep("4", "水盘位置：水平距离 d = H / tan(h)（App 水镜辅助会算）；打开「水镜辅助」看预测框。")
            UsageStep("5", "曝光：太阳直射 1 ms/ISO200 起步（必须 ND 滤镜）；反射像暗，用 20~500 ms 长曝另拍一组；月亮反射用 100~500 ms。")
            UsageStep("6", "看框：水镜辅助里「反射」是检测到的真实倒影，「反射?」是预测位置；两个都出现再拍。")
            UsageStep("7", "局限：波纹会把反射拉成竖亮带（天顶方向会偏 2~4°）；单一朝向的水镜只能测高度，不能校正三维垂直基准。")
        }
        ContentSection("内参标定的适用场景") {
            UsageStep("需要", "相机模组维修/更换、对焦异常、或内参来源不是 calibration（例如只有厂商估算值）；否则优先用已标定内参。")
            UsageStep("不需要", "只是换地点/换天气/换目标：内参是镜头+传感器的物理属性，不变就不用重标。")
            UsageStep("做法", "点阵板放 0.5~1.5 m，画面占 1/3 以上；12 帧覆盖不同角度/位置。点阵 PDF/PNG 在源码 star-positioning/calibration-pattern/，App「标定」页可一键导出到 下载/StarPosition。")
            UsageStep("注意", "当前自标定只拟合 fx/fy/cx/cy（畸变置 0）；强畸变镜头/广角边缘精度有限，星点最好用画面中部。")
        }
        ContentSection("场景处理路径（遇到问题先看这里）") {
            UsageStep("RAW", "相机不支持 RAW（只有 YUV）：App 会自动用 YUV 8-bit。继续拍，但曝光要更保守（避免高光溢出）、不要开 HDR、星点会更少；报告里会标 yuv8，精度低于 RAW，别用这类数据做 0.1° 级结论。")
            UsageStep("相机", "相机打不开/预览黑屏：先「重新预检」→ 切主摄/超广角 → 杀 App 重开；仍失败检查相机权限、是否被别的 App 占用。若是广角/长焦打不开，换主摄继续，别硬等。")
            UsageStep("0匹配", "找不到星/0 匹配：确认「找星」模式；曝光 500 ms~1 s、ISO 3200 起步；避开路灯直射与低空雾霾；转个方向找星多的天区；看到绿圈/编号再拍。")
            UsageStep("曝光", "月亮/太阳过曝：降 ISO 或曝光（月亮 1~2 ms / ISO 400~800；太阳必须装 ND 滤镜，2 ms / ISO 400 起步）。圆面边缘发白就是过曝。")
            UsageStep("GNSS", "没有 GNSS 真值：照样能采集（包里 truth=null）；但只能看绝对位置、无法给误差。需要定位时用两体/双历元路径；不要用 network 定位冒充真值。")
            UsageStep("时钟", "时钟脱网/不可信：状态聚合里点「校时」（自动 NTP 或手动）；实在没网就手动确认 UTC，否则该会话禁止 T1 结论。")
            UsageStep("水镜", "水镜看不到反射：水面要平静；曝光加到 20~500 ms；构图让太阳/月亮与倒影同框（直射在上、反射在下）；按 H 与高度调托盘距离 d=H/tan h。")
            UsageStep("标定", "换了镜头/相机模组：主摄外参不适用于超广角/长焦，必须分别标定（当前只标了主摄）。未标定镜头的数据只能定性看。")
            UsageStep("存储", "存储不足/导出失败：先清旧采集包；导出到「下载」或网盘；采集前确认可用空间 > 1 GB。")
        }
        ContentSection("什么时候需要重新标定") {
            UsageStep("外参", "手机摔过/拆修/换相机模组、或换机后：重做外参。判据：固定场景、真值可信时，星光单历元误差突然从几十 km 跳到 >150 km，且零偏/时钟都正常。")
            UsageStep("零偏", "环境温度与标定时相差 >15 °C、或状态聚合里静止时倾角显示偏 >0.3°：重做六位置零偏（室内 10 分钟）。")
            UsageStep("内参", "相机模组维修/更换、对焦异常、或内参来源不是 calibration：重做点阵内参（12 帧，0.5~1.5 m）。")
            UsageStep("星表", "星表/星历是代码/资产，不需要现场标定；只有换星表资产（如 Gaia J2016.0）时才更新。")
            UsageStep("垂直", "仅星光垂直修正目前是临时标定（同地点 23 帧）；在不同地点验证通过前，不建议按它下强结论。")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UsageStep(tag: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(AppAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(tag, color = AppAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = AppText.copy(alpha = 0.88f), fontSize = 11.sp, lineHeight = 16.sp)
    }
}

// ==================== 状态聚合 ====================

@Composable
private fun StatusContent(
    context: Context,
    state: com.dsh.starpos.android.FieldSessionController.State,
    clockText: String,
    clockOk: Boolean,
    syncMsg: String?,
    syncing: Boolean,
    onSync: () -> Unit,
    imuLive: ImuLive?,
    batteryPct: Int,
    onRefresh: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("系统状态", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row {
                Text(
                    if (syncing) "对时中…" else "校时",
                    color = AppAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = !syncing) { onSync() }.padding(8.dp),
                )
                Text(
                    "刷新",
                    color = AppAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onRefresh() }.padding(8.dp),
                )
            }
        }
        StatusRow("相机", state.cameraLine.ifBlank { "未检查（点刷新）" }, state.cameraLine.isNotBlank())
        StatusRow(
            "内参",
            state.intrinsics?.let {
                "fx=%.1f  fy=%.1f  来源 %s".format(it.fx, it.fy, it.source)
            } ?: "未检查",
            state.intrinsics != null,
        )
        val imuText = when {
            imuLive != null -> "实时 %.0f Hz · |a|=%.2f m/s² · 陀螺 %.3f rad/s（%s）".format(
                imuLive.hz, imuLive.accel, imuLive.gyro, if (imuLive.static) "静止" else "晃动",
            )
            state.imuHz > 0 -> "采集时 %.0f Hz".format(state.imuHz)
            else -> "面板打开后实时采样（1 s 刷新）"
        }
        StatusRow("IMU", imuText, imuLive?.static ?: (state.imuHz > 0))
        StatusRow("外参", state.extrinsicText, state.extrinsicText.contains("已载入"))
        StatusRow(
            "加速度零偏",
            AccelBiasStore.load(context)?.let { b ->
                "(%.1f, %.1f, %.1f) mg · 等效倾斜 %.2f°".format(
                    b.bias[0] * 101.97, b.bias[1] * 101.97, b.bias[2] * 101.97,
                    Math.toDegrees(
                        Math.atan(
                            kotlin.math.sqrt(b.bias[0] * b.bias[0] + b.bias[1] * b.bias[1] + b.bias[2] * b.bias[2]) / 9.80665,
                        ),
                    ),
                )
            } ?: "未标定（采集页 → IMU 零偏标定）",
            AccelBiasStore.load(context)?.calibrated == true,
        )
        StatusRow(
            "GNSS",
            "%s · %s · 卫星 %d".format(
                state.gnssPermission,
                if (state.gnssReady) state.gnssLastFixText else "尚未定位",
                state.gnssSatellites,
            ),
            state.gnssReady,
        )
        StatusRow("时钟", clockText, clockOk)
        if (syncMsg != null) {
            Text(syncMsg, color = if (syncMsg.contains("成功")) AppOk else AppWarn, fontSize = 10.sp)
        }
        StatusRow("电量", "$batteryPct%", batteryPct > 20)
        StatusRow("可用存储", formatBytes(context.getExternalFilesDir(null)?.usableSpace ?: 0), true)
        StatusRow("版本", "${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）", true)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("去采集", onOpenCapture)
            SmallAction("看会话", onOpenSessions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 4.dp).size(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (ok == true) AppOk else if (ok == false) AppWarn else AppDim.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = AppDim, fontSize = 11.sp, modifier = Modifier.width(74.dp))
        Text(value, color = AppText.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(AppAccent.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = AppAccent, fontSize = 12.sp)
    }
}

// ==================== 关于 ====================

@Composable
private fun AboutContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ContentSection("关于") {
            Text("星光定位 Star Position", color = AppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）", color = AppDim, fontSize = 11.sp)
            Text(
                "私有项目，不对外公开分发。用于 GNSS 拒止条件下的星光/天体定位研究与现场数据采集。",
                color = AppDim, fontSize = 11.sp, lineHeight = 16.sp,
            )
        }
        ContentSection("技术栈") {
            Text("Kotlin · Jetpack Compose · Camera2(RAW) · 自研星图识别/定位 · Meeus 星历", color = AppDim, fontSize = 11.sp, lineHeight = 17.sp)
        }
        ContentSection("致谢") {
            Text(
                "本项目由 DeepSeek Harness（DSH）驱动完成：需求讨论、代码实现、真机调试与文档记录均在该环境中进行。",
                color = AppDim, fontSize = 11.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            PoweredByDshBadge()
            Spacer(Modifier.height(6.dp))
            Text(
                "github.com/deepseek-ai/deepseek-harness",
                color = AppAccent.copy(alpha = 0.8f), fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ==================== 通用小组件 ====================

@Composable
private fun ContentSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppAccent.copy(alpha = 0.08f)))
    }
}

@Composable
private fun FormulaLine(text: String) {
    Text(
        text,
        color = AppText.copy(alpha = 0.86f),
        fontSize = 10.5.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 16.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

private fun batteryPercent(context: Context): Int = runCatching {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}.getOrDefault(-1)

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "未知"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
