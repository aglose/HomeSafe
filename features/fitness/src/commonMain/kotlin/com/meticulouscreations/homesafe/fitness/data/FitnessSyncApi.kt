package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A row as it goes to and from the server. [data] is null for one that was deleted. */
@Serializable
data class FitnessSyncRow(val kind: String, val id: String, val data: JsonObject? = null)

/** See `fitness_sync` in `relay/relay.py`. */
@Serializable
data class FitnessSyncAnswer(
    val owner: String,
    val reset: Boolean = false,
    val rev: Long = 0,
    val more: Boolean = false,
    val rows: List<FitnessSyncRow> = emptyList(),
)

@Serializable
data class FitnessSyncRequest(val owner: String? = null, val since: Long = 0, val changes: List<FitnessSyncRow> = emptyList())

/** The server's side of keeping a training log in step. */
interface FitnessSyncApi {
    /** Throws what it can't get an answer past: it is all tried again later. */
    suspend fun sync(url: String, request: FitnessSyncRequest): FitnessSyncAnswer
}

/** Through the relay (`/fitness/sync` in `relay/relay.py`), on the Frigate session cookie like every relay call. The relay says whose log that makes it. */
@Inject
@ContributesBinding(AppScope::class)
class FitnessRelayApi(private val httpClient: HttpClient) : FitnessSyncApi {
    override suspend fun sync(url: String, request: FitnessSyncRequest): FitnessSyncAnswer {
        val response = httpClient.post(PushRelayApi.relayUrl(url, "/fitness/sync")) {
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }
        // A session that has lapsed, or a relay from before this. Either way the log waits here.
        check(response.status.isSuccess()) { "The relay answered ${response.status}" }
        return response.body()
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
