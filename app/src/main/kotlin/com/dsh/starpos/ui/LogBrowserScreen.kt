package com.dsh.starpos.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dsh.starpos.android.DiagLog
import java.io.File

/** 诊断日志浏览：最近 3000 行，按级别着色；可刷新/分享。 */
@Composable
fun LogBrowserScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    fun loadLines(): List<String> {
        val mem = DiagLog.tail(2000)
        val f = DiagLog.file()
        val fromFile = runCatching {
            f?.readLines()?.takeLast(2000)
        }.getOrNull().orEmpty()
        return if (fromFile.size >= mem.size) fromFile else mem
    }
    var lines by remember { mutableStateOf(loadLines()) }
    val file: File? = DiagLog.file()
    var filter by remember { mutableStateOf(0) }
    val shown = when (filter) {
        1 -> lines.filter { it.contains(" W/") || it.contains(" E/") }
        2 -> lines.filterNot { it.contains("preview ticks") }
        else -> lines
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().background(AppBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← 返回", color = AppAccent, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("诊断日志", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("刷新", color = AppAccent, fontSize = 12.sp, modifier = Modifier.clickable { lines = loadLines() }.padding(8.dp))
            Text("分享", color = AppAccent, fontSize = 12.sp, modifier = Modifier.clickable {
                runCatching {
                    val f = file ?: return@runCatching
                    DiagLog.flush()
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(i, "分享诊断日志"))
                }
            }.padding(8.dp))
        }
        Text(
            if (file != null) "${file.absolutePath} · %.1f KB · 最近 %d 行".format(file.length() / 1024.0, lines.size)
            else "日志未初始化",
            color = AppDim, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            listOf("全部", "仅 W/E", "去 preview").forEachIndexed { i, label ->
                FilterChip(
                    selected = filter == i,
                    onClick = { filter = i },
                    label = { Text(label, fontSize = 10.sp) },
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无日志", color = AppDim, fontSize = 12.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                itemsIndexed(shown) { _, line ->
                    val color = when {
                        line.contains(" E/") -> AppBad
                        line.contains(" W/") -> AppWarn
                        line.contains(" I/") -> AppOk
                        else -> AppText.copy(alpha = 0.85f)
                    }
                    Text(
                        line,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
