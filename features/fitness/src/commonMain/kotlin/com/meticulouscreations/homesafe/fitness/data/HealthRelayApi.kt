package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.fitness.domain.DistanceSpan
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthReadings
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.Pause
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.fitness.domain.SleepStage
import com.meticulouscreations.homesafe.fitness.domain.SleepStageKind
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a phone read from its health store, as it is kept on the server: a format of its own, not the
 * domain's classes, since it is read by whichever version of the app another device has. Enums go by
 * name, and one this version doesn't know falls back as [SleepRow] says.
 */
@Serializable
data class HealthSnapshot(
    val at: Long = 0,
    val granted: List<String> = emptyList(),
    /** By local day. */
    val steps: Map<Long, Long> = emptyMap(),
    val sleep: List<SleepRow> = emptyList(),
    val resting: List<SampleRow> = emptyList(),
    val variability: List<SampleRow> = emptyList(),
    val runs: List<RunRow> = emptyList(),
)

@Serializable
data class SampleRow(val t: Long, val v: Double)

/** A stage of a kind this version doesn't know is read as plain [SleepStageKind.ASLEEP]. */
@Serializable
data class SleepRow(
    val start: Long,
    val end: Long,
    val stages: List<StageRow> = emptyList(),
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

@Serializable
data class StageRow(val start: Long, val end: Long, val kind: String)

@Serializable
data class RunRow(
    val id: String,
    val start: Long,
    val end: Long,
    val offset: Int? = null,
    val treadmill: Boolean = false,
    val meters: Double? = null,
    val activeSeconds: Long? = null,
    val calories: Double? = null,
    val averageBpm: Int? = null,
    val peakBpm: Int? = null,
    val steps: Long? = null,
    val climbMeters: Double? = null,
    val route: String = RouteAccess.NONE.name,
)

@Serializable
data class HealthTrackRows(
    val heart: List<SampleRow> = emptyList(),
    val distance: List<DistanceRow> = emptyList(),
    val route: List<RoutePointRow> = emptyList(),
    val pauses: List<PauseRow> = emptyList(),
)

@Serializable
data class DistanceRow(val start: Long, val end: Long, val meters: Double)

@Serializable
data class RoutePointRow(val lat: Double, val lng: Double)

@Serializable
data class PauseRow(val start: Long, val end: Long)

internal fun HealthReadings.toSnapshot() = HealthSnapshot(
    at = readAtEpochSeconds,
    granted = granted.map { it.name },
    steps = steps,
    sleep = sleep.map { night ->
        SleepRow(night.startEpochSeconds, night.endEpochSeconds, night.stages.map { StageRow(it.startEpochSeconds, it.endEpochSeconds, it.kind.name) }, night.startUtcOffsetSeconds, night.endUtcOffsetSeconds)
    },
    resting = resting.map { it.toRow() },
    variability = variability.map { it.toRow() },
    runs = runs.map {
        RunRow(it.id, it.startEpochSeconds, it.endEpochSeconds, it.utcOffsetSeconds, it.treadmill, it.meters, it.activeSeconds, it.calories, it.averageBpm, it.peakBpm, it.steps, it.climbMeters, it.route.name)
    },
)

internal fun HealthSnapshot.toReadings() = HealthReadings(
    readAtEpochSeconds = at,
    granted = granted.mapNotNull { name -> HealthKind.entries.firstOrNull { it.name == name } }.toSet(),
    steps = steps,
    sleep = sleep.map { night ->
        SleepSession(
            night.start,
            night.end,
            night.stages.map { stage -> SleepStage(stage.start, stage.end, SleepStageKind.entries.firstOrNull { it.name == stage.kind } ?: SleepStageKind.ASLEEP) },
            night.startOffset,
            night.endOffset,
        )
    },
    resting = resting.map { it.toSample() },
    variability = variability.map { it.toSample() },
    runs = runs.map {
        RunRecord(
            it.id,
            it.start,
            it.end,
            it.offset,
            it.treadmill,
            it.meters,
            it.activeSeconds,
            it.calories,
            it.averageBpm,
            it.peakBpm,
            it.steps,
            it.climbMeters,
            RouteAccess.entries.firstOrNull { access -> access.name == it.route } ?: RouteAccess.NONE,
        )
    },
)

internal fun RunTrack.toRows() = HealthTrackRows(
    heart = heart.map { it.toRow() },
    distance = distance.map { DistanceRow(it.startEpochSeconds, it.endEpochSeconds, it.meters) },
    route = route.map { RoutePointRow(it.latitude, it.longitude) },
    pauses = pauses.map { PauseRow(it.startEpochSeconds, it.endEpochSeconds) },
)

internal fun HealthTrackRows.toTrack() = RunTrack(
    heart = heart.map { it.toSample() },
    distance = distance.map { DistanceSpan(it.start, it.end, it.meters) },
    route = route.map { RoutePoint(it.lat, it.lng) },
    pauses = pauses.map { Pause(it.start, it.end) },
)

private fun HealthSample.toRow() = SampleRow(epochSeconds, value)

private fun SampleRow.toSample() = HealthSample(t, v)

@Serializable
data class HealthSendRequest(val device: String, val snapshot: HealthSnapshot, val tracks: List<String>)

@Serializable
data class HealthSendAnswer(val tracks: Map<String, String> = emptyMap())

@Serializable
data class HealthReadAnswer(val snapshot: HealthSnapshot? = null)

@Serializable
data class HealthTrackRequest(val id: String, val fingerprint: String, val track: HealthTrackRows)

@Serializable
data class HealthTrackAnswer(@SerialName("track") val track: HealthTrackRows? = null)

/** The server's copy of what a phone read from its health store. Every call throws what it can't get an answer past. */
interface HealthRelayApi {
    /** Replaces the login's snapshot. The answer is the tracks held of the ones [HealthSendRequest.tracks] lists, each by its fingerprint. */
    suspend fun send(url: String, request: HealthSendRequest): HealthSendAnswer

    suspend fun sendTrack(url: String, request: HealthTrackRequest)

    /** Takes the login's copy away, if it was [device] that sent it. */
    suspend fun clear(url: String, device: String)

    /** The login's snapshot, or null where no phone has sent one. */
    suspend fun read(url: String): HealthSnapshot?

    suspend fun readTrack(url: String, id: String): HealthTrackRows?
}

/** Through the relay (`/fitness/health` in `relay/relay.py`), on the Frigate session cookie like every relay call. The relay says whose copy that makes it. */
@Inject
@ContributesBinding(AppScope::class)
class HealthRelayApiImpl(private val httpClient: HttpClient) : HealthRelayApi {
    override suspend fun send(url: String, request: HealthSendRequest): HealthSendAnswer = httpClient.put(PushRelayApi.relayUrl(url, "/fitness/health")) {
        contentType(ContentType.Application.Json)
        setBody(request)
        timeout { requestTimeoutMillis = TIMEOUT_MS }
    }.answered().body()

    override suspend fun sendTrack(url: String, request: HealthTrackRequest) {
        httpClient.put(PushRelayApi.relayUrl(url, "/fitness/health/track")) {
            contentType(ContentType.Application.Json)
            setBody(request)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }.answered()
    }

    override suspend fun clear(url: String, device: String) {
        httpClient.delete(PushRelayApi.relayUrl(url, "/fitness/health")) {
            parameter("device", device)
            timeout { requestTimeoutMillis = TIMEOUT_MS }
        }.answered()
    }

    override suspend fun read(url: String): HealthSnapshot? = httpClient.get(PushRelayApi.relayUrl(url, "/fitness/health")) {
        timeout { requestTimeoutMillis = TIMEOUT_MS }
    }.answered().body<HealthReadAnswer>().snapshot

    override suspend fun readTrack(url: String, id: String): HealthTrackRows? = httpClient.get(PushRelayApi.relayUrl(url, "/fitness/health/track")) {
        parameter("id", id)
        timeout { requestTimeoutMillis = TIMEOUT_MS }
    }.answered().body<HealthTrackAnswer>().track

    // A session that has lapsed, or a relay from before this. Either way it is tried again later.
    private fun HttpResponse.answered(): HttpResponse = apply { check(status.isSuccess()) { "The relay answered $status" } }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
