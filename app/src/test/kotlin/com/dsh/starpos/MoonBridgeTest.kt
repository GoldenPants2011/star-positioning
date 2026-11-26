// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.Moon
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.V
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import org.junit.Test

/**
 * 月亮模式桥接验证：帧内月心 → 相机方向向量 → 与 IMU 垂直方向的夹角 → 视高度角
 * → 折射改正 → 与星历顶心高度对比（用 GNSS 真值算期望值）。
 *
 * 若单帧高度残差足够小（<0.2°），说明"帧 → 高度角"的桥接可用；
 * 位置解算（多历元 LOP）需要 1.5 h 以上的时间跨度，本组数据只有 ~1 分钟，只做残差验证。
 */
class MoonBridgeTest {

    @Test
    fun `月亮帧到高度角的桥接残差`() {
        val zip = File("../field-inbox/session-20261120-214304.zip")
        if (!zip.isFile) {
            println("跳过：找不到 $zip")
            return
        }
        val extrinsic = ExtrinsicConfig.load(File("../config/extrinsic.json"))
        BundleReader.open(zip).use { reader ->
            val truth = reader.readGnss().lastOrNull()
            val intr = reader.manifest.intrinsics ?: return
            val imu = reader.readImu()
            val metas = reader.frameMetas()
            println("=== ${zip.name} 帧数=${metas.size} 真值=${truth?.lat},${truth?.lon} ===")
            val sights = ArrayList<Moon.Sight>()
            for (m in metas) {
                val f = runCatching { reader.loadFrame(m.index) }.getOrNull() ?: continue
                val cam = intr.toCameraIntrinsics(f.width, f.height)
                val g = FieldAnalyzer.gravityAt(
                    imu, m, reader.manifest.camera?.sensorOrientation ?: 90,
                    extrinsic = extrinsic,
                )
                if (!g.valid) {
                    println("帧 ${m.index}: IMU 重力无效")
                    continue
                }
                // 月面检测：优先圆面拟合；过曝时退回"最亮连通域质心"
                val fit = Moon.fitDisk(f.luminance, f.width, f.height)
                val c = moonCenter(f.luminance, f.width, f.height)
                val useFit = fit != null && fit.radiusPx > 4.0 && fit.radiusPx < min(f.width, f.height) * 0.25
                val px = if (useFit) fit!!.cx else c?.first ?: continue
                val py = if (useFit) fit!!.cy else c?.second ?: continue
                val dir = cam.unproject(px, py)
                val cosZ = (dir[0] * g.upCam[0] + dir[1] * g.upCam[1] + dir[2] * g.upCam[2])
                    .coerceIn(-1.0, 1.0)
                val zApp = acos(cosZ)
                val altApp = Math.PI / 2 - zApp
                val altTrue = altApp - Moon.refractionRad(altApp)
                val ephem = Moon.ephemerisAt(m.wallClockUtcMs)
                val gast = SkyContext.at(m.wallClockUtcMs).gast
                // 期望值（用 GNSS 真值站心 + 视差 + 折射）
                val expected = if (truth != null) {
                    Moon.altitudeRad(
                        Moon.Site(Math.toRadians(truth.lat), Math.toRadians(truth.lon)),
                        ephem, m.wallClockUtcMs, gast,
                    )
                } else Double.NaN
                val residDeg = Math.toDegrees(altTrue - expected)
                println(
                    "帧 %d: %s 月心=(%.1f,%.1f) 半径=%s 高度=%.3f° 期望=%.3f° 残差=%+.3f°".format(
                        m.index,
                        if (useFit) "fitDisk" else "质心",
                        px, py,
                        if (useFit) "%.1fpx".format(fit!!.radiusPx) else "—",
                        Math.toDegrees(altTrue), Math.toDegrees(expected), residDeg,
                    ),
                )
                if (!residDeg.isNaN()) {
                    sights.add(
                        Moon.Sight(
                            utcMillis = m.wallClockUtcMs,
                            gast = gast,
                            altRad = altTrue,
                            sigmaDeg = max(0.15, g.sigmaDeg),   // 重力/外参主导
                            raRad = ephem.raRad,
                            decRad = ephem.decRad,
                            distanceKm = ephem.distanceKm,
                        ),
                    )
                }
            }
            if (sights.size >= 2) {
                val fix = Moon.solveOrbit(sights)
                println("轨道解算：${fix?.let { "%.4f, %.4f（rms %.3f°）".format(it.latDeg, it.lonDeg, it.rmsDeg) } ?: "失败（几何不足）"}")
                if (fix != null && truth != null) {
                    val d = haversineKm(fix.latDeg, fix.lonDeg, truth.lat, truth.lon)
                    println("与真值差：%.1f km（本组跨度仅 %.1f min，几何退化，仅供参考）".format(
                        d, (sights.maxOf { it.utcMillis } - sights.minOf { it.utcMillis }) / 60_000.0))
                }
                fix?.warnings?.forEach { println("警告：$it") }
            }
        }
    }

    /**
     * 月面中心：以全局最亮点为中心取窗口，扣局部背景后按亮度²加权求质心。
     * 对"小而亮的月面 + 不对称光晕"比全局阈值质心稳得多。
     */
    private fun moonCenter(lum: FloatArray, w: Int, h: Int): Pair<Double, Double>? {
        var best = -1.0f
        var bx = 0
        var by = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = lum[row + x]
                if (v > best) {
                    best = v
                    bx = x
                    by = y
                }
            }
        }
        if (best < 0.05f) return null
        val r = 24
        val x0 = (bx - r).coerceAtLeast(0)
        val x1 = (bx + r).coerceAtMost(w - 1)
        val y0 = (by - r).coerceAtLeast(0)
        val y1 = (by + r).coerceAtMost(h - 1)
        // 背景：窗口内 20% 分位
        val samples = ArrayList<Float>()
        for (y in y0..y1) for (x in x0..x1) samples.add(lum[y * w + x])
        samples.sort()
        val bg = samples[(samples.size * 0.2).toInt().coerceIn(0, samples.size - 1)]
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val dv = lum[row + x] - bg
                if (dv <= 0f) continue
                val wgt = (dv * dv).toDouble()
                sx += x * wgt
                sy += y * wgt
                sw += wgt
            }
        }
        if (sw <= 0.0) return null
        return (sx / sw) to (sy / sw)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(min(1.0, Math.sqrt(a)))
    }
}
