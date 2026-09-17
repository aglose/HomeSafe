package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.MaskPoint
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
    fun readsEachCamerasDetectSizeAlongsideTheModels() = runTest {
        val withCameras = configJson.dropLast(1) + ""","cameras":{"hikvision_1":{"detect":{"width":640,"height":360}},"bare":{}}}"""
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to withCameras })
        val config = api.getConfig("http://frigate:8971").getOrThrow()
        assertEquals(listOf("known_cars"), config.models.map { it.name })
        assertEquals(mapOf("hikvision_1" to FrigateDetectSize(640, 360), "bare" to FrigateDetectSize(1280, 720)), config.detectSizes)
    }

    /** A full queue names a few dozen events; they go out in batches, each id once. */
    @Test
    fun eventsAreFetchedByIdInBatches() = runTest {
        val requested = mutableListOf<List<String>>()
        val api = FrigateClassifierApi(
            client { req ->
                val ids = req.url.parameters["ids"].orEmpty().split(",")
                requested += ids
                HttpStatusCode.OK to ids.joinToString(",", "[", "]") { """{"id":"$it","label":"car","camera":"hikvision_1","start_time":1.0}""" }
            },
        )
        val ids = (1..60).map { "1789612326.39659-id$it" }
        val events = api.getEvents("http://frigate:8971", ids + ids.take(5)).getOrThrow()
        assertEquals(listOf(50, 10), requested.map { it.size })
        assertEquals(ids, events.map { it.id })
    }

    /** Trimmed from the real server on 2026-09-16: one event's lifecycle, the last entry without a box. */
    @Test
    fun timelineBoxesAreFetchedForAllTheQueuedEventsAtOnce() = runTest {
        var query: Map<String, String?> = emptyMap()
        val api = FrigateClassifierApi(
            client { req ->
                query = req.url.parameters.names().associateWith { req.url.parameters[it] }
                HttpStatusCode.OK to """[{"timestamp":1789612937.165,"camera":"hikvision_1","source":"tracked_object","source_id":"1789612937.165365-rf22rm","class_type":"visible","data":{"camera":"hikvision_1","label":"car","sub_label":null,"box":[0.484,0.214,0.075,0.053],"region":[0.5,0.0,0.5,0.888],"attribute":""}},
                    {"timestamp":1789613141.81,"camera":"hikvision_1","source":"tracked_object","source_id":"1789612937.165365-rf22rm","class_type":"gone","data":{}}]"""
            },
        )
        val entries = api.getTimeline("http://frigate:8971", listOf("1789612937.165365-rf22rm", "1789612326.39659-dg7l5c")).getOrThrow()
        assertEquals("1789612937.165365-rf22rm,1789612326.39659-dg7l5c", query["source_id"])
        assertEquals(listOf(0.484, 0.214, 0.075, 0.053), entries[0].data?.box)
        assertEquals(1789612937.165, entries[0].timestamp)
        assertNull(entries[1].data?.box)
    }

    /** From the real server on 2026-09-16: a parked car whose tracker jumped to another car for a moment. */
    @Test
    fun anEventsPositionAtAMomentIsTheLastPathPointBeforeIt() {
        val data = Json { ignoreUnknownKeys = true }.decodeFromString(
            FrigateEventData.serializer(),
            """{"box":[0.390625,0.25833,0.125,0.11389],"path_data":[[[0.4547,0.375],1789612330.591865],[[0.4562,0.375],1789612330.784124],[[0.5125,0.325],1789612990.373736],[[0.4547,0.375],1789612990.591818]]}""",
        )
        assertEquals(MaskPoint(0.4562, 0.375), data.bottomCentreAt(1789612658.45132))
        assertEquals(MaskPoint(0.5125, 0.325), data.bottomCentreAt(1789612990.45))
        assertEquals(MaskPoint(0.4547, 0.375), data.bottomCentreAt(1789612000.0), "before the path starts, its first point")

        val noPath = Json { ignoreUnknownKeys = true }.decodeFromString(FrigateEventData.serializer(), """{"box":[0.25,0.5,0.5,0.25]}""")
        assertEquals(MaskPoint(0.5, 0.75), noPath.bottomCentreAt(1.0), "without a path, the best frame's bottom-centre")
    }

    @Test
    fun readsDatasetCountsAndTrainingBookkeeping() = runTest {
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to datasetJson })
        val dataset = api.getDataset("http://frigate:8971", "known_cars").getOrThrow()
        assertEquals(mapOf("none" to 2, "sarahs_tesla" to 1), dataset.categories.mapValues { it.value.size })
        assertEquals(3, dataset.trainingMetadata?.newImagesCount)
        assertEquals(true, dataset.trainingMetadata?.hasTrained)
    }

    /** The other shape in the wild: no wrapper, no bookkeeping, just the folders. */
    @Test
    fun readsADatasetServedAsTheBareCategoryMap() = runTest {
        val bare = """{"none":["none-1788624440.054334-ka18oa.png"],"sarahs_tesla":["sarahs_tesla-1788624439.587271-9a6mag.png"]}"""
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to bare })
        val dataset = api.getDataset("http://frigate:8971", "known_cars").getOrThrow()
        assertEquals(mapOf("none" to 1, "sarahs_tesla" to 1), dataset.categories.mapValues { it.value.size })
        assertNull(dataset.trainingMetadata)
    }

    @Test
    fun anEmptyDatasetReadsAsNoCategoriesRatherThanFailing() = runTest {
        val api = FrigateClassifierApi(client { HttpStatusCode.OK to "{}" })
        val dataset = api.getDataset("http://frigate:8971", "known_cars").getOrThrow()
        assertTrue(dataset.categories.isEmpty())
        assertNull(dataset.trainingMetadata)
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

    /**
     * The empty-dataset trap: a model that has never been labelled (or whose dataset was cleared)
     * still has a queue full of crops, and every one of them needs something to file it under.
     */
    @Test
    fun everyCropCanBeFiledEvenWhenTheDatasetListsNoCategories() {
        val model = com.meticulouscreations.homesafe.domain.model.ClassifierModel("known_cars", listOf("car"))
        val guessed = UnlabeledCrop.fromFileName("1788832091.745689-e2bxi0-1788832104.148959-sarahs_tesla-0.91.webp")
        val notOurs = UnlabeledCrop.fromFileName("1788832095.985398-hkvbhs-1788832096.567646-none-0.99.webp")
        val untrained = UnlabeledCrop.fromFileName("1788623987.354018-0au6wn-1788624005.174541-unknown-0.0.webp")
        val mined = UnlabeledCrop.fromFileName("example_007.jpg")

        val empty = ClassifierDataset(model, emptyMap(), listOf(guessed, notOurs, untrained, mined), hasTrained = false, newImagesSinceTraining = 0)
        assertEquals(listOf("sarahs_tesla", "none"), empty.categories, "\"Not ours\" is always offered, and so is a name the model already uses")

        val nothingGuessed = ClassifierDataset(model, emptyMap(), listOf(untrained, mined), hasTrained = false, newImagesSinceTraining = 0)
        assertEquals(listOf("none"), nothingGuessed.categories, "placeholders never become categories")
        assertEquals(false, nothingGuessed.canTrain, "offering a category is not the same as having images in it")
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
