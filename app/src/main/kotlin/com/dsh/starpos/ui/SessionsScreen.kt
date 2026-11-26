package com.dsh.starpos.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import android.content.ContentValues
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.core.content.FileProvider
import com.dsh.starpos.field.BundleReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话（采集包）列表：查看/分享 ZIP。骨架版，后续加逐帧质量预览、删除、批量导出。 */
@Composable
fun SessionsScreen(onOpenResults: () -> Unit = {}, onOpenCleanup: () -> Unit = {}) {
    val context = LocalContext.current
    val root = remember {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")
    }
    var files by remember { mutableStateOf(listSessions(root)) }
    var stats by remember { mutableStateOf(Storage.stats(context)) }
    var detail by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(AppBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("会话", color = AppText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${files.size} 个包 · ZIP ${Storage.formatBytes(stats.zipBytes)} · 原始 ${Storage.formatBytes(stats.dirBytes)} · 可用 ${Storage.formatBytes(stats.freeBytes)}",
                    color = if (stats.freeBytes < Storage.DANGER_FREE_BYTES) AppBad else AppDim,
                    fontSize = 9.sp, maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "结果页",
                    color = AppAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable { onOpenResults() }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
                Text(
                    "清理",
                    color = AppAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable { onOpenCleanup() }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
                Text(
                    "刷新",
                    color = AppAccent,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable {
                        files = listSessions(root)
                        stats = Storage.stats(context)
                    }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
        if (files.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有采集包", color = AppDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("去「采集」页拍一帧，完成后会出现在这里", color = AppDim.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files) { f ->
                    SessionRow(
                        file = f,
                        onDetail = { detail = f },
                        onShare = { shareZip(context, f) },
                        onExport = { exportZipToDownloads(context, f) },
                        onRename = {
                            renameTarget = f
                            renameText = f.nameWithoutExtension
                            renameError = null
                        },
                    )
                }
            }
        }
    }
    detail?.let { f ->
        SessionDetailDialog(
            file = f,
            onClose = { detail = null },
            onShare = { shareZip(context, f) },
            onExport = { exportZipToDownloads(context, f) },
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名采集包") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = null },
                        singleLine = true,
                        label = { Text("新名称（自动加 .zip）") },
                    )
                    Text(
                        "建议保留 sun-/moon-/star- 前缀：结果页靠它识别数据类型。",
                        color = AppDim, fontSize = 10.sp,
                    )
                    renameError?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 11.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val base = renameText.trim().removeSuffix(".zip")
                    if (base.isEmpty() || base.contains('/') || base.contains('\\')) {
                        renameError = "名称不能为空，且不能包含路径分隔符"
                        return@TextButton
                    }
                    val newZip = File(target.parentFile, "$base.zip")
                    if (newZip.exists()) {
                        renameError = "已存在同名采集包"
                        return@TextButton
                    }
                    val oldDir = File(target.parentFile, target.nameWithoutExtension)
                    val newDir = File(target.parentFile, base)
                    val ok = target.renameTo(newZip)
                    if (!ok) {
                        renameError = "重命名失败（文件被占用？）"
                        return@TextButton
                    }
                    if (oldDir.isDirectory) {
                        if (!newDir.exists()) oldDir.renameTo(newDir)
                    }
                    files = listSessions(root)
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
}

internal fun listSessions(root: File): List<File> =
    root.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

@Composable
private fun SessionRow(file: File, onDetail: () -> Unit, onShare: () -> Unit, onExport: () -> Unit, onRename: () -> Unit) {
    val date = remember(file.lastModified()) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(file.lastModified()))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppCard)
            .border(1.dp, AppAccent.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable { onDetail() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.nameWithoutExtension, color = AppText, fontSize = 13.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                "$date · %.1f MB".format(file.length() / 1024.0 / 1024.0) +
                    (Storage.lensOf(file.name)?.let { " · cam$it" } ?: "") +
                    if (Storage.isExported(file)) " · 已导出" else "",
                color = if (Storage.isExported(file)) AppOk else AppDim, fontSize = 10.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "改名",
                color = AppAccent,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onRename() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "导出",
                color = AppAccent,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExport() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "分享",
                color = AppAccent,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppAccent.copy(alpha = 0.12f))
                    .clickable { onShare() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SessionDetailDialog(file: File, onClose: () -> Unit, onShare: () -> Unit, onExport: () -> Unit) {
    // 只读 manifest；读取失败也不崩
    val info = remember(file.absolutePath) {
        runCatching {
            BundleReader.open(file).use { reader ->
                val m = reader.manifest
                buildString {
                    appendLine("session: ${m.sessionId}")
                    appendLine("时间: ${formatUtc(m.createdAtUtcMs)}")
                    appendLine("版本: ${m.appVersion}")
                    appendLine("帧数: ${m.frames.size}")
                    appendLine("相机: ${m.camera?.usedCameraId ?: "?"} · RAW=${m.camera?.rawSupported}")
                    m.intrinsics?.let {
                        appendLine("内参: fx=%.1f fy=%.1f 来源=%s".format(it.fx, it.fy, it.source))
                    }
                    appendLine("外参: " + (m.gnssTruth?.let { "GNSS 真值 ±%.0f m".format(it.accuracyM) } ?: "无 GNSS 真值"))
                    if (m.note.isNotBlank()) appendLine("备注: ${m.note}")
                }
            }
        }.getOrElse { "读取失败：${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("采集包信息", color = AppText) },
        text = {
            Text(info, color = AppDim, fontSize = 12.sp, lineHeight = 18.sp)
        },
        confirmButton = {
            Row {
                TextButton(onClick = onExport) { Text("导出到下载", color = AppAccent) }
                TextButton(onClick = onShare) { Text("分享 ZIP", color = AppAccent) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭", color = AppDim) }
        },
        containerColor = AppCard,
    )
}

private fun formatUtc(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

private fun shareZip(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "星光定位采集包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "分享采集包"))
        Storage.markExported(file)
    }.onFailure {
        Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}

/** 导出 ZIP 到「下载/StarPosition」（不依赖分享面板）。 */
private fun exportZipToDownloads(context: Context, file: File) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StarPosition")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                Storage.markExported(file)
                Toast.makeText(context, "已导出到 下载/StarPosition/${file.name}", Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(context, "导出失败：无法写入下载目录", Toast.LENGTH_LONG).show()
    }.onFailure {
        Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
    }
}
