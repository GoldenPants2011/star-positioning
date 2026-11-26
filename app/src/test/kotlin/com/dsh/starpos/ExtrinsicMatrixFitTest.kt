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
 * 拟合外参的完整 3×3 映射，并做**留一交叉验证**（按会话分组），判断是否真的可泛化。
 */
class ExtrinsicMatrixFitTest {

    private data class S(val u: DoubleArray, val v: DoubleArray, val session: String)

    @Test
    fun `拟合 3x3 并留一验证`() {
        val dir = File("../field-inbox")
        val sessions = listOf(
            "session-20261119-011848.zip", "session-20261119-012017.zip", "session-20261119-012049.zip",
            "session-20261119-044811.zip", "session-20261119-044846.zip",
            "session-20261120-020344.zip", "session-20261120-004205.zip",
        )
        val samples = ArrayList<S>()
        for (name in sessions) {
            val zip = File(dir, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val truth = reader.readGnss().lastOrNull() ?: return@use
                val metas = reader.frameMetas()
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val first = reader.loadFrame(metas.first().index)
                val cam = intr.toCameraIntrinsics(first.width, first.height)
                val solver = PlateSolver(
                    SkyIndex(Fixtures.catalog), cam,
                    maxResidualArcsec = max(60.0, 2.0 * 206264.8 / cam.fx),
                )
                for (m in metas) {
                    val f = reader.loadFrame(m.index)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val sol = runCatching {
                        solver.solve(det.detections.filter { it.aspect < 2.0 && it.area >= 3 })
                    }.getOrNull() ?: continue
                    val g = FieldAnalyzer.gravityAt(imu, m, reader.manifest.camera?.sensorOrientation ?: 90)
                    if (!g.valid) continue
                    val ctx = SkyContext.at(m.wallClockUtcMs)
                    val latRad = Math.toRadians(truth.lat)
                    val lonRad = Math.toRadians(truth.lon)
                    val geoC = Math.atan((1.0 - 0.00669437999014) * Math.tan(latRad))
                    val ra = ((ctx.gast + lonRad) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI)
                    val zenith = V.fromRaDec(ra, geoC)
                    samples.add(S(V.unit(g.upCam), V.unit(apply(transpose(sol.rotation), zenith)), name))
                }
            }
        }
        println("样本 ${samples.size} 帧，${samples.map { it.session }.distinct().size} 组")

        fun rmsResidual(model: Array<DoubleArray>, list: List<S>): Double {
            var sum = 0.0
            for (s in list) {
                val ang = acos(V.dot(V.unit(apply(model, s.u)), s.v).coerceIn(-1.0, 1.0))
                sum += ang * ang
            }
            return Math.toDegrees(Math.sqrt(sum / list.size))
        }

        // 全量拟合
        val mAll = fit(samples)
        println("全量残差：%.3f°".format(rmsResidual(mAll, samples)))
        println("矩阵（行优先）：" + mAll.flatMap { it.toList() }.joinToString(", ") { "%.6f".format(it) })

        // λ 扫描
        println("λ 扫描（留一平均残差）：")
        val byS = samples.groupBy { it.session }
        for (lam in listOf(0.0, 1.0, 3.0, 8.0, 20.0)) {
            val rs = ArrayList<Double>()
            for ((held, hs) in byS) {
                val train = samples.filter { it.session != held }
                if (train.size < 10) continue
                rs.add(rmsResidual(fit(train, lam), hs))
            }
            val m0 = fit(samples, lam)
            println("  λ=%5.1f 留一平均 %.3f°  对角 %.2f/%.2f/%.2f".format(
                lam, rs.average(), m0[0][0], m0[1][1], m0[2][2]))
        }

        // 留一会话交叉验证
        val bySession = samples.groupBy { it.session }
        val looResiduals = ArrayList<Pair<String, Double>>()
        for ((held, heldSamples) in bySession) {
            val train = samples.filter { it.session != held }
            if (train.size < 10) continue
            val mTrain = fit(train)
            looResiduals.add(held to rmsResidual(mTrain, heldSamples))
        }
        println("留一验证（用其余会话拟合，在被留会话上评估）：")
        for ((s, r) in looResiduals) println("  %s → %.3f°".format(s.removePrefix("session-").removeSuffix(".zip"), r))
        println("留一平均 %.3f°（现用小旋转模型约 0.40°）".format(looResiduals.map { it.second }.average()))

        // 与现有配置对比：在全部样本上的残差
        val cur = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        if (cur != null) {
            val rotA = com.dsh.starpos.astro.Rodrigues.exp(cur.rx, cur.ry, cur.rz)
            println("现用值残差：%.3f°".format(rmsResidual(rotA, samples)))
        }
    }

    /** 岭回归：min ||v−Mu||² + λ·||M−I||²（向单位阵收缩，降低过拟合风险）。 */
    private fun fit(list: List<S>, lambda: Double = 0.0): Array<DoubleArray> {
        val a = Array(3) { DoubleArray(3) }
        val s = Array(3) { DoubleArray(3) }
        for (sm in list) {
            for (i in 0..2) {
                for (j in 0..2) {
                    a[i][j] += sm.v[i] * sm.u[j]
                    s[i][j] += sm.u[i] * sm.u[j]
                }
            }
        }
        // 加 λI（同时对 A 加 λI，等价于把 M 拉向 I）
        for (i in 0..2) { s[i][i] += lambda; a[i][i] += lambda }
        val sinv = inv3(s) ?: return Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        return Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * sinv[k][j] } } }
    }

    private fun inv3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (Math.abs(det) < 1e-9) return null
        val r = Array(3) { DoubleArray(3) }
        r[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det
        r[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det
        r[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det
        r[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det
        r[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det
        r[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det
        r[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det
        r[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det
        r[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det
        return r
    }
}

