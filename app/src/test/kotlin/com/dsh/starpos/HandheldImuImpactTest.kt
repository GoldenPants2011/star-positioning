package com.dsh.starpos

import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import org.junit.Test

/**
 * 手持对重力估计的影响量化：对每帧的 ±0.75 s 窗口计算
 *  - 陀螺均值（rad/s，手持通常 >0.02）；
 *  - 倾角散布 σ（瞬时 up 与窗口均值夹角 RMS）；
 *  - 半窗漂移（前半窗均值 up 与后半窗均值 up 的夹角，衡量窗口内低频倾斜）；
 * 并与太阳/月亮高度残差对照。
 */
class HandheldImuImpactTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun angDeg(a: DoubleArray, b: DoubleArray): Double =
        Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))

    @Test
    fun `手持对重力估计的影响`() {
        val root = projectRoot()
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val files = File(root, "field-inbox").listFiles { f -> f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: return
        println("%-22s %5s %8s %8s %8s %7s %6s".format("会话", "帧", "陀螺", "倾角σ", "半窗漂移", "静止%", "残差°"))
        for (zip in files) {
            BundleReader.open(zip).use { reader ->
                val imu = reader.readImu()
                if (imu.isEmpty()) return@use
                val gyros = ArrayList<Double>()
                val sigmas = ArrayList<Double>()
                val drifts = ArrayList<Double>()
                var staticCount = 0
                var frames = 0
                for (meta in reader.frameMetas()) {
                    val anchor = if (meta.sensorTimestampNs > 0) meta.sensorTimestampNs else meta.elapsedRealtimeNs
                    val near = imu.filter { abs(it.tNs - anchor) <= 750_000_000L }
                    if (near.size < 40) continue
                    frames++
                    gyros.add(near.map { Math.sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) }.average())
                    val ups = near.map { V.unit(doubleArrayOf(it.ax, it.ay, it.az)) }
                    val meanUp = V.unit(
                        doubleArrayOf(ups.sumOf { it[0] }, ups.sumOf { it[1] }, ups.sumOf { it[2] }),
                    )
                    var angSq = 0.0
                    ups.forEach { angSq += angDeg(it, meanUp) * angDeg(it, meanUp) }
                    sigmas.add(Math.sqrt(angSq / ups.size))
                    val half = ups.size / 2
                    val u1 = V.unit(doubleArrayOf(ups.take(half).sumOf { it[0] }, ups.take(half).sumOf { it[1] }, ups.take(half).sumOf { it[2] }))
                    val u2 = V.unit(doubleArrayOf(ups.drop(half).sumOf { it[0] }, ups.drop(half).sumOf { it[1] }, ups.drop(half).sumOf { it[2] }))
                    drifts.add(angDeg(u1, u2))
                    val g = FieldAnalyzer.gravityAt(imu, meta, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = null)
                    if (g.isStatic) staticCount++
                }
                if (frames == 0) return@use
                // 太阳/月亮包的残差（若有）
                var resid = Double.NaN
                val isSunOrMoon = zip.name.contains("-15") || zip.name.contains("-16") || zip.name.contains("-19") || zip.name.contains("-20")
                if (isSunOrMoon) {
                    val a = FieldAnalyzer.analyze(reader, Fixtures.catalog, extrinsic)
                    for (f in a.findings) {
                        Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let { resid = it.groupValues[1].toDouble() }
                    }
                }
                println("%-22s %5d %8.3f %8.3f %8.3f %6.0f%% %6s".format(
                    zip.name.removePrefix("session-").removeSuffix(".zip"), frames,
                    gyros.average(), sigmas.average(), drifts.average(),
                    100.0 * staticCount / frames,
                    if (resid.isNaN()) "-" else "%.2f".format(resid),
                ))
            }
        }
    }
}
