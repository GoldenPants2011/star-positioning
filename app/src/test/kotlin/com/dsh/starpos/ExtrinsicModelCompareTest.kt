// SPDX-License-Identifier: GPL-3.0-or-later

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
import kotlin.math.max
import org.junit.Test

/**
 * 外参模型对比：只做小旋转（现有 2.81°） vs 完整 3×3 线性映射 vs 仿射（映射+偏置）。
 *
 * 动机：2026-11-20 多帧融合没有改善 → 误差是系统性的；加速度计的**偏置/轴间串扰**不是纯旋转，
 * 用小旋转模型吸收不掉。用带 GNSS 真值的多组静止数据拟合更大自由度的模型，看残差能降到多少。
 */
class ExtrinsicModelCompareTest {

    private data class Sample(val u: DoubleArray, val v: DoubleArray, val session: String)

    @Test
    fun `三种外参模型对比`() {
        val dir = File("../field-inbox")
        val sessions = listOf(
            "session-20261119-011848.zip", "session-20261119-012017.zip", "session-20261119-012049.zip",
            "session-20261119-044811.zip", "session-20261119-044846.zip",
            "session-20261120-020344.zip", "session-20261120-004205.zip",
        )
        val samples = ArrayList<Sample>()
        val perSession = LinkedHashMap<String, ArrayList<Pair<DoubleArray, DoubleArray>>>()
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
                val list = ArrayList<Pair<DoubleArray, DoubleArray>>()
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
                    val v = V.unit(apply(transpose(sol.rotation), zenith))
                    list.add(V.unit(g.upCam) to v)
                    samples.add(Sample(V.unit(g.upCam), v, name))
                }
                if (list.isNotEmpty()) perSession[name] = list
            }
        }
        println("样本：${samples.size} 帧，来自 ${perSession.size} 组")

        // 模型 A：现有小旋转（读配置）
        val cur = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        val rotA = com.dsh.starpos.astro.Rodrigues.exp(cur!!.rx, cur.ry, cur.rz)

        // 模型 B：完整 3×3（最小二乘，u→v）
        val m3 = fitLinear(samples.map { it.u to it.v })
        // 模型 C：仿射（u→v 带偏置）
        val aff = fitAffine(samples.map { it.u to it.v })

        fun resid(model: (DoubleArray) -> DoubleArray): Double {
            var sum = 0.0
            for (s in samples) {
                val ang = Math.acos(V.dot(V.unit(model(s.u)), s.v).coerceIn(-1.0, 1.0))
                sum += ang * ang
            }
            return Math.toDegrees(Math.sqrt(sum / samples.size))
        }
        println("残差 rms：A(小旋转)=%.3f°  B(3×3)=%.3f°  C(仿射)=%.3f°".format(
            resid { apply(rotA, it) },
            resid { apply(m3, it) },
            resid { aff(it) },
        ))

        // 逐组位置误差
        println("组 | A(2.81°) | B(3×3) | C(仿射)")
        for ((name, list) in perSession) {
            val r = name.removePrefix("session-").removeSuffix(".zip")
            val row = StringBuilder("$r |")
            for (model in listOf<(DoubleArray) -> DoubleArray>(
                { apply(rotA, it) }, { apply(m3, it) }, { aff(it) },
            )) {
                // 用该组第一帧的姿态/重力算一次位置误差
                val (u, v) = list.first()
                // 由 v 反推相机姿态：这里直接用该组第一帧的 R 重新算位置更麻烦，改为比较 up 方向偏差换算的距离
                val ang = Math.acos(V.dot(V.unit(model(u)), v).coerceIn(-1.0, 1.0))
                row.append(" %.1f°".format(Math.toDegrees(ang)))
            }
            println(row)
        }
    }

    /** 最小二乘 M（9 参数）：v ≈ M u。 */
    private fun fitLinear(pairs: List<Pair<DoubleArray, DoubleArray>>): Array<DoubleArray> {
        val a = Array(3) { DoubleArray(3) }
        for ((u, v) in pairs) {
            for (i in 0..2) for (j in 0..2) a[i][j] += v[i] * u[j]
        }
        // M = a · (Σ u uᵀ)⁻¹
        val s = Array(3) { DoubleArray(3) }
        for ((u, _) in pairs) for (i in 0..2) for (j in 0..2) s[i][j] += u[i] * u[j]
        val sinv = inv3(s) ?: return Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        return Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * sinv[k][j] } } }
    }

    /** 仿射：v ≈ M u + b，12 参数，按分量解 4×4 正规方程。 */
    private fun fitAffine(pairs: List<Pair<DoubleArray, DoubleArray>>): (DoubleArray) -> DoubleArray {
        // 对每个输出分量：未知 [M_i0,M_i1,M_i2,b_i]
        val coef = Array(4) { DoubleArray(4) }
        val rhs = Array(4) { DoubleArray(3) }
        for ((u, v) in pairs) {
            val x = doubleArrayOf(u[0], u[1], u[2], 1.0)
            for (i in 0..3) {
                for (j in 0..3) coef[i][j] += x[i] * x[j]
                for (c in 0..2) rhs[i][c] += x[i] * v[c]
            }
        }
        val inv = inv4(coef) ?: return { it }
        val sol = Array(4) { DoubleArray(3) }
        for (i in 0..3) for (c in 0..2) sol[i][c] = (0..3).sumOf { k -> inv[i][k] * rhs[k][c] }
        return { u ->
            doubleArrayOf(
                sol[0][0] * u[0] + sol[1][0] * u[1] + sol[2][0] * u[2] + sol[3][0],
                sol[0][1] * u[0] + sol[1][1] * u[1] + sol[2][1] * u[2] + sol[3][1],
                sol[0][2] * u[0] + sol[1][2] * u[1] + sol[2][2] * u[2] + sol[3][2],
            )
        }
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

    private fun inv4(m: Array<DoubleArray>): Array<DoubleArray>? {
        // 高斯-约当
        val n = 4
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m[i][j] else if (i == j - n) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var piv = col
            for (r in col until n) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r
            if (Math.abs(a[piv][col]) < 1e-12) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val pv = a[col][col]
            for (j in 0 until 2 * n) a[col][j] /= pv
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (j in 0 until 2 * n) a[r][j] -= f * a[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }
}
