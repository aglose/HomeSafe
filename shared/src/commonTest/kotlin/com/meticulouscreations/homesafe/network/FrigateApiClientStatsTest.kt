package com.meticulouscreations.homesafe.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrigateApiClientStatsTest {

    /** Trimmed from `GET /api/stats` on Frigate 0.17.2: the per-pid `cpu_usages` rows are dropped, everything else verbatim. */
    private val statsJson = """{
      "cameras":{
        "hikvision_1":{"camera_fps":5.1,"process_fps":5.1,"skipped_fps":0.0,"detection_fps":5.7,"detection_enabled":true,"pid":762},
        "amcrest_1":{"camera_fps":5.1,"process_fps":5.1,"skipped_fps":0.3,"detection_fps":0.0,"detection_enabled":false,"pid":1018}},
      "detectors":{"onnx":{"inference_speed":6.78,"detection_start":1788623894.532921,"pid":698}},
      "camera_fps":15.3,"process_fps":15.3,"skipped_fps":0.0,"detection_fps":14.2,"embeddings":{},
      "gpu_usages":{"NVIDIA GeForce RTX 2070 SUPER":{"gpu":"8.0%","mem":"9.09%","enc":"0.0%","dec":"1.0%"}},
      "cpu_usages":{"frigate.full_system":{"cpu":"9.4","mem":"23.4"},"137":{"cpu":"3.2","cpu_average":"3","mem":"3.3","cmdline":"python3 -u -m frigate"}},
      "service":{"uptime":217087,"version":"0.17.2-3d4dd3a","latest_version":"0.17.2",
        "storage":{"/media/frigate/recordings":{"total":451335.9,"used":422652.9,"free":5684.6,"mount_type":"ext4"},
                   "/dev/shm":{"total":512.0,"used":61.1,"free":450.9,"mount_type":"tmpfs"}},
        "temperatures":{},"last_updated":1788623909},
      "processes":{"recording":{"pid":627}}
    }"""

    /** The server-wide parts of `GET /api/config` the Settings tab reads, shaped as 0.17.2 serves them. */
    private val configJson = """{
      "cameras":{
        "hikvision_1":{"enabled":true,"detect":{"enabled":true,"width":640,"height":360},"motion":{"enabled":true,"mask":""}},
        "amcrest_1":{"enabled":true,"detect":{"enabled":false,"width":704,"height":480},"motion":{"enabled":false,"mask":""}}},
      "record":{"enabled":true,"continuous":{"days":2.0},"motion":{"days":7.0},
                "detections":{"retain":{"days":10.0,"mode":"motion"}},"alerts":{"retain":{"days":10.0,"mode":"motion"}}},
      "detectors":{"onnx":{"type":"onnx","model":{"path":"/config/model_cache/yolo.onnx","width":320,"height":320}}},
      "model":{"path":"/config/model_cache/yolo.onnx","width":320,"height":320,"model_type":"yolo-generic","labelmap":{}},
      "face_recognition":{"enabled":false,"model_size":"small"},
      "lpr":{"enabled":true},
      "semantic_search":{"enabled":false,"model":"jinav1"}
    }"""

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient =
        HttpClient(MockEngine { req ->
            val (status, body) = handler(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun statsDistilToWhatTheSettingsTabShows() = runTest {
        val api = FrigateApiClient(client { HttpStatusCode.OK to statsJson })
        val stats = api.getStats("http://frigate:8971").getOrThrow()

        assertEquals("0.17.2-3d4dd3a", stats.version)
        assertEquals("0.17.2", stats.latestVersion)
        assertEquals(217087L, stats.uptimeSeconds)
        assertEquals(9.4, stats.cpuPercent)
        assertEquals(23.4, stats.memoryPercent)
        assertEquals(422652.9, stats.storage.getValue("/media/frigate/recordings").usedMb)
        assertEquals(listOf(FrigateDetector("onnx", 6.78)), stats.detectors)
        assertEquals(FrigateGpu("NVIDIA GeForce RTX 2070 SUPER", 8.0, 9.09, 1.0), stats.gpus.single(), "percent strings lose their sign")
        assertEquals(5.7, stats.cameras.getValue("hikvision_1").detectionFps)
        assertEquals(0.3, stats.cameras.getValue("amcrest_1").skippedFps)
        assertEquals(14.2, stats.totalDetectionFps)
    }

    @Test
    fun percentStringsInEveryShapeFrigateUses() {
        assertEquals(8.0, parseFrigatePercent("8.0%"))
        assertEquals(9.4, parseFrigatePercent("9.4"))
        assertEquals(0.0, parseFrigatePercent("0"))
        assertNull(parseFrigatePercent(null))
        assertNull(parseFrigatePercent("N/A"))
    }

    @Test
    fun serverConfigReadsRetentionDetectorFeaturesAndCameraSwitches() = runTest {
        val api = FrigateApiClient(client { HttpStatusCode.OK to configJson })
        val config = api.getServerConfig("http://frigate:8971").getOrThrow()

        assertEquals(FrigateRetention(continuousDays = 2.0, motionDays = 7.0, alertDays = 10.0, detectionDays = 10.0), config.retention)
        assertEquals(mapOf("onnx" to "onnx"), config.detectors)
        assertEquals(FrigateModelInfo("yolo-generic", "/config/model_cache/yolo.onnx", 320, 320), config.model)
        assertFalse(config.faceRecognitionEnabled)
        assertTrue(config.licensePlateRecognitionEnabled)
        assertFalse(config.semanticSearchEnabled)
        assertEquals(
            listOf(
                FrigateCameraPipelineConfig("hikvision_1", enabled = true, detectEnabled = true, motionEnabled = true),
                FrigateCameraPipelineConfig("amcrest_1", enabled = true, detectEnabled = false, motionEnabled = false),
            ),
            config.cameras,
        )
    }

    @Test
    fun enablingDetectionWithMotionOffTurnsMotionOnFirstAsRealBooleans() = runTest {
        val requests = mutableListOf<Triple<HttpMethod, String, String>>()
        val api = FrigateApiClient(client { req ->
            requests += Triple(req.method, req.url.encodedPath + "?" + req.url.encodedQuery, req.body.toByteArray().decodeToString())
            HttpStatusCode.OK to """{"success":true,"message":"Config successfully updated"}"""
        })

        api.setCameraDetection("http://frigate:8971", "amcrest_1", enabled = true, motionEnabled = false).getOrThrow()

        assertEquals(2, requests.size)
        val (motionMethod, motionUrl, motionBody) = requests[0]
        assertEquals(HttpMethod.Put, motionMethod)
        assertEquals("/api/config/set?", motionUrl, "booleans don't ride the query string: Frigate would write them as strings")
        assertTrue("\"update_topic\":\"config/cameras/amcrest_1/motion\"" in motionBody, motionBody)
        assertTrue("\"requires_restart\":0" in motionBody, motionBody)
        assertTrue("\"config_data\":{\"cameras\":{\"amcrest_1\":{\"motion\":{\"enabled\":true}}}}" in motionBody, motionBody)
        val (_, _, detectBody) = requests[1]
        assertTrue("\"update_topic\":\"config/cameras/amcrest_1/detect\"" in detectBody, detectBody)
        assertTrue("\"config_data\":{\"cameras\":{\"amcrest_1\":{\"detect\":{\"enabled\":true}}}}" in detectBody, detectBody)
    }

    @Test
    fun disablingDetectionLeavesMotionAlone() = runTest {
        val bodies = mutableListOf<String>()
        val api = FrigateApiClient(client { req ->
            bodies += req.body.toByteArray().decodeToString()
            HttpStatusCode.OK to """{"success":true}"""
        })

        api.setCameraDetection("http://frigate:8971", "amcrest_1", enabled = false, motionEnabled = true).getOrThrow()

        assertEquals(1, bodies.size)
        assertTrue("\"detect\":{\"enabled\":false}" in bodies.single(), bodies.single())
    }

    @Test
    fun frigateRefusalSurfacesItsMessage() = runTest {
        val api = FrigateApiClient(client { HttpStatusCode.BadRequest to """{"success":false,"message":"Motion detection can't be off while detection is on"}""" })
        val failure = api.setCameraMotion("http://frigate:8971", "amcrest_1", enabled = false).exceptionOrNull()
        assertEquals("Motion detection can't be off while detection is on", failure?.message)
    }

    @Test
    fun adminIsReadFromTheProfileRole() = runTest {
        val admin = FrigateApiClient(client { HttpStatusCode.OK to """{"username":"andrew","role":"admin","allowed_cameras":["a"]}""" })
        assertTrue(admin.isAdmin("http://frigate:8971").getOrThrow())
        val viewer = FrigateApiClient(client { HttpStatusCode.OK to """{"username":"phone","role":"viewer"}""" })
        assertFalse(viewer.isAdmin("http://frigate:8971").getOrThrow())
    }

    @Test
    fun eventsAfterIsPassedAsFrigateExpects() = runTest {
        var query = ""
        val api = FrigateApiClient(client { req -> query = req.url.encodedQuery; HttpStatusCode.OK to "[]" })
        api.getEvents("http://frigate:8971", limit = 50, afterEpochSeconds = 1788623909.25).getOrThrow()
        assertEquals("limit=50&after=1788623909.250", query)
    }
}
