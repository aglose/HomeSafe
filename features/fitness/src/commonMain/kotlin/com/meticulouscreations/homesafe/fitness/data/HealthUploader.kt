package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthReadings
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.HealthUpload
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.Run
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * [HealthUpload] over the relay. A snapshot replaces the one before it whole, so what stopped being
 * shared on the phone stops being on the server. It lists each track by a fingerprint of what the
 * track depends on: the server lets go of any it holds under another, at once, and says which it
 * still has, and only the rest are read and sent. Reading a track takes several calls to the
 * store, so a pass sends a few and the next goes on from there, newest first. Anything that fails
 * is thrown, and the next read tries again.
 *
 * A route the store only hands over when asked can't be read again, so it isn't part of its run's
 * track, which is read afresh whenever it changes. It is kept beside it on the server under an id
 * of its own, and is held here until the server has said it has it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HealthUploader(
    private val api: HealthRelayApi,
    private val connection: ConnectionRepository,
    private val identity: DeviceIdentityStore,
    private val source: HealthSource,
) : HealthUpload {
    /** What was last sent with every track it needed, its time set aside: the same again needn't go until [RESEND_SECONDS] on, when the other devices' "as of" has grown old. */
    private var settled: HealthReadings? = null
    private var settledAt = 0L

    /** Which tracks went with [settled]: a run recorded here changes what is wanted without changing what was read. */
    private var settledTracks: Set<String> = emptySet()

    /** Routes handed over and not yet on the server, by run. Only while the app runs: nothing read is stored on the phone. */
    private val routesOnTheirWay = mutableMapOf<String, List<RoutePoint>>()

    override suspend fun send(readings: HealthReadings, recorded: List<Run>) {
        val url = serverUrl() ?: return
        val at = readings.readAtEpochSeconds
        val unstamped = readings.copy(readAtEpochSeconds = 0)
        val tracks = bandTracks(readings) + recordedHearts(readings, recorded)
        if (unstamped == settled && at - settledAt < RESEND_SECONDS && settledTracks == tracks.keys && routesOnTheirWay.isEmpty()) return
        val askedFor = if (HealthKind.EXERCISE in readings.granted) readings.runs.filter { it.route == RouteAccess.ASK }.map { it.id } else emptyList()
        val listed = tracks.mapValues { it.value.fingerprint } + askedFor.associate { routeTrackId(it) to ROUTE_FINGERPRINT }
        val held = api.send(url, HealthSendRequest(identity.deviceId(), readings.toSnapshot(), listed)).tracks
        routesOnTheirWay.keys.retainAll(askedFor.toSet())
        sendRoutes(url)
        val due = tracks.filter { (id, track) -> held[id] != track.fingerprint }
        due.entries.take(TRACKS_A_PASS).forEach { (id, track) ->
            api.sendTrack(url, HealthTrackRequest(id, track.fingerprint, track.read().toRows()))
        }
        if (due.size <= TRACKS_A_PASS) {
            settled = unstamped
            settledAt = at
            settledTracks = tracks.keys
        } else {
            settled = null
        }
    }

    override suspend fun sendRoute(run: RunRecord, route: List<RoutePoint>) {
        if (run.route != RouteAccess.ASK || route.isEmpty()) return
        routesOnTheirWay[run.id] = route
        sendRoutes(serverUrl() ?: return)
    }

    private suspend fun sendRoutes(url: String) {
        routesOnTheirWay.toMap().forEach { (runId, route) ->
            val request = HealthTrackRequest(routeTrackId(runId), ROUTE_FINGERPRINT, RunTrack(route = route).toRows())
            if (api.sendTrack(url, request)) routesOnTheirWay.remove(runId)
        }
    }

    override suspend fun clear() {
        settled = null
        routesOnTheirWay.clear()
        api.clear(checkNotNull(serverUrl()) { "No server to tell" }, identity.deviceId())
    }

    /** The store's runs, newest first, while it shares them. */
    private fun bandTracks(readings: HealthReadings): Map<String, PendingTrack> = if (HealthKind.EXERCISE in readings.granted) {
        readings.runs.sortedByDescending { it.startEpochSeconds }.associate { run -> run.id to PendingTrack(fingerprint(run, readings.granted)) { source.track(run) } }
    } else {
        emptyMap()
    }

    /**
     * The heart through each run recorded in the app, which has only a band's readings to go by.
     * They can arrive long after the run, with whenever the band next syncs, so for a while it is
     * read again each hour before it is taken to be all there is.
     */
    private fun recordedHearts(readings: HealthReadings, recorded: List<Run>): Map<String, PendingTrack> {
        if (HealthKind.HEART_RATE !in readings.granted) return emptyMap()
        val at = readings.readAtEpochSeconds
        return recorded.sortedByDescending { it.startEpochSeconds }.associate { run ->
            val fingerprint = if (at - run.endEpochSeconds < HEART_SETTLES_SECONDS) "settling-${at / SECONDS_PER_HOUR}" else "settled"
            recordedHeartId(run.startEpochSeconds, run.endEpochSeconds) to PendingTrack(fingerprint) {
                RunTrack(heart = source.heartRate(run.startEpochSeconds, run.endEpochSeconds))
            }
        }
    }

    /** What a run's track depends on: a change here, or a kind switched on or off, means it is read and sent again. */
    private fun fingerprint(run: RunRecord, granted: Set<HealthKind>): String =
        listOf(run.endEpochSeconds, run.meters, run.averageBpm, run.route, HealthKind.HEART_RATE in granted, HealthKind.DISTANCE in granted).joinToString("|")

    private fun serverUrl(): String? = when {
        connection.activeConnection.value == null -> null
        connection.serverUnreachable.value -> null
        else -> connection.currentServerUrl.value
    }

    private class PendingTrack(val fingerprint: String, val read: suspend () -> RunTrack)

    private companion object {
        const val TRACKS_A_PASS = 10
        const val ROUTE_FINGERPRINT = "route"
        const val RESEND_SECONDS = 15 * 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val HEART_SETTLES_SECONDS = 2 * SECONDS_PER_DAY
    }
}
