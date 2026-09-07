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
import kotlin.test.assertTrue
import kotlin.test.fail

class FrigateApiClientMasksTest {

    /** Trimmed from `GET /api/config` on Frigate 0.17.2: masks come back as `""`, one string, or a list. */
    private val configJson = """{"cameras":{
      "hikvision_1":{"enabled":true,"detect":{"width":640,"height":360,"fps":5},
        "motion":{"mask":"","threshold":30},"objects":{"mask":"0.1,0.1,0.4,0.1,0.4,0.4","track":["person"]}},
      "amcrest_1":{"enabled":true,"detect":{"width":704,"height":480},
        "motion":{"mask":["0,0,1,0,1,0.1","0,0.9,1,0.9,1,1"]},"objects":{"mask":null,"track":["person","dog"]},
        "zones":{"driveway":{"coordinates":"0.1,0.1,0.4,0.1,0.4,0.4","objects":["car"],"friendly_name":"Driveway","inertia":3,"loitering_time":0},
                 "lawn":{"coordinates":"0,0.5,1,0.5,1,1","objects":[],"friendly_name":null}}}
    }}"""

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient =
        HttpClient(
            MockEngine { req ->
                val (status, body) = handler(req)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun readsDetectSizeAndMasksInEveryShapeFrigateServes() = runTest {
        val api = FrigateApiClient(client { HttpStatusCode.OK to configJson })

        val hik = api.getDetectionConfig("http://frigate:8971", "hikvision_1").getOrThrow()
        assertEquals(640 to 360, hik.detectWidth to hik.detectHeight)
        assertEquals(emptyList(), hik.motionMasks, "\"\" means no mask")
        assertEquals(listOf("0.1,0.1,0.4,0.1,0.4,0.4"), hik.objectMasks, "a single string is one polygon")

        val amcrest = api.getDetectionConfig("http://frigate:8971", "amcrest_1").getOrThrow()
        assertEquals(2, amcrest.motionMasks.size)
        assertEquals(emptyList(), amcrest.objectMasks, "null means no mask")
        assertEquals(listOf("person", "dog"), amcrest.trackedObjects)
        assertEquals(
            listOf(
                FrigateZone("driveway", "0.1,0.1,0.4,0.1,0.4,0.4", listOf("car"), "Driveway"),
                FrigateZone("lawn", "0,0.5,1,0.5,1,1", emptyList(), null),
            ),
            amcrest.zones,
        )
    }

    @Test
    fun unknownCameraIsAFailureNotACrash() = runTest {
        val api = FrigateApiClient(client { HttpStatusCode.OK to configJson })
        val result = api.getDetectionConfig("http://frigate:8971", "nope")
        assertTrue(result.isFailure)
    }

    @Test
    fun savesMasksTheWayFrigatesOwnEditorDoes() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        val api = FrigateApiClient(
            client { req ->
                captured = req
                body = req.body.toByteArray().decodeToString()
                HttpStatusCode.OK to """{"success":true,"message":"Config successfully updated, restart to apply"}"""
            },
        )

        api.setCameraMasks("http://frigate:8971/", "hikvision_1", FrigateApiClient.CameraSection.OBJECTS, listOf("0,0,1,0,1,1", "0,0,0.5,0,0.5,0.5")).getOrThrow()

        val req = captured ?: fail("no request sent")
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/api/config/set", req.url.encodedPath)
        assertEquals(listOf("0,0,1,0,1,1", "0,0,0.5,0,0.5,0.5"), req.url.parameters.getAll("cameras.hikvision_1.objects.mask"), "one query parameter per polygon")
        assertTrue(body.contains("\"requires_restart\":0"), body)
        assertTrue(body.contains("\"update_topic\":\"config/cameras/hikvision_1/objects\""), body)
    }

    @Test
    fun clearingALayerSendsABlankValueWhichDeletesTheKey() = runTest {
        var captured: HttpRequestData? = null
        val api = FrigateApiClient(
            client { req ->
                captured = req
                HttpStatusCode.OK to """{"success":true}"""
            },
        )

        api.setCameraMasks("http://frigate:8971", "amcrest_1", FrigateApiClient.CameraSection.MOTION, emptyList()).getOrThrow()

        val req = captured ?: fail("no request sent")
        assertEquals(listOf(""), req.url.parameters.getAll("cameras.amcrest_1.motion.mask"))
        assertTrue(req.body.toByteArray().decodeToString().contains("config/cameras/amcrest_1/motion"))
    }

    @Test
    fun surfacesFrigatesValidationMessageOnFailure() = runTest {
        val api = FrigateApiClient(
            client {
                HttpStatusCode.BadRequest to """{"success":false,"message":"Error parsing config. Check logs for error message."}"""
            },
        )

        val result = api.setCameraMasks("http://frigate:8971", "hikvision_1", FrigateApiClient.CameraSection.MOTION, listOf("5,5,6,6,7,7"))

        val error = result.exceptionOrNull() ?: fail("expected a failure")
        assertEquals("Error parsing config. Check logs for error message.", error.message)
    }
}
