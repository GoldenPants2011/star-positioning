package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.PositionSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.StarSolveParams
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用今天的水镜数据拟合"垂直基准修正"，再跑之前夜间星光会话，看定位误差变化。
 *
 * 做法：
 *  1) 水镜帧：检测太阳直射像 + 反射像 → n_mirror = unit(u_direct − u_reflected)（图像自定天顶）；
 *  2) 同一帧 IMU 给 upBase（当前外参下）→ 拟合一个小旋转 w（相机系），使 R(w)·upBase ≈ n_mirror；
 *  3) 对旧星光会话逐帧解姿态，位置解算分别用 upBase 与修正后的 up，和 GNSS 真值比较。
 */
class VerticalDatumEvalTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
    private fun rotate(w: DoubleArray, u: DoubleArray) =
        V.unit(doubleArrayOf(u[0] + w[1] * u[2] - w[2] * u[1], u[1] + w[2] * u[0] - w[0] * u[2], u[2] + w[0] * u[1] - w[1] * u[0]))

    private fun crossMatrix(u: DoubleArray) = arrayOf(
        doubleArrayOf(0.0, -u[2], u[1]),
        doubleArrayOf(u[2], 0.0, -u[0]),
        doubleArrayOf(-u[1], u[0], 0.0),
    )

    /** 盒均值 + 局部极大。 */
    private fun boxMean(img: FloatArray, w: Int, h: Int, r: Int): Pair<FloatArray, Pair<Int, Int>> {
        val iw = w - 2 * r
        val ih = h - 2 * r
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += img[y * w + x]
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + rowSum
            }
        }
        val out = FloatArray(iw * ih)
        var best = -1.0
        var bx = 0
        var by = 0
        val area = ((2 * r + 1) * (2 * r + 1)).toDouble()
        for (y in 0 until ih) for (x in 0 until iw) {
            val y0 = y
            val x0 = x
            val y1 = y + 2 * r
            val x1 = x + 2 * r
            val s = ii[(y1 + 1) * (w + 1) + x1 + 1] - ii[y0 * (w + 1) + x1 + 1] -
                ii[(y1 + 1) * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
            val v = (s / area).toFloat()
            out[y * iw + x] = v
            if (v > best) { best = v.toDouble(); bx = x + r; by = y + r }
        }
        return out to (bx to by)
    }

    private fun median(a: FloatArray): Float {
        val s = a.copyOf()
        s.sort()
        return s[s.size / 2]
    }

    /** 检测水镜对：返回 (n_mirror, upBase, 残差度, 直射像素, 反射像素, 反射亮度σ)。 */
    private class MirrorHit(val n: DoubleArray, val uD: DoubleArray, val msg: String)

    private fun detectMirrorPair(
        lum: FloatArray,
        w: Int,
        h: Int,
        cam: CameraIntrinsics,
        upBase: DoubleArray,
        ephemAltDeg: Double,
    ): MirrorHit? {
        val med = median(lum)
        var sumSq = 0.0
        for (v in lum) { val d = (v - med).toDouble(); sumSq += d * d }
        val sigma = sqrt(sumSq / lum.size).coerceAtLeast(1e-6)
        val (bm, sun) = boxMean(lum, w, h, 5)
        val (sx, sy) = sun
        val uD = V.unit(cam.unproject(sx.toDouble(), sy.toDouble()))
        val iw = w - 10
        var best: MirrorHit? = null
        var bestRes = 0.8
        var bestBright = 0.0
        for (y in (sy + 300).coerceAtLeast(12) until h - 12 step 8) for (x in 12 until w - 12 step 8) {
            val b = bm[(y - 5) * iw + (x - 5)]
            val bright = b - med
            if (bright / sigma < 0.6) continue
            val uR = V.unit(cam.unproject(x.toDouble(), y.toDouble()))
            val n = doubleArrayOf(uD[0] - uR[0], uD[1] - uR[1], uD[2] - uR[2])
            val nn = sqrt(dot(n, n))
            if (nn < 1e-9) continue
            val nu = V.unit(n)
            val alt = Math.toDegrees(Math.asin(dot(uD, nu).coerceIn(-1.0, 1.0)))
            val res = abs(alt - ephemAltDeg)
            if (res < bestRes) {
                bestRes = res
                bestBright = bright / sigma
                best = MirrorHit(nu, uD, "直射(%d,%d) 反射(%d,%d) 亮度=%.1fσ".format(sx, sy, x, y, bright / sigma))
            }
        }
        if (best == null || bestBright < 0.8) return null
        return best
    }

    @Test
    fun `水镜修正垂直基准并回放旧星光数据`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val catalog = Fixtures.catalog
        val index = SkyIndex(catalog)

        // ---------- 1) 水镜帧 → (upBase, n_mirror) ----------
        data class MirrorSession(val name: String, val lat: Double, val lon: Double)
        val mirrorSessions = listOf(
            MirrorSession("session-20261124-150016.zip", 30.0, 120.0),
            MirrorSession("session-20261124-153328.zip", 30.0, 120.0),
            MirrorSession("session-20261124-153438.zip", 30.0, 120.0),
            MirrorSession("session-20261124-153850.zip", 30.0, 120.0),
            MirrorSession("session-20261124-154008.zip", 30.0, 120.0),
        )
        val pairs = ArrayList<Pair<DoubleArray, DoubleArray>>()   // (upBase, n_mirror)
        val sunDirs = ArrayList<DoubleArray>()
        for (ms in mirrorSessions) {
            val zip = File(inbox, ms.name)
            if (!zip.isFile) { println("缺文件 ${ms.name}"); continue }
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!g.valid) { println("${ms.name} 帧${meta.index}: 无有效重力"); continue }
                    val ctx = SkyContext.at(meta.wallClockUtcMs)
                    val site = Moon.Site(Math.toRadians(ms.lat), Math.toRadians(ms.lon))
                    val sun = Moon.sunEphemerisAt(meta.wallClockUtcMs)
                    val alt = Math.toDegrees(Moon.altitudeRad(site, sun, meta.wallClockUtcMs, ctx.gast))
                    val pair = detectMirrorPair(f.luminance, f.width, f.height, cam, g.upCam, alt) ?: continue
                    pairs.add(g.upCam to pair.n)
                    sunDirs.add(pair.uD)
                    println("水镜 ${ms.name} 帧${meta.index}: 星历高度 %+.2f°，镜像天顶与 IMU 天顶差 %.2f°；%s".format(
                        alt,
                        Math.toDegrees(
                            Math.acos(dot(g.upCam, pair.n).coerceIn(-1.0, 1.0)),
                        ),
                        pair.msg,
                    ))
                }
            }
        }
        println("水镜配对共 ${pairs.size} 帧")
        assertTrue("水镜配对太少", pairs.size >= 4)

        // ---------- 2) 拟合小旋转 w ----------
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for ((up, n) in pairs) {
            val M = crossMatrix(up)
            val d = doubleArrayOf(up[0] - n[0], up[1] - n[1], up[2] - n[2])
            for (i in 0 until 3) for (j in 0 until 3) for (k in 0 until 3) a[i][j] += M[k][i] * M[k][j]
            for (i in 0 until 3) for (k in 0 until 3) b[i] += M[k][i] * d[k]
        }
        for (i in 0 until 3) a[i][i] += 1e-6
        val w = solve3(a, b)
        val wDeg = Math.toDegrees(sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]))
        println("垂直基准修正（不加约束）：w = (%.4f, %.4f, %.4f)°，大小 %.3f°".format(
            Math.toDegrees(w[0]), Math.toDegrees(w[1]), Math.toDegrees(w[2]), wDeg,
        ))
        // 约束：去掉沿太阳方向的分量（水镜对"绕太阳视线"的旋转不敏感，该分量不可信）
        val meanSun = V.unit(
            doubleArrayOf(
                sunDirs.map { it[0] }.average(), sunDirs.map { it[1] }.average(), sunDirs.map { it[2] }.average(),
            ),
        )
        val wPar = dot(w, meanSun)
        val wc = doubleArrayOf(w[0] - wPar * meanSun[0], w[1] - wPar * meanSun[1], w[2] - wPar * meanSun[2])
        val wcDeg = Math.toDegrees(sqrt(dot(wc, wc)))
        println("约束修正（去掉绕太阳视线分量 %.2f°）：w_c = (%.4f, %.4f, %.4f)°，大小 %.3f°".format(
            Math.toDegrees(wPar), Math.toDegrees(wc[0]), Math.toDegrees(wc[1]), Math.toDegrees(wc[2]), wcDeg,
        ))

        // 修正后的水镜高度残差
        var before = 0.0
        var after = 0.0
        for ((up, n) in pairs) {
            before += Math.toDegrees(Math.acos(dot(up, n).coerceIn(-1.0, 1.0)))
            after += Math.toDegrees(Math.acos(dot(rotate(w, up), n).coerceIn(-1.0, 1.0)))
        }
        println("水镜天顶残差：修正前 %.2f° → 修正后 %.2f°（平均）".format(before / pairs.size, after / pairs.size))

        // ---------- 3) 旧星光会话 ----------
        val starSessions = listOf(
            "session-20261120-003438.zip",
            "session-20261120-020344.zip",
            "session-20261120-034851.zip",
            "session-20261120-202453.zip",
            "session-20261121-002708.zip",
            "session-20261121-193428.zip",
            "session-20261122-000444.zip",
            "session-20261122-190714.zip",
        )
        // 收集帧（姿态只需解一次），然后扫不同修正强度 λ
        data class EvalFrame(
            val session: String,
            val rot: Array<DoubleArray>,
            val upBase: DoubleArray,
            val ctx: SkyContext,
            val gravitySigma: Double,
            val attitudeSigma: Double,
            val truthLat: Double,
            val truthLon: Double,
        )
        val frames = ArrayList<EvalFrame>()
        for (name in starSessions) {
            val zip = File(inbox, name)
            if (!zip.isFile) { println("缺文件 $name"); continue }
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                val imu = reader.readImu()
                val orientation = reader.manifest.camera?.sensorOrientation ?: 90
                val gnss = reader.readGnss()
                if (gnss.isEmpty()) { println("$name 无 GNSS 真值"); return@use }
                val truth = gnss[gnss.size / 2]
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val g = FieldAnalyzer.gravityAt(imu, meta, orientation, extrinsic = extrinsic)
                    if (!g.valid) continue
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0, medianPrefilter = false)
                    val starLike = det.detections.filter { StarSolveParams.accept(it, cam) }
                    if (starLike.size < 6) continue
                    val solver = PlateSolver(
                        index, cam,
                        triangleMagLimit = StarSolveParams.triangleMag(cam),
                        maxResidualArcsec = maxOf(60.0, 2.0 * 206264.8 / cam.fx),
                    )
                    val plate = runCatching {
                        solver.solve(
                            starLike,
                            maxImageStars = StarSolveParams.maxImageStars(cam),
                            matchToleranceDeg = StarSolveParams.matchToleranceDeg(cam),
                            upCam = g.upCam,
                        )
                    }.getOrNull() ?: continue
                    if (plate.matchedStars < 4) continue
                    frames.add(
                        EvalFrame(
                            name, plate.rotation, g.upCam, SkyContext.at(meta.wallClockUtcMs),
                            g.sigmaDeg, plate.attitudeSigmaDeg, truth.lat, truth.lon,
                        ),
                    )
                }
            }
        }
        println("旧星光会话可用帧：${frames.size}")
        for (kind in listOf("无约束", "去太阳轴")) {
            val wv = if (kind == "无约束") w else wc
            for (lambda in listOf(0.0, 0.5, 1.0, 1.5)) {
            val errs = ArrayList<Double>()
            for (fr in frames) {
                val up = if (lambda == 0.0) fr.upBase else rotate(
                    doubleArrayOf(wv[0] * lambda, wv[1] * lambda, wv[2] * lambda), fr.upBase,
                )
                val pos = PositionSolver.solve(
                    fr.rot, up, fr.ctx,
                    gravitySigmaDeg = fr.gravitySigma, attitudeSigmaDeg = fr.attitudeSigma,
                    timeUncertaintySec = 0.2,
                ).fix ?: continue
                errs.add(haversineKm(fr.truthLat, fr.truthLon, pos.latDeg, pos.lonDeg))
            }
            if (errs.isEmpty()) continue
            val s = errs.sorted()
            println("%s λ=%.2f（修正 %.2f°）：%d 帧，中位 %.1f km，平均 %.1f km".format(
                kind, lambda, if (kind == "无约束") wDeg * lambda else wcDeg * lambda,
                errs.size, s[s.size / 2], errs.average(),
            ))
            }
        }
        val baseErrs = ArrayList<Double>()
        for (fr in frames) {
            val pos = PositionSolver.solve(
                fr.rot, fr.upBase, fr.ctx,
                gravitySigmaDeg = fr.gravitySigma, attitudeSigmaDeg = fr.attitudeSigma,
                timeUncertaintySec = 0.2,
            ).fix ?: continue
            baseErrs.add(haversineKm(fr.truthLat, fr.truthLon, pos.latDeg, pos.lonDeg))
        }
        val baseMed = baseErrs.sorted()[baseErrs.size / 2]
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val d = m[col][col]
            for (k in col until 4) m[col][k] /= d
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * Math.asin(sqrt(h).coerceIn(0.0, 1.0))
    }
}
