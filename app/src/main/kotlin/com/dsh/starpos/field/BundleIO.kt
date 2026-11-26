// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 把一次现场采集写成目录，最后压成 zip 交给用户带回来。 */
class BundleWriter(val root: File) {

    init {
        root.mkdirs()
        File(root, "frames").mkdirs()
        File(root, "imu").mkdirs()
        File(root, "gnss").mkdirs()
    }

    fun writeManifest(manifest: SessionManifest) {
        File(root, SessionManifest.FILE).writeText(manifest.toJson(), Charsets.UTF_8)
    }

    fun writeFrame(meta: FrameMeta, pixels16: ShortArray?, pixels8: ByteArray?) {
        val binName = "frames/frame_%03d.bin".format(meta.index)
        val out = FileOutputStream(File(root, binName))
        when {
            pixels16 != null -> FrameBinary.write(out, meta.width, meta.height, 16, pixels16)
            pixels8 != null -> FrameBinary.write8(out, meta.width, meta.height, pixels8)
            else -> error("帧 ${meta.index} 没有像素数据")
        }
        File(root, "frames/frame_%03d.json".format(meta.index))
            .writeText(JsonText.write(meta.toMap()), Charsets.UTF_8)
    }

    fun writeImu(rows: List<ImuRow>) {
        File(root, "imu/imu.csv").writeText(Csv.writeImu(rows), Charsets.UTF_8)
    }

    fun writeGnss(rows: List<GnssRow>) {
        File(root, "gnss/gnss.csv").writeText(Csv.writeGnss(rows), Charsets.UTF_8)
    }

    /**
     * 系统"最后已知位置"单独落盘，**不写进 gnss.csv**。
     * 它可能来自几小时前、甚至另一个城市，只能作参考，绝不能当成本次采集的真值。
     */
    fun writeLastKnown(row: GnssRow?) {
        val f = File(root, "gnss/last_known.json")
        if (row == null) {
            if (f.exists()) f.delete()
        } else {
            f.writeText(JsonText.write(row.toMap()), Charsets.UTF_8)
        }
    }

    fun writeNotes(text: String) {
        File(root, "notes.txt").writeText(text, Charsets.UTF_8)
    }

    /** 打包为 zip（覆盖）。 */
    fun zipTo(target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { f ->
                val name = f.relativeTo(root).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}

class LoadedFrame(
    val meta: FrameMeta,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    /** 归一化到 0..1 的亮度。 */
    val luminance: FloatArray,
)

/**
 * 采集包读取器：既接受 zip，也接受已解开的目录。
 * 打开 zip 时会解到临时目录，close() 清理，便于在 JVM 分析端使用。
 */
class BundleReader private constructor(
    val root: File,
    private val tempDir: File?,
) : AutoCloseable {

    val manifest: SessionManifest by lazy { SessionManifest.fromJson(File(root, SessionManifest.FILE).readText()) }

    fun frameMetas(): List<FrameMeta> {
        val dir = File(root, "frames")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { runCatching { FrameMeta.from(JsonText.parse(it.readText()).asMap()) }.getOrNull() }
            ?: emptyList()
    }

    fun readImu(): List<ImuRow> {
        val f = File(root, "imu/imu.csv")
        return if (f.isFile) Csv.readImu(f.readText()) else emptyList()
    }

    fun readGnss(): List<GnssRow> {
        val f = File(root, "gnss/gnss.csv")
        return if (f.isFile) Csv.readGnss(f.readText()) else emptyList()
    }

    /** 系统最后已知位置（参考用，不是真值）。 */
    fun readLastKnown(): GnssRow? {
        val f = File(root, "gnss/last_known.json")
        if (!f.isFile) return null
        return runCatching { GnssRow.from(JsonText.parse(f.readText()).asMap()) }.getOrNull()
    }

    fun readNotes(): String {
        val f = File(root, "notes.txt")
        return if (f.isFile) f.readText() else ""
    }

    fun loadFrame(index: Int): LoadedFrame {
        val bin = File(root, "frames/frame_%03d.bin".format(index))
        val meta = frameMetas().firstOrNull { it.index == index } ?: error("缺少帧 $index 的元数据")
        bin.inputStream().use { stream ->
            val decoded = FrameBinary.read(stream)
            val n = decoded.width * decoded.height
            val scale = if (decoded.bitDepth == 16) 1.0 / 65535.0 else 1.0 / 255.0
            val lum = FloatArray(n) { (decoded.values[it].toInt() and 0xFFFF) * scale.toFloat() }
            return LoadedFrame(meta, decoded.width, decoded.height, decoded.bitDepth, lum)
        }
    }

    override fun close() {
        tempDir?.deleteRecursively()
    }

    companion object {
        /** 打开 zip 或目录。 */
        fun open(source: File): BundleReader {
            require(source.exists()) { "采集包不存在：${source.absolutePath}" }
            if (source.isDirectory) {
                require(File(source, SessionManifest.FILE).isFile) { "目录里缺少 ${SessionManifest.FILE}" }
                return BundleReader(source, null)
            }
            require(source.name.endsWith(".zip", ignoreCase = true)) { "只支持 .zip 或已解开的目录" }
            val temp = File.createTempFile("starpos-session-", "")
            if (temp.exists()) temp.delete()
            temp.mkdirs()
            ZipInputStream(source.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(temp, entry.name)
                    // 防目录穿越
                    require(target.canonicalPath.startsWith(temp.canonicalPath)) { "非法路径 ${entry.name}" }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zis.copyTo(it) }
                    zis.closeEntry()
                }
            }
            require(File(temp, SessionManifest.FILE).isFile) { "zip 里缺少 ${SessionManifest.FILE}" }
            return BundleReader(temp, temp)
        }
    }
}
