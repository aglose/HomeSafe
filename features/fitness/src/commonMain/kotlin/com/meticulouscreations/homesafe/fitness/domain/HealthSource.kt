package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable

/** Whether this device has a health store the app can read. */
enum class HealthAvailability {
    /** This platform has none the app knows how to read. */
    UNSUPPORTED,

    /** There is one for this device, but it isn't installed or is too old to answer. */
    MISSING,

    AVAILABLE,
}

/** A kind of record in the health store, each of which the user allows or refuses by itself. */
enum class HealthKind {
    STEPS,
    SLEEP,
    HEART_RATE,
    RESTING_HEART_RATE,
    HEART_RATE_VARIABILITY,
    EXERCISE,
    DISTANCE,
    CALORIES,
    ELEVATION,
}

/** One reading of something at a moment: a resting heart rate, a heart-rate variability, a heart rate mid-run. */
@Immutable
data class HealthSample(val epochSeconds: Long, val value: Double)

/** What a stretch of a night was spent as. [ASLEEP] is sleep whose depth the tracker didn't say. */
enum class SleepStageKind { AWAKE, ASLEEP, LIGHT, DEEP, REM }

@Immutable
data class SleepStage(val startEpochSeconds: Long, val endEpochSeconds: Long, val kind: SleepStageKind)

/**
 * A sleep as the tracker recorded it, with its [stages] when it kept any, and the offsets from
 * UTC it began and ended in when it kept those: a night away from home, or across a change of
 * the clocks, has its own.
 */
@Immutable
data class SleepSession(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val stages: List<SleepStage> = emptyList(),
    val startUtcOffsetSeconds: Int? = null,
    val endUtcOffsetSeconds: Int? = null,
)

/** A point on a run's route. */
@Immutable
data class RoutePoint(val latitude: Double, val longitude: Double)

/** Whether a run's route can be drawn. */
enum class RouteAccess {
    /** The run has none: a treadmill, or a band with no phone's GPS to go by. */
    NONE,

    /** It has one, and the user is asked before each one is read. */
    ASK,

    READABLE,
}

/**
 * A run as the health store holds it: when it was, and what the store adds it up to. A total is
 * null when nothing of that kind was recorded for it, or reading that kind isn't allowed.
 * [activeSeconds] leaves out the time it was paused. [utcOffsetSeconds] is the offset it was
 * recorded in, when the tracker kept one.
 */
@Immutable
data class RunRecord(
    val id: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val utcOffsetSeconds: Int? = null,
    val treadmill: Boolean = false,
    val meters: Double? = null,
    val activeSeconds: Long? = null,
    val calories: Double? = null,
    val averageBpm: Int? = null,
    val peakBpm: Int? = null,
    val steps: Long? = null,
    val climbMeters: Double? = null,
    val route: RouteAccess = RouteAccess.NONE,
)

/** Ground covered between two moments of a run. */
@Immutable
data class DistanceSpan(val startEpochSeconds: Long, val endEpochSeconds: Long, val meters: Double)

/** A while the runner paused a run recorded here. */
@Immutable
data class Pause(val startEpochSeconds: Long, val endEpochSeconds: Long)

/**
 * A run moment by moment: its heart rate, the ground covered stretch by stretch, and its route
 * where that can be read. A run recorded here also says when it was paused ([pauses]).
 */
@Immutable
data class RunTrack(
    val heart: List<HealthSample> = emptyList(),
    val distance: List<DistanceSpan> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
    val pauses: List<Pause> = emptyList(),
)

/**
 * The device's health store, as little of it as the fitness app needs: on Android, Health
 * Connect, which is where the Google Health app puts what a Fitbit band records. It only hands
 * records over. Which night is last night, what the readiness score is and where a run's miles
 * fall are worked out in common code (`Body.kt`, `Runs.kt`), so they can be tested without one.
 *
 * Every read may throw when the store can't be reached or the kind isn't allowed; the caller
 * treats that as nothing to show.
 */
interface HealthSource {
    fun availability(): HealthAvailability

    /** The kinds the user has allowed so far. */
    suspend fun granted(): Set<HealthKind>

    /** Asks the user to allow every kind the app reads, and waits. What is allowed afterwards. */
    suspend fun request(): Set<HealthKind>

    /**
     * Opens the store's own page for this app, where each kind is switched on and off; where the
     * store is [HealthAvailability.MISSING], the place to get it. False when nothing opened.
     */
    fun manage(): Boolean

    /** Steps on each local day from [fromEpochDay] to [toEpochDay], both included, counted once however many trackers saw them. */
    suspend fun stepsByDay(fromEpochDay: Long, toEpochDay: Long): Map<Long, Long>

    /** Every sleep recorded in the range. */
    suspend fun sleep(fromEpochSeconds: Long, toEpochSeconds: Long): List<SleepSession>

    /** Resting heart rates in beats a minute. */
    suspend fun restingHeartRates(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample>

    /** Heart-rate variability (RMSSD) in milliseconds. */
    suspend fun heartRateVariability(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample>

    /** The runs that began in the range, with their totals. */
    suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long): List<RunRecord>

    suspend fun track(run: RunRecord): RunTrack

    /** Every heart-rate reading in the range, from whichever tracker took it: the heart through a run recorded in this app. */
    suspend fun heartRate(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample>

    /** Asks the user for the route of the run [runId], and waits. Empty when they said no or it has none. */
    suspend fun requestRoute(runId: String): List<RoutePoint>
}

/** For the platforms with no health store this app can read. */
internal object UnsupportedHealthSource : HealthSource {
    override fun availability() = HealthAvailability.UNSUPPORTED

    override suspend fun granted(): Set<HealthKind> = emptySet()

    override suspend fun request(): Set<HealthKind> = emptySet()

    override fun manage() = false

    override suspend fun stepsByDay(fromEpochDay: Long, toEpochDay: Long): Map<Long, Long> = emptyMap()

    override suspend fun sleep(fromEpochSeconds: Long, toEpochSeconds: Long): List<SleepSession> = emptyList()

    override suspend fun restingHeartRates(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> = emptyList()

    override suspend fun heartRateVariability(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> = emptyList()

    override suspend fun runs(fromEpochSeconds: Long, toEpochSeconds: Long): List<RunRecord> = emptyList()

    override suspend fun track(run: RunRecord) = RunTrack()

    override suspend fun heartRate(fromEpochSeconds: Long, toEpochSeconds: Long): List<HealthSample> = emptyList()

    override suspend fun requestRoute(runId: String): List<RoutePoint> = emptyList()
}
