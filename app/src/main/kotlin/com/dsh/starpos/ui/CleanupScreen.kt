// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import java.io.File

/** 会话存储清理：统计、原始目录清理、ZIP 多选进回收站、回收站恢复/清空。 */
@Composable
fun CleanupScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var stats by remember { mutableStateOf(Storage.stats(context)) }
    var zips by remember { mutableStateOf(Storage.listZips(context)) }
    var dirs by remember { mutableStateOf(Storage.listDirs(context)) }
    var trash by remember { mutableStateOf(Storage.trashItems(context)) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var kindFilter by remember { mutableStateOf("all") }
    var onlyExported by remember { mutableStateOf(false) }
    var oldDays by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) } // 0 包 1 原始目录 2 回收站
    var confirm by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        Storage.purgeOldTrash(context)
        stats = Storage.stats(context)
        zips = Storage.listZips(context)
        dirs = Storage.listDirs(context)
        trash = Storage.trashItems(context)
        selected = emptySet()
    }

    val shownZips = zips.filter { z ->
        (kindFilter == "all" || Storage.kindOf(z.file.name) == kindFilter) &&
            (!onlyExported || Storage.isExported(z.file))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().background(AppBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← 返回", color = AppAccent, fontSize = 13.sp, modifier = Modifier.clickable { onBack() }.padding(8.dp))
            Spacer(Modifier.weight(1f))
            Text("清理与存储", color = AppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("刷新", color = AppAccent, fontSize = 12.sp, modifier = Modifier.clickable { reload() }.padding(8.dp))
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = AppCard)) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "ZIP ${stats.zipCount} 个 · ${Storage.formatBytes(stats.zipBytes)}   " +
                        "原始目录 ${stats.dirCount} 个 · ${Storage.formatBytes(stats.dirBytes)}",
                    color = AppText, fontSize = 11.sp,
                )
                Text(
                    "可用 ${Storage.formatBytes(stats.freeBytes)} / 总 ${Storage.formatBytes(stats.totalBytes)} · 回收站 ${trash.size} 项 " +
                        "${Storage.formatBytes(stats.trashBytes)}",
                    color = if (stats.freeBytes < Storage.DANGER_FREE_BYTES) AppBad else AppDim, fontSize = 10.sp,
                )
                if (stats.freeBytes < Storage.DANGER_FREE_BYTES) {
                    Text("⚠ 可用空间 <1 GB：建议立即清理原始目录或导出旧包", color = AppBad, fontSize = 10.sp)
                } else if (stats.freeBytes < Storage.WARN_FREE_BYTES) {
                    Text("提示：可用空间 <3 GB，建议清理", color = AppWarn, fontSize = 10.sp)
                }
                if (stats.zipCount > Storage.DANGER_PACKS) {
                    Text("⚠ 采集包 >800：列表/分析会变慢，建议清理旧包", color = AppBad, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            listOf("采集包", "原始目录", "回收站").forEachIndexed { i, label ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label, fontSize = 10.sp) })
            }
        }
        msg?.let { Text(it, color = AppOk, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (tab == 0) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                listOf("all" to "全部", "sun" to "太阳", "moon" to "月亮", "star" to "星光", "other" to "其它").forEach { (v, label) ->
                    FilterChip(selected = kindFilter == v, onClick = { kindFilter = v }, label = { Text(label, fontSize = 9.sp) })
                }
                FilterChip(selected = onlyExported, onClick = { onlyExported = !onlyExported }, label = { Text("仅已导出", fontSize = 9.sp) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Text("批量：", color = AppDim, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp))
                listOf(7, 30, 90, 180).forEach { d ->
                    FilterChip(
                        selected = oldDays == d,
                        onClick = {
                            oldDays = if (oldDays == d) 0 else d
                            selected = if (oldDays == 0) emptySet() else {
                                val cutoff = System.currentTimeMillis() - d * 24L * 3600_000L
                                shownZips.filter { it.lastModified < cutoff }.map { it.file.name }.toSet()
                            }
                        },
                        label = { Text("旧于${d}天", fontSize = 9.sp) },
                    )
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    selected = if (selected.size == shownZips.size) emptySet() else shownZips.map { it.file.name }.toSet()
                }) { Text(if (selected.size == shownZips.size && shownZips.isNotEmpty()) "全不选" else "全选", color = AppAccent, fontSize = 11.sp) }
                Text("已选 ${selected.size} 个", color = AppDim, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (selected.isEmpty()) { msg = "先勾选要删除的 ZIP"; return@TextButton }
                    confirm = "zip"
                }) { Text("删除选中（回收站）", color = AppBad, fontSize = 11.sp) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(shownZips) { z ->
                    val checked = z.file.name in selected
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                            selected = if (checked) selected - z.file.name else selected + z.file.name
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(
                                z.file.name.removeSuffix(".zip"),
                                color = AppText, fontSize = 10.sp,
                            )
                            Text(
                                "${Storage.kindText(z.file.name)} · ${Storage.formatBytes(z.bytes)} · ${Storage.formatDate(z.lastModified)}" +
                                    (Storage.lensOf(z.file.name)?.let { " · cam$it" } ?: "") +
                                    if (Storage.isExported(z.file)) " · 已导出" else "",
                                color = if (Storage.isExported(z.file)) AppOk else AppDim, fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        } else if (tab == 1) {
            val safeDirs = dirs.filter { d -> File(d.file.parentFile, d.file.name + ".zip").isFile }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "可清理目录 ${safeDirs.size} 个 · ${Storage.formatBytes(safeDirs.sumOf { it.bytes })}" +
                        if (dirs.size > safeDirs.size) "（${dirs.size - safeDirs.size} 个无 ZIP，未列入）" else "",
                    color = AppDim, fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (safeDirs.isNotEmpty()) confirm = "dirs" }) {
                    Text("删除原始目录（保留 ZIP）", color = AppBad, fontSize = 11.sp)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(safeDirs) { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.file.name, color = AppText, fontSize = 10.sp)
                            Text("${Storage.formatBytes(d.bytes)} · ${Storage.formatDate(d.lastModified)}", color = AppDim, fontSize = 9.sp)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${trash.size} 项 · ${Storage.formatBytes(stats.trashBytes)}（保留 7 天）", color = AppDim, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (trash.isNotEmpty()) confirm = "empty" }) { Text("清空回收站", color = AppBad, fontSize = 11.sp) }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(trash) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Storage.nameFromTrash(t.file.name), color = AppText, fontSize = 10.sp)
                            Text("${Storage.formatBytes(t.bytes)} · ${Storage.formatDate(t.lastModified)}", color = AppDim, fontSize = 9.sp)
                        }
                        TextButton(onClick = {
                            if (Storage.restore(context, t.file)) { msg = "已恢复"; reload() } else msg = "恢复失败（同名文件已存在？）"
                        }) { Text("恢复", color = AppAccent, fontSize = 10.sp) }
                    }
                }
            }
        }
    }

    confirm?.let { what ->
        val text = when (what) {
            "zip" -> {
                val chosen = zips.filter { it.file.name in selected }
                "将删除 ${chosen.size} 个 ZIP（共 ${Storage.formatBytes(chosen.sumOf { it.bytes })}）并移入回收站 7 天。"
            }
            "dirs" -> {
                val safeDirs = dirs.filter { d -> File(d.file.parentFile, d.file.name + ".zip").isFile }
                "将删除 ${safeDirs.size} 个原始帧目录（共 ${Storage.formatBytes(safeDirs.sumOf { it.bytes })}），ZIP 保留；没有 ZIP 的目录不会动。"
            }
            else -> "将永久清空回收站（${trash.size} 项，${Storage.formatBytes(stats.trashBytes)}）。"
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认清理") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    var freed = 0L
                    when (what) {
                        "zip" -> {
                            val chosen = zips.filter { it.file.name in selected }
                            for (z in chosen) {
                                Storage.moveToTrash(context, z.file)
                                val dir = File(z.file.parentFile, z.file.nameWithoutExtension)
                                if (dir.isDirectory) Storage.moveToTrash(context, dir)
                                freed += z.bytes
                            }
                            msg = "已移入回收站：${chosen.size} 个 ZIP（${Storage.formatBytes(freed)}）"
                        }
                        "dirs" -> {
                            val safeDirs = dirs.filter { d -> File(d.file.parentFile, d.file.name + ".zip").isFile }
                            freed = safeDirs.sumOf { it.bytes }
                            safeDirs.forEach { Storage.deleteDir(it.file) }
                            msg = "已删除原始目录，回收约 ${Storage.formatBytes(freed)}"
                        }
                        else -> {
                            freed = stats.trashBytes
                            Storage.emptyTrash(context)
                            msg = "回收站已清空（${Storage.formatBytes(freed)}）"
                        }
                    }
                    confirm = null
                    reload()
                }) { Text("确认", color = AppBad) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
