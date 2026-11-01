package com.meticulouscreations.homesafe.packages.data

import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.PackagesUnavailableException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_gone
import homesafe.shared.generated.resources.packages_error_not_a_number
import homesafe.shared.generated.resources.packages_error_relay_answered
import homesafe.shared.generated.resources.packages_error_relay_outdated
import homesafe.shared.generated.resources.packages_error_signed_out
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parcels, through the relay (`/packages` in `relay/relay.py`). The relay holds the Gmail
 * tokens and looks through the camera, so all the phone ever holds is what it was told. Like every
 * relay call it rides on the Frigate session cookie; [device] says which phone is asking, which is
 * what decides whose surprises it may see.
 */
@Inject
class PackagesRelayApi(private val httpClient: HttpClient) {

    suspend fun snapshot(serverUrl: String, device: String): Result<RelayPackages> = call {
        httpClient.get(url(serverUrl, "/packages")) { parameter("device", device) }
    }

    /** Reads the mailboxes now, which takes a moment longer than a list. */
    suspend fun sync(serverUrl: String, device: String): Result<RelayPackages> = call {
        httpClient.post(url(serverUrl, "/packages/sync")) {
            parameter("device", device)
            timeout { requestTimeoutMillis = SYNC_TIMEOUT_MS }
        }
    }

    suspend fun add(serverUrl: String, device: String, body: RelayPackageAdd): Result<RelayPackages> = call(badRequest = UiText.of(Res.string.packages_error_not_a_number)) {
        httpClient.post(url(serverUrl, "/packages")) {
            parameter("device", device)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun change(serverUrl: String, device: String, id: String, body: RelayPackagePatch): Result<RelayPackages> = call {
        httpClient.patch(url(serverUrl, "/packages/$id")) {
            parameter("device", device)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun forget(serverUrl: String, device: String, id: String): Result<RelayPackages> = call {
        httpClient.delete(url(serverUrl, "/packages/$id")) { parameter("device", device) }
    }

    suspend fun setPhone(serverUrl: String, device: String, person: String?): Result<RelayPackages> = call {
        httpClient.put(url(serverUrl, "/packages/phone")) {
            contentType(ContentType.Application.Json)
            setBody(RelayPackagePhone(device, person))
        }
    }

    suspend fun settings(serverUrl: String, device: String, body: RelayPackageSettingsChange): Result<RelayPackages> = call {
        httpClient.put(url(serverUrl, "/packages/settings")) {
            parameter("device", device)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun url(serverUrl: String, path: String) = PushRelayApi.relayUrl(serverUrl, path)

    private suspend fun call(badRequest: UiText? = null, request: suspend () -> HttpResponse): Result<RelayPackages> = try {
        val response = request()
        when {
            response.status.isSuccess() -> Result.success(response.body<RelayPackages>())

            response.status == HttpStatusCode.BadRequest && badRequest != null ->
                Result.failure(PackagesUnavailableException(PackagesProblem.OTHER, badRequest, technical = "HTTP 400"))

            // A package that has gone since the list was asked for, or a surprise that was just hidden.
            response.status == HttpStatusCode.NotFound && "No such package" in response.bodyAsText() ->
                Result.failure(PackagesUnavailableException(PackagesProblem.OTHER, UiText.of(Res.string.packages_error_gone), technical = "HTTP 404"))

            // FastAPI's own 404 for a route it doesn't have: a relay from before this feature.
            response.status == HttpStatusCode.NotFound ->
                Result.failure(PackagesUnavailableException(PackagesProblem.RELAY_OUTDATED, UiText.of(Res.string.packages_error_relay_outdated), technical = "HTTP 404"))

            response.status == HttpStatusCode.Unauthorized ->
                Result.failure(PackagesUnavailableException(PackagesProblem.SIGNED_OUT, UiText.of(Res.string.packages_error_signed_out), technical = "HTTP 401"))

            else -> Result.failure(PackagesUnavailableException(PackagesProblem.OTHER, UiText.of(Res.string.packages_error_relay_answered, response.status.toString()), technical = "The relay answered ${response.status}"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 60_000L

        /** The address of a kept picture, or of the landing as it is now; a picture's address carries who is asking, since a surprise's aren't for everyone. */
        fun pictureUrl(serverUrl: String, device: String, parcelId: String?, name: String): String {
            val path = if (parcelId == null) "/packages/porch/now" else "/packages/$parcelId/frames/$name"
            return PushRelayApi.relayUrl(serverUrl, path) + "?device=$device"
        }
    }
}

@Serializable
data class RelayPackages(
    val viewer: String? = null,
    val people: List<String> = emptyList(),
    val now: Double = 0.0,
    val today: String? = null,
    @SerialName("mail_on") val mailOn: Boolean = false,
    val synced: RelayPackageSynced? = null,
    @SerialName("camera_on") val cameraOn: Boolean = false,
    val camera: String = "",
    @SerialName("camera_id") val cameraId: String = "",
    @SerialName("door_sensor") val doorSensor: Boolean = false,
    @SerialName("model_on") val modelOn: Boolean = false,
    @SerialName("tracker_on") val trackerOn: Boolean = false,
    val away: Boolean = false,
    val settings: RelayPackageSettings = RelayPackageSettings(),
    val packages: List<RelayPackage> = emptyList(),
)

@Serializable
data class RelayPackageSynced(val at: Double? = null, val failed: List<String> = emptyList())

@Serializable
data class RelayPackageSettings(val notify: Map<String, Boolean> = emptyMap(), @SerialName("hide_new") val hideNew: Map<String, Boolean> = emptyMap())

@Serializable
data class RelayPackageEvent(val at: Double = 0.0, val kind: String = "", val detail: String? = null)

@Serializable
data class RelayPackage(
    val id: String,
    val owner: String? = null,
    val source: String? = null,
    val stage: String = "ordered",
    @SerialName("stage_at") val stageAt: Double? = null,
    @SerialName("expected_from") val expectedFrom: String? = null,
    @SerialName("expected_to") val expectedTo: String? = null,
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
    val signature: Boolean = false,
    val problem: String? = null,
    @SerialName("problem_at") val problemAt: Double? = null,
    val created: Double? = null,
    val updated: Double? = null,
    @SerialName("delivered_at") val deliveredAt: Double? = null,
    val surprise: Boolean = false,
    val masked: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val porch: String? = null,
    @SerialName("porch_at") val porchAt: Double? = null,
    @SerialName("porch_by") val porchBy: String? = null,
    val verdict: String? = null,
    @SerialName("verdict_by") val verdictBy: String? = null,
    val late: Boolean = false,
    val carrier: String? = null,
    val tracking: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    val shop: String? = null,
    val title: String? = null,
    val nickname: String? = null,
    @SerialName("left_note") val leftNote: String? = null,
    val total: Double? = null,
    val notes: String? = null,
    @SerialName("return_by") val returnBy: String? = null,
    @SerialName("return_state") val returnState: String? = null,
    @SerialName("refund_at") val refundAt: Double? = null,
    @SerialName("verdict_what") val verdictWhat: String? = null,
    @SerialName("verdict_confidence") val verdictConfidence: Double? = null,
    @SerialName("visit_event") val visitEvent: String? = null,
    @SerialName("visit_at") val visitAt: Double? = null,
    val url: String? = null,
    val frames: List<String> = emptyList(),
    val timeline: List<RelayPackageEvent> = emptyList(),
)

@Serializable
data class RelayPackageAdd(val tracking: String, val nickname: String? = null, val owner: String? = null, val surprise: Boolean = false)

@Serializable
data class RelayPackagePatch(
    val nickname: String? = null,
    val notes: String? = null,
    val owner: String? = null,
    val surprise: Boolean? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    @SerialName("return_by") val returnBy: String? = null,
    @SerialName("return_state") val returnState: String? = null,
    val porch: String? = null,
    val camera: String? = null,
    val stage: String? = null,
)

@Serializable
data class RelayPackagePhone(@SerialName("device_id") val deviceId: String, val person: String? = null)

@Serializable
data class RelayPackageSettingsChange(val notify: Map<String, Boolean>? = null, @SerialName("hide_new") val hideNew: Map<String, Boolean>? = null)
