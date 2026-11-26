package com.dsh.starpos.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dsh.starpos.BuildConfig
import com.dsh.starpos.android.DiagLog
import java.io.File

/** 设置（骨架）：外观、采集准备、诊断、关于。 */
@Composable
fun SettingsScreen(
    nightMode: Boolean,
    onNightModeChange: (Boolean) -> Unit,
    colorAssist: Boolean,
    onColorAssistChange: (Boolean) -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("starpos", android.content.Context.MODE_PRIVATE) }
    var autoNtp by remember { mutableStateOf(prefs.getBoolean("auto_ntp", true)) }
    var coordDms by remember { mutableStateOf(prefs.getString("coord_format", "dec") == "dms") }
    var starVertical by remember { mutableStateOf(prefs.getBoolean("star_vertical", true)) }
    var jointFloor by remember { mutableStateOf(prefs.getFloat("joint_floor_deg", 0.3f)) }
    var looMax by remember { mutableStateOf(prefs.getInt("loo_max", 6)) }
    var ntpHost by remember { mutableStateOf(prefs.getString("ntp_host", "ntp.aliyun.com") ?: "ntp.aliyun.com") }
    var showNtp by remember { mutableStateOf(false) }
    var ntpCustom by remember { mutableStateOf("") }
    var defaultCamera by remember { mutableStateOf(prefs.getString("default_camera", "0") ?: "0") }
    var starCatalog by remember { mutableStateOf(prefs.getString("star_catalog", "j2000") ?: "j2000") }
    var bortle by remember { mutableStateOf(prefs.getInt("bortle", 5)) }
    var ntpTestMsg by remember { mutableStateOf<String?>(null) }
    var ntpTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(AppBg)) {
        Text(
            "设置",
            color = AppText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            GroupTitle("外观")
            ToggleRow(
                title = "夜视模式（琥珀）",
                subtitle = "减少蓝光、保护暗适应；全 App 统一（首页/采集/会话/结果/设置）",
                checked = nightMode,
                onChange = onNightModeChange,
            )
            ToggleRow(
                title = "色觉辅助（蓝/橙）",
                subtitle = "用蓝/橙/黄替代红绿状态色，便于色觉异常用户区分",
                checked = colorAssist,
                onChange = onColorAssistChange,
            )
            GroupTitle("诊断")
            ActionRow("自检（合成用例，真值已知）", "开发/验收用，验证解算链路", onOpenSelfCheck)
            ActionRow("查看会话", "采集包列表、分享/导出 ZIP", onOpenSessions)
            ActionRow("浏览诊断日志", "查看最近 2000 行（按级别着色）") { onOpenLogs() }
            ActionRow("分享诊断日志", "最近日志文件，用于排查卡帧/闪退") { shareDiagLog(context) }
            GroupTitle("高级")
            ToggleRow(
                title = "自动 NTP 对时",
                subtitle = "打开状态聚合时，若时钟陈旧且网络可用则自动对时一次",
                checked = autoNtp,
                onChange = {
                    autoNtp = it
                    prefs.edit().putBoolean("auto_ntp", it).apply()
                },
            )
            ActionRow(
                title = "NTP 服务器",
                subtitle = "当前：$ntpHost（点开选择长期可用服务器）",
            ) { showNtp = true }
            ToggleRow(
                title = "星光垂直修正（StarVertical）",
                subtitle = "由星光+GNSS真值标定的设备系小倾角（默认开；关掉用于对照）",
                checked = starVertical,
                onChange = {
                    starVertical = it
                    prefs.edit().putBoolean("star_vertical", it).apply()
                },
            )
            ChoiceRow(
                title = "默认相机",
                subtitle = "进入采集页时自动切换（预设也可单独指定镜头）",
                options = listOf("主摄 0" to "0", "超广角 3" to "3", "长焦 4" to "4"),
                selected = defaultCamera,
                onSelect = {
                    defaultCamera = it
                    prefs.edit().putString("default_camera", it).apply()
                },
            )
            ChoiceRow(
                title = "坐标格式",
                subtitle = "结果页经纬度显示方式（不影响解算）",
                options = listOf("十进制度" to "dec", "度分秒 DMS" to "dms"),
                selected = if (coordDms) "dms" else "dec",
                onSelect = {
                    coordDms = it == "dms"
                    prefs.edit().putString("coord_format", it).apply()
                },
            )
            ChoiceRow(
                title = "联解系统误差底噪",
                subtitle = "更大的底噪更保守（椭圆更大）；默认 0.3°",
                options = listOf("0.1°" to "0.1", "0.2°" to "0.2", "0.3°" to "0.3", "0.5°" to "0.5", "1.0°" to "1.0"),
                selected = "%.1f".format(jointFloor),
                onSelect = {
                    jointFloor = it.toFloat()
                    prefs.edit().putFloat("joint_floor_deg", jointFloor).apply()
                },
            )
            ChoiceRow(
                title = "结果页剔除复核点",
                subtitle = "每次剔除一包做复核的一致性点数量（0=关闭，越大越慢）",
                options = listOf("0" to "0", "3" to "3", "6" to "6", "12" to "12"),
                selected = looMax.toString(),
                onSelect = {
                    looMax = it.toInt()
                    prefs.edit().putInt("loo_max", looMax).apply()
                },
            )
            ActionRow("曝光/ISO 预设…", "新增/编辑/删除，导入导出 JSON（换机备份）") { onOpenPresets() }
            ChoiceRow(
                title = "光污染等级（Bortle）",
                subtitle = "影响对准辅助里的「肉眼可见星」估计；1=极暗 9=市区",
                options = listOf("1 极暗" to "1", "3 乡村" to "3", "5 郊区" to "5", "7 城市" to "7", "9 市区" to "9"),
                selected = bortle.toString(),
                onSelect = {
                    bortle = it.toInt()
                    prefs.edit().putInt("bortle", bortle).apply()
                },
            )
            ChoiceRow(
                title = "星表版本",
                subtitle = if (starCatalog == "j2016") "Gaia 参考历元（J2016.0，ICRS）；与 J2000+自行等价，用于对照/换表试验"
                else "默认：HYG v41 J2000（含自行；约 2000–2050 适用）",
                options = listOf("J2000" to "j2000", "J2016.0" to "j2016"),
                selected = starCatalog,
                onSelect = {
                    starCatalog = it
                    prefs.edit().putString("star_catalog", it).apply()
                },
            )
            GroupTitle("数据与隐私")
            ActionRow("清理会话与存储…", "查看占用、删原始目录、ZIP 回收站") { onOpenCleanup() }
            InfoRow("存储位置", "外部私有目录 sessions/（不联网、不公开分发）")
            InfoRow("数据用途", "仅本机保存 + 手动导出/分享；不会上传")
            GroupTitle("关于")
            InfoRow("应用", "星光定位 Star Position")
            InfoRow("版本", "${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
            InfoRow("仓库", "私有 Git 仓库，不对外公开")
            Spacer(Modifier.height(10.dp))
            Text(
                "由 DeepSeek Harness（DSH）驱动完成：需求、实现、真机调试与文档均在该环境下进行。",
                color = AppDim, fontSize = 10.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    if (showNtp) {
        val servers = listOf(
            "ntp.aliyun.com" to "阿里云 NTP（国内长期）",
            "cn.pool.ntp.org" to "中国 NTP 池",
            "asia.pool.ntp.org" to "亚洲 NTP 池",
            "pool.ntp.org" to "全球 NTP 池",
            "time.cloudflare.com" to "Cloudflare",
            "time.google.com" to "Google（国内可能不可达）",
        )
        AlertDialog(
            onDismissRequest = { showNtp = false },
            title = { Text("NTP 服务器") },
            text = {
                Column {
                    servers.forEach { (host, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                ntpHost = host
                                prefs.edit().putString("ntp_host", host).apply()
                                showNtp = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = ntpHost == host, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(label, color = AppText, fontSize = 12.sp)
                                Text(host, color = AppDim, fontSize = 9.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = ntpCustom,
                        onValueChange = { ntpCustom = it },
                        singleLine = true,
                        label = { Text("自定义服务器", fontSize = 10.sp) },
                    )
                    ntpTestMsg?.let {
                        Text(it, color = if (it.startsWith("成功")) AppOk else AppWarn, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        enabled = !ntpTesting,
                        onClick = {
                            val host = ntpCustom.trim().ifEmpty { ntpHost }
                            ntpTesting = true
                            ntpTestMsg = "测试中：$host …"
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    runCatching { com.dsh.starpos.core.NtpClient.query(host, 2500) }.getOrNull()
                                }
                                ntpTesting = false
                                ntpTestMsg = if (res != null) {
                                    "成功：偏差 ${res.offsetMs} ms，往返 ${res.rttMs} ms（${res.server}）"
                                } else {
                                    "失败：$host 无响应/不可达"
                                }
                            }
                        },
                    ) { Text(if (ntpTesting) "测试中…" else "测试连接") }
                    TextButton(onClick = {
                        val v = ntpCustom.trim()
                        if (v.isNotEmpty()) {
                            ntpHost = v
                            prefs.edit().putString("ntp_host", v).apply()
                        }
                        showNtp = false
                        ntpTestMsg = null
                    }) { Text("完成") }
                }
            },
            dismissButton = { TextButton(onClick = { showNtp = false }) { Text("取消") } },
        )
    }
}
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        color = AppAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = AppCard,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppText, fontSize = 13.sp)
                Text(subtitle, color = AppDim, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = AppCard,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppText, fontSize = 13.sp)
                Text(subtitle, color = AppDim, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Text("›", color = AppDim, fontSize = 18.sp)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = AppDim, fontSize = 12.sp)
        Text(value, color = AppText.copy(alpha = 0.9f), fontSize = 11.sp)
    }
}

private fun shareDiagLog(context: Context) {
    runCatching {
        val f: File? = DiagLog.file()
        if (f == null || !f.exists()) return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "星光定位诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "分享诊断日志"))
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        color = AppCard,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, color = AppText, fontSize = 13.sp)
            Text(subtitle, color = AppDim, fontSize = 10.sp, lineHeight = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                options.forEach { (label, value) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        label = { Text(label, fontSize = 10.sp) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }
    }
}
