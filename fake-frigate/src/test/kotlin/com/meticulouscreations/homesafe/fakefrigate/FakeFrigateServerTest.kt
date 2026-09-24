package com.meticulouscreations.homesafe.fakefrigate

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake Frigate itself, over plain `HttpURLConnection`: if these fail, every journey built on
 * it fails for a reason that has nothing to do with the app, so they are checked on their own.
 */
class FakeFrigateServerTest {

    private val server = FakeFrigateServer().start()

    @AfterTest
    fun stop() = server.close()

    private class Response(val status: Int, val body: String, val cookie: String?)

    private fun call(method: String, path: String, body: String? = null, cookie: String? = null): Response {
        val connection = URI("${server.baseUrl}$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val setCookie = connection.getHeaderField("Set-Cookie")?.substringBefore(';')
        return Response(status, text, setCookie)
    }

    private fun signIn(user: FakeUser = FakeFrigateState.ADMIN): String {
        val response = call("POST", "/api/login", """{"user":"${user.username}","password":"${user.password}"}""")
        assertEquals(200, response.status)
        return checkNotNull(response.cookie) { "login set no session cookie" }
    }

    @Test
    fun versionAnswersWithoutASession() {
        assertEquals(200, call("GET", "/api/version").status)
    }

    @Test
    fun theApiRefusesRequestsWithoutASession() {
        assertEquals(401, call("GET", "/api/config").status)
        assertEquals(401, call("GET", "/api/profile").status)
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val response = call("POST", "/api/login", """{"user":"admin","password":"nope"}""")
        assertEquals(401, response.status)
    }

    @Test
    fun aSignedInSessionReadsTheConfigAndProfile() {
        val cookie = signIn()
        val config = call("GET", "/api/config", cookie = cookie)
        assertEquals(200, config.status)
        assertContains(config.body, "\"front_door\"")
        assertContains(config.body, "\"driveway_sub\"")
        assertContains(call("GET", "/api/profile", cookie = cookie).body, "\"role\":\"admin\"")
    }

    @Test
    fun eventsAreNewestFirstAndFilterable() {
        val cookie = signIn()
        val all = call("GET", "/api/events?limit=100", cookie = cookie).body
        assertTrue(all.indexOf("frnt01") < all.indexOf("drv001"), "newest first")
        val driveway = call("GET", "/api/events?cameras=driveway", cookie = cookie).body
        assertFalse("front_door" in driveway)
        assertContains(driveway, "sarahs_tesla")
    }

    @Test
    fun aDetectionSwitchIsAppliedToTheConfig() {
        val cookie = signIn()
        val body = """{"requires_restart":0,"update_topic":"config/cameras/back_yard/detect","config_data":{"cameras":{"back_yard":{"detect":{"enabled":false}}}}}"""
        assertEquals(200, call("PUT", "/api/config/set", body, cookie).status)
        assertFalse(server.state.camera("back_yard")!!.detectEnabled)
        server.awaitRequest { it.path == "/api/config/set" }
    }

    @Test
    fun aViewerMayNotWriteConfig() {
        val cookie = signIn(FakeFrigateState.VIEWER)
        val body = """{"requires_restart":0,"update_topic":"x","config_data":{}}"""
        assertEquals(401, call("PUT", "/api/config/set", body, cookie).status)
    }

    @Test
    fun filingAFaceAttemptMovesItToThatPerson() {
        val cookie = signIn()
        val attempt = server.state.faces.getValue(FakeFrigateState.TRAIN_FOLDER).first()
        val response = call("POST", "/api/faces/train/Bob/classify", """{"training_file":"$attempt"}""", cookie)
        assertEquals(200, response.status)
        assertTrue(attempt in server.state.faces.getValue("Bob"))
        assertTrue(server.state.faces.getValue(FakeFrigateState.TRAIN_FOLDER).isEmpty())
    }

    @Test
    fun injectedFailuresAnswerWithTheirStatus() {
        val cookie = signIn()
        server.state.edit { failures["/api/stats"] = 500 }
        assertEquals(500, call("GET", "/api/stats", cookie = cookie).status)
    }

    @Test
    fun imagesAreServedAndUnknownPathsAreReported() {
        val cookie = signIn()
        assertEquals(200, call("GET", "/api/front_door/latest.jpg?h=480", cookie = cookie).status)
        assertEquals(404, call("GET", "/api/nothing-here", cookie = cookie).status)
        assertEquals("/api/nothing-here", server.unhandled.single().path)
    }
}
