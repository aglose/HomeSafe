package com.meticulouscreations.homesafe.atlas.data

import com.meticulouscreations.homesafe.atlas.domain.AtlasProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasUnavailableException
import com.meticulouscreations.homesafe.finance.data.suspendRunCatching
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_error_not_allowed
import homesafe.shared.generated.resources.atlas_error_relay_answered
import homesafe.shared.generated.resources.atlas_error_relay_outdated
import homesafe.shared.generated.resources.atlas_error_signed_out
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The location history, through the relay (`/timeline` in `relay/relay.py`). The relay reads the
 * Google Maps Timeline export from a Drive folder and names its places, so the phone never holds
 * a Google credential. Like every relay call it rides on the Frigate session cookie, and the
 * relay answers only an admin, or the usernames it has been told the history belongs to.
 */
@Inject
class AtlasRelayApi(private val httpClient: HttpClient) {

    /** Which years the relay has and how its upkeep is going. With [askDrive] it also looks in the Drive folder now. */
    suspend fun timeline(serverUrl: String, askDrive: Boolean = false): Result<RelayTimeline> = suspendRunCatching {
        val url = PushRelayApi.relayUrl(serverUrl, if (askDrive) "/timeline/sync" else "/timeline")
        val response = if (askDrive) httpClient.post(url) { timeout { requestTimeoutMillis = MANIFEST_TIMEOUT_MS } } else httpClient.get(url) { timeout { requestTimeoutMillis = MANIFEST_TIMEOUT_MS } }
        response.answer()
    }

    /** One year of visits, moves and paths: around a megabyte, so given longer than other calls. */
    suspend fun year(serverUrl: String, year: Int): Result<RelayYear> = suspendRunCatching {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/timeline/year/$year")) { timeout { requestTimeoutMillis = YEAR_TIMEOUT_MS } }.answer()
    }

    suspend fun places(serverUrl: String): Result<RelayPlaces> = suspendRunCatching {
        httpClient.get(PushRelayApi.relayUrl(serverUrl, "/timeline/places")) { timeout { requestTimeoutMillis = YEAR_TIMEOUT_MS } }.answer()
    }

    private suspend inline fun <reified T> HttpResponse.answer(): T = when {
        status.isSuccess() -> body<T>()

        // FastAPI's own 404 for a route it doesn't have: a relay from before this feature.
        status == HttpStatusCode.NotFound ->
            throw AtlasUnavailableException(AtlasProblem.RELAY_OUTDATED, UiText.of(Res.string.atlas_error_relay_outdated), technical = "HTTP 404")

        status == HttpStatusCode.Forbidden ->
            throw AtlasUnavailableException(AtlasProblem.NOT_ALLOWED, UiText.of(Res.string.atlas_error_not_allowed), technical = "HTTP 403")

        status == HttpStatusCode.Unauthorized ->
            throw AtlasUnavailableException(AtlasProblem.SIGNED_OUT, UiText.of(Res.string.atlas_error_signed_out), technical = "HTTP 401")

        else -> throw AtlasUnavailableException(AtlasProblem.OTHER, UiText.of(Res.string.atlas_error_relay_answered, status.toString()), technical = "The relay answered $status")
    }

    private companion object {
        const val MANIFEST_TIMEOUT_MS = 20_000L
        const val YEAR_TIMEOUT_MS = 120_000L
    }
}

@Serializable
data class RelayTimeline(
    val years: List<RelayYearSummary> = emptyList(),
    val first: Long? = null,
    val last: Long? = null,
    val places: RelayPlaceCounts = RelayPlaceCounts(),
    val file: RelayFile? = null,
    val drive: RelayDrive = RelayDrive(),
    val naming: RelayNaming = RelayNaming(),
    val syncing: Boolean = false,
)

@Serializable
data class RelayYearSummary(val year: Int, val etag: String, val visits: Int = 0, val moves: Int = 0, val points: Int = 0)

@Serializable
data class RelayPlaceCounts(val version: Int = 0, val total: Int = 0, val named: Int = 0)

@Serializable
data class RelayFile(val name: String? = null, @SerialName("ingested_at") val ingestedAt: Double? = null)

@Serializable
data class RelayDrive(
    val configured: Boolean = false,
    @SerialName("service_account") val serviceAccount: String? = null,
    @SerialName("checked_at") val checkedAt: Double? = null,
    @SerialName("next_check_at") val nextCheckAt: Double? = null,
    val error: String? = null,
    val message: String? = null,
    @SerialName("activation_url") val activationUrl: String? = null,
)

@Serializable
data class RelayNaming(
    val configured: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @SerialName("asked_this_month") val askedThisMonth: Int = 0,
    @SerialName("monthly_cap") val monthlyCap: Int = 0,
)

/** A year as `timeline_year_body` writes it: columns, a visit's place as its index into [places], a path as two packed strings. */
@Serializable
data class RelayYear(
    val year: Int,
    val places: List<String> = emptyList(),
    val visits: RelayVisits = RelayVisits(),
    val moves: RelayMoves = RelayMoves(),
    val paths: RelayPaths = RelayPaths(),
)

@Serializable
data class RelayVisits(
    val started: List<Long> = emptyList(),
    val ended: List<Long> = emptyList(),
    val offset: List<Int> = emptyList(),
    val lat: List<Double> = emptyList(),
    val lon: List<Double> = emptyList(),
    val place: List<Int> = emptyList(),
    val kind: List<String> = emptyList(),
)

@Serializable
data class RelayMoves(
    val started: List<Long> = emptyList(),
    val ended: List<Long> = emptyList(),
    val offset: List<Int> = emptyList(),
    val mode: List<String> = emptyList(),
    val lat: List<Double> = emptyList(),
    val lon: List<Double> = emptyList(),
    @SerialName("to_lat") val toLat: List<Double> = emptyList(),
    @SerialName("to_lon") val toLon: List<Double> = emptyList(),
    val metres: List<Double> = emptyList(),
)

@Serializable
data class RelayPaths(
    val started: List<Long> = emptyList(),
    val offset: List<Int> = emptyList(),
    val points: List<String> = emptyList(),
    val times: List<String> = emptyList(),
    val count: List<Int> = emptyList(),
)

@Serializable
data class RelayPlaces(val version: Int = 0, val places: List<RelayPlace> = emptyList())

@Serializable
data class RelayPlace(
    val id: String,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val kind: String = "other",
    val name: String? = null,
    val address: String? = null,
    val type: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
)
