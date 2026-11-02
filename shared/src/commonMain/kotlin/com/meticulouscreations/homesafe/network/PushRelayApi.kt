package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.CarCheck
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.CarLive
import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.model.CurbSpot
import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorJoin
import com.meticulouscreations.homesafe.domain.model.DoorJoinStatus
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorPairing
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.NotTrackedReason
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.domain.model.ParkingCurb
import com.meticulouscreations.homesafe.domain.model.ParkingRule
import com.meticulouscreations.homesafe.domain.model.ParkingSnapshot
import com.meticulouscreations.homesafe.domain.model.ParkingStreet
import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.model.PlanDoor
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.domain.model.PlanRoom
import com.meticulouscreations.homesafe.domain.model.PlanStairs
import com.meticulouscreations.homesafe.domain.model.PlanWall
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.StairDirection
import com.meticulouscreations.homesafe.domain.model.UptimeCheck
import com.meticulouscreations.homesafe.domain.model.UptimeDevice
import com.meticulouscreations.homesafe.domain.model.UptimeOutage
import com.meticulouscreations.homesafe.domain.model.UptimeState
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_error_no_zigbee
import homesafe.shared.generated.resources.doors_error_zigbee_silent
import homesafe.shared.generated.resources.doors_rename_invalid
import homesafe.shared.generated.resources.error_relay_answered
import homesafe.shared.generated.resources.error_relay_no_secret
import homesafe.shared.generated.resources.error_relay_no_spot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

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
        response.body<RelayRegistration>().let {
            DeviceCredentials(
                deviceId = it.deviceId,
                secret = it.secret ?: throw FrigateResponseException(UiText.of(Res.string.error_relay_no_secret), technical = "Relay issued no secret"),
                // A relay too old to say pushes to an FCM token, as it always has, but not to an
                // iPhone's APNs one: an iPhone keeps its in-app alerts until a relay says it can.
                push = it.push ?: (registration.platform != "ios"),
            )
        }
    }

    /**
     * Asks whether this install may announce the shower [key], before its own check does: the relay
     * pushes the same notification, and one shower is said once. Asked from the background job,
     * so by the device's own secret, and not for long: a relay out of reach mustn't hold up a
     * check that can go ahead without it.
     */
    suspend fun claimRainSoon(serverUrl: String, deviceId: String, secret: String, key: String): Result<RainSoonClaim> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/devices/$deviceId/rain-soon")) {
            contentType(ContentType.Application.Json)
            bearer(secret)
            setBody(RainSoonClaimRequest(key))
            timeout { requestTimeoutMillis = RAIN_SOON_CLAIM_TIMEOUT_MILLIS }
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RainSoonClaim>()
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

    /**
     * The household's Teslas: where each was last seen, and its spot at a watched street's curb
     * (the relay's `/parking`). The relay answers from what it already knows, without asking
     * Tesla. None from a relay that predates parking (404), or that has no Teslas linked.
     */
    suspend fun getCars(serverUrl: String): Result<List<HouseholdCar>> = getParking(serverUrl).map { it.cars }

    /**
     * [getCars], with the watched streets' signs along each curb, for the cars' map to draw. No
     * streets from a relay without a `parking.json`, or from before it sent them.
     */
    suspend fun getParking(serverUrl: String): Result<ParkingSnapshot> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/parking"))
        if (response.status == HttpStatusCode.NotFound) return@runCatching ParkingSnapshot(emptyList(), emptyList())
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        val body = response.body<RelayParking>()
        ParkingSnapshot(body.cars.mapNotNull { it.toDomain() }, body.streets.mapNotNull { it.toDomain() })
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * The door sensors and their openings and closings over the last [days] (the relay's
     * `/doors`). Reads with the session cookie, or as this install ([deviceId], [secret]).
     * [DoorsSnapshot.supported] is false from a relay that predates the door sensors (404).
     *
     * With [since] (the [DoorsSnapshot.version] last read) the relay holds the request open up to
     * [waitSeconds] and answers the moment a door changes. A relay from before that answers at
     * once, with no version.
     */
    suspend fun getDoors(
        serverUrl: String,
        deviceId: String? = null,
        secret: String? = null,
        days: Int = DOOR_HISTORY_DAYS,
        since: Long? = null,
        waitSeconds: Int = 0,
    ): Result<DoorsSnapshot> =
        runCatching {
            val response = httpClient.get(relayUrl(serverUrl, "/doors")) {
                asDevice(deviceId, secret)
                parameter("days", days)
                if (since != null && waitSeconds > 0) {
                    parameter("since", since)
                    parameter("wait", waitSeconds)
                    // The client's usual cap is shorter than the wait; this allows for the wait plus an ordinary request.
                    timeout { requestTimeoutMillis = (waitSeconds + DOOR_WAIT_SLACK_SECONDS) * 1000L }
                }
            }
            if (response.status == HttpStatusCode.NotFound) return@runCatching DoorsSnapshot(supported = false, answered = true)
            if (!response.status.isSuccess()) throw relayRefused(response.status)
            response.body<RelayDoors>().toDomain()
        }.onFailure { if (it is CancellationException) throw it }

    /** Sets when [doorId]'s openings push, for the whole household. A user action, so the session cookie. Answers the doors as they now stand. */
    suspend fun setDoorNotify(serverUrl: String, doorId: String, notify: DoorNotify): Result<DoorsSnapshot> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/doors/$doorId")) {
            contentType(ContentType.Application.Json)
            setBody(DoorNotifyUpdate(notify.wire))
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayDoors>().toDomain()
    }

    /**
     * Lets sensors join the Zigbee network for [seconds] (the relay caps it at 254), or with 0
     * stops. A user action, so the session cookie. Answers the doors as they now stand, with
     * [DoorsSnapshot.pairing] saying how the window is.
     */
    suspend fun setDoorPairing(serverUrl: String, seconds: Int): Result<DoorsSnapshot> = runCatching {
        val response = httpClient.post(relayUrl(serverUrl, "/doors/pairing")) {
            contentType(ContentType.Application.Json)
            setBody(DoorPairingUpdate(seconds))
        }
        if (!response.status.isSuccess()) throw zigbeeRefused(response)
        response.body<RelayDoors>().toDomain()
    }

    /** Renames [doorId]'s sensor in Zigbee2MQTT, for the whole household. A user action, so the session cookie. Answers the doors as they now stand. */
    suspend fun renameDoor(serverUrl: String, doorId: String, name: String): Result<DoorsSnapshot> = runCatching {
        val response = httpClient.put(relayUrl(serverUrl, "/doors/$doorId/name")) {
            contentType(ContentType.Application.Json)
            setBody(DoorRename(name))
        }
        if (!response.status.isSuccess()) throw zigbeeRefused(response)
        response.body<RelayDoors>().toDomain()
    }

    /**
     * Why the relay couldn't have Zigbee2MQTT do something: it can't reach it (503), it didn't
     * answer (504), or it said no, in its own words (502, which are shown as they are).
     */
    private suspend fun zigbeeRefused(response: HttpResponse): Exception = when (response.status) {
        HttpStatusCode.ServiceUnavailable -> FrigateResponseException(UiText.of(Res.string.doors_error_no_zigbee), technical = "Relay answered 503")

        HttpStatusCode.GatewayTimeout -> FrigateResponseException(UiText.of(Res.string.doors_error_zigbee_silent), technical = "Relay answered 504")

        HttpStatusCode.BadRequest -> FrigateResponseException(UiText.of(Res.string.doors_rename_invalid), technical = "Relay answered 400")

        HttpStatusCode.BadGateway -> runCatching { response.body<RelayDetail>().detail }.getOrNull()?.ifBlank { null }
            ?.let { FrigateResponseException(it) } ?: relayRefused(response.status)

        else -> relayRefused(response.status)
    }

    /**
     * The house's floor plan (the relay's `/floorplan`, from a file kept only on the box). Null when
     * the box has none, or the relay predates it (both answer 404).
     */
    suspend fun getFloorPlan(serverUrl: String, deviceId: String? = null, secret: String? = null): Result<FloorPlan?> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/floorplan")) { asDevice(deviceId, secret) }
        if (response.status == HttpStatusCode.NotFound) return@runCatching null
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayFloorPlan>().toDomain()
    }.onFailure { if (it is CancellationException) throw it }

    /** Places [doorId] on the floor plan, or with null takes it off. A user action, so the session cookie. Answers the doors as they now stand. */
    suspend fun setDoorSpot(serverUrl: String, doorId: String, spot: DoorSpot?): Result<DoorsSnapshot> = runCatching {
        val url = relayUrl(serverUrl, "/doors/$doorId/spot")
        val response = if (spot == null) {
            httpClient.delete(url)
        } else {
            httpClient.put(url) {
                contentType(ContentType.Application.Json)
                setBody(RelayDoorSpot(spot.floorId, spot.x.toDouble(), spot.y.toDouble()))
            }
        }
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayDoors>().toDomain()
    }

    /**
     * The household key vault as the signed-in user's phones see it (docs/key-vault.md): every
     * value sealed on a phone. A user action's session cookie. Null from a relay too old to have one.
     */
    suspend fun getVault(serverUrl: String): Result<RelayVault?> = runCatching {
        val response = httpClient.get(relayUrl(serverUrl, "/vault"))
        if (response.status == HttpStatusCode.NotFound) return@runCatching null
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayVault>()
    }.onFailure { if (it is CancellationException) throw it }

    /**
     * Sets the signed-in user's key pair, its private half sealed with their login, in place of
     * [replaces] (the public key this phone last saw, or null for none). Fails with
     * [VaultConflictException] when another phone has set one since.
     */
    suspend fun putVaultMember(serverUrl: String, public: String, sealedPrivate: String, replaces: String?): Result<RelayVault> =
        vaultCall { httpClient.put(relayUrl(serverUrl, "/vault/me")) { json(RelayVaultMemberBody(public, sealedPrivate, replaces)) } }

    /** Gives [member] the household key, sealed to their public key. */
    suspend fun putVaultGrant(serverUrl: String, member: String, grant: RelayVaultGrantBody): Result<RelayVault> =
        vaultCall { httpClient.put(relayUrl(serverUrl, "/vault/grants/$member")) { json(grant) } }

    /** Starts a new household key, granted to the signed-in user; only while nobody holds the old one. */
    suspend fun startVaultHousehold(serverUrl: String, grant: RelayVaultGrantBody): Result<RelayVault> =
        vaultCall { httpClient.post(relayUrl(serverUrl, "/vault/household")) { json(grant) } }

    /** Sets sealed secrets by name, or removes the ones given as null. */
    suspend fun putVaultSecrets(serverUrl: String, household: String, secrets: Map<String, String?>): Result<RelayVault> =
        vaultCall { httpClient.put(relayUrl(serverUrl, "/vault/secrets")) { json(RelayVaultSecretsBody(household, secrets)) } }

    private suspend fun vaultCall(call: suspend () -> HttpResponse): Result<RelayVault> = runCatching {
        val response = call()
        if (response.status == HttpStatusCode.Conflict) throw VaultConflictException(relayRefused(response.status))
        if (!response.status.isSuccess()) throw relayRefused(response.status)
        response.body<RelayVault>()
    }.onFailure { if (it is CancellationException) throw it }

    private inline fun <reified T> HttpRequestBuilder.json(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
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

        /** How far back the door history goes in the app. */
        const val DOOR_HISTORY_DAYS = 7

        /** How much longer than its wait a held `/doors` may take before it counts as failed. */
        private const val DOOR_WAIT_SLACK_SECONDS = 10

        private const val RAIN_SOON_CLAIM_TIMEOUT_MILLIS = 5_000L

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
    /** The phone it runs on, kept across reinstalls (`DeviceInfo.hardwareId`): what the relay tracks a phone by. */
    @SerialName("hardware_id") val hardwareId: String? = null,
    /** An emulator or simulator, which is never tracked whatever its build. */
    val emulator: Boolean = false,
    /** Quiet hours, in minutes after local midnight; both null when they're off. */
    @SerialName("quiet_start") val quietStart: Int? = null,
    @SerialName("quiet_end") val quietEnd: Int? = null,
    /** "Only when everyone's away": the relay sends this phone Away alerts and nothing else. */
    @SerialName("only_away") val onlyAway: Boolean = false,
    /** Where the phone's quiet hours are read: its IANA zone, and its UTC offset now for a relay that can't resolve the zone. */
    val tz: String? = null,
    @SerialName("utc_offset") val utcOffsetMinutes: Int? = null,
    /** The weather's "rain on the way" is wanted here, so the relay may push it (docs/weather.md). */
    @SerialName("rain_soon") val rainSoon: Boolean = false,
)

@Serializable
internal data class RainSoonClaimRequest(val key: String)

/** The relay's answer to [PushRelayApi.claimRainSoon]: not [granted], this phone was told of a shower at [at] (epoch seconds). */
@Serializable
data class RainSoonClaim(val granted: Boolean, val at: Double? = null)

/**
 * What the relay knows this install as, and the secret that proves it; and whether it can push
 * to the token it was given ([push]): an iPhone's only once the relay has APNs set up. A relay
 * too old to say can push to Android's FCM token, as it always could, and not to an iPhone's.
 */
data class DeviceCredentials(val deviceId: String, val secret: String, val push: Boolean = true)

@Serializable
internal data class RelayRegistration(
    val ok: Boolean = true,
    @SerialName("device_id") val deviceId: String,
    val secret: String? = null,
    val push: Boolean? = null,
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

/** The relay's `GET /presence` (and `PUT .../presence`, `PUT`/`DELETE /home`) body. */
@Serializable
internal data class RelayPresence(
    /** The tracked phones alone: one Android phone and one iPhone at most. */
    val devices: List<RelayPresenceDevice> = emptyList(),
    @SerialName("everyone_away") val everyoneAway: Boolean = false,
    val home: RelayHome? = null,
    /**
     * Why the install the request named isn't listed: "debug", "emulator", "taken" (another phone
     * of its platform is tracked), "old_app", "platform" or "unregistered"; null when it is listed.
     */
    @SerialName("not_tracked") val notTracked: String? = null,
) {
    fun toDomain() = HouseholdPresence(
        devices = devices.map {
            PresenceDevice(
                name = it.name,
                platform = it.platform,
                away = it.away,
                updatedEpochSeconds = it.awayUpdated,
                isThisDevice = it.thisDevice,
                pendingAway = it.pendingAway,
                id = it.id,
                lastSeenEpochSeconds = it.lastSeen,
            )
        },
        everyoneAway = everyoneAway,
        home = home?.let { HomeLocation(it.lat, it.lng, it.radiusMeters) },
        thisDeviceNotTracked = when (notTracked) {
            null -> null
            "debug" -> NotTrackedReason.DEBUG_BUILD
            "emulator" -> NotTrackedReason.EMULATOR
            "taken" -> NotTrackedReason.ANOTHER_PHONE
            else -> NotTrackedReason.UNKNOWN
        },
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
    @SerialName("pending_away") val pendingAway: Boolean = false,
    /** The device_id; older relays don't send it, and their devices can't be removed from the app. */
    val id: String? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
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

@Serializable
internal data class DoorNotifyUpdate(val notify: String)

@Serializable
internal data class DoorPairingUpdate(val seconds: Int)

@Serializable
internal data class DoorRename(val name: String)

/** FastAPI's error body. */
@Serializable
internal data class RelayDetail(val detail: String? = null)

@Serializable
internal data class RelayDoorPairing(
    val open: Boolean = false,
    val until: Double? = null,
    val joined: List<RelayDoorJoin> = emptyList(),
) {
    fun toDomain() = DoorPairing(
        open = open,
        untilMillis = until?.takeIf { open }?.let { (it * 1000).toLong() },
        joined = joined.mapNotNull { join ->
            DoorJoinStatus.fromWire(join.status)?.let { DoorJoin(join.id, join.name?.ifBlank { null } ?: join.id, it, (join.at * 1000).toLong()) }
        },
    )
}

@Serializable
internal data class RelayDoorJoin(val id: String, val name: String? = null, val status: String? = null, val at: Double = 0.0)

@Serializable
internal data class RelayDoors(
    val doors: List<RelayDoor> = emptyList(),
    val events: List<RelayDoorEvent> = emptyList(),
    val broker: Boolean = false,
    val bridge: Boolean = false,
    /** Moves on every change; null from a relay that can't hold a request open for the next one. */
    val version: Long? = null,
    /** Null from a relay that can't pair sensors. */
    val pairing: RelayDoorPairing? = null,
) {
    fun toDomain() = DoorsSnapshot(
        doors = doors.map { it.toDomain() },
        events = events.map { DoorEvent(it.door, it.open, (it.at * 1000).toLong(), it.pushed) },
        listening = broker && bridge,
        answered = true,
        version = version,
        pairing = pairing?.toDomain(),
    )
}

@Serializable
internal data class RelayDoor(
    val id: String,
    val name: String? = null,
    val model: String? = null,
    val open: Boolean? = null,
    val changed: Double? = null,
    val battery: Int? = null,
    @SerialName("battery_low") val batteryLow: Boolean? = null,
    val link: Int? = null,
    val online: Boolean? = null,
    val notify: String? = null,
    val spot: RelayDoorSpot? = null,
) {
    fun toDomain() = Door(
        id = id,
        name = name?.ifBlank { null } ?: id,
        open = open,
        changedAtMillis = changed?.let { (it * 1000).toLong() },
        battery = battery,
        batteryLow = batteryLow == true,
        linkQuality = link,
        online = online,
        notify = DoorNotify.fromWire(notify),
        model = model,
        spot = spot?.let { DoorSpot(it.floor, it.x.toFloat(), it.y.toFloat()) },
    )
}

@Serializable
internal data class RelayDoorSpot(val floor: String, val x: Double, val y: Double)

@Serializable
internal data class RelayFloorPlan(val floors: List<RelayFloor> = emptyList(), val street: String? = null) {
    /** Null when no floor has an outline to draw: nothing to show. */
    fun toDomain(): FloorPlan? = floors.mapNotNull { it.toDomain() }.takeIf { it.isNotEmpty() }?.let { FloorPlan(it, street?.ifBlank { null }) }
}

@Serializable
internal data class RelayFloor(
    val id: String,
    val name: String? = null,
    val outline: List<List<Double>> = emptyList(),
    val rooms: List<RelayRoom> = emptyList(),
    /** Each as {x, y, direction, facing, flight}; a plan from before stairs had a direction lists bare [x, y]s. */
    val stairs: List<JsonElement> = emptyList(),
    /** Each inside wall as [x1, y1, x2, y2]. */
    val walls: List<List<Double>> = emptyList(),
    val doors: List<RelayPlanDoor> = emptyList(),
) {
    fun toDomain(): PlanFloor? {
        val points = outline.mapNotNull { it.point() }
        if (points.size < 3) return null
        return PlanFloor(
            id = id,
            name = name?.ifBlank { null } ?: id,
            outline = points,
            rooms = rooms.map { PlanRoom(it.name, PlanPoint(it.x.toFloat(), it.y.toFloat())) },
            stairs = stairs.mapNotNull { it.stairs() },
            walls = walls.filter { it.size >= 4 }.map { PlanWall(PlanPoint(it[0].toFloat(), it[1].toFloat()), PlanPoint(it[2].toFloat(), it[3].toFloat())) },
            doors = doors.mapNotNull { door ->
                val from = door.from.point() ?: return@mapNotNull null
                val to = door.to.point() ?: return@mapNotNull null
                PlanDoor(from, to, if (door.swing == -1) -1 else 1, door.name?.ifBlank { null }, door.sensor?.ifBlank { null })
            },
        )
    }
}

@Serializable
internal data class RelayPlanDoor(
    val from: List<Double> = emptyList(),
    val to: List<Double> = emptyList(),
    val swing: Int = 1,
    val name: String? = null,
    val sensor: String? = null,
)

@Serializable
internal data class RelayRoom(val name: String, val x: Double, val y: Double)

private fun List<Double>.point(): PlanPoint? = if (size >= 2) PlanPoint(this[0].toFloat(), this[1].toFloat()) else null

private fun JsonElement.stairs(): PlanStairs? = when (this) {
    is JsonArray -> {
        val x = getOrNull(0)?.jsonPrimitive?.doubleOrNull
        val y = getOrNull(1)?.jsonPrimitive?.doubleOrNull
        if (x != null && y != null) PlanStairs(PlanPoint(x.toFloat(), y.toFloat())) else null
    }

    is JsonObject -> {
        val x = this["x"]?.jsonPrimitive?.doubleOrNull
        val y = this["y"]?.jsonPrimitive?.doubleOrNull
        if (x == null || y == null) {
            null
        } else {
            PlanStairs(
                at = PlanPoint(x.toFloat(), y.toFloat()),
                direction = when (this["direction"]?.jsonPrimitive?.contentOrNull) {
                    "up" -> StairDirection.UP
                    "down" -> StairDirection.DOWN
                    else -> null
                },
                facingDegrees = when (this["facing"]?.jsonPrimitive?.contentOrNull) {
                    "e" -> 90
                    "s" -> 180
                    "w" -> 270
                    else -> 0
                },
                flight = this["flight"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
            )
        }
    }

    else -> null
}

@Serializable
internal data class RelayDoorEvent(val door: String, val open: Boolean, val at: Double, val pushed: String? = null)

/** The vault as `GET /vault` answers it (relay.py, "vault"). Every string but the names is sealed or a public key. */
@Serializable
data class RelayVault(
    val user: String = "",
    val household: String? = null,
    val me: RelayVaultMe? = null,
    val members: List<RelayVaultMember> = emptyList(),
    val secrets: Map<String, String> = emptyMap(),
)

/** The signed-in user's own key pair, its private half sealed with their login, and their copy of the household key if they have one. */
@Serializable
data class RelayVaultMe(val public: String, val private: String, val grant: RelayVaultGrant? = null)

@Serializable
data class RelayVaultMember(val user: String, val public: String, val granted: Boolean = false)

@Serializable
data class RelayVaultGrant(val ephemeral: String, val box: String)

@Serializable
internal data class RelayVaultMemberBody(val public: String, val private: String, val replaces: String?)

/** The household key sealed to [recipient], the public key it is for: the relay refuses it once that key pair has been replaced. */
@Serializable
data class RelayVaultGrantBody(val household: String, val recipient: String, val ephemeral: String, val box: String)

/** The vault changed under this phone (another phone set a key pair, or someone's was replaced): look again and redo. */
class VaultConflictException(refused: FrigateResponseException) : LocalizedException(refused.text, refused.message, refused)

@Serializable
internal data class RelayVaultSecretsBody(val household: String, val secrets: Map<String, String?>)

/** The relay's `GET /parking` body. */
@Serializable
internal data class RelayParking(val cars: List<RelayParkedCar> = emptyList(), val streets: List<RelayParkingStreet> = emptyList())

/** A stretch of a watched street and its curbs' signs, as `parking_signs` in relay.py sends it. */
@Serializable
internal data class RelayParkingStreet(
    val street: String = "",
    val line: List<List<Double>> = emptyList(),
    @SerialName("width_m") val widthMetres: Double? = null,
    val sides: List<RelayParkingCurb> = emptyList(),
) {
    fun toDomain(): ParkingStreet? {
        val points = line.mapNotNull { p ->
            val (lat, lng) = (p.getOrNull(0) ?: return@mapNotNull null) to (p.getOrNull(1) ?: return@mapNotNull null)
            ParkingStreet.Point(lat, lng).takeIf { lat in -90.0..90.0 && lng in -180.0..180.0 }
        }
        if (points.size < 2) return null
        return ParkingStreet(street, points, widthMetres?.takeIf { it > 0 } ?: ParkingStreet.WIDTH_METRES, sides.map { it.toDomain() })
    }
}

@Serializable
internal data class RelayParkingCurb(val side: String? = null, val offset: String? = null, val rules: List<RelayParkingSign> = emptyList()) {
    fun toDomain() = ParkingCurb(
        side = ParkingAlert.Side.entries.firstOrNull { it.name.equals(side, ignoreCase = true) },
        left = offset == "left",
        rules = rules.map { it.toDomain() },
    )
}

/**
 * One rule as the household wrote it in `parking.json`, with what the relay worked out about it.
 * Read loosely, a field at a time: the file is typed by hand, and one odd value mustn't lose the
 * whole answer.
 */
@Serializable
internal data class RelayParkingSign(
    val kind: String? = null,
    val minutes: JsonElement? = null,
    // Null is as good as left out, to the relay as to the household writing the file.
    val days: List<JsonElement>? = null,
    val weeks: List<JsonElement>? = null,
    val months: List<JsonElement>? = null,
    val start: JsonElement? = null,
    val end: JsonElement? = null,
    val note: JsonElement? = null,
    val permit: JsonElement? = null,
    val lifted: Boolean = false,
    val next: RelayParkingWindow? = null,
) {
    fun toDomain() = ParkingRule(
        limitMinutes = minutes?.number()?.toInt()?.takeIf { kind == "limit" && it > 0 },
        days = days.orEmpty().mapNotNull { day -> DAY_WORDS.indexOf(day.text()?.lowercase()?.take(3)).takeIf { it >= 0 } }.distinct().sorted(),
        weeks = weeks.orEmpty().mapNotNull { it.whole()?.takeIf { week -> week in 1..5 || week == -1 } }.distinct().sortedBy { if (it == -1) 6 else it },
        months = months.orEmpty().mapNotNull { it.whole()?.takeIf { month -> month in 1..12 } }.distinct().sorted(),
        startMinutes = start.clockMinutes() ?: 0,
        endMinutes = end.clockMinutes() ?: 1440,
        note = note?.text()?.takeIf { it.isNotBlank() },
        permit = permit?.text()?.takeIf { it.isNotBlank() },
        lifted = lifted,
        nextFromEpochSeconds = next?.from?.toLong(),
        nextUntilEpochSeconds = next?.until?.toLong(),
    )

    private companion object {
        val DAY_WORDS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull

        fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

        /**
         * A week or a month as `rule_windows` in relay.py matches it: a whole JSON number (2 or
         * 2.0). Quoted ("2") or fractional, it matches no day there, so it counts for none here.
         */
        fun JsonElement.whole(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toInt()

        /** "08:00" as 480, as `clock_minutes` in relay.py reads it. */
        fun JsonElement?.clockMinutes(): Int? {
            val text = this?.text()?.takeIf { it.isNotBlank() } ?: return null
            val hours = text.substringBefore(':').trim().toIntOrNull() ?: return null
            val minutes = text.substringAfter(':', "0").trim().toIntOrNull() ?: return null
            return (hours * 60 + minutes).takeIf { it in 0..1440 }
        }
    }
}

@Serializable
internal data class RelayParkedCar(
    val car: String = "",
    val name: String? = null,
    val street: String? = null,
    val side: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val since: Double? = null,
    @SerialName("move_by") val moveBy: Double? = null,
    val rule: RelayParkingRule? = null,
    val window: RelayParkingWindow? = null,
    val last: RelayCarFix? = null,
    val asleep: Boolean = false,
    val live: RelayCarLive? = null,
) {
    fun toDomain(): HouseholdCar? {
        if (car.isBlank()) return null
        val limit = rule?.minutes?.takeIf { rule.kind == "limit" && it > 0 }?.toInt()
        return HouseholdCar(
            id = car,
            name = name?.takeIf { it.isNotBlank() } ?: car,
            lastSeen = last?.toDomain(),
            parked = if (street != null && lat != null && lng != null) {
                CurbSpot(
                    latitude = lat,
                    longitude = lng,
                    street = street,
                    side = ParkingAlert.Side.entries.firstOrNull { it.name.equals(side, ignoreCase = true) },
                    sinceEpochSeconds = since?.toLong(),
                    moveByEpochSeconds = moveBy?.toLong(),
                    note = rule?.note?.takeIf { it.isNotBlank() },
                    limitMinutes = limit,
                    fromEpochSeconds = window?.from?.toLong()?.takeIf { limit == null },
                    untilEpochSeconds = window?.until?.toLong()?.takeIf { limit == null },
                )
            } else {
                null
            },
            asleep = asleep,
            live = live?.toDomain(),
        )
    }
}

/** What the car last streamed, the relay's `live` (miles, hours, °C, percent, as the car gives them). */
@Serializable
internal data class RelayCarLive(
    val connected: Boolean = false,
    val heard: Double? = null,
    val battery: Double? = null,
    @SerialName("range_mi") val rangeMiles: Double? = null,
    val charging: String? = null,
    @SerialName("hours_to_full") val hoursToFull: Double? = null,
    val locked: Boolean? = null,
    val sentry: String? = null,
    val destination: String? = null,
    @SerialName("minutes_to_arrival") val minutesToArrival: Double? = null,
    @SerialName("inside_c") val insideCelsius: Double? = null,
) {
    fun toDomain(): CarLive {
        val charge = when (charging) {
            "Charging", "Starting" -> CarLive.Charging.CHARGING
            "Complete" -> CarLive.Charging.COMPLETE
            "Stopped", "NoPower" -> CarLive.Charging.PLUGGED_IN
            else -> null // Disconnected, Unknown, or not said
        }
        return CarLive(
            connected = connected,
            heardEpochSeconds = heard?.toLong(),
            batteryPercent = battery?.takeIf { it in 0.0..100.0 }?.roundToInt(),
            rangeMiles = rangeMiles?.takeIf { it >= 0 },
            charging = charge,
            minutesToFull = hoursToFull?.takeIf { charge == CarLive.Charging.CHARGING && it > 0 }?.let { (it * 60).roundToInt() },
            locked = locked,
            sentry = sentry?.let { it in setOf("Armed", "Aware", "Panic", "Quiet") },
            destination = destination?.trim()?.takeIf { it.isNotEmpty() },
            minutesToArrival = minutesToArrival?.takeIf { it >= 0 }?.roundToInt(),
            insideCelsius = insideCelsius,
        )
    }
}

@Serializable
internal data class RelayParkingRule(val kind: String? = null, val minutes: Double? = null, val note: String? = null)

@Serializable
internal data class RelayParkingWindow(val from: Double? = null, val until: Double? = null)

@Serializable
internal data class RelayCarFix(
    val lat: Double? = null,
    val lng: Double? = null,
    val heading: Double? = null,
    val at: Double? = null,
    val moving: Boolean = false,
    val home: Boolean? = null,
) {
    fun toDomain(): CarFix? {
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return CarFix(lat, lng, heading?.toFloat(), at?.toLong() ?: 0L, moving, home)
    }
}
