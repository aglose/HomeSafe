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

class FrigateFaceApiTest {

    /** Shape of `GET /api/faces` once a person has been filed and one attempt is waiting (Frigate 0.17). */
    private val facesJson = """{"andrew":["andrew_1788661000.12.webp","andrew_1788661200.5.webp"],"train":["1788661300.1-abc123-1788661305.2-unknown-0.55.webp"]}"""

    private fun client(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): HttpClient =
        HttpClient(MockEngine { req ->
            val (status, body) = handler(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun readsFoldersIncludingTheAttemptsFolder() = runTest {
        val api = FrigateFaceApi(client { HttpStatusCode.OK to facesJson })
        val folders = api.getFaces("http://frigate:8971").getOrThrow()
        assertEquals(setOf("andrew", "train"), folders.keys)
        assertEquals(2, folders.getValue("andrew").size)
    }

    @Test
    fun anEmptyLibraryIsAnEmptyObjectNotAnError() = runTest {
        val api = FrigateFaceApi(client { HttpStatusCode.OK to "{}" })
        assertEquals(emptyMap(), api.getFaces("http://frigate:8971").getOrThrow())
    }

    @Test
    fun filingAnAttemptPostsTheTrainingFileTheWayFrigateExpects() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        val api = FrigateFaceApi(client { req -> captured = req; body = req.body.toByteArray().decodeToString(); HttpStatusCode.OK to """{"success":true}""" })

        api.classifyAttempt("http://frigate:8971/", "1788661300.1-abc123-1788661305.2-unknown-0.55.webp", "andrew").getOrThrow()

        val req = captured ?: fail("no request")
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/faces/train/andrew/classify", req.url.encodedPath)
        assertTrue(body.contains("\"training_file\":\"1788661300.1-abc123-1788661305.2-unknown-0.55.webp\""), body)
    }

    @Test
    fun deletingAndCreatingUseTheirOwnRoutes() = runTest {
        val calls = mutableListOf<Triple<HttpMethod, String, String>>()
        val api = FrigateFaceApi(client { req ->
            calls += Triple(req.method, req.url.encodedPath, req.body.toByteArray().decodeToString())
            HttpStatusCode.OK to """{"success":true}"""
        })

        api.deleteImages("http://frigate:8971", "train", listOf("a.webp", "b.webp")).getOrThrow()
        api.createPerson("http://frigate:8971", "sarah").getOrThrow()
        api.renamePerson("http://frigate:8971", "sarah", "sara").getOrThrow()

        assertEquals(HttpMethod.Post to "/api/faces/train/delete", calls[0].first to calls[0].second)
        assertTrue(calls[0].third.contains("\"ids\":[\"a.webp\",\"b.webp\"]"), calls[0].third)
        assertEquals(HttpMethod.Post to "/api/faces/sarah/create", calls[1].first to calls[1].second)
        assertEquals(HttpMethod.Put to "/api/faces/sarah/rename", calls[2].first to calls[2].second)
        assertTrue(calls[2].third.contains("\"new_name\":\"sara\""), calls[2].third)
    }

    @Test
    fun createTrustsA2xxOverFrigatesMisleadingSuccessFlag() = runTest {
        // Frigate answers a successful create with success=false and a "Successfully created" message.
        val api = FrigateFaceApi(client { HttpStatusCode.OK to """{"success":false,"message":"Successfully created face folder."}""" })
        api.createPerson("http://frigate:8971", "sarah").getOrThrow()
    }

    @Test
    fun aServerErrorSurfacesFrigatesMessage() = runTest {
        val api = FrigateFaceApi(client { HttpStatusCode.NotFound to """{"success":false,"message":"Invalid filename or no file exists"}""" })
        val error = api.classifyAttempt("http://frigate:8971", "missing.webp", "andrew").exceptionOrNull()
        assertEquals("Invalid filename or no file exists", error?.message)
    }

    @Test
    fun imageUrlsPointAtTheClipsFolder() {
        assertEquals("http://frigate:8971/clips/faces/train/x.webp", frigateFaceImageUrl("http://frigate:8971/", "train", "x.webp"))
        assertEquals("http://frigate:8971/clips/faces/andrew/andrew_1.webp", frigateFaceImageUrl("http://frigate:8971", "andrew", "andrew_1.webp"))
    }
}
