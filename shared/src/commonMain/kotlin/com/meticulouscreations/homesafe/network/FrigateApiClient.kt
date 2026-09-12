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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The server answered, but not with success — bad credentials, say. The transport itself was fine. */
open class FrigateResponseException(message: String) : Exception(message)

/** The server understood the login and turned it down: the username or password is wrong for this server. */
class CredentialsRejectedException(message: String) : FrigateResponseException(message)

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
        if (response.status.isSuccess()) return@runCatching
        val message = "Login failed: ${response.status}"
        val rejected = response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden
        throw if (rejected) CredentialsRejectedException(message) else FrigateResponseException(message)
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
    enum class CameraSection(val configKey: String) { DETECT("detect"), MOTION("motion"), OBJECTS("objects"), ZONES("zones") }

    /**
     * Turns [cameraName]'s object detection on or off, live. Same `PUT /api/config/set` path as
     * the masks editor, so the change also lands in config.yml and survives a restart — unlike
     * Frigate's own UI toggle, which is MQTT/websocket-only and forgotten on restart. Detection
     * needs motion to feed it, so enabling it also enables motion first, the way Frigate's own
     * toggle does.
     */
    suspend fun setCameraDetection(serverUrl: String, cameraName: String, enabled: Boolean, motionEnabled: Boolean): Result<Unit> {
        if (enabled && !motionEnabled) {
            setCameraMotion(serverUrl, cameraName, enabled = true).onFailure { return Result.failure(it) }
        }
        return setCameraSwitch(serverUrl, cameraName, CameraSection.DETECT, enabled)
    }

    /** Turns [cameraName]'s motion detection on or off, live. See [setCameraDetection] for the mechanism. */
    suspend fun setCameraMotion(serverUrl: String, cameraName: String, enabled: Boolean): Result<Unit> =
        setCameraSwitch(serverUrl, cameraName, CameraSection.MOTION, enabled)

    /**
     * Writes `cameras.<cam>.<section>.enabled` as a real boolean and hot-reloads the section.
     * Goes through the body's `config_data` rather than the query string on purpose: query
     * values are written to config.yml as strings (`enabled: 'false'`), which Frigate tolerates
     * on the next restart but is wrong in the file. See [setCameraConfig] for the rest.
     */
    private suspend fun setCameraSwitch(serverUrl: String, cameraName: String, section: CameraSection, enabled: Boolean): Result<Unit> = runCatching {
        val configData = buildJsonObject {
            putJsonObject("cameras") {
                putJsonObject(cameraName) {
                    putJsonObject(section.configKey) { put("enabled", enabled) }
                }
            }
        }
        val response = httpClient.put("${serverUrl.trimEnd('/')}/api/config/set") {
            contentType(ContentType.Application.Json)
            setBody(ConfigSetRequest(requiresRestart = 0, updateTopic = "config/cameras/$cameraName/${section.configKey}", configData = configData))
        }
        val result = runCatching { response.body<ConfigSetResponse>() }.getOrNull()
        if (!response.status.isSuccess() || result?.success == false) {
            throw FrigateResponseException(result?.message ?: "Couldn't save: ${response.status}")
        }
    }

    /**
     * The most recent [limit] detections across all cameras, newest first. [afterEpochSeconds]
     * restricts it to detections that started after that moment (Frigate's `after` filter), which
     * is what a poller that only wants what's new since its last look asks for.
     */
    suspend fun getEvents(serverUrl: String, limit: Int = 100, afterEpochSeconds: Double? = null): Result<List<FrigateEvent>> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/events") {
            parameter("limit", limit)
            if (afterEpochSeconds != null) parameter("after", formatEpochSeconds(afterEpochSeconds))
        }
        check(response.status.isSuccess()) { "Couldn't load events: ${response.status}" }
        response.body<List<FrigateEvent>>()
    }

    /** What the server is doing right now — version, uptime, load, disk, detector and per-camera pipeline stats. */
    suspend fun getStats(serverUrl: String): Result<FrigateServerStats> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/stats")
        check(response.status.isSuccess()) { "Couldn't load server stats: ${response.status}" }
        response.body<FrigateStatsResponse>().toServerStats()
    }

    /** The server-wide config the Settings tab reports: retention, detector, AI features, and each camera's pipeline switches. */
    suspend fun getServerConfig(serverUrl: String): Result<FrigateServerConfig> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/config")
        check(response.status.isSuccess()) { "Couldn't load server config: ${response.status}" }
        val config = response.body<FrigateConfigResponse>()
        FrigateServerConfig(
            retention = FrigateRetention(
                continuousDays = config.record?.continuous?.days ?: 0.0,
                motionDays = config.record?.motion?.days ?: 0.0,
                alertDays = config.record?.alerts?.retain?.days,
                detectionDays = config.record?.detections?.retain?.days,
            ),
            detectors = config.detectors.mapValues { (_, detector) -> detector.type },
            model = config.model?.let { FrigateModelInfo(it.modelType, it.path, it.width, it.height) },
            faceRecognitionEnabled = config.faceRecognition?.enabled == true,
            licensePlateRecognitionEnabled = config.lpr?.enabled == true,
            semanticSearchEnabled = config.semanticSearch?.enabled == true,
            cameras = config.cameras.map { (name, camera) ->
                FrigateCameraPipelineConfig(
                    name = name,
                    enabled = camera.enabled,
                    detectEnabled = camera.detect?.enabled ?: true,
                    motionEnabled = camera.motion?.enabled ?: true,
                    zones = camera.zones.map { (zoneName, zone) ->
                        FrigateZone(zoneName, zone.coordinates.firstOrNull().orEmpty(), zone.objects, zone.friendlyName?.takeIf { it.isNotBlank() })
                    },
                )
            },
        )
    }

    /** Whether the signed-in account may change config (`role == admin`). Viewers get 401s from `/api/config/set`. */
    suspend fun isAdmin(serverUrl: String): Result<Boolean> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}/api/profile")
        check(response.status.isSuccess()) { "Couldn't load profile: ${response.status}" }
        response.body<FrigateProfileResponse>().role == "admin"
    }

    /** Raw bytes of a detection's thumbnail, for a notification's picture. */
    suspend fun getEventThumbnail(serverUrl: String, eventId: String): Result<ByteArray> = runCatching {
        val response = httpClient.get(frigateEventThumbnailUrl(serverUrl, eventId))
        check(response.status.isSuccess()) { "No thumbnail: ${response.status}" }
        response.body<ByteArray>()
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

/** "9.4" -> 9.4, "8.0%" -> 8.0; null for anything else. Frigate reports percentages as strings, some with a sign. */
internal fun parseFrigatePercent(raw: String?): Double? = raw?.trim()?.trimEnd('%')?.toDoubleOrNull()

internal fun FrigateStatsResponse.toServerStats(): FrigateServerStats {
    // The one `cpu_usages` row that isn't a process: the whole machine.
    val system = cpuUsages["frigate.full_system"]
    return FrigateServerStats(
        version = service?.version.orEmpty(),
        latestVersion = service?.latestVersion?.takeIf { it.isNotBlank() },
        uptimeSeconds = service?.uptime ?: 0,
        cpuPercent = parseFrigatePercent(system?.cpu),
        memoryPercent = parseFrigatePercent(system?.mem),
        storage = service?.storage.orEmpty().mapValues { (_, mount) -> FrigateStorage(mount.total, mount.used, mount.free) },
        detectors = detectors.map { (name, detector) -> FrigateDetector(name, detector.inferenceSpeed) },
        gpus = gpuUsages.map { (name, gpu) ->
            FrigateGpu(name, parseFrigatePercent(gpu.gpu), parseFrigatePercent(gpu.mem), parseFrigatePercent(gpu.dec))
        },
        cameras = cameras.mapValues { (_, camera) ->
            FrigateCameraPipeline(camera.cameraFps, camera.processFps, camera.skippedFps, camera.detectionFps)
        },
        totalDetectionFps = detectionFps,
    )
}
