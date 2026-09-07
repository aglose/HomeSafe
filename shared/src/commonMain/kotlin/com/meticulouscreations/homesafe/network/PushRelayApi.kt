package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The HomeSafe push relay that runs next to Frigate on the same box (port [RELAY_PORT]). It
 * authenticates every call by forwarding the Frigate session cookie to Frigate, and the shared
 * Ktor client attaches that cookie on its own because the relay is the same host — so there is
 * no second login and no second secret.
 */
@Inject
class PushRelayApi(private val httpClient: HttpClient) {

    /**
     * Registers (or refreshes) this device's Firebase token with the relay for [serverUrl]'s box.
     * [quietFamiliar] carries the "only strangers" alert preference so the relay can skip this
     * phone for people Frigate recognised; re-register whenever it changes. [build] is "release"
     * or "debug": only release installs count towards away mode, so a debug build on an emulator
     * or beside the real app can't decide the house is empty.
     */
    suspend fun registerDevice(
        serverUrl: String,
        token: String,
        platform: String,
        name: String,
        quietFamiliar: Boolean = false,
        build: String = "unknown",
    ): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/devices")) {
            contentType(ContentType.Application.Json)
            setBody(DeviceRegistration(token = token, platform = platform, name = name, quietFamiliar = quietFamiliar, build = build))
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
    }

    /** Asks the relay to push a sample alert to every registered phone. */
    suspend fun sendTestPush(serverUrl: String): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/test"))
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
    }

    /** Marks this device (by its push [token]) away or home; the relay answers with the household's new presence. */
    suspend fun setPresence(serverUrl: String, token: String, away: Boolean): Result<HouseholdPresence> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/devices/$token/presence")) {
            contentType(ContentType.Application.Json)
            setBody(PresenceUpdate(away = away))
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayPresence>().toDomain()
    }

    /** Who's home. With this device's [token] the relay flags its own entry, so the app knows which switch is its own. */
    suspend fun getPresence(serverUrl: String, token: String? = null): Result<HouseholdPresence> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/presence")) {
            if (token != null) parameter("token", token)
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayPresence>().toDomain()
    }

    companion object {
        const val RELAY_PORT = 8787

        /** Same host as Frigate, the relay's port, no query — works for both the LAN and Tailscale addresses. */
        fun relayUrl(serverUrl: String, path: String): String =
            URLBuilder(serverUrl).apply { port = RELAY_PORT; pathSegments = path.trim('/').split('/'); parameters.clear() }.buildString()
    }
}

@Serializable
internal data class DeviceRegistration(
    val token: String,
    val platform: String,
    val name: String,
    @SerialName("quiet_familiar") val quietFamiliar: Boolean = false,
    val build: String = "unknown",
)

@Serializable
internal data class PresenceUpdate(val away: Boolean)

/** The relay's `GET /presence` (and `PUT .../presence`) body. */
@Serializable
internal data class RelayPresence(
    val devices: List<RelayPresenceDevice> = emptyList(),
    @SerialName("everyone_away") val everyoneAway: Boolean = false,
) {
    fun toDomain() = HouseholdPresence(
        devices = devices.map { PresenceDevice(it.name, it.platform, it.away, it.awayUpdated, it.thisDevice, it.counts) },
        everyoneAway = everyoneAway,
    )
}

@Serializable
internal data class RelayPresenceDevice(
    val name: String = "",
    val platform: String = "",
    val away: Boolean = false,
    @SerialName("away_updated") val awayUpdated: Double? = null,
    @SerialName("this_device") val thisDevice: Boolean = false,
    /** Older relays don't send this; treat their devices as counting, which is what they did. */
    val counts: Boolean = true,
)
