package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession

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

    /** The routes the user hands over when asked, by run. */
    var routes: Map<String, List<RoutePoint>> = emptyMap()

    /** Kinds the store can't be read for just now. */
    var failing: Set<HealthKind> = emptySet()

    /** Every read asked for, in order, by what it was of. */
    val reads = mutableListOf<String>()

    private fun <T> read(name: String, kind: HealthKind, value: T): T {
        reads += name
        check(kind in allowed) { "$name read without being allowed" }
        check(kind !in failing) { "the store is away" }
        return value
    }

    override fun availability() = available

    override suspend fun granted(): Set<HealthKind> = allowed

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

    override suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long) =
        read("runs", HealthKind.EXERCISE, runRecords.filter { it.startEpochSeconds in fromEpochSeconds..toEpochSeconds })

    /** As the real store does: the heart rate and the distance of a track only where each is allowed. */
    override suspend fun track(run: RunRecord): RunTrack = read("track ${run.id}", HealthKind.EXERCISE, tracks[run.id] ?: RunTrack()).let {
        it.copy(heart = if (HealthKind.HEART_RATE in allowed) it.heart else emptyList(), distance = if (HealthKind.DISTANCE in allowed) it.distance else emptyList())
    }

    override suspend fun requestRoute(runId: String): List<RoutePoint> {
        reads += "route $runId"
        return routes[runId].orEmpty()
    }
}
