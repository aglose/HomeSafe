package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.renderCookieHeader

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
        check(response.status.isSuccess()) { "Login failed: ${response.status}" }
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
}
