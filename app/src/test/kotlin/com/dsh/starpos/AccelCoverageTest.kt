package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import org.junit.Test

/**
 * 定向覆盖度诊断：今晚粗糙调整角度的 21 组数据能覆盖多少姿态、垂直误差随姿态怎么变。
 */
class AccelCoverageTest {

    @Test
    fun `定向覆盖度与残差诊断`() {
        val dir = File("../field-inbox")
        val files = dir.listFiles { f -> f.name.startsWith("session-20261121-00") && f.name.endsWith(".zip") }
            ?.sortedBy { it.name } ?: emptyList()
        println("会话数：${files.size}")
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val ups = ArrayList<DoubleArray>()
        val rows = ArrayList<Triple<String, Double, Double>>()   // name, rawErr, correctedErr
        for (zip in files) {
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val first = runCatching { reader.loadFrame(reader.frameMetas().first().index) }.getOrNull() ?: return@use
                val cam = intr.toCameraIntrinsics(first.width, first.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                val latRad = Math.toRadians(truth.lat)
                val lonRad = Math.toRadians(truth.lon)
                val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                var solved = 0
                var rawSum = 0.0
                var corrSum = 0.0
                for (m in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: continue
                    if (sol.matchedStars < 5) continue
                    val ctx = SkyContext.at(m.wallClockUtcMs)
                    val ra = ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)
                    val zenith = V.fromRaDec(ra, geoC)
                    val v = V.unit(apply(transpose(sol.rotation), zenith))
                    val gRaw = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = null)
                    val gExt = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                    if (!gRaw.valid || !gExt.valid) continue
                    solved++
                    rawSum += Math.toDegrees(acos(V.dot(V.unit(gRaw.upCam), v).coerceIn(-1.0, 1.0)))
                    corrSum += Math.toDegrees(acos(V.dot(V.unit(gExt.upCam), v).coerceIn(-1.0, 1.0)))
                    ups.add(V.unit(gRaw.upCam))
                }
                if (solved > 0) {
                    rows.add(Triple(zip.name.removePrefix("session-").removeSuffix(".zip"), rawSum / solved, corrSum / solved))
                }
            }
        }
        for ((n, raw, corr) in rows) {
            println("%s：星解 %s，原始垂直误差 %.3f°，加外参后 %.3f°".format(n, "OK", raw, corr))
        }
        if (ups.size >= 3) {
            // 协方差特征值：越小说明某个方向覆盖不足
            val m = Array(3) { DoubleArray(3) }
            for (u in ups) for (i in 0..2) for (j in 0..2) m[i][j] += u[i] * u[j]
            val n = ups.size.toDouble()
            for (i in 0..2) for (j in 0..2) m[i][j] /= n
            val eigs = eigenvalues3(m)
            println("u 方向协方差特征值（归一化，总和=1）：%s".format(eigs.map { "%.3f".format(it) }))
            var minAng = 180.0
            var maxAng = 0.0
            for (i in ups.indices) for (j in i + 1 until ups.size) {
                val a = Math.toDegrees(acos(V.dot(ups[i], ups[j]).coerceIn(-1.0, 1.0)))
                if (a < minAng) minAng = a
                if (a > maxAng) maxAng = a
            }
            println("u 向量两两夹角：%.1f°–%.1f°（覆盖张角越大越好，理想 >60°）".format(minAng, maxAng))
        }
        val raw = rows.map { it.second }
        val corr = rows.map { it.third }
        if (raw.isNotEmpty()) {
            println("原始误差：均值 %.3f°，max %.3f°".format(raw.average(), raw.max()))
            println("加 2.81° 外参后：均值 %.3f°，max %.3f°".format(corr.average(), corr.max()))
        }
    }

    private fun eigenvalues3(m: Array<DoubleArray>): DoubleArray {
        // 解析解（对称 3×3）
        val p1 = m[0][1] * m[0][1] + m[0][2] * m[0][2] + m[1][2] * m[1][2]
        val q = (m[0][0] + m[1][1] + m[2][2]) / 3.0
        val p2 = (m[0][0] - q) * (m[0][0] - q) + (m[1][1] - q) * (m[1][1] - q) + (m[2][2] - q) * (m[2][2] - q) + 2 * p1
        val p = Math.sqrt(p2 / 6.0)
        if (p < 1e-12) return doubleArrayOf(q, q, q)
        val b = Array(3) { i -> DoubleArray(3) { j -> if (i == j) (m[i][j] - q) / p else m[i][j] / p } }
        val detB = b[0][0] * (b[1][1] * b[2][2] - b[1][2] * b[2][1]) -
            b[0][1] * (b[1][0] * b[2][2] - b[1][2] * b[2][0]) +
            b[0][2] * (b[1][0] * b[2][1] - b[1][1] * b[2][0])
        val r = (detB / 2.0).coerceIn(-1.0, 1.0)
        val phi = Math.acos(r) / 3.0
        val e1 = q + 2 * p * Math.cos(phi)
        val e3 = q + 2 * p * Math.cos(phi + 2 * Math.PI / 3)
        val e2 = 3 * q - e1 - e3
        return doubleArrayOf(e1, e2, e3).sortedArray()
    }
}
