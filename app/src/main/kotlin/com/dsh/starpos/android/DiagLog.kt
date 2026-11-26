package com.dsh.starpos.android

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断日志（现场排查"卡帧/闪退/采集中断"用）。
 *
 * 设计目标：
 * - **不依赖 adb**：写进 App 私有目录 `files/logs/diag.log`，现场可通过"分享日志"导出；
 * - 同时镜像到 logcat（tag=dsh-diag），有 USB 时直接 `adb logcat -s dsh-diag`；
 * - 内存保留最近 [MAX_MEMORY_LINES] 行，界面可查看；落盘按体积滚动（超过 1.5 MB 转 `diag.prev.log`）；
 * - 每次采集打出的 zip 里自动附带 `diag.log`（最后 1500 行），带回即可分析。
 */
object DiagLog {
    private const val MAX_MEMORY_LINES = 3000
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val LOG_TAG = "dsh-diag"
    private const val FLUSH_EVERY = 20

    private val ring = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val lock = Any()
    private var file: File? = null
    private var writer: BufferedWriter? = null
    private var pending = 0
    private val startNs = System.nanoTime()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** App 启动时调用一次（MainActivity.onCreate）。 */
    fun init(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "diag.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                runCatching { f.renameTo(File(dir, "diag.prev.log")) }
            }
            file = f
            writer = runCatching { BufferedWriter(FileWriter(f, true)) }.getOrNull()
        }
        i("App", "DiagLog start pid=${android.os.Process.myPid()} build=${com.dsh.starpos.BuildConfig.VERSION_NAME}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        val nowMs = System.nanoTime()
        val elapsed = (nowMs - startNs) / 1_000_000
        val line = "${fmt.format(Date())} +${elapsed}ms ${level}/$tag: $msg"
        when (level) {
            "E" -> Log.e(LOG_TAG, "$tag: $msg")
            "W" -> Log.w(LOG_TAG, "$tag: $msg")
            else -> Log.i(LOG_TAG, "$tag: $msg")
        }
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_MEMORY_LINES) ring.removeFirst()
            val w = writer
            if (w != null) {
                runCatching {
                    w.write(line)
                    w.newLine()
                    pending++
                    if (pending >= FLUSH_EVERY) {
                        w.flush()
                        pending = 0
                    }
                }
            }
        }
    }

    /** 最近 n 行。 */
    fun tail(n: Int = 300): List<String> = synchronized(lock) { ring.toList().takeLast(n) }

    fun tailText(n: Int = 300): String = tail(n).joinToString("\n")

    /** 日志文件（App 私有目录）；分享时用 FileProvider 授权。 */
    fun file(): File? = file

    fun flush() {
        synchronized(lock) { runCatching { writer?.flush() } }
    }

    /** 把日志追加进采集包（zip 内 diag.log），便于离线排查。 */
    fun writeIntoBundle(dir: File) {
        val f = File(dir, "diag.log")
        runCatching { f.writeText(tailText(1500)) }
    }
}
