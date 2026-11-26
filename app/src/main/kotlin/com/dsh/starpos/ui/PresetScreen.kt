package com.dsh.starpos.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 曝光/ISO 预设管理：新增/编辑/删除、导入/导出 JSON、恢复默认。 */
@Composable
fun PresetScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var presets by remember { mutableStateOf(ExposurePresets.load(context)) }
    var editing by remember { mutableStateOf<ExposurePreset?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var msg by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val parsed = ExposurePresets.fromJson(text) ?: error("JSON 里没有有效预设")
            presets = parsed
            ExposurePresets.save(context, parsed)
            msg = "已导入 ${parsed.size} 个预设"
        }.onFailure { msg = "导入失败：${it.message}" }
    }

    fun export() {
        runCatching {
            val json = ExposurePresets.toJson(presets)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "starpos-exposure-presets.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StarPosition")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    msg = "已导出到 下载/StarPosition/starpos-exposure-presets.json"
                    return
                }
            }
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, json)
                putExtra(Intent.EXTRA_SUBJECT, "星光定位 曝光/ISO 预设")
            }
            context.startActivity(Intent.createChooser(i, "导出预设"))
        }.onFailure { msg = "导出失败：${it.message}" }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(AppBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← 返回", color = AppAccent, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("曝光/ISO 预设", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("＋", color = AppAccent, fontSize = 20.sp, modifier = Modifier.clickable {
                editing = ExposurePreset("新预设", 1_000_000_000L, 3200, 1)
                editIndex = -1
            }.padding(8.dp))
        }
        Text(
            "预设会出现在采集页「采集参数与预览」的快捷行；JSON 可备份/换机导入。",
            color = AppDim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
        msg?.let { Text(it, color = AppOk, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text("导入 JSON", color = AppAccent, fontSize = 11.sp)
            }
            TextButton(onClick = { export() }) { Text("导出 JSON", color = AppAccent, fontSize = 11.sp) }
            TextButton(onClick = {
                presets = ExposurePresets.DEFAULTS
                ExposurePresets.save(context, presets)
                msg = "已恢复默认 ${presets.size} 个预设"
            }) { Text("恢复默认", color = AppWarn, fontSize = 11.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(presets) { p ->
                val idx = presets.indexOf(p)
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .clickable { editing = p; editIndex = idx },
                    color = AppCard, shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = AppText, fontSize = 13.sp)
                            Text("${p.exposureText} · ISO ${p.iso} · ${p.modeText} · ${p.cameraText}", color = AppDim, fontSize = 10.sp)
                        }
                        Text("删除", color = AppBad, fontSize = 11.sp, modifier = Modifier.clickable {
                            presets = presets.filterIndexed { i, _ -> i != idx }
                            ExposurePresets.save(context, presets)
                        }.padding(8.dp))
                    }
                }
            }
        }
    }

    editing?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        var expMs by remember(p) { mutableStateOf((p.exposureNs / 1_000_000.0).toString()) }
        var iso by remember(p) { mutableStateOf(p.iso.toString()) }
        var mode by remember(p) { mutableStateOf(p.mode) }
        var cameraId by remember(p) { mutableStateOf(p.cameraId) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (editIndex < 0) "新增预设" else "编辑预设") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(expMs, { expMs = it }, label = { Text("曝光（毫秒）") }, singleLine = true)
                    OutlinedTextField(iso, { iso = it }, label = { Text("ISO") }, singleLine = true)
                    Text("模式", color = AppDim, fontSize = 10.sp)
                    Row {
                        listOf(1 to "找星", 3 to "月亮", 4 to "太阳", 5 to "白天月亮").forEach { (v, label) ->
                            FilterChip(selected = mode == v, onClick = { mode = v }, label = { Text(label, fontSize = 9.sp) })
                        }
                    }
                    Text("镜头", color = AppDim, fontSize = 10.sp)
                    Row {
                        listOf(null to "保持镜头", "0" to "主摄", "3" to "超广角", "4" to "长焦").forEach { (v, label) ->
                            FilterChip(selected = cameraId == v, onClick = { cameraId = v }, label = { Text(label, fontSize = 9.sp) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = expMs.toDoubleOrNull() ?: return@TextButton
                    val isv = iso.toIntOrNull() ?: return@TextButton
                    val np = ExposurePreset(
                        name.ifBlank { "未命名" }, (e * 1_000_000).toLong().coerceAtLeast(1000L),
                        isv.coerceIn(50, 12800), mode, cameraId,
                    )
                    presets = if (editIndex < 0) presets + np else presets.toMutableList().also { it[editIndex] = np }
                    ExposurePresets.save(context, presets)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}
