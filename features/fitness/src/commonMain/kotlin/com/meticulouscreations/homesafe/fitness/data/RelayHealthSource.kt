package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthReadings
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import dev.zacsweers.metro.Inject

/**
 * The health store of a device with none of its own: what this login's phone last read from its
 * own, as the server holds it (see [HealthUploader]). So everything above it, the readiness score
 * included, works as it does on the phone, and from today's point of view: last night stays last
 * night here, whenever the phone last looked.
 *
 * [granted] fetches the snapshot, as the first thing asked of a store each time it is read, and the
 * rest answer from that. Nothing is kept once the app is closed. Signed out, it holds nothing.
 */
@Inject
class RelayHealthSource(private val api: HealthRelayApi, private val connection: ConnectionRepository) : HealthSource {
    private var held: HealthReadings? = null

    override fun availability() = HealthAvailability.REMOTE

    override fun readAtEpochSeconds(): Long? = held?.readAtEpochSeconds

    override suspend fun granted(): Set<HealthKind> {
        val url = serverUrl()
        if (url == null) {
            held = null
            return emptySet()
        }
        val snapshot = api.read(url)?.toReadings()
        held = snapshot
        return snapshot?.granted.orEmpty()
    }

    // Allowing and refusing is done on the phone that has the store.
    override suspend fun request(): Set<HealthKind> = granted()

    override fun manage() = false

    override suspend fun stepsByDay(fromEpochDay: Long, toEpochDay: Long): Map<Long, Long> = held?.steps.orEmpty().filterKeys { it in fromEpochDay..toEpochDay }

    override suspend fun sleep(fromEpochSeconds: Long, toEpochSeconds: Long): List<SleepSession> =
        held?.sleep.orEmpty().filter { it.endEpochSeconds >= fromEpochSeconds && it.startEpochSeconds <= toEpochSeconds }

    override suspend fun restingHeartRates(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        held?.resting.orEmpty().filter { it.epochSeconds in fromEpochSeconds..toEpochSeconds }

    override suspend fun heartRateVariability(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        held?.variability.orEmpty().filter { it.epochSeconds in fromEpochSeconds..toEpochSeconds }

    // A route the phone's store would only hand over when asked can't be asked for from here: the run's page offers no way to, and draws one the phone has sent.
    override suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long): List<RunRecord> = held?.runs.orEmpty()
        .filter { it.startEpochSeconds in fromEpochSeconds..toEpochSeconds }
        .map { if (it.route == RouteAccess.ASK) it.copy(route = RouteAccess.NONE) else it }

    override suspend fun track(run: RunRecord): RunTrack = serverUrl()?.let { api.readTrack(it, run.id)?.toTrack() } ?: RunTrack()

    /** The heart through a run recorded in the app: what the phone sent for exactly that run's minutes. */
    override suspend fun heartRate(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> =
        serverUrl()?.let { api.readTrack(it, recordedHeartId(fromEpochSeconds, toEpochSeconds))?.toTrack()?.heart }.orEmpty()

    override suspend fun requestRoute(runId: String): List<RoutePoint> = emptyList()

    private fun serverUrl(): String? = connection.currentServerUrl.value.takeIf { connection.activeConnection.value != null }
}

/** Where the server keeps the heart through a run recorded in the app, which is read by the run's minutes and not by a run in the store. */
internal fun recordedHeartId(startEpochSeconds: Long, endEpochSeconds: Long) = "heart-$startEpochSeconds-$endEpochSeconds"
