package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The HomeSafe push relay that runs next to Frigate on the same box (port [RELAY_PORT]). It
 * authenticates every call one of two ways: a signed-in user's Frigate session cookie, which the
 * shared Ktor client attaches on its own because the relay is the same host — so there is no
 * second login and no second secret for the user — or, for the routes an install may use on
 * itself, the *device secret* the relay handed it at registration (`Authorization: Bearer`).
 * The second way is what lets a geofence crossing report presence from a cold background wake
 * that has no session at all.
 */
@Inject
class PushRelayApi(private val httpClient: HttpClient) {

    /**
     * Registers (or refreshes) this install with the relay for [serverUrl]'s box, and gets back
     * its identity and secret. Re-register whenever the push token or the "only strangers"
     * preference changes. [secret] is the one from last time, if any — with it the call works
     * without a session (a token rotated while the app was closed); without it the session
     * cookie must be there.
     */
    suspend fun registerDevice(serverUrl: String, registration: DeviceRegistration, secret: String? = null): Result<DeviceCredentials> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/devices")) {
            contentType(ContentType.Application.Json)
            bearer(secret)
            setBody(registration)
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayRegistration>().let { DeviceCredentials(it.deviceId, it.secret ?: throw FrigateResponseException("Relay issued no secret")) }
    }

    /** Asks the relay to push a sample alert to every registered phone. */
    suspend fun sendTestPush(serverUrl: String): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/test"))
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
    }

    /**
     * Marks this install ([deviceId]) away or home; the relay answers with the household's new
     * presence. With [dwellSeconds] > 0 an `away` is only armed — see `docs/away-mode.md`.
     */
    suspend fun setPresence(
        serverUrl: String,
        deviceId: String,
        secret: String?,
        away: Boolean,
        source: String = "manual",
        dwellSeconds: Int = 0,
    ): Result<HouseholdPresence> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/devices/$deviceId/presence")) {
            contentType(ContentType.Application.Json)
            bearer(secret)
            setBody(PresenceUpdate(away = away, source = source, dwellSeconds = dwellSeconds))
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayPresence>().toDomain()
    }

    /** Who's home. The relay flags [deviceId]'s own entry, so the app knows which switch is its own. */
    suspend fun getPresence(serverUrl: String, deviceId: String, secret: String? = null): Result<HouseholdPresence> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/presence")) {
            parameter("device", deviceId)
            bearer(secret)
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayPresence>().toDomain()
    }

    /**
     * Sets — or with null clears — the household's home. A user action, so it rides on the
     * session cookie; [deviceId] only lets the answer flag this phone's own row, as `/presence` does.
     */
    suspend fun setHome(serverUrl: String, home: HomeLocation?, deviceId: String): Result<HouseholdPresence> = runCatching {
        val response = if (home == null) {
            httpClient.delete(relayUrl(serverUrl, "/home")) { parameter("device", deviceId) }
        } else {
            httpClient.put(relayUrl(serverUrl, "/home")) {
                parameter("device", deviceId)
                contentType(ContentType.Application.Json)
                setBody(HomeUpdate(lat = home.latitude, lng = home.longitude, radiusMeters = home.radiusMeters))
            }
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Relay answered ${response.status}")
        response.body<RelayPresence>().toDomain()
    }

    private fun HttpRequestBuilder.bearer(secret: String?) {
        if (secret != null) header(HttpHeaders.Authorization, "Bearer $secret")
    }

    companion object {
        const val RELAY_PORT = 8787

        /** Same host as Frigate, the relay's port, no query — works for both the LAN and Tailscale addresses. */
        fun relayUrl(serverUrl: String, path: String): String =
            URLBuilder(serverUrl).apply {
                port = RELAY_PORT
                pathSegments = path.trim('/').split('/')
                parameters.clear()
            }.buildString()
    }
}

/** What an install tells the relay about itself. [token] is null where there's no push (iOS, for now). */
@Serializable
data class DeviceRegistration(
    @SerialName("device_id") val deviceId: String,
    val token: String?,
    val platform: String,
    val name: String,
    @SerialName("quiet_familiar") val quietFamiliar: Boolean = false,
    val build: String = "unknown",
)

/** What the relay knows this install as, and the secret that proves it. */
data class DeviceCredentials(val deviceId: String, val secret: String)

@Serializable
internal data class RelayRegistration(
    val ok: Boolean = true,
    @SerialName("device_id") val deviceId: String,
    val secret: String? = null,
)

/** No defaults on purpose: the relay defaults match, but a body that says what it means is easier to read in its log. */
@Serializable
internal data class PresenceUpdate(
    val away: Boolean,
    val source: String,
    @SerialName("dwell_seconds") val dwellSeconds: Int,
)

@Serializable
internal data class HomeUpdate(
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusMeters: Double,
)

/** The relay's `GET /presence` (and `PUT .../presence`, `PUT /home`) body. */
@Serializable
internal data class RelayPresence(
    val devices: List<RelayPresenceDevice> = emptyList(),
    @SerialName("everyone_away") val everyoneAway: Boolean = false,
    val home: RelayHome? = null,
) {
    fun toDomain() = HouseholdPresence(
        devices = devices.map { PresenceDevice(it.name, it.platform, it.away, it.awayUpdated, it.thisDevice, it.counts, it.pendingAway) },
        everyoneAway = everyoneAway,
        home = home?.let { HomeLocation(it.lat, it.lng, it.radiusMeters) },
    )
}

@Serializable
internal data class RelayHome(
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusMeters: Double = 150.0,
)

@Serializable
internal data class RelayPresenceDevice(
    val name: String = "",
    val platform: String = "",
    val away: Boolean = false,
    @SerialName("away_updated") val awayUpdated: Double? = null,
    @SerialName("this_device") val thisDevice: Boolean = false,
    /** Older relays don't send this; treat their devices as counting, which is what they did. */
    val counts: Boolean = true,
    @SerialName("pending_away") val pendingAway: Boolean = false,
)
