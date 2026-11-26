// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.field.BundleReader
import com.dsh.starpos.field.ExtrinsicConfig
import com.dsh.starpos.field.FieldAnalyzer
import java.io.File
import kotlin.math.abs
import org.junit.Test

/**
 * 垂直修正（dt）对"太阳/月亮高度"路径的留出集复验：
 * 同一组日/月会话，分别用旧外参与"dt 合成后的新外参"跑 FieldAnalyzer，
 * 对比 findings 里的"高度残差 均值"。
 *
 * dt 来自 VerticalRevalidateTest（星位拟合肥合，2026-11-24）：
 *   dt = (3.964, 1.129, -4.635)°   M' = M_ext · M_dev · R(dt) · M_dev⁻¹
 * 只输出数据，不改配置、不出 APK。
 */
class VerticalSunMoonHoldoutTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    private fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val c = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i][k] * b[k][j]
            c[i][j] = s
        }
        return c
    }
    private fun matT(a: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> a[j][i] } }

    private fun extMatrix(ext: ExtrinsicConfig): Array<DoubleArray> {
        val mm = ext.m
        if (mm != null) return arrayOf(
            doubleArrayOf(mm[0], mm[1], mm[2]),
            doubleArrayOf(mm[3], mm[4], mm[5]),
            doubleArrayOf(mm[6], mm[7], mm[8]),
        )
        return arrayOf(
            doubleArrayOf(1.0, -ext.rz, ext.ry),
            doubleArrayOf(ext.rz, 1.0, -ext.rx),
            doubleArrayOf(-ext.ry, ext.rx, 1.0),
        )
    }

    private fun composedConfig(old: ExtrinsicConfig, dtDeg: DoubleArray): ExtrinsicConfig {
        val d = doubleArrayOf(
            Math.toRadians(dtDeg[0]), Math.toRadians(dtDeg[1]), Math.toRadians(dtDeg[2]),
        )
        val r = arrayOf(
            doubleArrayOf(1.0, -d[2], d[1]),
            doubleArrayOf(d[2], 1.0, -d[0]),
            doubleArrayOf(-d[1], d[0], 1.0),
        )
        val mdev = DeviceFrame.cameraFromDevice(90)
        val mp = matMul(matMul(matMul(extMatrix(old), mdev), r), matT(mdev))
        val flat = doubleArrayOf(
            mp[0][0], mp[0][1], mp[0][2],
            mp[1][0], mp[1][1], mp[1][2],
            mp[2][0], mp[2][1], mp[2][2],
        )
        return ExtrinsicConfig(0.0, 0.0, 0.0, note = "vertical-dt", source = "test", m = flat)
    }

    private fun resid(a: com.dsh.starpos.field.SessionAnalysis): Double {
        for (f in a.findings) {
            Regex("高度残差 均值 ([+-]?[0-9.]+)°").find(f)?.let { return it.groupValues[1].toDouble() }
        }
        return Double.NaN
    }

    @Test
    fun `日月高度留出集新旧外参对比`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val oldCfg = ExtrinsicConfig.load(File(root, "config/extrinsic.json")) ?: run {
            println("无外参配置"); return
        }
        // 来自 VerticalRevalidateTest 的拟合值（设备系小倾角，度）
        val newCfg = composedConfig(oldCfg, doubleArrayOf(3.964, 1.129, -4.635))
        println("旧外参：%s；新外参 m' 已合成（见 VerticalRevalidateTest）".format(oldCfg.source))

        val sets = linkedMapOf<String, List<File>>()
        // ExtrinsicRefitValidateTest 用的同一留出集：20261121 下午太阳 + 19:00 月亮
        sets["留出集(21日下午/19点)"] = inbox.listFiles { f ->
            f.name.endsWith(".zip") && (
                f.name.startsWith("session-20261121-15") ||
                    f.name.startsWith("session-20261121-16") ||
                    f.name.startsWith("session-20261121-190")
                )
        }?.sortedBy { it.name } ?: emptyList()
        // 今天(11-24)下午太阳会话（与星位 dt 训练数据完全独立）
        sets["今天(11-24)太阳会话"] = inbox.listFiles { f ->
            f.name.endsWith(".zip") && f.name.startsWith("session-20261124-15")
        }?.sortedBy { it.name } ?: emptyList()

        for ((label, zips) in sets) {
            println("=".repeat(72))
            println(label)
            var sumOld = 0.0
            var sumNew = 0.0
            var n = 0
            for (zip in zips) {
                BundleReader.open(zip).use { reader ->
                    val aOld = runCatching { FieldAnalyzer.analyze(reader, Fixtures.catalog, oldCfg) }.getOrNull()
                    val aNew = runCatching { FieldAnalyzer.analyze(reader, Fixtures.catalog, newCfg) }.getOrNull()
                    if (aOld == null || aNew == null) { println("${zip.name}: 分析失败"); return@use }
                    val rOld = resid(aOld)
                    val rNew = resid(aNew)
                    if (rOld.isNaN() || rNew.isNaN()) {
                        // 没有高度残差时打印原因（两体/月面不可用等）
                        val why = aOld.findings.firstOrNull { it.contains("高度") || it.contains("月亮") || it.contains("太阳") }
                        println("${zip.name}: 无高度残差（%s）".format((why ?: "无").take(80)))
                        return@use
                    }
                    println("${zip.name}: 高度残差 旧 %+.3f° → 新 %+.3f°（变化 %+.3f°）".format(
                        rOld, rNew, rNew - rOld,
                    ))
                    sumOld += abs(rOld); sumNew += abs(rNew); n++
                }
            }
            if (n > 0) {
                println("→ %d 个包：|高度残差| 均值 旧 %.3f° → 新 %.3f°".format(n, sumOld / n, sumNew / n))
            }
        }
    }
}
