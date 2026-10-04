package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.CarCheck
import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeCheck
import com.meticulouscreations.homesafe.domain.model.UptimeDevice
import com.meticulouscreations.homesafe.domain.model.UptimeOutage
import com.meticulouscreations.homesafe.domain.model.UptimeState
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.error_relay_answered
import homesafe.shared.generated.resources.error_relay_no_secret
import homesafe.shared.generated.resources.error_relay_no_spot
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayRegistration>().let { DeviceCredentials(it.deviceId, it.secret ?: throw FrigateResponseException(UiText.of(Res.string.error_relay_no_secret), technical = "Relay issued no secret")) }
    }

    /** Asks the relay to push a sample alert to every registered phone. */
    suspend fun sendTestPush(serverUrl: String): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/test"))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
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
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPresence>().toDomain()
    }

    /** Who's home. The relay flags [deviceId]'s own entry, so the app knows which switch is its own. */
    suspend fun getPresence(serverUrl: String, deviceId: String, secret: String? = null): Result<HouseholdPresence> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/presence")) {
            parameter("device", deviceId)
            bearer(secret)
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPresence>().toDomain()
    }

    /**
     * Makes this install ([deviceId]) the household's presence authority: from then on its away
     * switch alone says whether the house is empty, and every other phone's is only its own. An
     * install may choose itself, so this bears its [secret] (the session cookie also works). The
     * relay answers with the household's presence, [HouseholdPresence.authorityDeviceId] now this one.
     */
    suspend fun setPresenceAuthority(serverUrl: String, deviceId: String, secret: String?): Result<HouseholdPresence> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/presence/authority")) {
            contentType(ContentType.Application.Json)
            bearer(secret)
            setBody(AuthorityUpdate(deviceId = deviceId))
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPresence>().toDomain()
    }

    /**
     * Hands the decision back from the presence authority — to the box's configured phone if it
     * has one, or else to every counting phone. The authority may step down on its own [secret],
     * [deviceId] naming it; any other install needs the session cookie. Answers the new presence.
     */
    suspend fun clearPresenceAuthority(serverUrl: String, deviceId: String, secret: String?): Result<HouseholdPresence> = runCatching {
        val response = httpClient.delete(relayUrl(serverUrl, "/presence/authority")) {
            parameter("device", deviceId)
            bearer(secret)
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPresence>().toDomain()
    }

    /**
     * Forgets another install ([deviceId]), e.g. an old one a reinstall left behind: it stops
     * getting pushes and stops counting for away mode. A user action on someone else's row, so it
     * rides on the session cookie alone — this install's secret only speaks for itself.
     */
    suspend fun removeDevice(serverUrl: String, deviceId: String): Result<Unit> = runCatching {
        val response = httpClient.delete(relayUrl(serverUrl, "/devices/$deviceId"))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
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
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPresence>().toDomain()
    }

    /**
     * One of a pushed alert's pictures — [name] is `thumbnail.jpg` or `preview.gif` — fetched
     * through the relay, which proxies Frigate. The push may have woken the app with no Frigate
     * session, so this is the install's own door: [deviceId] and its [secret].
     */
    suspend fun getEventMedia(serverUrl: String, eventId: String, name: String, deviceId: String, secret: String?): Result<ByteArray> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/events/$eventId/$name")) {
            parameter("device", deviceId)
            bearer(secret)
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<ByteArray>()
    }

    /**
     * Adds a car someone boxed on a camera frame to [modelName]'s dataset, under [category]. Frigate
     * can only file the crops it queued itself, so the relay — which can reach the dataset folder on
     * the same box — cuts the example out of [frame] (the JPEG exactly as Frigate served it) the way
     * Frigate's classifier would frame [box]. A user action, so the session cookie.
     */
    suspend fun addClassifierExample(serverUrl: String, modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> =
        runCatching {
            val response = httpClient.post(relayUrl(serverUrl, "/classification/$modelName/dataset/$category")) {
                parameter("x", box.x)
                parameter("y", box.y)
                parameter("w", box.width)
                parameter("h", box.height)
                contentType(ContentType.Image.JPEG)
                setBody(frame)
            }
            if (!response.status.isSuccess()) throw relayRefused(response.status)
        }

    /**
     * A person naming [eventId], through the relay so the name is known to be a person's: the
     * classifier gives some of its own guesses Frigate's top score too. The relay passes it on to
     * Frigate with the session cookie, so Frigate still decides who may. A null [subLabel] takes
     * the name away.
     */
    suspend fun setSubLabel(serverUrl: String, eventId: String, subLabel: String?, score: Double?): Result<Unit> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/events/$eventId/sub_label")) {
            contentType(ContentType.Application.Json)
            setBody(SubLabelRequest(subLabel = subLabel.orEmpty(), subLabelScore = score))
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
    }

    /** The household cars' make, model, colour and plate, which the relay checks the classifier's names against. */
    suspend fun getCarProfiles(serverUrl: String): Result<CarProfiles> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/cars/profiles"))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayCarProfiles>().toDomain()
    }

    /** Saves [profile]; blank fields aren't checked. Answers the profile as the relay keeps it (make lower case, plate normalised). */
    suspend fun saveCarProfile(serverUrl: String, profile: CarProfile): Result<CarProfile> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/cars/profiles/${profile.name}")) {
            contentType(ContentType.Application.Json)
            setBody(RelayCarProfile(profile.name, profile.make, profile.model, profile.colour, profile.plate))
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayCarProfile>().toDomain()
    }

    /** Forgets the profile of the car filed as [name]; the relay stops checking that name. */
    suspend fun deleteCarProfile(serverUrl: String, name: String): Result<Unit> = runCatching {
        val response = httpClient.delete(relayUrl(serverUrl, "/cars/profiles/$name"))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
    }

    /**
     * What the relay's car check made of each of [eventIds], keyed by event id. Events it hasn't
     * looked at are missing. Null when the relay has no such route (an older relay, which answers
     * 404); fails when it couldn't say this time (unreachable, timed out, an error).
     */
    suspend fun getCarChecks(serverUrl: String, eventIds: List<String>): Result<Map<String, CarCheck>?> = runCatching {
        if (eventIds.isEmpty()) return@runCatching emptyMap()
        val checks = mutableMapOf<String, CarCheck>()
        for (ids in eventIds.chunked(CAR_CHECKS_PER_CALL)) {
            val response = httpClient.get(relayUrl(serverUrl, "/cars/checks")) { parameter("events", ids.joinToString(",")) }
            if (response.status == HttpStatusCode.NotFound) return@runCatching null
            if (!response.status.isSuccess()) throw relayRefused(response.status)
            response.body<RelayCarChecks>().checks.forEach { (id, check) -> checks[id] = check.toDomain() }
        }
        checks
    }

    /**
     * Someone saying [eventId], a person detection, was not a person: the relay keeps its box as a
     * phantom spot on its camera, so the same thing there isn't pushed again, and files the
     * detection as a phantom example for the person classifier when there is one. A user action:
     * the session cookie in the app, or — for a notification's "Not a person" button, which may
     * wake the app with no session — this install's [deviceId] and [secret]. Answers the spot.
     */
    suspend fun markNotAPerson(serverUrl: String, eventId: String, deviceId: String? = null, secret: String? = null): Result<PhantomSpot> =
        runCatching {
            val response = httpClient.post(relayUrl(serverUrl, "/events/$eventId/not_a_person")) { asDevice(deviceId, secret) }
            if (!response.status.isSuccess()) throw relayRefused(response.status)
            response.body<RelayNotAPerson>().spot.toDomain() ?: throw FrigateResponseException(UiText.of(Res.string.error_relay_no_spot), technical = "Relay kept no spot")
        }

    /** Takes a [markNotAPerson] back, the same way it was given: the phantom spot goes, and the example it filed. */
    suspend fun undoNotAPerson(serverUrl: String, eventId: String, deviceId: String? = null, secret: String? = null): Result<Unit> = runCatching {
        val response = httpClient.delete(relayUrl(serverUrl, "/events/$eventId/not_a_person")) { asDevice(deviceId, secret) }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
    }

    /** Every phantom spot the relay keeps, for the feed to hide what the relay no longer pushes. */
    suspend fun getPhantomSpots(serverUrl: String): Result<List<PhantomSpot>> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/phantoms"))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayPhantoms>().spots.mapNotNull { it.toDomain() }
    }

    /**
     * The server's uptime record over the last [hours] (the relay's `/status/data`): what the box
     * could reach each minute, cut into spans for drawing. Works on whichever address [serverUrl]
     * is, the home network's or Tailscale's.
     */
    suspend fun getUptime(serverUrl: String, hours: Int): Result<ServerUptime> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/status/data")) { parameter("hours", hours) }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayUptime>().toDomain()
    }.onFailure {
        // A read that was cancelled (another range was chosen, the route moved) has no result to
        // hand back: as a failure it would be shown as an error over the read that replaced it.
        if (it is CancellationException) throw it
    }

    /** The relay answered [status] instead of success; the message keeps the status for callers that look for a 401 or 403. */
    private fun relayRefused(status: HttpStatusCode) =
        FrigateResponseException(UiText.of(Res.string.error_relay_answered, status.toString()), technical = "Relay answered $status")

    private fun HttpRequestBuilder.bearer(secret: String?) {
        if (secret != null) header(HttpHeaders.Authorization, "Bearer $secret")
    }

    /** The install's own door, when there's an install to speak for; otherwise the session cookie alone. */
    private fun HttpRequestBuilder.asDevice(deviceId: String?, secret: String?) {
        if (deviceId == null) return
        parameter("device", deviceId)
        bearer(secret)
    }

    companion object {
        const val RELAY_PORT = 8787

        /** The relay answers for at most 200 events a call (its `CAR_CHECKS_MAX`). */
        private const val CAR_CHECKS_PER_CALL = 200

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
    /** Quiet hours, in minutes after local midnight; both null when they're off. */
    @SerialName("quiet_start") val quietStart: Int? = null,
    @SerialName("quiet_end") val quietEnd: Int? = null,
    /** "Only when everyone's away": the relay sends this phone Away alerts and nothing else. */
    @SerialName("only_away") val onlyAway: Boolean = false,
    /** Where the phone's quiet hours are read: its IANA zone, and its UTC offset now for a relay that can't resolve the zone. */
    val tz: String? = null,
    @SerialName("utc_offset") val utcOffsetMinutes: Int? = null,
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
internal data class AuthorityUpdate(@SerialName("device_id") val deviceId: String)

@Serializable
internal data class HomeUpdate(
    val lat: Double,
    val lng: Double,
    @SerialName("radius_m") val radiusMeters: Double,
)

/** The relay's `GET /presence` (and `PUT .../presence`, `PUT /home`, `PUT`/`DELETE /presence/authority`) body. */
@Serializable
internal data class RelayPresence(
    val devices: List<RelayPresenceDevice> = emptyList(),
    @SerialName("everyone_away") val everyoneAway: Boolean = false,
    val home: RelayHome? = null,
    /** The device_id whose away switch alone decides away mode; null while every counting phone votes, and from older relays. */
    val authority: String? = null,
) {
    fun toDomain() = HouseholdPresence(
        devices = devices.map {
            PresenceDevice(
                name = it.name,
                platform = it.platform,
                away = it.away,
                updatedEpochSeconds = it.awayUpdated,
                isThisDevice = it.thisDevice,
                countsForAway = it.counts,
                pendingAway = it.pendingAway,
                id = it.id,
                lastSeenEpochSeconds = it.lastSeen,
                build = it.build,
                decides = authority != null && it.id == authority,
            )
        },
        everyoneAway = everyoneAway,
        home = home?.let { HomeLocation(it.lat, it.lng, it.radiusMeters) },
        authorityDeviceId = authority,
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
    /** The device_id; older relays don't send it, and their devices can't be removed from the app. */
    val id: String? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    /** "release" or "debug", as the install registered; older relays don't send it. */
    val build: String? = null,
)

/** The relay's `GET /cars/profiles` body. */
@Serializable
internal data class RelayCarProfiles(
    val profiles: List<RelayCarProfile> = emptyList(),
    val makes: List<String> = emptyList(),
    val colours: List<String> = emptyList(),
) {
    fun toDomain() = CarProfiles(profiles = profiles.map { it.toDomain() }, makes = makes, colours = colours)
}

@Serializable
internal data class RelayCarProfile(
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val colour: String? = null,
    val plate: String? = null,
) {
    fun toDomain() = CarProfile(name = name, make = make.orEmpty(), model = model.orEmpty(), colour = colour.orEmpty(), plate = plate.orEmpty())
}

/** The relay's `GET /cars/checks` body. */
@Serializable
internal data class RelayCarChecks(val checks: Map<String, RelayCarCheck> = emptyMap())

@Serializable
internal data class RelayCarCheck(
    val verdict: String,
    val classifier: String? = null,
    val name: String? = null,
    val verified: String? = null,
    val saw: RelayCarLooks? = null,
) {
    fun toDomain() = CarCheck(
        verdict = verdict,
        classifierName = classifier,
        name = name,
        verified = verified,
        sawColour = saw?.colour,
        sawMake = saw?.make,
        sawModel = saw?.model,
    )
}

@Serializable
internal data class RelayCarLooks(
    val colour: String? = null,
    val make: String? = null,
    val model: String? = null,
)

/** One of the relay's phantom spots (`GET /phantoms`, `POST .../not_a_person`). */
@Serializable
internal data class RelayPhantomSpot(
    @SerialName("event_id") val eventId: String,
    val camera: String,
    val box: List<Double> = emptyList(),
) {
    fun toDomain(): PhantomSpot? = DetectionBox.fromFractions(box)?.let { PhantomSpot(eventId, camera, it) }
}

@Serializable
internal data class RelayPhantoms(val spots: List<RelayPhantomSpot> = emptyList())

@Serializable
internal data class RelayNotAPerson(val spot: RelayPhantomSpot)

/** The relay's `/status/data`. Times are epoch seconds; `states` is one letter per span (see [UptimeState.fromLetter]). */
@Serializable
internal data class RelayUptime(
    val since: Double,
    val until: Double,
    @SerialName("bucket_seconds") val bucketSeconds: Double,
    @SerialName("recording_since") val recordingSince: Double? = null,
    val checks: List<RelayUptimeCheck> = emptyList(),
    val devices: List<RelayUptimeDevice> = emptyList(),
    val outages: List<RelayUptimeOutage> = emptyList(),
) {
    fun toDomain() = ServerUptime(
        sinceEpochSeconds = since.toLong(),
        untilEpochSeconds = until.toLong(),
        bucketSeconds = bucketSeconds,
        recordingSinceEpochSeconds = recordingSince?.toLong(),
        checks = checks.map { UptimeCheck(it.key, it.name.orEmpty().ifBlank { it.key }, it.states.map(UptimeState::fromLetter), it.upFraction, it.downSeconds, it.up) },
        devices = devices.map { UptimeDevice(it.name, it.os.orEmpty(), it.online, it.lastSeen?.toLong(), it.states.map(UptimeState::fromLetter)) },
        outages = outages.map { UptimeOutage(it.check, it.start.toLong(), it.end?.toLong(), it.seconds) },
    )
}

@Serializable
internal data class RelayUptimeCheck(
    val key: String,
    val name: String? = null,
    val states: String = "",
    @SerialName("up_fraction") val upFraction: Double? = null,
    @SerialName("down_seconds") val downSeconds: Long = 0,
    val up: Boolean? = null,
)

@Serializable
internal data class RelayUptimeDevice(
    val name: String,
    val os: String? = null,
    val online: Boolean? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    val states: String = "",
)

@Serializable
internal data class RelayUptimeOutage(val check: String, val start: Double, val end: Double? = null, val seconds: Long = 0)
