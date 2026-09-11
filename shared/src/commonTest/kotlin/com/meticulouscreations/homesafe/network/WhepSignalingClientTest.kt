package com.meticulouscreations.homesafe.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WhepSignalingClientTest {

    private val url = "http://frigate:1984/api/webrtc?src=cam"
    private val offer = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n"
    private val answer = "v=0\r\no=- 2 2 IN IP4 192.168.68.64\r\na=candidate:1 1 udp 1 192.168.68.64 8555 typ host\r\n"

    private class Server(handler: suspend (method: HttpMethod, contentType: String?, body: String) -> Pair<HttpStatusCode, Pair<String, String>>) {
        var seenBody: String? = null
        var seenContentType: String? = null
        var seenMethod: HttpMethod? = null
        val engine = MockEngine { request ->
            seenMethod = request.method
            seenContentType = request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
            val body = request.body.toByteArray().decodeToString()
            seenBody = body
            val (status, typed) = handler(request.method, seenContentType, body)
            respond(typed.second, status, headersOf(HttpHeaders.ContentType, typed.first))
        }
        val client = WhepSignalingClient(HttpClient(engine) { install(HttpTimeout) })
    }

    @Test
    fun postsTheOfferAsSdpAndReturnsAPlainSdpAnswer() = runTest {
        val server = Server { _, _, _ -> HttpStatusCode.Created to ("application/sdp" to answer) }

        val got = server.client.exchange(url, offer)

        assertEquals(answer, got)
        assertEquals(HttpMethod.Post, server.seenMethod)
        assertEquals("application/sdp", server.seenContentType)
        assertEquals(offer, server.seenBody)
    }

    @Test
    fun unwrapsAJsonAnswer() = runTest {
        val json = """{"type":"answer","sdp":"v=0\r\nfrom json"}"""
        val server = Server { _, _, _ -> HttpStatusCode.OK to ("application/json" to json) }

        assertEquals("v=0\r\nfrom json", server.client.exchange(url, offer))
    }

    @Test
    fun aNonSuccessStatusIsASignalingExceptionCarryingIt() = runTest {
        val server = Server { _, _, _ -> HttpStatusCode.InternalServerError to ("text/plain" to "EOF") }

        val error = assertFailsWith<WhepSignalingException> { server.client.exchange(url, offer) }

        assertEquals(500, error.status)
    }

    @Test
    fun aSuccessfulResponseThatIsNotAnSdpIsRejectedWithoutAStatus() = runTest {
        val html = Server { _, _, _ -> HttpStatusCode.OK to ("text/html" to "<html>login</html>") }
        assertNull(assertFailsWith<WhepSignalingException> { html.client.exchange(url, offer) }.status)

        val jsonWithoutSdp = Server { _, _, _ -> HttpStatusCode.OK to ("application/json" to """{"type":"error"}""") }
        assertNull(assertFailsWith<WhepSignalingException> { jsonWithoutSdp.client.exchange(url, offer) }.status)
    }
}
