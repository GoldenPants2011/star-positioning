package com.dsh.starpos

import com.dsh.starpos.astro.CameraIntrinsics
import com.dsh.starpos.astro.Detection
import com.dsh.starpos.astro.FrameAnalyzer
import com.dsh.starpos.astro.PlateSolver
import com.dsh.starpos.astro.SkyContext
import com.dsh.starpos.astro.SkyIndex
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import com.dsh.starpos.astro.transpose
import com.dsh.starpos.field.BundleReader
import java.io.File
import org.junit.Test

/**
 * 用已拍的星空数据做**镜头畸变自标定**（无穷远对焦，正是实际使用条件）：
 * 由星点"预测位置 vs 实测位置"的一阶径向畸变关系，最小二乘估 k1。
 */
class LensSelfCalibTest {

    private fun projectRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return dir
        }
        return File(".").absoluteFile
    }

    @Test
    fun `星空自标定 k1`() {
        val root = projectRoot()
        val inbox = File(root, "field-inbox")
        val zips = inbox.listFiles { f ->
            f.name.endsWith(".zip") &&
                (f.name >= "session-20261122-000000" || f.name.startsWith("session-20261120-05") ||
                    f.name.startsWith("session-20261121-00"))
        }?.sortedBy { it.name } ?: return
        val index = SkyIndex(Fixtures.catalog)
        val catPos = HashMap<Int, Int>()
        index.ids.forEachIndexed { i, id -> catPos[id] = i }
        var num = 0.0
        var den = 0.0
        val perFrame = ArrayList<Pair<String, Double>>()
        var used = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val filtered = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    if (filtered.size < 20) continue
                    val solver = PlateSolver(index, cam, maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx))
                    val sol = runCatching { solver.solve(filtered) }.getOrNull() ?: continue
                    if (sol.matchedStars < 8) continue
                    // 逐星：预测像素 vs 实测像素 → k1 线性项
                    var fn = 0.0
                    var fd = 0.0
                    for (m in sol.matches) {
                        val d: Detection = filtered.getOrNull(m.detectionIndex) ?: continue
                        val catIdx = catPos[m.catalogIndex] ?: continue
                        if (catIdx < 0) continue
                        val vSky = index.vectorOf(catIdx)
                        val vCam = V.unit(apply(transpose(sol.rotation), vSky))
                        if (vCam[2] <= 0.05) continue
                        val xu = vCam[0] / vCam[2]
                        val yu = vCam[1] / vCam[2]
                        val r2 = xu * xu + yu * yu
                        val predX = cam.fx * xu + cam.cx
                        val predY = cam.fy * yu + cam.cy
                        val dx = d.x - predX
                        val dy = d.y - predY
                        val ax = cam.fx * xu * r2
                        val ay = cam.fy * yu * r2
                        fn += ax * dx + ay * dy
                        fd += ax * ax + ay * ay
                    }
                    if (fd > 1e-9) {
                        num += fn
                        den += fd
                        perFrame.add(zip.name to fn / fd)
                        used++
                    }
                }
            }
        }
        println("参与帧数：$used")
        val k1 = num / den
        println("全局最小二乘 k1 = %.5f".format(k1))
        perFrame.take(20).forEach { (n, k) -> println("  ${n.removePrefix("session-").removeSuffix(".zip")}: k1=%.4f".format(k)) }
        // 残差改善检查：对每一帧用 k1 重新投影星点，比较像素残差
        var before = 0.0
        var after = 0.0
        var n = 0
        for (zip in zips) {
            BundleReader.open(zip).use { reader ->
                val intr = reader.manifest.intrinsics ?: return@use
                for (meta in reader.frameMetas()) {
                    val f = runCatching { reader.loadFrame(meta.index) }.getOrNull() ?: continue
                    val cam = intr.toCameraIntrinsics(f.width, f.height)
                    val det = FrameAnalyzer.analyze(f.luminance, f.width, f.height, thresholdSigma = 7.0)
                    val filtered = det.detections.filter { it.aspect < 2.0 && it.area >= 3 }
                    if (filtered.size < 20) continue
                    val solver = PlateSolver(index, cam, maxResidualArcsec = kotlin.math.max(60.0, 2.0 * 206264.8 / cam.fx))
                    val sol = runCatching { solver.solve(filtered) }.getOrNull() ?: continue
                    if (sol.matchedStars < 8) continue
                    val camDist = CameraIntrinsics(
                        cam.width, cam.height, cam.fx, cam.fy, cam.cx, cam.cy,
                        k1 = k1, k2 = 0.0, p1 = 0.0, p2 = 0.0, k3 = 0.0,
                        projection = cam.projection,
                    )
                    for (m in sol.matches) {
                        val d: Detection = filtered.getOrNull(m.detectionIndex) ?: continue
                        val catIdx = catPos[m.catalogIndex] ?: continue
                        if (catIdx < 0) continue
                        val vSky = index.vectorOf(catIdx)
                        val vCam = V.unit(apply(transpose(sol.rotation), vSky))
                        if (vCam[2] <= 0.05) continue
                        val xu = vCam[0] / vCam[2]
                        val yu = vCam[1] / vCam[2]
                        val p0 = cam.project(vCam) ?: continue
                        val p1 = camDist.project(vCam) ?: continue
                        before += (d.x - p0[0]) * (d.x - p0[0]) + (d.y - p0[1]) * (d.y - p0[1])
                        after += (d.x - p1[0]) * (d.x - p1[0]) + (d.y - p1[1]) * (d.y - p1[1])
                        n++
                    }
                }
            }
        }
        if (n > 0) {
            println("星点像素残差 RMS：无畸变 %.2f px → k1=%.4f 后 %.2f px （%d 点）".format(
                Math.sqrt(before / n), k1, Math.sqrt(after / n), n,
            ))
        }
    }
}
