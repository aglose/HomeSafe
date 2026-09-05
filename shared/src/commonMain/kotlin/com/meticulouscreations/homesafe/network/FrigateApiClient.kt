package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.CancellationException

/** The server answered, but not with success — bad credentials, say. The transport itself was fine. */
class FrigateResponseException(message: String) : Exception(message)

/**
 * Talks to a Frigate NVR's REST API. Login establishes a session cookie on [httpClient]
 * (via the `HttpCookies` plugin), which subsequent requests on the same client reuse
 * automatically — no manual bearer-token bookkeeping needed.
 */
@Inject
class FrigateApiClient(private val httpClient: HttpClient) {

    suspend fun login(serverUrl: String, username: String, password: String): Result<Unit> = runCatching {
        val response = httpClient.post("${serverUrl.trimEnd('/')}/api/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(user = username, password = password))
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Login failed: ${response.status}")
    }

    /**
     * Whether [serverUrl] answers HTTP at all within [timeoutMillis]. Any response counts — a
     * 401 from the authenticated port proves the host is there just as well as a 200 — so only
     * a connection failure or a timeout means "not reachable". Used to decide between a
     * server's private LAN address and its Tailscale address.
     */
    suspend fun isReachable(serverUrl: String, timeoutMillis: Long): Boolean =
        try {
            httpClient.get("${serverUrl.trimEnd('/')}/api/version") {
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    suspend fun getCameras(serverUrl: String): Result<List<FrigateCamera>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config")
        check(response.status.isSuccess()) { "Couldn't load cameras: ${response.status}" }
        response.body<FrigateConfigResponse>()
            .cameras
            .map { (name, config) -> toFrigateCamera(name, config) }
    }

    /**
     * Resolves a camera's full-quality and grid stream names from its (entirely user-defined,
     * optional) `live.streams` config. When exactly two distinct go2rtc stream names are
     * configured, the one matching the camera's own name is treated as the full-quality stream
     * (Frigate's docs note this match is required for its own mse/webrtc live views) and the
     * other as the lower-quality grid candidate. Any other shape (none configured, only one
     * stream, or three or more) falls back to using the camera name for both — today's behavior,
     * unchanged.
     */
    private fun toFrigateCamera(name: String, config: FrigateCameraConfig): FrigateCamera {
        val distinctStreamNames = config.live?.streams?.values?.distinct().orEmpty()
        val (liveStreamName, gridStreamName) = if (distinctStreamNames.size == 2) {
            val main = distinctStreamNames.firstOrNull { it == name }
            val sub = distinctStreamNames.firstOrNull { it != name }
            if (main != null && sub != null) main to sub else name to name
        } else {
            name to name
        }
        return FrigateCamera(
            name = name,
            enabled = config.enabled,
            liveStreamName = liveStreamName,
            gridStreamName = gridStreamName,
        )
    }

    /** [cameraName]'s detect resolution and current masks, from `/api/config`. */
    suspend fun getDetectionConfig(serverUrl: String, cameraName: String): Result<FrigateDetectionConfig> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config")
        check(response.status.isSuccess()) { "Couldn't load camera config: ${response.status}" }
        val config = response.body<FrigateConfigResponse>().cameras[cameraName]
            ?: throw FrigateResponseException("Camera $cameraName isn't on this server")
        FrigateDetectionConfig(
            cameraName = cameraName,
            detectWidth = config.detect?.width ?: DEFAULT_DETECT_WIDTH,
            detectHeight = config.detect?.height ?: DEFAULT_DETECT_HEIGHT,
            objectMasks = config.objects?.mask.orEmpty(),
            motionMasks = config.motion?.mask.orEmpty(),
            zones = config.zones.mapNotNull { (name, zone) ->
                zone.coordinates.firstOrNull()?.let { FrigateZone(name, it, zone.objects, zone.friendlyName) }
            },
            trackedObjects = config.objects?.track.orEmpty(),
        )
    }

    /**
     * Replaces [cameraName]'s `motion.mask` or `objects.mask` with [polygons] (Frigate-format
     * coordinate strings) and applies it live — one query parameter per polygon, or a blank one
     * to delete the key. See [setCameraConfig].
     */
    suspend fun setCameraMasks(
        serverUrl: String,
        cameraName: String,
        section: CameraSection,
        polygons: List<String>,
    ): Result<Unit> {
        val key = "cameras.$cameraName.${section.configKey}.mask"
        val params = if (polygons.isEmpty()) listOf(key to "") else polygons.map { key to it }
        return setCameraConfig(serverUrl, cameraName, section, params)
    }

    /**
     * Writes camera config keys and applies them live, exactly the way Frigate's own editor
     * does: `PUT /api/config/set?cameras.<cam>.<key>=<value>&...` with a body that says "no
     * restart needed" and names the camera [section] to hot-reload. Every entry in [params] is
     * one query parameter: a key repeated with several values becomes a YAML list, and a key
     * with a blank value deletes it (so only send a blank for a key that exists — deleting a
     * missing key is a server error). Frigate rewrites config.yml, re-validates it (rolling back
     * and answering 400 if the result is invalid), then pushes the section to the camera
     * process. Needs the `admin` role.
     */
    suspend fun setCameraConfig(
        serverUrl: String,
        cameraName: String,
        section: CameraSection,
        params: List<Pair<String, String>>,
    ): Result<Unit> = runCatching {
        require(params.isNotEmpty()) { "Nothing to save" }
        val response = httpClient.put("${serverUrl.trimEnd('/')}/api/config/set") {
            params.forEach { (key, value) -> parameter(key, value) }
            contentType(ContentType.Application.Json)
            setBody(ConfigSetRequest(requiresRestart = 0, updateTopic = "config/cameras/$cameraName/${section.configKey}"))
        }
        val result = runCatching { response.body<ConfigSetResponse>() }.getOrNull()
        if (!response.status.isSuccess() || result?.success == false) {
            throw FrigateResponseException(result?.message ?: "Couldn't save: ${response.status}")
        }
    }

    /** A hot-reloadable camera config section; the name doubles as the update topic. */
    enum class CameraSection(val configKey: String) { MOTION("motion"), OBJECTS("objects"), ZONES("zones") }

    /** The most recent [limit] detections across all cameras, newest first. */
    suspend fun getEvents(serverUrl: String, limit: Int = 100): Result<List<FrigateEvent>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/events") {
            parameter("limit", limit)
        }
        check(response.status.isSuccess()) { "Couldn't load events: ${response.status}" }
        response.body<List<FrigateEvent>>()
    }

    /** Recorded clips of [cameraName] overlapping [afterEpochSeconds]..[beforeEpochSeconds], oldest first. */
    suspend fun getRecordings(
        serverUrl: String,
        cameraName: String,
        afterEpochSeconds: Double,
        beforeEpochSeconds: Double,
    ): Result<List<FrigateRecording>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/$cameraName/recordings") {
            parameter("after", formatEpochSeconds(afterEpochSeconds))
            parameter("before", formatEpochSeconds(beforeEpochSeconds))
        }
        check(response.status.isSuccess()) { "Couldn't load recordings: ${response.status}" }
        response.body<List<FrigateRecording>>()
    }

    /**
     * The session cookies [httpClient] holds for [serverUrl], rendered as a `Cookie` request
     * header value — so a native video player (which has its own HTTP stack and no access to
     * Ktor's cookie jar) can hit Frigate's authenticated `/vod/` endpoints. Null when the server
     * issued no cookies, e.g. when auth is disabled.
     */
    suspend fun sessionCookieHeader(serverUrl: String): String? =
        httpClient.cookies(serverUrl)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ", transform = ::renderCookieHeader)

    private companion object {
        /** Frigate's own defaults when a camera config omits `detect.width`/`height`. */
        const val DEFAULT_DETECT_WIDTH = 1280
        const val DEFAULT_DETECT_HEIGHT = 720
    }
}
