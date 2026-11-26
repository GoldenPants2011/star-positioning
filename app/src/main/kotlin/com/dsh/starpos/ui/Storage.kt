package com.dsh.starpos.ui

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话存储统计 / 回收站 / 导出标记。只做本地文件操作，不联网。 */
object Storage {
    data class Stats(
        val zipCount: Int,
        val zipBytes: Long,
        val dirCount: Int,
        val dirBytes: Long,
        val trashCount: Int,
        val trashBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long,
    )

    data class Item(val file: File, val bytes: Long, val lastModified: Long)

    const val WARN_FREE_BYTES = 3L * 1024 * 1024 * 1024
    const val DANGER_FREE_BYTES = 1L * 1024 * 1024 * 1024
    const val WARN_PACKS = 300
    const val DANGER_PACKS = 800
    const val TRASH_DAYS = 7

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions")

    fun trashDir(context: Context): File = File(root(context), ".trash").apply { mkdirs() }

    fun listZips(context: Context): List<Item> =
        root(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.map { Item(it, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun listDirs(context: Context): List<Item> =
        root(context).listFiles { f -> f.isDirectory && f.name != ".trash" }
            ?.map { Item(it, dirSize(it), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun trashItems(context: Context): List<Item> =
        trashDir(context).listFiles()
            ?.map { Item(it, if (it.isDirectory) dirSize(it) else it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    fun stats(context: Context): Stats {
        val zips = listZips(context)
        val dirs = listDirs(context)
        val trash = trashItems(context)
        val stat = runCatching { StatFs(root(context).absolutePath.ifEmpty { context.filesDir.absolutePath }) }.getOrNull()
        return Stats(
            zipCount = zips.size,
            zipBytes = zips.sumOf { it.bytes },
            dirCount = dirs.size,
            dirBytes = dirs.sumOf { it.bytes },
            trashCount = trash.size,
            trashBytes = trash.sumOf { it.bytes },
            freeBytes = stat?.availableBytes ?: 0L,
            totalBytes = stat?.totalBytes ?: 0L,
        )
    }

    fun kindOf(name: String): String = when {
        name.startsWith("sun-") -> "sun"
        name.startsWith("moon-") -> "moon"
        name.startsWith("star-") -> "star"
        name.startsWith("ext-") -> "ext"
        name.startsWith("cal-") -> "cal"
        else -> "other"
    }

    fun kindText(name: String): String = when (kindOf(name)) {
        "sun" -> "太阳"
        "moon" -> "月亮"
        "star" -> "星光"
        "ext" -> "外参"
        "cal" -> "标定"
        else -> "其它"
    }

    fun lensOf(name: String): String? = Regex("cam([0-9])").find(name)?.groupValues?.getOrNull(1)

    private fun exportMarker(zip: File): File = File(zip.parentFile, zip.name + ".exported")

    fun isExported(zip: File): Boolean = exportMarker(zip).isFile

    fun markExported(zip: File) {
        runCatching { exportMarker(zip).writeText(System.currentTimeMillis().toString()) }
    }

    /** 删除原始帧目录（可重建/保留 ZIP 时用）。 */
    fun deleteDir(dir: File): Boolean = runCatching { dir.deleteRecursively() }.getOrDefault(false)

    /** 移入回收站（ZIP，或整个原始目录）。 */
    fun moveToTrash(context: Context, file: File): Boolean {
        val dest = File(trashDir(context), "${System.currentTimeMillis()}-${file.name}")
        return runCatching { file.renameTo(dest) }.getOrDefault(false)
    }

    fun nameFromTrash(trashName: String): String = trashName.substringAfter("-", trashName)

    fun restore(context: Context, trashEntry: File): Boolean {
        val name = nameFromTrash(trashEntry.name)
        return runCatching { trashEntry.renameTo(File(root(context), name)) }.getOrDefault(false)
    }

    /** 清空超过 [days] 天的回收站条目（默认 7 天）。 */
    fun purgeOldTrash(context: Context, days: Int = TRASH_DAYS): Int {
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
        var n = 0
        trashDir(context).listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                n++
            }
        }
        return n
    }

    fun emptyTrash(context: Context): Int {
        var n = 0
        trashDir(context).listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
            n++
        }
        return n
    }

    fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun formatBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
        b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        b >= 1024 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
}
