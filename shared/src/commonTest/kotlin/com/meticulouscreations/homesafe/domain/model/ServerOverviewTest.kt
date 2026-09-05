package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.data.buildOverview
import com.meticulouscreations.homesafe.network.FrigateCameraPipeline
import com.meticulouscreations.homesafe.network.FrigateCameraPipelineConfig
import com.meticulouscreations.homesafe.network.FrigateDetector
import com.meticulouscreations.homesafe.network.FrigateModelInfo
import com.meticulouscreations.homesafe.network.FrigateRetention
import com.meticulouscreations.homesafe.network.FrigateServerConfig
import com.meticulouscreations.homesafe.network.FrigateServerStats
import com.meticulouscreations.homesafe.network.FrigateStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerOverviewTest {

    @Test
    fun uptimeReadsInTheTwoLargestUnits() {
        assertEquals("2 days, 12 hours", formatUptime(2 * 86_400L + 12 * 3_600 + 59 * 60))
        assertEquals("1 day, 0 hours", formatUptime(86_400))
        assertEquals("3 hours, 5 minutes", formatUptime(3 * 3_600L + 5 * 60 + 40))
        assertEquals("12 minutes", formatUptime(12 * 60L + 3))
        assertEquals("40 seconds", formatUptime(40))
        assertEquals("0 seconds", formatUptime(-5), "a clock skew never renders as negative")
    }

    @Test
    fun megabytesRenderLikeFrigatesOwnUi() {
        assertEquals("422.7 GB", formatMegabytes(422652.9))
        assertEquals("451.3 GB", formatMegabytes(451335.9))
        assertEquals("1.2 TB", formatMegabytes(1_200_000.0))
        assertEquals("512 MB", formatMegabytes(512.0))
        assertEquals("2 GB", formatMegabytes(2000.0), "no trailing .0")
    }

    @Test
    fun retentionAndPercentFormats() {
        assertEquals("7 days", formatRetentionDays(7.0))
        assertEquals("1 day", formatRetentionDays(1.0))
        assertEquals("1.5 days", formatRetentionDays(1.5))
        assertEquals("9%", formatPercent(9.4))
        assertEquals("—", formatPercent(null))
    }

    @Test
    fun updateAvailableOnlyWhenTheLatestReleaseIsNotThisOne() {
        val base = overview(version = "0.17.2-3d4dd3a", latest = "0.17.2")
        assertFalse(base.updateAvailable, "a build hash suffix doesn't make the same release look old")
        assertTrue(base.copy(latestVersion = "0.18.0").updateAvailable)
        assertFalse(base.copy(latestVersion = null).updateAvailable)
    }

    @Test
    fun switchesComeFromConfigAndThroughputFromStats() {
        val stats = stats(cameras = mapOf("hikvision_1" to FrigateCameraPipeline(5.1, 5.1, 0.2, 3.9)))
        val config = config(
            cameras = listOf(
                FrigateCameraPipelineConfig("hikvision_1", enabled = true, detectEnabled = false, motionEnabled = true),
                FrigateCameraPipelineConfig("new_cam", enabled = true, detectEnabled = true, motionEnabled = false),
            ),
        )
        val overview = buildOverview(stats, config, isAdmin = true)
        val hik = overview.cameras.first { it.name == "hikvision_1" }
        assertFalse(hik.detectionEnabled)
        assertTrue(hik.motionEnabled)
        assertEquals(5.1, hik.cameraFps)
        assertEquals(3.9, hik.detectionFps)
        val fresh = overview.cameras.first { it.name == "new_cam" }
        assertTrue(fresh.detectionEnabled)
        assertFalse(fresh.motionEnabled)
        assertEquals(null, fresh.cameraFps, "no stats row yet for a camera only in config")
        assertTrue(overview.canEditConfig)
    }

    @Test
    fun recordingsMountIsPreferredAndDetectorJoinsConfigWithStats() {
        val overview = buildOverview(
            stats(storage = mapOf("/dev/shm" to FrigateStorage(512.0, 61.1, 450.9), "/media/frigate/recordings" to FrigateStorage(451335.9, 422652.9, 5684.6))),
            config(),
            isAdmin = false,
        )
        assertEquals("/media/frigate/recordings", overview.recordingsStorage?.path)
        assertEquals(0.936f, overview.recordingsStorage!!.usedFraction, 0.001f)
        val detector = overview.detector!!
        assertEquals("onnx", detector.type)
        assertEquals("yolo-generic", detector.modelType)
        assertEquals("yolo.onnx", detector.modelFileName)
        assertEquals(6.78, detector.inferenceMs)
        assertFalse(overview.canEditConfig)
    }

    private fun stats(
        cameras: Map<String, FrigateCameraPipeline> = emptyMap(),
        storage: Map<String, FrigateStorage> = emptyMap(),
    ) = FrigateServerStats(
        version = "0.17.2-3d4dd3a", latestVersion = "0.17.2", uptimeSeconds = 100,
        cpuPercent = 9.4, memoryPercent = 23.4, storage = storage,
        detectors = listOf(FrigateDetector("onnx", 6.78)), gpus = emptyList(), cameras = cameras, totalDetectionFps = 1.0,
    )

    private fun config(cameras: List<FrigateCameraPipelineConfig> = emptyList()) = FrigateServerConfig(
        retention = FrigateRetention(2.0, 7.0, 10.0, 10.0),
        detectors = mapOf("onnx" to "onnx"),
        model = FrigateModelInfo("yolo-generic", "/config/model_cache/yolo.onnx", 320, 320),
        faceRecognitionEnabled = false, licensePlateRecognitionEnabled = false, semanticSearchEnabled = false,
        cameras = cameras,
    )

    private fun overview(version: String, latest: String?) = buildOverview(stats().copy(version = version, latestVersion = latest), config(), isAdmin = true)
}
