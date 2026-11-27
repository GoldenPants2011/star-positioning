// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.JointSolver
import com.dsh.starpos.field.SessionJointAnalyzer
import com.dsh.starpos.field.StarVertical
import com.dsh.starpos.field.Utm
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * 结果页：多包联合定位（无地图）。
 *  - 主坐标 WGS84 经纬度；辅：局部 ENU（参考原点=解算点，有真值时默认真值）、UTM；
 *  - 同时给 1σ 与 95% 概率椭圆；多历元用"剔除单包复核"点画误差图；
 *  - 无真值：只显示椭圆，不显示误差、不给 T 级；
 *  - 系统偏差迁移：手动勾选参考包/同地点/同设备，应用 δv 后重解；
 *  - 与会话页互操作：返回会话列表 / 会话页入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultsScreen(onBack: () -> Unit, onOpenSessions: () -> Unit, backEnabled: Boolean = true) {
    BackHandler(enabled = backEnabled) { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("starpos", android.content.Context.MODE_PRIVATE) }
    val coordDms = remember { prefs.getString("coord_format", "dec") == "dms" }
    val starVertical = remember { prefs.getBoolean("star_vertical", true) }
    val jointFloorDeg = remember { prefs.getFloat("joint_floor_deg", 0.3f).toDouble() }
    val looMax = remember { prefs.getInt("loo_max", 6) }
    val catalogLabel = remember { if ((prefs.getString("star_catalog", "j2000") ?: "j2000") == "j2016") "J2016.0" else "J2000" }
    val root = remember { File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions") }
    val scope = rememberCoroutineScope()

    var pkgs by remember { mutableStateOf<List<Pkg>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sameSite by remember { mutableStateOf(false) }
    var sameDevice by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("选择同地点的太阳/月亮包（新包带 sun-/moon- 前缀）") }
    var extracted by remember { mutableStateOf<Map<String, SessionJointAnalyzer.SessionData>>(emptyMap()) }
    var result by remember { mutableStateOf<JointSolver.Result?>(null) }
    var bias by remember { mutableStateOf<JointSolver.BiasEstimate?>(null) }
    var sessionBias by remember { mutableStateOf<Map<String, JointSolver.BiasEstimate?>>(emptyMap()) }
    var refSel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loo by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var originTruth by remember { mutableStateOf(false) }
    var truthLat by remember { mutableStateOf<Double?>(null) }
    var truthLon by remember { mutableStateOf<Double?>(null) }
    var showAllPacks by remember { mutableStateOf(false) }
    var refInit by remember { mutableStateOf(false) }
    var mixedLensOk by remember { mutableStateOf(false) }
    var manualMirror by remember { mutableStateOf(false) }
    var manualMirrorH by remember { mutableStateOf("0.4") }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                .onSuccess { Toast.makeText(context, "结果已导出", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = listSessions(root)
                .map { f -> Pkg(f, SessionJointAnalyzer.kindOf(f), hasTruth = false, lens = Storage.lensOf(f.name)) }
                .sortedWith(
                    compareBy<Pkg> { if (it.kind == "sun" || it.kind == "moon") 0 else 1 }
                        .thenByDescending { it.file.name },
                )
            withContext(Dispatchers.Main) {
                pkgs = list
                status = "已发现 ${list.size} 个包；选择同地点、同设备的太阳/月亮包（真值在解算时读取）"
            }
        }
    }

    fun selectedFiles() = pkgs.filter { it.file.name in selected }

    fun runSolve(filesIn: List<File>, useTransfer: Boolean, single: Boolean = false) {
        if (running) return
        val files = filesIn
        if (files.isEmpty()) { status = "请先勾选采集包"; return }
        if (!single && files.size < 2) { status = "至少选 2 个包（或改用「单包解算」）"; return }
        if (!sameSite) { status = "请先确认「这些包是同一地点」"; return }
        val lenses = files.mapNotNull { Storage.lensOf(it.name) }.distinct()
        if (lenses.size > 1 && !mixedLensOk) {
            status = "所选包镜头不一致（${lenses.joinToString { "cam$it" }}）：请确认已分别标定，或勾选下方「镜头不一致仍继续」"
            return
        }
        if (useTransfer && !sameDevice) { status = "偏差迁移需要确认「同一设备/同一套标定」"; return }
        running = true
        status = "正在读取 ${files.size} 个包并提取观测…"
        scope.launch {
            val datas = withContext(Dispatchers.IO) {
                files.map { f -> SessionJointAnalyzer.extract(context, f, if (starVertical) com.dsh.starpos.android.DeviceParams.starVertical(context) else null, treatAsMirror = manualMirror) }
            }
            extracted = datas.associateBy { it.file.name }
            val obs = datas.flatMap { it.observations }
            val fixObs = datas.mapNotNull { it.fixObs }
            val skipped = datas.filter { it.observations.isEmpty() && it.fixObs == null }
            if (obs.size < 2 && fixObs.isEmpty()) {
                status = "可用观测不足（高度 ${obs.size} 条 / FixObs ${fixObs.size} 个）" +
                    (skipped.firstOrNull()?.error?.let { "：$it" } ?: "")
                running = false
                return@launch
            }
            val t = datas.mapNotNull { d -> d.truthLatDeg?.let { la -> d.truthLonDeg?.let { lo -> la to lo } } }
            truthLat = t.firstOrNull()?.first
            truthLon = t.firstOrNull()?.second
            if (t.isNotEmpty()) originTruth = true
            val body = obs.map { it.body }.distinct().singleOrNull()
            status = "高度 ${obs.size} 条 + FixObs ${fixObs.size} 个 / ${datas.count { it.observations.isNotEmpty() || it.fixObs != null }} 包，正在解算…"

            // 每个有真值的参考包的 δv
            val sb = HashMap<String, JointSolver.BiasEstimate?>()
            if (t.isNotEmpty()) {
                for (d in datas.filter { it.truthLatDeg != null && it.observations.size >= 3 }) {
                    sb[d.file.name] = JointSolver.estimateVerticalBias(
                        d.observations, Math.toRadians(d.truthLatDeg!!), Math.toRadians(d.truthLonDeg!!), body,
                    )
                }
            }
            sessionBias = sb
            if (!refInit) {
                refSel = sb.keys
                refInit = true
            }

            val options = JointSolver.Options(systemFloorRad = Math.toRadians(jointFloorDeg))
            var r: JointSolver.Result?
            var b: JointSolver.BiasEstimate? = null
            if (useTransfer && refSel.isEmpty()) {
                status = "偏差迁移：请至少勾选一个参考包"
                running = false
                return@launch
            }
            if (useTransfer) {
                val refObs = datas.filter { it.file.name in refSel }.flatMap { it.observations }
                val est = if (t.isNotEmpty()) {
                    JointSolver.estimateVerticalBias(refObs, Math.toRadians(t[0].first), Math.toRadians(t[0].second), body)
                } else null
                b = est
                val corrected = if (est != null) JointSolver.applyVerticalBias(obs, est.verticalBiasRad) else obs
                val floor = Math.toRadians(jointFloorDeg) + (est?.scatterRad ?: 0.0)
                r = JointSolver.solve(
                    corrected,
                    fixObs = fixObs,
                    options = options.copy(systemFloorRad = floor),
                    truthLatRad = t.firstOrNull()?.let { Math.toRadians(it.first) },
                    truthLonRad = t.firstOrNull()?.let { Math.toRadians(it.second) },
                )
            } else {
                r = JointSolver.solve(
                    obs, fixObs = fixObs, options = options,
                    truthLatRad = t.firstOrNull()?.let { Math.toRadians(it.first) },
                    truthLonRad = t.firstOrNull()?.let { Math.toRadians(it.second) },
                )
            }
            bias = b
            result = r
            if (r == null) {
                status = "解算失败：观测几何不足或无法收敛"
                running = false
                return@launch
            }
            status = buildString {
                append("解算完成：高度 ${obs.size} 条 + FixObs ${fixObs.size} 个 / ${r.sessionCount} 包")
                append(if (t.isNotEmpty()) " · 已用 GNSS 真值校验" else " · 无真值，仅内部椭圆")
                if (skipped.isNotEmpty()) append(" · ${skipped.size} 包未参与")
            }

            // 剔除单包复核点（多历元误差图）：最多 6 个，几何退化/失败跳过
            val looPts = ArrayList<Pair<Double, Double>>()
            if (looMax > 0 && datas.count { it.observations.isNotEmpty() || it.fixObs != null } in 2..looMax) {
                for (d in datas.filter { it.observations.isNotEmpty() || it.fixObs != null }) {
                    val rest = obs.filter { it.session != d.file.name }
                    val restFix = fixObs.filter { it.session != d.file.name }
                    if (rest.size + restFix.size < 2) continue
                    val rr = JointSolver.solve(
                        rest, fixObs = restFix, options = options,
                        truthLatRad = t.firstOrNull()?.let { Math.toRadians(it.first) },
                        truthLonRad = t.firstOrNull()?.let { Math.toRadians(it.second) },
                    ) ?: continue
                    if (rr.major95M < 5_000_000) looPts.add(rr.latDeg to rr.lonDeg)
                }
            }
            loo = looPts
            showAllPacks = false
            running = false
        }
    }

    val premiseContent: @Composable () -> Unit = {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                Column(Modifier.padding(12.dp)) {
                    Text("数据与前提（手动确认）", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("无 GNSS 时无法自动判断地点；联合解算只允许同地点、同设备/同一套标定的包。", color = AppDim, fontSize = 10.sp)
                    CheckRow("这些包是同一地点（必须）", sameSite) { sameSite = it }
                    CheckRow("同一设备 / 同一套标定（迁移时必选）", sameDevice) { sameDevice = it }
                    Text(
                        "已选 ${selected.size} 包（解算时会读取每个包的真值与观测）",
                        color = AppAccent, fontSize = 11.sp,
                    )
                    val lensCounts = selectedFiles().mapNotNull { it.lens }.groupingBy { it }.eachCount()
                    val unknownLens = selectedFiles().count { it.lens == null }
                    Text(
                        "镜头：" + (if (lensCounts.isEmpty()) "未知" else lensCounts.entries.joinToString { "cam${it.key}×${it.value}" }) +
                            if (unknownLens > 0) " · 未知×$unknownLens" else "",
                        color = if (lensCounts.size > 1) AppWarn else AppDim, fontSize = 10.sp,
                    )
                    if (lensCounts.size > 1) {
                        Text(
                            "⚠ 所选包镜头不一致：不同镜头有不同外参/内参，混合联合解算会引入系统误差。",
                            color = AppWarn, fontSize = 9.sp, lineHeight = 13.sp,
                        )
                        CheckRow("镜头不一致仍继续（需各自已标定）", mixedLensOk) { mixedLensOk = it }
                    }
                    if (selected.isNotEmpty() && extracted.values.none { it.mirrorMeta }) {
                        CheckRow("这些包按水镜采集处理（手动标记，仅提示）", manualMirror) { manualMirror = it }
                        if (manualMirror) {
                            OutlinedTextField(
                                value = manualMirrorH,
                                onValueChange = { manualMirrorH = it },
                                singleLine = true,
                                label = { Text("镜头高 H（m）", fontSize = 10.sp) },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                    Row {
                        Button(
                            onClick = { runSolve(selectedFiles().map { it.file }, useTransfer = false) }, enabled = !running,
                            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = Color(0xFF0A0E17)),
                        ) { Text(if (running) "解算中…" else "联合解算") }
                        Spacer(Modifier.width(8.dp))
                        if (selected.size == 1) {
                            Button(
                                onClick = { runSolve(selectedFiles().map { it.file }, useTransfer = false, single = true) },
                                enabled = !running,
                                colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = Color(0xFF0A0E17)),
                            ) { Text("单包解算", fontSize = 12.sp) }
                        } else {
                            OutlinedButton(
                                onClick = { status = "单包解算：请只勾选 1 个包" },
                                enabled = !running,
                            ) { Text("单包解算", fontSize = 12.sp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { runSolve(selectedFiles().map { it.file }, useTransfer = true) }, enabled = !running) {
                            Text("迁移重解", color = AppAccent, fontSize = 12.sp)
                        }
                    }
                    Text(status, color = AppDim, fontSize = 10.sp, lineHeight = 14.sp)
                    Text(
                        "当前参数：坐标 ${if (coordDms) "DMS" else "十进制度"} · " +
                            "星垂修正 ${if (starVertical) "开" else "关"} · " +
                            "误差底噪 %.1f° · 剔除复核点 %d · 星表 $catalogLabel".format(jointFloorDeg, looMax),
                        color = if (starVertical) AppDim else AppWarn,
                        fontSize = 9.sp, lineHeight = 13.sp,
                    )
                }
            }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(AppBg)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("← 返回", color = AppAccent, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                Text("结果页 · 联合解算", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("会话页", color = AppAccent, fontSize = 13.sp, modifier = Modifier.clickable { onOpenSessions() }.padding(8.dp))
            }
        
        if (showAllPacks) premiseContent()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(AppBg)) {
            if (!showAllPacks) {
                item { premiseContent() }
            }

        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2A))) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已勾选（${selected.size}）", color = AppAccent, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        if (selected.isNotEmpty()) {
                            Text(
                                "取消全部勾选",
                                color = AppWarn,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    selected = emptySet()
                                    status = "已取消全部勾选"
                                }.padding(6.dp),
                            )
                        }
                    }
                    if (selected.isEmpty()) {
                        Text("尚未勾选采集包", color = AppDim, fontSize = 10.sp)
                    } else {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            selectedFiles().forEach { p ->
                                Row(
                                    Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1A2738))
                                        .clickable { selected = selected - p.file.name }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(p.file.name.removeSuffix(".zip"), color = AppText, fontSize = 10.sp)
                                    Text("×", color = AppWarn, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                    Text("点 × 可从已勾选中移除（采集包窗口保持原顺序）", color = AppDim, fontSize = 9.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("采集包（新包带 sun-/moon- 前缀）", color = AppText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (showAllPacks) "收起" else "展开全部（${pkgs.size}）",
                            color = AppAccent, fontSize = 10.sp,
                            modifier = Modifier.clickable { showAllPacks = !showAllPacks }.padding(6.dp),
                        )
                    }
                    val shown = pkgs.take(if (showAllPacks) 120 else 8)
                    shown.forEach { p ->
                        val checked = p.file.name in selected
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - p.file.name else selected + p.file.name
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                p.file.name.removeSuffix(".zip"),
                                color = if (p.kind == "legacy") AppDim else AppText,
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                p.kind + if (p.hasTruth) " · 真值" else "",
                                color = if (p.hasTruth) AppOk else AppDim, fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        }
        if (sessionBias.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("系统偏差迁移（手动选参考包）", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("参考包必须有可信 GNSS 真值；δv=观测高度−模型高度（鲁棒中位数）。异常包请取消勾选。", color = AppDim, fontSize = 10.sp)
                        sessionBias.entries.sortedBy { it.key }.forEach { (name, est) ->
                            val checked = name in refSel
                            val dv = est?.verticalBiasRad?.let { Math.toDegrees(it) }
                            Row(Modifier.fillMaxWidth().clickable {
                                refSel = if (checked) refSel - name else refSel + name
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(name.removeSuffix(".zip"), color = AppText, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (dv == null) "—" else "δv %+.2f°".format(dv),
                                    color = if (dv != null && kotlin.math.abs(dv) > 1.5) AppWarn else AppDim,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Text(
                            "参考包条件：① 本次会话有 GNSS 真值；② 至少 3 帧高度观测；③ 与目标同一天体；④ 同地点/同设备。" +
                                "每包都可单独勾选/取消；勾选多个时按中位数合并。",
                            color = AppDim, fontSize = 9.sp, lineHeight = 13.sp,
                        )
                        val ineligible = extracted.values.filter { it.file.name in selected && it.file.name !in sessionBias.keys }
                        ineligible.forEach { d ->
                            val why = when {
                                d.truthLatDeg == null -> "无 GNSS 真值"
                                d.fixObs != null -> "星光 FixObs，无高度观测"
                                d.observations.size < 3 -> "高度观测 <3 帧"
                                d.error != null -> d.error!!
                                else -> "不可用"
                            }
                            Text("未列入参考包：${d.file.name.removeSuffix(".zip")}（$why）", color = AppWarn, fontSize = 9.sp)
                        }
                        bias?.let {
                            Text(
                                "当前迁移：δv %+.3f° · 散度 %.3f° · 参考 %d 条".format(
                                    Math.toDegrees(it.verticalBiasRad), Math.toDegrees(it.scatterRad), it.count,
                                ),
                                color = AppAccent, fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
        item {
            val r = result
            if (r != null) {
                val oLat = if (originTruth && truthLat != null) truthLat!! else r.latDeg
                val oLon = if (originTruth && truthLon != null) truthLon!! else r.lonDeg
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("位置", color = AppAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatCoord(r.latDeg, r.lonDeg, coordDms), color = AppText, fontSize = 16.sp)
                        val utm = Utm.fromLatLon(r.latDeg, r.lonDeg)
                        Text("UTM ${Utm.format(utm)}", color = AppDim, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("参考原点：", color = AppDim, fontSize = 10.sp)
                            FilterChip(
                                selected = !originTruth || truthLat == null,
                                onClick = { originTruth = false },
                                label = { Text("解算点", fontSize = 10.sp) },
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = originTruth && truthLat != null,
                                onClick = { if (truthLat != null) originTruth = true },
                                enabled = truthLat != null,
                                label = { Text("GNSS 真值", fontSize = 10.sp) },
                            )
                        }
                        val east = (r.lonDeg - oLon) * 111_320.0 * cos(Math.toRadians(oLat))
                        val north = (r.latDeg - oLat) * 110_540.0
                        Text("局部坐标（相对原点）：东 %+.1f m · 北 %+.1f m".format(east, north), color = AppText, fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("95% 概率椭圆", color = AppAccent, fontSize = 12.sp)
                        Text(
                            "半长轴 %.1f m · 半短轴 %.1f m · 方位 %.0f° · 面积 %.2f km²".format(
                                r.major95M, r.minor95M, r.sigmaAzimuthDeg, r.area95Km2,
                            ),
                            color = AppText, fontSize = 11.sp,
                        )
                        Text(
                            "1σ：%.1f / %.1f m".format(r.sigmaMajorM, r.sigmaMinorM),
                            color = AppDim, fontSize = 10.sp,
                        )
                        Text(
                            if (r.truthErrorKm != null) {
                                "真值误差 %.1f km · T 级 %s".format(r.truthErrorKm, r.tier)
                            } else {
                                "无 GNSS 真值：仅显示内部椭圆，不显示误差、不给 T 级"
                            },
                            color = if (r.truthErrorKm != null && r.truthErrorKm <= 50) AppOk else AppWarn,
                            fontSize = 11.sp,
                        )
                        Text(
                            "残差 RMS %.3f° · 观测 %d 条 · %d 包".format(
                                Math.toDegrees(r.residualRmsRad), r.obsCount, r.sessionCount,
                            ),
                            color = AppDim, fontSize = 10.sp,
                        )
                        Row {
                            TextButton(onClick = {
                                val text = buildResultText(r, oLat, oLon, coordDms, starVertical, jointFloorDeg, looMax)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("星光定位结果", text))
                                Toast.makeText(context, "结果已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("复制结果", color = AppAccent, fontSize = 11.sp) }
                            TextButton(onClick = {
                                pendingExportJson = buildResultJson(r, oLat, oLon)
                                exportLauncher.launch("starpos-result-${System.currentTimeMillis()}.json")
                            }) { Text("导出结果 JSON", color = AppAccent, fontSize = 11.sp) }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("误差图（ENU，不含地图；单包剔除复核点）", color = AppText, fontSize = 12.sp)
                        ErrorPlot(
                            result = r,
                            originLat = oLat, originLon = oLon,
                            truthLat = truthLat, truthLon = truthLon,
                            points = loo,
                        )
                        Text(
                            "点：剔除单包复核（${loo.size} 个）· 叉：真值 · 椭圆：95%（实线）/1σ（虚线）",
                            color = AppDim, fontSize = 9.sp,
                        )
                    }
                }
                val mirrorPkgs = extracted.values.filter { it.mirrorEnabled }
                if (mirrorPkgs.isNotEmpty() || manualMirror) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("水镜采集（诊断项）", color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                (if (mirrorPkgs.isNotEmpty()) "${mirrorPkgs.size} 个包" else "手动标记 ${selected.size} 个包") +
                                    " · 镜头高 H=" + (if (mirrorPkgs.isNotEmpty()) mirrorPkgs.mapNotNull { it.mirrorHeightM }.distinct()
                                    .joinToString { "%.2fm".format(it) } else manualMirrorH + "m") +
                                    "；当前结果使用直射像高度。",
                                color = AppDim, fontSize = 10.sp,
                            )
                            mirrorPkgs.forEach { d ->
                                val rep = d.mirrorReport
                                Text(d.file.name.removeSuffix(".zip"), color = AppText, fontSize = 9.sp)
                                if (rep == null || rep.frames.isEmpty()) {
                                    Text("  · 未检测到成对直射/反射像（需同框、反射足够亮）", color = AppDim, fontSize = 9.sp)
                                } else {
                                    Text(
                                        "  · 有效帧 ${rep.frames.size}；镜像高度 %+.2f° vs IMU %+.2f°（差 %+.2f°）".format(
                                            rep.medianMirrorAltDeg, rep.medianImuAltDeg,
                                            rep.medianMirrorAltDeg - rep.medianImuAltDeg,
                                        ),
                                        color = AppText, fontSize = 9.sp,
                                    )
                                    Text(
                                        "  · 天顶差 %.2f° · 镜像残差 %.2f°".format(rep.medianZenithDiffDeg, rep.medianResidualDeg) +
                                            (rep.medianEphemDiffDeg?.let { " · 与星历差 %+.2f°".format(it) } ?: ""),
                                        color = AppDim, fontSize = 9.sp,
                                    )
                                }
                            }
                            Text(
                                "镜像解出的天顶与 IMU 天顶对照；目前作为**校验项**，尚未作为高度观测进入位置解算。" +
                                    "水镜数据不能替代星光/日月的高度观测（下一阶段再评估独立 LOP）。",
                                color = AppWarn, fontSize = 9.sp, lineHeight = 13.sp,
                            )
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("逐包贡献", color = AppText, fontSize = 12.sp)
                        r.contributions.sortedBy { it.session }.forEach { c ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(c.session.removeSuffix(".zip"), color = AppDim, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                Text("${c.count} 条 · rms %.2f°".format(Math.toDegrees(c.rmsRad)), color = AppDim, fontSize = 9.sp)
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1410))) {
                    Column(Modifier.padding(12.dp)) {
                        r.warnings.forEach { Text("⚠ $it", color = AppWarn, fontSize = 10.sp) }
                        Text("假设：同一地点/同一设备；折射 1010 hPa/10°C；月亮视差已改正；偏差模型为常数 δv。", color = AppDim, fontSize = 9.sp)
                        Row {
                            TextButton(onClick = onOpenSessions) { Text("去会话页", color = AppAccent, fontSize = 11.sp) }
                            TextButton(onClick = onBack) { Text("返回", color = AppAccent, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        }
    }
}

private data class Pkg(val file: File, val kind: String, val hasTruth: Boolean, val lens: String? = null)

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, color = AppText, fontSize = 11.sp)
    }
}

@Composable
private fun ErrorPlot(
    result: JointSolver.Result,
    originLat: Double,
    originLon: Double,
    truthLat: Double?,
    truthLon: Double?,
    points: List<Pair<Double, Double>>,
) {
    val mPerLat = 110_540.0
    val mPerLon = 111_320.0 * cos(Math.toRadians(originLat))
    fun eastOf(latDeg: Double, lonDeg: Double) = (lonDeg - originLon) * mPerLon
    fun northOf(latDeg: Double, lonDeg: Double) = (latDeg - originLat) * mPerLat
    val estEast = eastOf(result.latDeg, result.lonDeg)
    val estNorth = northOf(result.latDeg, result.lonDeg)
    val maxExtent = maxOf(
        result.major95M * 1.4,
        kotlin.math.hypot(estEast, estNorth) + result.major95M * 1.2,
        points.maxOfOrNull { p -> maxOf(kotlin.math.abs(eastOf(p.first, p.second)), kotlin.math.abs(northOf(p.first, p.second))) } ?: 0.0,
        truthLat?.let { t -> maxOf(kotlin.math.abs(eastOf(t, truthLon!!)), kotlin.math.abs(northOf(t, truthLon))) } ?: 0.0,
        1_000.0,
    )
    Canvas(Modifier.fillMaxWidth().height(210.dp).padding(top = 6.dp)) {
        val cx0 = size.width / 2
        val cy0 = size.height / 2
        val half = minOf(size.width, size.height) / 2 * 0.88f
        val scale = half / maxExtent
        // 估计点在"参考原点"坐标系里的位置（原点=解算点时就是中心；原点=真值时偏移）
        val cx = cx0 + (estEast * scale).toFloat()
        val cy = cy0 - (estNorth * scale).toFloat()
        // 坐标轴仍以原点为中心
        val cxA = cx0
        val cyA = cy0
        val axis = Color(0xFF2A3443)
        val grid = Color(0xFF1E2734)
        for (i in -2..2) {
            val x = cxA + i * half / 2
            val y = cyA + i * half / 2
            drawLine(grid, Offset(x, cyA - half), Offset(x, cyA + half), 1f)
            drawLine(grid, Offset(cxA - half, y), Offset(cxA + half, y), 1f)
        }
        drawLine(axis, Offset(cxA - half, cyA), Offset(cxA + half, cyA), 1.5f)
        drawLine(axis, Offset(cxA, cyA - half), Offset(cxA, cyA + half), 1.5f)
        // 椭圆（95% 实线，1σ 虚线风格用两段颜色近似）
        fun ellipsePath(major: Double, minor: Double): Path {
            val p = Path()
            val az = Math.toRadians(result.sigmaAzimuthDeg)
            for (i in 0..96) {
                val t = i / 96.0 * 2 * Math.PI
                val e = (major * cos(t)) * sin(az) + (minor * sin(t)) * cos(az)
                val n = (major * cos(t)) * cos(az) - (minor * sin(t)) * sin(az)
                val x = cx + (e * scale).toFloat()
                val y = cy - (n * scale).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            return p
        }
        drawPath(ellipsePath(result.major95M, result.minor95M), Color(0xFF4D9BFF).copy(alpha = 0.25f))
        drawPath(ellipsePath(result.major95M, result.minor95M), Color(0xFF4D9BFF), style = Stroke(2f))
        drawPath(ellipsePath(result.sigmaMajorM, result.sigmaMinorM), Color(0xFF8FB8FF), style = Stroke(1f))
        // 估计点
        drawCircle(Color(0xFF4D9BFF), 4f, Offset(cx, cy))
        // 真值
        if (truthLat != null && truthLon != null) {
            val tx = cx + (eastOf(truthLat, truthLon) * scale).toFloat()
            val ty = cy - (northOf(truthLat, truthLon) * scale).toFloat()
            drawLine(Color(0xFFFFB84D), Offset(tx - 5, ty - 5), Offset(tx + 5, ty + 5), 2f)
            drawLine(Color(0xFFFFB84D), Offset(tx - 5, ty + 5), Offset(tx + 5, ty - 5), 2f)
        }
        // 剔除单包复核点
        for (p in points) {
            val px = cx + (eastOf(p.first, p.second) * scale).toFloat()
            val py = cy - (northOf(p.first, p.second) * scale).toFloat()
            drawCircle(Color(0xFF7FE0A8), 2.5f, Offset(px, py))
        }
        // 自适应比例尺
        val targetM = maxExtent * 0.35
        val niceM = niceScale(targetM)
        val barPx = (niceM * scale).toFloat()
        val left = 14.dp.toPx()
        val bottom = size.height - 12.dp.toPx()
        drawLine(Color(0xFF9AA5B8), Offset(left, bottom), Offset(left + barPx, bottom), 3f)
        drawLine(Color(0xFF9AA5B8), Offset(left, bottom - 4.dp.toPx()), Offset(left, bottom + 4.dp.toPx()), 2f)
        drawLine(Color(0xFF9AA5B8), Offset(left + barPx, bottom - 4.dp.toPx()), Offset(left + barPx, bottom + 4.dp.toPx()), 2f)
        val label = if (niceM >= 1000) "%.0f km".format(niceM / 1000.0) else "%.0f m".format(niceM)
        val paint = Paint().apply {
            color = Color(0xFF9AA5B8).toArgb()
            textSize = 10.dp.toPx()
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(label, left, bottom - 7.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("N↑", size.width - 26.dp.toPx(), 16.dp.toPx(), paint)
    }
}

private fun niceScale(x: Double): Double {
    if (x <= 0 || x.isNaN()) return 1.0
    val exp = floor(log10(x))
    val f = x / 10.0.pow(exp)
    val n = when {
        f < 1.5 -> 1.0
        f < 3.5 -> 2.0
        f < 7.5 -> 5.0
        else -> 10.0
    }
    return n * 10.0.pow(exp)
}

/** 坐标格式：十进制度 或 度分秒。 */
private fun formatCoord(latDeg: Double, lonDeg: Double, dms: Boolean): String {
    if (!dms) return "%.6f° %s  %.6f° %s".format(kotlin.math.abs(latDeg), if (latDeg >= 0) "N" else "S",
        kotlin.math.abs(lonDeg), if (lonDeg >= 0) "E" else "W")
    fun one(v: Double, pos: String, neg: String): String {
        val sign = if (v >= 0) pos else neg
        val a = kotlin.math.abs(v)
        val d = a.toInt()
        val minFull = (a - d) * 60
        val m = minFull.toInt()
        val s = (minFull - m) * 60
        return "%d°%02d′%04.1f″%s".format(d, m, s, sign)
    }
    return one(latDeg, "N", "S") + "  " + one(lonDeg, "E", "W")
}

/** 结果文本（复制/分享用）。 */
private fun buildResultText(
    r: JointSolver.Result,
    originLat: Double,
    originLon: Double,
    dms: Boolean,
    starVertical: Boolean,
    floorDeg: Double,
    looMax: Int,
): String {
    val east = (r.lonDeg - originLon) * 111_320.0 * cos(Math.toRadians(originLat))
    val north = (r.latDeg - originLat) * 110_540.0
    val utm = Utm.fromLatLon(r.latDeg, r.lonDeg)
    return buildString {
        appendLine("星光定位结果（联合解算）")
        appendLine("WGS84: ${formatCoord(r.latDeg, r.lonDeg, dms)}")
        appendLine("UTM: ${Utm.format(utm)}")
        appendLine("ENU(相对原点): 东 %+.1f m 北 %+.1f m".format(east, north))
        appendLine("95% 椭圆: 半长轴 %.1f m 半短轴 %.1f m 方位 %.0f° 面积 %.3f km²".format(r.major95M, r.minor95M, r.sigmaAzimuthDeg, r.area95Km2))
        appendLine("1σ: %.1f / %.1f m".format(r.sigmaMajorM, r.sigmaMinorM))
        appendLine(if (r.truthErrorKm != null) "真值误差 %.1f km · T级 %s".format(r.truthErrorKm, r.tier) else "无 GNSS 真值（仅内部椭圆）")
        appendLine("观测 %d 条 / %d 包 · 残差 RMS %.3f°".format(r.obsCount, r.sessionCount, Math.toDegrees(r.residualRmsRad)))
        appendLine("参数: 星垂修正=${if (starVertical) "开" else "关"} 底噪=%.1f° 剔除复核点=%d".format(floorDeg, looMax))
        r.warnings.forEach { appendLine("⚠ $it") }
    }
}

private fun buildResultJson(r: JointSolver.Result, originLat: Double, originLon: Double): String {
    val o = JSONObject()
    o.put("lat_deg", r.latDeg)
    o.put("lon_deg", r.lonDeg)
    o.put("utm", Utm.format(Utm.fromLatLon(r.latDeg, r.lonDeg)))
    o.put("origin_lat_deg", originLat)
    o.put("origin_lon_deg", originLon)
    o.put("enu_east_m", (r.lonDeg - originLon) * 111_320.0 * cos(Math.toRadians(originLat)))
    o.put("enu_north_m", (r.latDeg - originLat) * 110_540.0)
    o.put("ellipse_1sigma_major_m", r.sigmaMajorM)
    o.put("ellipse_1sigma_minor_m", r.sigmaMinorM)
    o.put("ellipse_95_major_m", r.major95M)
    o.put("ellipse_95_minor_m", r.minor95M)
    o.put("ellipse_azimuth_deg", r.sigmaAzimuthDeg)
    o.put("area_95_km2", r.area95Km2)
    o.put("residual_rms_deg", Math.toDegrees(r.residualRmsRad))
    o.put("obs_count", r.obsCount)
    o.put("session_count", r.sessionCount)
    r.truthErrorKm?.let { o.put("truth_error_km", it) }
    r.tier?.let { o.put("tier", it) }
    o.put("warnings", r.warnings.joinToString("; "))
    return o.toString(2)
}
