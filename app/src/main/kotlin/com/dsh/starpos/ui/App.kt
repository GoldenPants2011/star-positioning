// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.starpos.android.AndroidTimeSource
import com.dsh.starpos.android.Assets
import com.dsh.starpos.android.SharedPreferencesStore
import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0A0E17)
private val CardBg = Color(0xFF141A26)
private val Accent = Color(0xFF7FB2FF)
private val Warn = Color(0xFFFFB020)
private val Ok = Color(0xFF57D39B)
private val Bad = Color(0xFFFF6B6B)

private enum class AppTab(val title: String, val glyph: String) {
    HOME("首页", "⌂"),
    CAPTURE("采集", "◎"),
    SESSIONS("会话", "▤"),
    RESULTS("结果", "◈"),
    SETTINGS("设置", "⚙"),
}

@Composable
fun StarPosApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AppTab.HOME) }
    var showSelfCheck by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("starpos", android.content.Context.MODE_PRIVATE) }
    var night by remember { mutableStateOf(prefs.getBoolean("night", false)) }
    var colorAssist by remember { mutableStateOf(prefs.getBoolean("color_assist", false)) }

    applyTheme(night, colorAssist)
    val bg = AppBg
    val card = AppCard
    val accent = AppAccent

    MaterialTheme(colorScheme = darkColorScheme(background = bg, surface = card, primary = accent)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            key(night, colorAssist) {
            if (showSelfCheck) {
                SelfCheckScreen(onBack = { showSelfCheck = false })
            } else if (showLogs) {
                LogBrowserScreen(onBack = { showLogs = false })
            } else if (showCleanup) {
                CleanupScreen(onBack = { showCleanup = false })
            } else if (showPresets) {
                PresetScreen(onBack = { showPresets = false })
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            AppTab.HOME -> HomeScreen(
                                onOpenCapture = { tab = AppTab.CAPTURE },
                                onOpenSessions = { tab = AppTab.SESSIONS },
                            )
                            AppTab.CAPTURE -> FieldScreen(onOpenCleanup = { showCleanup = true })
                            AppTab.SESSIONS -> SessionsScreen(onOpenResults = { tab = AppTab.RESULTS }, onOpenCleanup = { showCleanup = true })
                            AppTab.RESULTS -> ResultsScreen(
                                onBack = { tab = AppTab.SESSIONS },
                                onOpenSessions = { tab = AppTab.SESSIONS },
                            )
                            AppTab.SETTINGS -> SettingsScreen(
                                nightMode = night,
                                onNightModeChange = { night = it; prefs.edit().putBoolean("night", it).apply() },
                                colorAssist = colorAssist,
                                onColorAssistChange = {
                                    colorAssist = it
                                    prefs.edit().putBoolean("color_assist", it).apply()
                                },
                                onOpenSelfCheck = { showSelfCheck = true },
                                onOpenLogs = { showLogs = true },
                                onOpenPresets = { showPresets = true },
                                onOpenCleanup = { showCleanup = true },
                                onOpenCapture = { tab = AppTab.CAPTURE },
                                onOpenSessions = { tab = AppTab.SESSIONS },
                            )
                        }
                    }
                    NavigationBar(containerColor = card, contentColor = accent) {
                        AppTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Text(
                                        t.glyph,
                                        fontSize = 18.sp,
                                        color = if (tab == t) accent else AppDim,
                                    )
                                },
                                label = { Text(t.title, fontSize = 10.sp) },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
internal fun SelfCheckScreen(onBack: (() -> Unit)? = null) {
    // 系统返回键也回到设置，避免"进入自检后出不来"
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf<List<DemoRun>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var showJson by remember { mutableStateOf(false) }
    var showBudget by remember { mutableStateOf(false) }
    var clockStatus by remember { mutableStateOf<ClockStatus?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    val clockMonitor = remember {
        ClockMonitor(AndroidTimeSource(context), SharedPreferencesStore(context))
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val catalog = Assets.loadCatalog(context)
                val camera = CameraIntrinsics.fromFov(1080, 1440, 30.0)
                val runner = DemoRunner(catalog, camera)
                clockStatus = clockMonitor.status()
                val clock = if (clockStatus!!.stale) syncedClockStatus(offlineHours = 200.0, stale = true)
                else syncedClockStatus(uncertaintyMs = 250.0)
                runs = DemoRunner.defaultScenes().map { runner.run(it, clock) }
            } catch (t: Throwable) {
                loadError = "${t::class.java.simpleName}: ${t.message}"
            } finally {
                loading = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg, primary = Accent)) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                if (onBack != null) {
                    Text(
                        "← 返回",
                        color = Accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable { onBack() }.padding(bottom = 12.dp),
                    )
                }
                Text("星光定位", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "GNSS 拒止下的星图定位 · M1 合成数据自检",
                    fontSize = 12.sp, color = Color(0xFF8A93A6), modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (loading) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("正在加载内置星表…", color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                        }
                    }
                    return@Column
                }
                loadError?.let {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1414))) {
                        Column(Modifier.padding(16.dp)) {
                            Text("初始化失败", color = Bad, fontWeight = FontWeight.Bold)
                            Text(it, color = Color(0xFFE0B4B4), fontSize = 12.sp)
                        }
                    }
                    return@Column
                }

                // 时钟状态
                ClockCard(clockStatus, syncing) {
                    scope.launch {
                        syncing = true
                        syncMessage = null
                        val res = withContext(Dispatchers.IO) { NtpClient.query() }
                        if (res == null) {
                            syncMessage = "对时失败：无网络或 NTP 不可达（离线时请手动确认 UTC）"
                        } else {
                            clockMonitor.recordSync(res.offsetMs)
                            clockStatus = clockMonitor.status()
                            syncMessage = "对时成功：偏差 ${res.offsetMs} ms，往返 ${res.rttMs} ms"
                        }
                        syncing = false
                    }
                }
                syncMessage?.let {
                    Text(it, color = Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(Modifier.height(12.dp))

                // 用例选择
                Text("合成用例（真值已知）", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    runs.forEachIndexed { idx, run ->
                        val label = "#${idx + 1} ${run.scene.latDeg.toInt()},${run.scene.lonDeg.toInt()}" +
                            if (run.scene.cloudCover > 0.5) " 厚云" else ""
                        Button(
                            onClick = { selected = idx; showJson = false; showBudget = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (idx == selected) Accent else CardBg,
                                contentColor = if (idx == selected) Color(0xFF0A0E17) else Color(0xFFB8C0D0),
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }

                val run = runs.getOrNull(selected)
                if (run != null) {
                    Spacer(Modifier.height(12.dp))
                    ResultCard(run)
                    Spacer(Modifier.height(10.dp))
                    GuidanceCard(run.outcome.result)
                    Spacer(Modifier.height(10.dp))
                    DiagnosticsCard(run)

                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(
                            onClick = { showJson = !showJson },
                            colors = ButtonDefaults.buttonColors(containerColor = CardBg, contentColor = Accent),
                        ) { Text(if (showJson) "隐藏 JSON" else "查看 JSON") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { showBudget = !showBudget },
                            colors = ButtonDefaults.buttonColors(containerColor = CardBg, contentColor = Accent),
                        ) { Text(if (showBudget) "隐藏误差预算" else "误差预算") }
                    }
                    if (showJson) {
                        Spacer(Modifier.height(8.dp))
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1220))) {
                            Text(
                                run.outcome.result.toJson(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFBFD0E8),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    if (showBudget) {
                        Spacer(Modifier.height(8.dp))
                        BudgetCard(run.outcome.budget)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.height(10.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = Color(0xFF8A93A6), fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ClockCard(clock: ClockStatus?, syncing: Boolean, onSync: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            CardTitle("时间新鲜度（FR-06）")
            Spacer(Modifier.height(6.dp))
            if (clock == null) {
                Text("检查中…", color = Color.White)
            } else {
                val off = if (clock.offlineHours.isInfinite()) "从未对时" else "%.1f 小时".format(clock.offlineHours)
                Text(
                    "脱网 $off · 钟差不确定 ±%.0f ms".format(clock.clockUncertaintyMs),
                    color = if (clock.stale) Bad else Ok, fontSize = 14.sp,
                )
                Text(
                    "自动时间 ${if (clock.autoTimeEnabled) "开" else "关"} · 网络 ${if (clock.networkAvailable) "有" else "无"}",
                    color = Color(0xFF9AA5B8), fontSize = 12.sp,
                )
                if (clock.warnings.isNotEmpty()) {
                    Text("警告：" + clock.warnings.joinToString(", "), color = Warn, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSync, enabled = !syncing,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0A0E17))) {
                Text(if (syncing) "对时中…" else "网络对时（SNTP）")
            }
        }
    }
}

@Composable
private fun ResultCard(run: DemoRun) {
    val r = run.outcome.result
    val isFix = r.isFix()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isFix) "FIX" else "NO_FIX",
                    color = if (isFix) Ok else Bad, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(10.dp))
                if (r.tier != null) {
                    Surface(color = Color(0xFF24304A), shape = RoundedCornerShape(6.dp)) {
                        Text(r.tier, color = Accent, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text("置信度 %.2f".format(r.confidence),
                    color = if (r.confidence >= 0.5) Color(0xFFCFE0FF) else Bad, fontSize = 13.sp)
            }
            if (!isFix) {
                Text("原因：${reasonCn(r.reason)}", color = Warn, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (r.position != null && r.ellipse != null) {
                val truthLat = run.scene.latDeg
                val truthLon = run.scene.lonDeg
                val dLatKm = (r.position.latDeg - truthLat) * 111.32
                val dLonKm = (r.position.lonDeg - truthLon) * 111.32 *
                    kotlin.math.cos(Math.toRadians(truthLat))
                val err = kotlin.math.sqrt(dLatKm * dLatKm + dLonKm * dLonKm)
                Text("纬度 %.4f°  经度 %.4f°".format(r.position.latDeg, r.position.lonDeg),
                    color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "95%% 椭圆 %.0f × %.0f km · 真值偏差 %.1f km".format(
                        r.ellipse.semiMajorM / 1000, r.ellipse.semiMinorM / 1000, err,
                    ),
                    color = Color(0xFF9AA5B8), fontSize = 12.sp,
                )
            }
            Text(
                "用星 ${r.starsUsed} · 姿态 3σ %.3f° · 重力 σ %.3f° · 云量 %.0f%%".format(
                    r.attitudeSigmaDeg * 3, r.gravitySigmaDeg, r.cloudCover * 100,
                ),
                color = Color(0xFF7E889C), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (r.warnings.isNotEmpty()) {
                Text("告警：" + r.warnings.joinToString(", "), color = Warn, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun GuidanceCard(r: SolveResult) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2A))) {
        Column(Modifier.padding(14.dp)) {
            CardTitle("怎么拍（FR-14：只说人话）")
            Spacer(Modifier.height(6.dp))
            Text(guidanceCn(r), color = Color(0xFFD6E4FF), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "提示：转身在 2–3 个方向各拍一组（间隔 ≥60°）；T2 需要让地平线出现在画面下部。",
                color = Color(0xFF7E889C), fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(run: DemoRun) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            CardTitle("诊断")
            Spacer(Modifier.height(4.dp))
            Text("渲染星数 ${run.frame.starsRendered} · 检测 ${run.detections.detections.size} · 匹配 ${run.outcome.plate?.matchedStars ?: 0}",
                color = Color(0xFF9AA5B8), fontSize = 12.sp)
            Text("PSF 最长宽比 %.2f · 饱和 %d · 背景 %.3f · 噪声 %.4f".format(
                run.detections.quality.maxPsfAspect, run.detections.quality.saturatedCount,
                run.detections.quality.background, run.detections.quality.noiseSigma),
                color = Color(0xFF9AA5B8), fontSize = 12.sp)
            run.outcome.plate?.let {
                Text("匹配残差 RMS %.2f″".format(it.rmsArcsec), color = Color(0xFF9AA5B8), fontSize = 12.sp)
            }
            Text("星表 ${run.frame.context.let { "GAST %.5f°" }.format(Math.toDegrees(run.frame.context.gast))}",
                color = Color(0xFF6E7889), fontSize = 11.sp)
        }
    }
}

@Composable
private fun BudgetCard(rows: List<BudgetRow>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1220))) {
        Column(Modifier.padding(12.dp)) {
            Text("误差预算（§8）", color = Accent, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) Text("无（NO_FIX 时不计预算）", color = Color(0xFF9AA5B8), fontSize = 12.sp)
            rows.forEach { row ->
                Text(
                    "· ${row.source}：${row.typical} → ${"%.1f".format(row.groundMeters / 1000)} km",
                    color = Color(0xFFBFD0E8), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text("    ${row.note}", color = Color(0xFF6E7889), fontSize = 10.sp)
            }
        }
    }
}

private fun reasonCn(reason: String?): String = when (reason) {
    "TOO_FEW_STARS" -> "画面里星星太少"
    "CLOUD_COVER_TOO_HIGH" -> "云太厚，星被挡住"
    "TRAILING_DETECTED" -> "照片有拖尾（手机晃了）"
    "PLATE_SOLVE_FAILED" -> "没能认出星图"
    "TOO_FEW_MATCHED_STARS" -> "认出的星不够"
    "ATTITUDE_COVARIANCE_EXCEEDED" -> "姿态精度不够"
    "NOT_STATIC" -> "手机在动"
    "STALE_TIME" -> "手机时间可能不准"
    "LOW_CONFIDENCE" -> "结果不够可靠"
    else -> reason ?: "未知"
}

private fun guidanceCn(r: SolveResult): String = when (r.reason) {
    "TOO_FEW_STARS" -> "对准更开阔的天空，确认镜头没被挡住；也可以转身换个方向再拍一组。"
    "CLOUD_COVER_TOO_HIGH" -> "等云过去，或者往云缝、别处走几步再拍。"
    "TRAILING_DETECTED" -> "把手机放稳：靠墙、靠着栏杆或放地上，手别抖。"
    "NOT_STATIC" -> "保持不动：站稳，手机别晃，等 2 秒再拍。"
    "STALE_TIME" -> "请先连一下网自动对时（或手动校准时间），否则算出来的位置会偏。"
    "ATTITUDE_COVARIANCE_EXCEEDED", "TOO_FEW_MATCHED_STARS", "PLATE_SOLVE_FAILED" ->
        "多拍几张，转身换方位再拍一组；擦一下镜头，避开路灯直射。"
    "LOW_CONFIDENCE" -> "这次不太靠谱：换个更暗、更开阔的地方再试。"
    else -> if (r.isFix()) "已定位。想更准的话：把手机放稳别动，多拍几张。" else "再试一次。"
}

