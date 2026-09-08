package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class FrigateClassifierApiTest {

    /** Trimmed from the real server on 2026-09-05 after the first training run. */
    private val configJson = """{"classification":{"bird":{"enabled":false},"custom":{"known_cars":{"enabled":true,"name":"known_cars","threshold":0.8,"object_config":{"objects":["car"],"classification_type":"sub_label"}}}}}"""
    private val datasetJson = """{"categories":{"none":["none-1788624440.054334-ka18oa.png","none-1788624439.677754-33td8a.png"],"sarahs_tesla":["sarahs_tesla-1788624439.587271-9a6mag.png"]},
        "training_metadata":{"has_trained":true,"last_training_date":"2026-09-05T09:07:47","last_training_image_count":24,"current_image_count":27,"new_images_count":3,"dataset_changed":true}}"""

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient =
        HttpClient(
            MockEngine { req ->
                val (status, body) = handler(req)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun readsCustomObjectModelsFromConfig() = runTest {
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to configJson })
        val models = api.getModels("http://frigate:8971").getOrThrow()
        assertEquals(listOf(FrigateClassifierModel("known_cars", enabled = true, objects = listOf("car"))), models)
    }

    @Test
    fun readsDatasetCountsAndTrainingBookkeeping() = runTest {
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to datasetJson })
        val dataset = api.getDataset("http://frigate:8971", "known_cars").getOrThrow()
        assertEquals(mapOf("none" to 2, "sarahs_tesla" to 1), dataset.categories.mapValues { it.value.size })
        assertEquals(3, dataset.trainingMetadata?.newImagesCount)
        assertEquals(true, dataset.trainingMetadata?.hasTrained)
    }

    @Test
    fun labellingPostsTheFileAndCategoryTheWayFrigateExpects() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        val api = FrigateClassifierApi(
            client { req ->
                captured = req
                body = req.body.toByteArray().decodeToString()
                HttpStatusCode.OK to """{"success":true}"""
            },
        )

        api.categorize("http://frigate:8971/", "known_cars", "1788624497.850494-lvvjnr-1788624498.497929-sarahs_tesla-0.96.webp", "sarahs_tesla").getOrThrow()

        val req = captured ?: fail("no request")
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/classification/known_cars/dataset/categorize", req.url.encodedPath)
        assertTrue(body.contains("\"category\":\"sarahs_tesla\""), body)
        assertTrue(body.contains("\"training_file\":\"1788624497.850494-lvvjnr-1788624498.497929-sarahs_tesla-0.96.webp\""), body)
    }

    @Test
    fun discardingSendsIdsAndTrainingIsABarePost() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val api = FrigateClassifierApi(
            client { req ->
                calls += req.url.encodedPath to req.body.toByteArray().decodeToString()
                HttpStatusCode.OK to """{"success":true}"""
            },
        )

        api.deleteQueued("http://frigate:8971", "known_cars", listOf("a.webp", "b.webp")).getOrThrow()
        api.train("http://frigate:8971", "known_cars").getOrThrow()
        api.createCategory("http://frigate:8971", "known_cars", "ron_and_judys_mercedes").getOrThrow()

        assertEquals("/api/classification/known_cars/train/delete", calls[0].first)
        assertTrue(calls[0].second.contains("\"ids\":[\"a.webp\",\"b.webp\"]"), calls[0].second)
        assertEquals("/api/classification/known_cars/train" to "", calls[1])
        assertEquals("/api/classification/known_cars/dataset/ron_and_judys_mercedes/create" to "", calls[2])
    }

    @Test
    fun serverFailuresBecomeReadableErrors() = runTest {
        val api = FrigateClassifierApi(client { HttpStatusCode.NotFound to """{"success":false,"message":"nope is not a known classification model."}""" })
        val error = api.train("http://frigate:8971", "nope").exceptionOrNull() ?: fail("expected failure")
        assertEquals("nope is not a known classification model.", error.message)
    }

    @Test
    fun queuedCropFileNamesAreParsedIntoTheirParts() {
        val live = UnlabeledCrop.fromFileName("1788624497.850494-lvvjnr-1788624498.497929-sarahs_tesla-0.96.webp")
        assertEquals("1788624497.850494-lvvjnr", live.eventId)
        assertEquals(1788624498.497929, live.capturedEpochSeconds)
        assertEquals("sarahs_tesla", live.guessedCategory)
        assertEquals(0.96, live.guessedScore)

        val untrained = UnlabeledCrop.fromFileName("1788623987.354018-0au6wn-1788624005.174541-unknown-0.0.webp")
        assertEquals("unknown", untrained.guessedCategory)

        val mined = UnlabeledCrop.fromFileName("example_007.jpg")
        assertNull(mined.eventId)
        assertNull(mined.guessedCategory)
    }

    @Test
    fun confidentCropsAreSplitOutOfTheQueue() {
        val sureNotOurs = UnlabeledCrop.fromFileName("1788832095.985398-hkvbhs-1788832096.567646-none-1.0.webp")
        val sureOurs = UnlabeledCrop.fromFileName("1788832091.745689-e2bxi0-1788832104.148959-sarahs_tesla-1.0.webp")
        val nearlySure = UnlabeledCrop.fromFileName("1788832130.376381-6wpol8-1788832130.907575-none-0.98.webp")
        val untrained = UnlabeledCrop.fromFileName("1788623987.354018-0au6wn-1788624005.174541-unknown-0.0.webp")
        val mined = UnlabeledCrop.fromFileName("example_007.jpg")
        assertTrue(sureNotOurs.isConfident)
        assertTrue(sureOurs.isConfident)
        assertTrue(!nearlySure.isConfident && !untrained.isConfident && !mined.isConfident)

        val model = com.meticulouscreations.homesafe.domain.model.ClassifierModel("known_cars", listOf("car"))
        val dataset = ClassifierDataset(model, mapOf("none" to 1), listOf(sureNotOurs, nearlySure, sureOurs, untrained, mined), hasTrained = true, newImagesSinceTraining = 0)
        assertEquals(listOf(nearlySure, untrained, mined), dataset.uncertainQueue)
        assertEquals(listOf(sureNotOurs, sureOurs), dataset.confidentQueue)
    }

    @Test
    fun datasetKnowsWhenItCanTrainAndOrdersNoneLast() {
        val model = com.meticulouscreations.homesafe.domain.model.ClassifierModel("known_cars", listOf("car"))
        val one = ClassifierDataset(model, mapOf("none" to 19), emptyList(), hasTrained = false, newImagesSinceTraining = 0)
        assertEquals(false, one.canTrain)
        val two = one.copy(categoryCounts = mapOf("none" to 19, "sarahs_tesla" to 5, "ron_and_judys_mercedes" to 0))
        assertEquals(true, two.canTrain)
        assertEquals(listOf("ron_and_judys_mercedes", "sarahs_tesla", "none"), two.categories)
    }
}

class PushRelayApiTest {
    @Test
    fun relayUrlKeepsTheHostAndSwapsThePort() {
        assertEquals("http://100.64.0.1:8787/devices", PushRelayApi.relayUrl("http://100.64.0.1:8971", "/devices"))
        assertEquals("http://debian-surveillance.tail4c441a.ts.net:8787/test", PushRelayApi.relayUrl("http://debian-surveillance.tail4c441a.ts.net:8971/?x=1", "/test"))
        assertEquals("http://192.168.68.55:8787/devices", PushRelayApi.relayUrl("http://192.168.68.55:8971/", "devices"))
    }
}
