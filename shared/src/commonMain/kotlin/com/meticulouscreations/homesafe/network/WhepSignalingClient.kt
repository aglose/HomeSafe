package com.meticulouscreations.homesafe.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Trades an SDP offer for the server's SDP answer — the one round trip a WebRTC join needs. */
interface WhepSignaling {
    /** Returns the answer SDP for [offerSdp], or throws [WhepSignalingException] when the server answers but not with one. */
    suspend fun exchange(signalingUrl: String, offerSdp: String): String
}

/** The signaling server answered, but not with an SDP answer. [status] is the HTTP status, or null when the body was the problem. */
class WhepSignalingException(val status: Int?, message: String) : Exception(message)

/**
 * go2rtc's WebRTC signaling over plain HTTP, WHEP-style: `POST` the offer as `application/sdp`
 * to the stream's signaling URL (see `frigateWebRtcSignalingUrl`) and the answer comes back in
 * the response body. go2rtc also speaks a JSON dialect (`{"type":"answer","sdp":…}`); either is
 * accepted so the client keeps working if the server's default changes. There is no trickle:
 * the offer is sent once ICE gathering has finished, and the answer carries the server's
 * candidates, so one request settles everything.
 *
 * The client needs Ktor's `HttpTimeout` plugin installed; the whole exchange is capped at
 * [REQUEST_TIMEOUT_MS], comfortably inside the connect budget a caller gives a join.
 */
class WhepSignalingClient(private val httpClient: HttpClient) : WhepSignaling {

    override suspend fun exchange(signalingUrl: String, offerSdp: String): String {
        val response = httpClient.post(signalingUrl) {
            contentType(SDP)
            setBody(offerSdp)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw WhepSignalingException(response.status.value, "Signaling answered ${response.status}")
        val sdp = if (body.trimStart().startsWith("{")) {
            Json.parseToJsonElement(body).jsonObject["sdp"]?.jsonPrimitive?.content
                ?: throw WhepSignalingException(null, "Signaling answer carried no sdp")
        } else {
            body
        }
        if (!sdp.startsWith("v=")) throw WhepSignalingException(null, "Signaling answer is not an SDP")
        return sdp
    }

    companion object {
        val SDP: ContentType = ContentType("application", "sdp")
        const val REQUEST_TIMEOUT_MS = 5_000L
    }
}
