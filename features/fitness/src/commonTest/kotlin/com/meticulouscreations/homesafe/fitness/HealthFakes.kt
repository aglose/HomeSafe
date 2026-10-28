package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.RecorderProblem
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunRecorder
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A health store for tests: the test says what it holds and what the user has allowed, and
 * reads back what was asked of it.
 */
internal class FakeHealthSource(var available: HealthAvailability = HealthAvailability.AVAILABLE) : HealthSource {
    var allowed: Set<HealthKind> = emptySet()

    /** What the user allows when asked; null when the sheet doesn't come up, as after two refusals. */
    var grantsWhenAsked: Set<HealthKind>? = null
    var asked = 0
    var managed = 0

    var steps: Map<Long, Long> = emptyMap()
    var sleeps: List<SleepSession> = emptyList()
    var resting: List<HealthSample> = emptyList()
    var variability: List<HealthSample> = emptyList()
    var runRecords: List<RunRecord> = emptyList()
    var tracks: Map<String, RunTrack> = emptyMap()
    var heart: List<HealthSample> = emptyList()

    /** The routes the user hands over when asked, by run. */
    var routes: Map<String, List<RoutePoint>> = emptyMap()

    /** Kinds the store can't be read for just now. */
    var failing: Set<HealthKind> = emptySet()

    /** The store can't say what is allowed just now. */
    var grantsFailing = false

    /** When set, reading runs waits on it: a store slow to add up a run's totals. */
    var runsHeld: CompletableDeferred<Unit>? = null

    /** Every read asked for, in order, by what it was of. */
    val reads = mutableListOf<String>()

    private fun <T> read(name: String, kind: HealthKind, value: T): T {
        reads += name
        check(kind in allowed) { "$name read without being allowed" }
        check(kind !in failing) { "the store is away" }
        return value
    }

    override fun availability() = available

    override suspend fun granted(): Set<HealthKind> {
        check(!grantsFailing) { "the store is away" }
        return allowed
    }

    override suspend fun request(): Set<HealthKind> {
        asked++
        grantsWhenAsked?.let { allowed = allowed + it }
        return allowed
    }

    override fun manage(): Boolean {
        managed++
        return true
    }

    override suspend fun stepsByDay(fromEpochDay: Long, toEpochDay: Long) = read("steps", HealthKind.STEPS, steps.filterKeys { it in fromEpochDay..toEpochDay })

    override suspend fun sleep(fromEpochSeconds: Long, toEpochSeconds: Long) = read("sleep", HealthKind.SLEEP, sleeps)

    override suspend fun restingHeartRates(fromEpochSeconds: Long, toEpochSeconds: Long) = read("resting", HealthKind.RESTING_HEART_RATE, resting)

    override suspend fun heartRateVariability(fromEpochSeconds: Long, toEpochSeconds: Long) = read("variability", HealthKind.HEART_RATE_VARIABILITY, variability)

    override suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long): List<RunRecord> {
        runsHeld?.await()
        return read("runs", HealthKind.EXERCISE, runRecords.filter { it.startEpochSeconds in fromEpochSeconds..toEpochSeconds })
    }

    /** As the real store does: the heart rate and the distance of a track only where each is allowed. */
    override suspend fun track(run: RunRecord): RunTrack = read("track ${run.id}", HealthKind.EXERCISE, tracks[run.id] ?: RunTrack()).let {
        it.copy(heart = if (HealthKind.HEART_RATE in allowed) it.heart else emptyList(), distance = if (HealthKind.DISTANCE in allowed) it.distance else emptyList())
    }

    override suspend fun heartRate(fromEpochSeconds: Long, toEpochSeconds: Long) =
        read("heart", HealthKind.HEART_RATE, heart.filter { it.epochSeconds in fromEpochSeconds..toEpochSeconds })

    override suspend fun requestRoute(runId: String): List<RoutePoint> {
        reads += "route $runId"
        return routes[runId].orEmpty()
    }
}

/** A run recorder for tests: it records nothing, and keeps count of what it was asked. */
internal class FakeRunRecorder(override val isSupported: Boolean = true) : RunRecorder {
    override val recording = MutableStateFlow<Recording?>(null)
    override val problem = MutableStateFlow<RecorderProblem?>(null)
    val asked = mutableListOf<String>()

    override fun start() {
        asked += "start"
    }

    override fun pause() {
        asked += "pause"
    }

    override fun resume() {
        asked += "resume"
    }

    override fun finish() {
        asked += "finish"
    }

    override fun discard() {
        asked += "discard"
    }
}
