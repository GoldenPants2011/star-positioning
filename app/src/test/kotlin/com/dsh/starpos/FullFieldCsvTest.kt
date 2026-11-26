package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.StarVertical
import com.dsh.starpos.field.JsonText
import com.dsh.starpos.field.asMap
import com.dsh.starpos.field.FieldAnalyzer
import com.dsh.starpos.field.MirrorPair
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Test

/**
 * 全量跑 field-inbox 里的 session，用当前在用的方法（FieldAnalyzer + 星解 + 水镜配对），
 * 输出长表 CSV：每行带 type（session/frame/sun/moon/twobody/mirror），供离线转 Excel 多 sheet。
 */
class FullFieldCsvTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun csv(s: String?): String {
        if (s == null) return ""
        val v = s.replace("\r", " ").replace("\n", " ")
        return if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private fun num(v: Double, digits: Int = 4): String =
        if (v.isNaN() || v.isInfinite()) "" else "%.${digits}f".format(v)

    @Test
    fun `全量 field-inbox 结果`() {
        val root = File(".").absoluteFile.let { d ->
            var x = d
            repeat(4) {
                if (File(x, "settings.gradle.kts").isFile) return@let x
                x = x.parentFile ?: x
            }
            x
        }
        val inbox = File(root, "field-inbox")
        val extrinsic = ExtrinsicConfig.load(File(root, "config/extrinsic.json"))
        val catalog = Fixtures.catalog
        val index = SkyIndex(catalog)
        val zips = inbox.listFiles { f -> f.name.endsWith(".zip") }?.sortedBy { it.name } ?: return
        val out = File(inbox, "full_report.csv")
        // Excel 本地打开 CSV 时默认按系统 ANSI 解码 → 必须写 UTF-8 BOM
        val fos = java.io.FileOutputStream(out)
        fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        fos.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(
                listOf(
                    "type", "session_id", "created_local", "camera_id", "frames", "exposures_ms", "iso_list",
                    "note", "status", "gnss_lat", "gnss_lon", "gnss_acc_m", "gnss_provider",
                    "frame", "exposure_ms", "iso", "detections", "matched", "rms_arcsec", "frame_solved",
                    "sun_frames", "sun_resid_mean", "sun_resid_rms",
                    "moon_frames", "moon_resid_mean", "moon_resid_rms",
                    "moon_fix_lat", "moon_fix_lon", "moon_fix_rms_deg", "moon_fix_err_km",
                    "two_body_obs", "two_body_span_min", "two_body_fix_lat", "two_body_fix_lon",
                    "two_body_rms_deg", "two_body_err_km",
                    "mirror_pairs", "mirror_alt_deg", "mirror_residual_deg",
                    "summary",
                ).joinToString(",") + "\n",
            )
            println("共 ${zips.size} 个会话")
            for ((si, zip) in zips.withIndex()) {
                val t0 = System.currentTimeMillis()
                val rows = ArrayList<List<String>>()
                BundleReader.open(zip).use { reader ->
                    val m = reader.manifest
                    val created = runCatching { Instant.ofEpochMilli(m.createdAtUtcMs).atZone(zone).format(fmt) }
                        .getOrDefault("")
                    val truth = reader.readGnss().lastOrNull()
                    val metas = reader.frameMetas()
                    val imuRows = runCatching { reader.readImu() }.getOrDefault(emptyList())
                    val exps = metas.map { it.exposureNs / 1_000_000.0 }
                    val isos = metas.map { it.iso }.distinct()
                    val camId = m.camera?.usedCameraId ?: ""
                    val note = m.note
                    // 1) 当前在用的离线分析
                    // 老会话 manifest 没记录零偏 → 用六位置标定值兜底；星光分支用独立垂直修正
        val bias = runCatching {
            val txt = File(root, "config/accel-bias-mg.json").readText()
            val arr = (JsonText.parse(txt).asMap()["bias_mg"] as? List<*>)!!
                .mapNotNull { (it as? Number)?.toDouble() }
            doubleArrayOf(arr[0] * 9.80665e-3, arr[1] * 9.80665e-3, arr[2] * 9.80665e-3)
        }.getOrNull()
        val starVertical = StarVertical.load(File(root, "config/star-vertical.json"))
        val analysis = FieldAnalyzer.analyze(
            reader, catalog, extrinsic,
            starVertical = starVertical, accelBiasOverride = bias,
        )
                    val findings = analysis.findings
                    var sunFrames = -1
                    var sunMean = Double.NaN
                    var sunRms = Double.NaN
                    var moonFrames = -1
                    var moonMean = Double.NaN
                    var moonRms = Double.NaN
                    var moonFixLat = Double.NaN
                    var moonFixLon = Double.NaN
                    var moonFixRms = Double.NaN
                    var moonFixErr = Double.NaN
                    var tbObs = -1
                    var tbSpan = Double.NaN
                    var tbLat = Double.NaN
                    var tbLon = Double.NaN
                    var tbRms = Double.NaN
                    var tbErr = Double.NaN
                    for (f in findings) {
                        Regex("太阳：识别 (\\d+) 帧，高度残差 均值 ([-+0-9.]+)°、RMS ([0-9.]+)°").find(f)?.let {
                            sunFrames = it.groupValues[1].toInt()
                            sunMean = it.groupValues[2].toDouble()
                            sunRms = it.groupValues[3].toDouble()
                        }
                        Regex("月亮：识别 (\\d+) 帧，高度残差 均值 ([-+0-9.]+)°、RMS ([0-9.]+)°").find(f)?.let {
                            moonFrames = it.groupValues[1].toInt()
                            moonMean = it.groupValues[2].toDouble()
                            moonRms = it.groupValues[3].toDouble()
                        }
                        Regex("月亮轨道解算：([-0-9.]+), ([-0-9.]+)（rms ([0-9.]+)°，跨度 [0-9]+ min，[0-9]+ 次观测）(?:，与真值差 ([0-9.]+) km)?").find(f)?.let {
                            moonFixLat = it.groupValues[1].toDouble()
                            moonFixLon = it.groupValues[2].toDouble()
                            moonFixRms = it.groupValues[3].toDouble()
                            moonFixErr = it.groupValues[4].toDouble()
                        }
                        Regex("两体姿态定位：([-0-9.]+), ([-0-9.]+)（rms ([0-9.]+)°，跨度 ([0-9.]+) min，([0-9]+) 个天体观测）(?:，与真值差 ([0-9.]+) km)?").find(f)?.let {
                            tbLat = it.groupValues[1].toDouble()
                            tbLon = it.groupValues[2].toDouble()
                            tbRms = it.groupValues[3].toDouble()
                            tbSpan = it.groupValues[4].toDouble()
                            tbObs = it.groupValues[5].toInt()
                            tbErr = it.groupValues[6].toDouble()
                        }
                        if (f.contains("两体姿态：识别到")) {
                            Regex("识别到 (\\d+) 次双天体观测（跨度 ([0-9.]+) min）").find(f)?.let {
                                tbObs = it.groupValues[1].toInt()
                                tbSpan = it.groupValues[2].toDouble()
                            }
                        }
                    }
                    // 2) 逐帧星解 + 水镜配对（用当前方法）
                    val intr = m.intrinsics
                    var mirrorPairs = 0
                    var mirrorAlt = Double.NaN
                    var mirrorResid = Double.NaN
                    var frameRows = ArrayList<List<String>>()
                    var solvedFrames = 0
                    for (meta in metas) {
                        val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                        var detections = 0
                        var matched = 0
                        var rmsArc = Double.NaN
                        var solved = false
                        val cam: CameraIntrinsics? = intr?.toCameraIntrinsics(f.width, f.height)
                        if (cam != null) {
                            var det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                            detections = det.detections.size
                            var starLike = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                            if (starLike.size >= 6) {
                                val solver = PlateSolver(
                                    index, cam,
                                    maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx),
                                )
                                var sol = runCatching { solver.solve(starLike) }.getOrNull()
                                if (sol == null || sol.matchedStars < 5) {
                                    for (thr in doubleArrayOf(5.5, 9.0)) {
                                        val d2 = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = thr)
                                        val s2 = d2.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                                        val p2 = runCatching { solver.solve(s2) }.getOrNull() ?: continue
                                        if (sol == null || p2.matchedStars > sol.matchedStars) { sol = p2; starLike = s2 }
                                    }
                                }
                                if (sol != null) {
                                    matched = sol.matchedStars
                                    rmsArc = sol.rmsArcsec
                                    solved = sol.matchedStars >= 5
                                }
                            }
                            // 水镜配对（只在天体在地平线上时有意义；用 IMU 天顶做 upHint）
                            if (truth != null && (meta.iso in 50..6400)) {
                                runCatching {
                                    val gast = SkyContext.at(meta.wallClockUtcMs).gast
                                    val site = Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon))
                                    val moonAlt = Math.toDegrees(Moon.altitudeRad(site, Moon.ephemerisAt(meta.wallClockUtcMs), meta.wallClockUtcMs, gast))
                                    val g = FieldAnalyzer.gravityAt(imuRows, meta, m.camera?.sensorOrientation ?: 90, extrinsic = extrinsic)
                                    if (moonAlt > 5 && g.isStatic) {
                                        val pair = MirrorPair.findPair(f.luminance, f.width, f.height, cam, upHint = g.upCam)
                                        if (pair != null && pair.first.residualDeg < 3.0) {
                                            mirrorPairs++
                                            if (mirrorAlt.isNaN()) {
                                                mirrorAlt = Math.toDegrees(pair.first.apparentAltRad)
                                                mirrorResid = pair.first.residualDeg
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (solved) solvedFrames++
                        frameRows.add(
                            listOf(
                                "frame", zip.name, created, camId, metas.size.toString(),
                                "", "", "", "", "", "", "", "",
                                meta.index.toString(), num(meta.exposureNs / 1_000_000.0, 2), meta.iso.toString(),
                                detections.toString(), matched.toString(), num(rmsArc, 1), solved.toString(),
                                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                                "",
                            ),
                        )
                    }
                    val status = when {
                        sunFrames > 0 || moonFrames > 0 || solvedFrames > 0 || tbObs > 0 -> "可解"
                        metas.isEmpty() -> "不可解（无帧）"
                        truth == null -> "不可解（无 GNSS 真值且无星解）"
                        findings.any { it.contains("所有帧都没有解出位置") } -> "不可解（未检出目标/星图匹配失败）"
                        else -> "不可解"
                    }
                    val summary = findings
                        .filter { !it.startsWith("模型：") && !it.startsWith("内参：") }
                        .joinToString(" | ")
                        .take(600)
                    val sessionRow = listOf(
                        "session", zip.name, created, camId, metas.size.toString(),
                        exps.joinToString("/"), isos.joinToString("/"), note, status,
                        truth?.lat?.toString() ?: "", truth?.lon?.toString() ?: "",
                        truth?.accuracyM?.let { num(it, 1) } ?: "", truth?.provider ?: "",
                        "", "", "", "", "", "", "",
                        if (sunFrames >= 0) sunFrames.toString() else "", num(sunMean, 3), num(sunRms, 3),
                        if (moonFrames >= 0) moonFrames.toString() else "", num(moonMean, 3), num(moonRms, 3),
                        num(moonFixLat), num(moonFixLon), num(moonFixRms, 3), num(moonFixErr, 1),
                        if (tbObs >= 0) tbObs.toString() else "", num(tbSpan, 1), num(tbLat), num(tbLon), num(tbRms, 3), num(tbErr, 1),
                        mirrorPairs.toString(), num(mirrorAlt, 3), num(mirrorResid, 3),
                        summary,
                    )
                    rows.add(sessionRow)
                    if (!sunMean.isNaN()) {
                        val r = sessionRow.toMutableList()
                        r[0] = "sun"
                        rows.add(r)
                    }
                    if (!moonMean.isNaN()) {
                        val r = sessionRow.toMutableList()
                        r[0] = "moon"
                        rows.add(r)
                    }
                    if (tbObs > 0) {
                        val r = sessionRow.toMutableList()
                        r[0] = "twobody"
                        rows.add(r)
                    }
                    if (mirrorPairs > 0) {
                        val r = sessionRow.toMutableList()
                        r[0] = "mirror"
                        rows.add(r)
                    }
                    rows.addAll(frameRows)
                }
                for (r in rows) w.write(r.joinToString(",") { csv(it) } + "\n")
                println("[${si + 1}/${zips.size}] ${zip.name}  行 ${rows.size}  ${(System.currentTimeMillis() - t0) / 1000}s")
            }
        }
        println("已写出：${out.absolutePath}")
    }
}
