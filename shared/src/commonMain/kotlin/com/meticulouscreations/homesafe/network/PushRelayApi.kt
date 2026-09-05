package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * The HomeSafe push relay that runs next to Frigate on the same box (port [RELAY_PORT]). It
 * authenticates every call by forwarding the Frigate session cookie to Frigate, and the shared
 * Ktor client attaches that cookie on its own because the relay is the same host — so there is
 * no second login and no second secret.
 */
@Inject
class PushRelayApi(private val httpClient: HttpClient) {

    /** Registers (or refreshes) this device's Firebase token with the relay for [serverUrl]'s box. */
    suspend fun registerDevice(serverUrl: String, token: String, platform: String, name: String): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/devices")) {
            contentType(ContentType.Application.Json)
            setBody(DeviceRegistration(token = token, platform = platform, name = name))
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
    }

    /** Asks the relay to push a sample alert to every registered phone. */
    suspend fun sendTestPush(serverUrl: String): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/test"))
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
    }

    companion object {
        const val RELAY_PORT = 8787

        /** Same host as Frigate, the relay's port, no query — works for both the LAN and Tailscale addresses. */
        fun relayUrl(serverUrl: String, path: String): String =
            URLBuilder(serverUrl).apply { port = RELAY_PORT; pathSegments = path.trim('/').split('/'); parameters.clear() }.buildString()
    }
}

@Serializable
internal data class DeviceRegistration(val token: String, val platform: String, val name: String)
