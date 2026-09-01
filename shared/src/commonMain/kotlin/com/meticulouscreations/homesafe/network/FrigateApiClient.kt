package com.meticulouscreations.homesafe.network

import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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
            .map { (name, config) -> FrigateCamera(name = name, enabled = config.enabled) }
    }
}
