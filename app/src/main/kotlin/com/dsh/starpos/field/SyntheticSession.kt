// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsh.starpos.field

import com.dsh.starpos.astro.DeviceFrame
import com.dsh.starpos.astro.SyntheticScene
import com.dsh.starpos.astro.SyntheticSky
import com.dsh.starpos.astro.StarCatalog
import com.dsh.starpos.astro.V
import com.dsh.starpos.astro.apply
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/**
 * 生成“合成采集包”：磁盘格式与真机采集完全一致，只是像素/IMU/GNSS 由真值模型产生。
 *
 * 用途有两个：
 *  1) 在**没有真机**的情况下验证「采集 → 打包 → 带走 → 离线分析」整条异步链路；
 *  2) 现场采集失败时，用它做对照，判断是不是环境问题而不是代码问题。
 */
object SyntheticSession {

    /**
     * 合成样例的固定时刻（2026-07-15T17:00:00Z）：此时天鹅座/银河正好在默认观测点的天顶附近，
     * 星点密度足够稳定解算。之前用 System.currentTimeMillis()，随一天中的时间变化，
     * 有时会指向星稀的天区导致样例解不出来（2026-11-19 复现）。
     */
    const val DEMO_UTC_MS = 1784134800000L

    fun build(
        destDir: File,
        catalog: StarCatalog,
        scene: SyntheticScene,
        frameCount: Int = 3,
        gravityNoiseDeg: Double = 0.1,
        deviceInfo: Map<String, Any?> = mapOf(
            "model" to "synthetic-simulator",
            "manufacturer" to "dsh",
            "android_sdk" to 0,
            "note" to "由 SyntheticSession 生成，非真机数据",
        ),
    ): File {
        val frame = SyntheticSky.render(catalog, scene)
        val camera = frame.camera
        val baseNs = 5_000_000_000L
        val writer = BundleWriter(destDir)

        val intrinsics = IntrinsicsRecord(
            width = camera.width,
            height = camera.height,
            fx = camera.fx,
            fy = camera.fy,
            cx = camera.cx,
            cy = camera.cy,
            k1 = camera.k1,
            k2 = camera.k2,
            p1 = camera.p1,
            p2 = camera.p2,
            k3 = camera.k3,
            projection = "pinhole",
            source = "synthetic-truth",
        )

        val caps = CameraCaps(
            usedCameraId = "0",
            cameraIds = listOf("0"),
            physicalCameraIds = emptyList(),
            lensFacing = "back",
            hardwareLevel = "synthetic",
            activeArrayW = camera.width,
            activeArrayH = camera.height,
            pixelArrayW = camera.width,
            pixelArrayH = camera.height,
            sensorPhysicalWmm = 5.6,
            sensorPhysicalHmm = 7.5,
            focalLengthsMm = listOf(4.3),
            rawSupported = true,
            yuvSupported = true,
            timestampSource = "REALTIME",
            exposureRangeNs = listOf(100_000L, 30_000_000_000L),
            isoRange = listOf(25, 6400),
            intrinsicCalibration = emptyList(),
            distortion = emptyList(),
            oisUsed = false,
        )

        val metas = ArrayList<FrameMeta>()
        for (i in 0 until frameCount) {
            val pixels = ShortArray(frame.image.size) { k -> (frame.image[k] * 65535f).toInt().coerceIn(0, 65535).toShort() }
            val meta = FrameMeta(
                index = i,
                sensorTimestampNs = baseNs + i * 2_000_000_000L,
                elapsedRealtimeNs = baseNs + i * 2_000_000_000L,
                wallClockUtcMs = scene.utcMillis + i * 2_000L,
                exposureNs = (scene.exposureMs * 1_000_000).toLong(),
                iso = 800,
                focalLengthMm = 4.3,
                focusDistanceDiopters = 0.0,
                zoomRatio = 1.0,
                width = frame.width,
                height = frame.height,
                bitDepth = 16,
                source = "raw16",
                oisEnabled = false,
                awbMode = 0,
                frameDurationNs = 33_000_000L,
                note = "synthetic",
            )
            writer.writeFrame(meta, pixels, null)
            metas.add(meta)
        }

        // IMU：设备坐标系下的比力（静止时 ≈ +9.81·up_dev），采样 200 Hz
        val rnd = Random(scene.seed)
        val upCam = frame.truthUpCam
        val camToDev = DeviceFrame.deviceToCameraRear() // 自逆
        val upDev = apply(camToDev, upCam)
        val helper = if (abs(upDev[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val e1 = V.unit(V.cross(upDev, helper))
        val e2 = V.cross(upDev, e1)
        val imu = ArrayList<ImuRow>()
        val sigmaRad = gravityNoiseDeg * Math.PI / 180.0
        var t = baseNs - 1_000_000_000L
        val end = baseNs + frameCount * 2_000_000_000L + 1_000_000_000L
        while (t <= end) {
            val a = gauss(rnd) * sigmaRad
            val b = gauss(rnd) * sigmaRad
            val u = V.unit(V.add(upDev, V.add(V.scale(e1, a), V.scale(e2, b))))
            imu.add(ImuRow(t, u[0] * 9.81, u[1] * 9.81, u[2] * 9.81,
                gauss(rnd) * 0.002, gauss(rnd) * 0.002, gauss(rnd) * 0.002))
            t += 5_000_000L
        }
        writer.writeImu(imu)

        val truth = GnssRow(scene.utcMillis, scene.latDeg, scene.lonDeg, 12.0, 6.0, "synthetic")
        writer.writeGnss(listOf(truth))

        val manifest = SessionManifest(
            sessionId = "synthetic-${scene.seed}-${scene.utcMillis}",
            createdAtUtcMs = scene.utcMillis,
            appVersion = "0.1.0-M1",
            device = deviceInfo,
            camera = caps,
            intrinsics = intrinsics,
            capture = linkedMapOf(
                "frames" to frameCount,
                "exposure_ns" to (scene.exposureMs * 1_000_000).toLong(),
                "iso" to 800,
                "focus" to "infinity",
                "mode" to "synthetic",
                "cloud_cover_injected" to scene.cloudCover,
                "device_to_camera" to "diag(1,-1,-1) rear camera",
            ),
            clock = linkedMapOf(
                "auto_time" to true,
                "network" to true,
                "uncertainty_ms" to 250.0,
                "source" to "synthetic",
            ),
            note = "合成采集包：真值 lat=${scene.latDeg} lon=${scene.lonDeg}",
            frames = metas,
            gnssTruth = truth,
        )
        writer.writeManifest(manifest)
        writer.writeNotes("合成会话，用于验证异步调试链路；真值经纬度见 session.json 的 gnss_truth。")

        val zip = File(destDir.parentFile ?: destDir, "${destDir.name}.zip")
        writer.zipTo(zip)
        return zip
    }

    private fun gauss(rnd: Random): Double {
        var u = rnd.nextDouble()
        if (u < 1e-12) u = 1e-12
        return sqrt(-2.0 * ln(u)) * cos(2 * PI * rnd.nextDouble())
    }
}

