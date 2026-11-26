package com.dsh.starpos

import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.StarSolveParams
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

/**
 * J2016.0 历元星表实验：
 *  - J2016.0 = Gaia 的参考历元（JD 2457389.0，TT），ICRS 坐标，不是春分点；
 *  - 用 HYG v41（J2000）按自行传播到 J2016.0，构造参考历元=2016 的 StarCatalog；
 *  - 验证「J2000+自行推到观测时刻」与「J2016+自行推到观测时刻」等价；
 *  - 对比三种星表/历元用法在同一批星光帧上的匹配结果。
 * 注：没有官方“J2016 亮星表”，Gaia DR3 亮端（G≤7）混入 8–13 等伪源，不能直接用。
 */
class J2016EpochTest {

    private fun root(): File {
        var d = File(".").absoluteFile
        repeat(4) {
            if (File(d, "settings.gradle.kts").isFile) return d
            d = d.parentFile ?: return d
        }
        return File(".").absoluteFile
    }

    private class Raw(val ra: Double, val dec: Double, val mag: Double, val pmRa: Double, val pmDec: Double)

    /** 从 HYG v41 CSV 取 mag≤limit 的星（RA 单位小时）。 */
    private fun loadHyg(file: File, magLimit: Double): List<Raw> {
        val out = ArrayList<Raw>(20_000)
        file.bufferedReader().useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext()) return emptyList()
            val header = it.next().split(",").map { c -> c.trim().trim('"') }
            val iRa = header.indexOf("ra")
            val iDec = header.indexOf("dec")
            val iMag = header.indexOf("mag")
            val iPmRa = header.indexOf("pmra")
            val iPmDec = header.indexOf("pmdec")
            while (it.hasNext()) {
                val cols = it.next().split(",").map { c -> c.trim().trim('"') }
                if (cols.size <= maxOf(iRa, iDec, iMag)) continue
                val mag = cols[iMag].toDoubleOrNull() ?: continue
                if (mag > magLimit) continue
                val raH = cols[iRa].toDoubleOrNull() ?: continue
                val dec = cols[iDec].toDoubleOrNull() ?: continue
                out.add(
                    Raw(
                        raH * 15.0 * Math.PI / 180.0,
                        dec * Math.PI / 180.0,
                        mag,
                        cols.getOrNull(iPmRa)?.toDoubleOrNull() ?: 0.0,
                        cols.getOrNull(iPmDec)?.toDoubleOrNull() ?: 0.0,
                    ),
                )
            }
        }
        return out
    }

    /** 从 refEpoch 传播到 targetEpoch 的 RA/Dec（线性自行模型；pm 单位 mas/yr）。 */
    private fun propagate(raw: List<Raw>, refEpochJd: Double, targetEpochJd: Double): Pair<DoubleArray, DoubleArray> {
        val years = (targetEpochJd - refEpochJd) / 365.25
        val ra = DoubleArray(raw.size)
        val dec = DoubleArray(raw.size)
        for (i in raw.indices) {
            val r = raw[i]
            ra[i] = r.ra + (r.pmRa * years * 1e-3 / 3600.0) * (Math.PI / 180.0) / cos(r.dec).coerceAtLeast(1e-6)
            dec[i] = r.dec + (r.pmDec * years * 1e-3 / 3600.0) * (Math.PI / 180.0)
        }
        return ra to dec
    }

    private fun catalog(raw: List<Raw>, ra: DoubleArray, dec: DoubleArray, refEpochJd: Double): StarCatalog {
        val n = raw.size
        return StarCatalog(
            ra = ra, dec = dec,
            mag = DoubleArray(n) { raw[it].mag },
            pmRa = DoubleArray(n) { raw[it].pmRa },
            pmDec = DoubleArray(n) { raw[it].pmDec },
            hr = IntArray(n) { -it },
            refEpochJd = refEpochJd,
        )
    }

    @Test
    fun `J2016 历元星表等价性与数据试跑`() {
        val root = root()
        val hyg = File(root, "catalog/hygdata_v41.csv")
        if (!hyg.isFile) { println("缺 HYG csv"); return }
        val raw = loadHyg(hyg, 7.0)
        println("HYG mag≤7：${raw.size} 颗")

        val j2000 = 2451545.0
        val j2016 = 2457389.0
        val obsMs = 1763982000000L   // 2026-11-24 前后
        val obsJd = SkyContext.at(obsMs).jdTt
        println("观测时刻 JD(TT)=%.3f".format(obsJd))

        val (ra0, dec0) = propagate(raw, j2000, j2000)
        val (ra16, dec16) = propagate(raw, j2000, j2016)
        val cat2000 = catalog(raw, ra0, dec0, j2000)
        val cat2016 = catalog(raw, ra16, dec16, j2016)
        // “忘记历元元数据”的错误用法：J2016 坐标但按 J2000 传播
        val catNaive = catalog(raw, ra16, dec16, j2000)

        fun maxDev(a: StarCatalog, b: StarCatalog): Triple<Double, Double, Double> {
            var max = 0.0
            var sum = 0.0
            var n = 0
            for (i in 0 until a.size) {
                val ang = Math.toDegrees(V.angle(a.icrs(i, obsJd), b.icrs(i, obsJd))) * 3600.0
                if (ang > max) max = ang
                sum += ang; n++
            }
            return Triple(max, sum / n, 0.0)
        }

        val d2016 = maxDev(cat2000, cat2016)
        val dNaive = maxDev(cat2000, catNaive)
        println("J2000 表 vs J2016 表（均正确传播到观测时刻）：最大差 %.3f″，平均 %.4f″".format(d2016.first, d2016.second))
        println("若把 J2016 表当 J2000 用（历元记账错误）：最大差 %.1f″，平均 %.1f″".format(dNaive.first, dNaive.second))

        // 同一批星光帧：三种索引用法对比
        val sessions = listOf("session-20261120-003438.zip", "session-20261120-034851.zip")
        val inbox = File(root, "field-inbox")
        var idxJ2000Pin = 0
        var idxObs = 0
        var idx2016 = 0
        var rmsJ2000 = 0.0
        var rmsObs = 0.0
        var rms2016 = 0.0
        for (name in sessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) continue
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0, medianPrefilter = false)
                    val starLike = det.detections.filter { StarSolveParams.accept(it, cam) }
                    if (starLike.size < 6) continue
                    val jdObs = SkyContext.at(meta.wallClockUtcMs).jdTt
                    fun trySolve(index: SkyIndex): Pair<Int, Double> {
                        val solver = PlateSolver(
                            index, cam,
                            triangleMagLimit = StarSolveParams.triangleMag(cam),
                            maxResidualArcsec = maxOf(60.0, 2.0 * 206264.8 / cam.fx),
                        )
                        val sol = runCatching {
                            solver.solve(
                                starLike,
                                maxImageStars = StarSolveParams.maxImageStars(cam),
                                matchToleranceDeg = StarSolveParams.matchToleranceDeg(cam),
                            )
                        }.getOrNull()
                        return (sol?.matchedStars ?: 0) to (sol?.rmsArcsec ?: Double.NaN)
                    }
                    val a = trySolve(SkyIndex(Fixtures.catalog))                       // 现状：索引固定在 J2000
                    val b = trySolve(SkyIndex(Fixtures.catalog, epochJd = jdObs))      // 正确：按拍摄时刻传播自行
                    val c = trySolve(SkyIndex(cat2016, epochJd = jdObs))               // J2016 表 + 观测时刻
                    if (a.first > 0) { idxJ2000Pin++; rmsJ2000 += a.second }
                    if (b.first > 0) { idxObs++; rmsObs += b.second }
                    if (c.first > 0) { idx2016++; rms2016 += c.second }
                    println(
                        "  帧%s：J2000固定 匹配%d rms%.0f″ | 观测历元 匹配%d rms%.0f″ | J2016表 匹配%d rms%.0f″".format(
                            meta.index, a.first, a.second, b.first, b.second, c.first, c.second,
                        ),
                    )
                }
            }
        }
        if (idxJ2000Pin > 0) {
            println(
                "合计：J2000固定 %d 帧(rms均 %.0f″) / 观测历元 %d 帧(%.0f″) / J2016表 %d 帧(%.0f″)".format(
                    idxJ2000Pin, rmsJ2000 / idxJ2000Pin,
                    idxObs, if (idxObs > 0) rmsObs / idxObs else 0.0,
                    idx2016, if (idx2016 > 0) rms2016 / idx2016 else 0.0,
                ),
            )
        }
    }
}
