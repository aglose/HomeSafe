package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A position the phone's GPS gave: where, when, and how far off it may be (the radius it is 68% sure of). */
@Immutable
data class GpsFix(val epochMillis: Long, val latitude: Double, val longitude: Double, val accuracyMeters: Double)

/** What happened while a run was recorded. */
enum class RunEventKind { FIX, PAUSE, RESUME }

/**
 * One thing that happened while a run was recorded, in order: a position, or the runner pausing
 * or going on. These are what is kept of a run recorded here, and everything else about it (its
 * distance, its moving time, its splits, its route) is worked out again from them by [RunTracker].
 * The position is only there for a [RunEventKind.FIX].
 */
@Immutable
data class RunEvent(
    val kind: RunEventKind,
    val epochMillis: Long,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
) {
    val fix: GpsFix? get() = if (kind == RunEventKind.FIX) GpsFix(epochMillis, latitude, longitude, accuracyMeters) else null

    companion object {
        fun of(fix: GpsFix) = RunEvent(RunEventKind.FIX, fix.epochMillis, fix.latitude, fix.longitude, fix.accuracyMeters)
    }
}

/** A run recorded in this app, finished: when, how far, and how long it was moving for. Its route and splits come from its events. */
@Immutable
data class RecordedRun(
    val id: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val utcOffsetSeconds: Int,
    val meters: Double,
    val movingMillis: Long,
)

/** How the GPS is doing for a run being recorded. */
enum class GpsSignal {
    /** No position yet, or none good enough to measure by. */
    SEARCHING,

    /** Positions are coming, good enough to measure by. */
    GOOD,

    /** Nothing good enough has come for [RunTracker.LOST_AFTER_MILLIS]: the distance is standing still. */
    LOST,
}

/**
 * A run being recorded, as the screens show it. [movingMillis] is as of [asOfMillis]: while it
 * isn't [paused] the screen adds the time since. [paceSeconds] is the pace of the last half
 * minute or so, null until there has been enough of it to say.
 */
@Immutable
data class Recording(
    val id: String,
    val startEpochMillis: Long,
    val paused: Boolean = false,
    val meters: Double = 0.0,
    val movingMillis: Long = 0,
    val asOfMillis: Long = startEpochMillis,
    val paceSeconds: Int? = null,
    val signal: GpsSignal = GpsSignal.SEARCHING,
    val route: List<RoutePoint> = emptyList(),
) {
    fun movingMillisAt(nowMillis: Long): Long = movingMillis + if (paused) 0 else (nowMillis - asOfMillis).coerceAtLeast(0)

    /** Seconds a mile over the whole run so far; null until it has gone far enough for that to mean anything. */
    fun averagePaceSeconds(nowMillis: Long): Int? =
        if (meters >= Runs.LEAST_PACED_METERS) (movingMillisAt(nowMillis) / 1_000.0 / (meters / METERS_PER_MILE)).roundToInt() else null
}

/**
 * Works out a run from what the GPS gave while it was recorded, one [RunEvent] at a time: how
 * far it went, how long it was moving, the stretches of distance its splits are cut from, its
 * route, and its pace just now. The same events give the same run, so a run is kept as its
 * events and worked out again when it is opened.
 *
 * A position is measured from the last one that counted:
 * - one less sure than [WORST_ACCURACY_METERS] is passed over;
 * - one closer than about half how sure the two are (and never under [LEAST_STEP_METERS]) is
 *   the GPS wandering about a runner standing still, and waits for the next;
 * - one further from the last position in line with the run than a runner could have gone
 *   ([FASTEST_METERS_PER_SECOND]) is a jump, and so is one nearer where the GPS jumped to than to
 *   that position: both are passed over unless nothing in line with the run has come for half a
 *   minute, when the run is started again from there with nothing added for the leap.
 *
 * Nothing counts while paused, and the first position after going on is started from afresh, so
 * a pause to cross a road elsewhere doesn't add a line across it. The pauses are kept, so that a
 * split isn't timed through one.
 */
class RunTracker(private val startEpochMillis: Long) {
    var meters: Double = 0.0
        private set

    var paused: Boolean = false
        private set

    private var movingBefore = 0L
    private var resumedAt = startEpochMillis
    private var anchor: GpsFix? = null

    /** The last position in line with the run, counted or not: what a jump is told by. */
    private var lastSeen: GpsFix? = null

    /** Where the GPS is while a jump is being passed over. */
    private var jumpedTo: GpsFix? = null
    private var lastGoodAt: Long? = null
    private var latestAt = startEpochMillis

    private val spans = ArrayList<DistanceSpan>()
    private var pendingFrom = 0L
    private var pendingTo = 0L
    private var pendingMeters = 0.0

    private val routePoints = ArrayList<RoutePoint>()

    private val pauses = ArrayList<Pause>()
    private var pausedAt = 0L

    /** The steps counted in the last [PACE_WINDOW_MILLIS] or so: when each ended, and how far it was. */
    private val recent = ArrayDeque<Pair<Long, Double>>()

    val route: List<RoutePoint> get() = routePoints

    /** The stretches of distance so far, at least [SPAN_MILLIS] each but the last. */
    val distance: List<DistanceSpan> get() = spans + listOfNotNull(pendingSpan())

    fun movingMillisAt(nowMillis: Long): Long = movingBefore + if (paused) 0 else (nowMillis - resumedAt).coerceAtLeast(0)

    fun signalAt(nowMillis: Long): GpsSignal {
        val good = lastGoodAt ?: return GpsSignal.SEARCHING
        return if (nowMillis - good > LOST_AFTER_MILLIS) GpsSignal.LOST else GpsSignal.GOOD
    }

    /** Seconds a mile over the last half minute or so of running; null without enough of it, or while paused. */
    fun paceSecondsAt(nowMillis: Long): Int? {
        if (paused) return null
        val window = recent.filter { nowMillis - it.first < PACE_WINDOW_MILLIS }
        val covered = window.sumOf { it.second }
        if (covered < LEAST_PACE_METERS) return null
        val from = maxOf(nowMillis - PACE_WINDOW_MILLIS, resumedAt)
        val seconds = (nowMillis - from) / 1_000.0
        return if (seconds <= 0) null else (seconds / (covered / METERS_PER_MILE)).roundToInt()
    }

    fun apply(event: RunEvent) {
        latestAt = maxOf(latestAt, event.epochMillis)
        when (event.kind) {
            RunEventKind.PAUSE -> if (!paused) {
                movingBefore += (event.epochMillis - resumedAt).coerceAtLeast(0)
                paused = true
                pausedAt = event.epochMillis
                flush()
                anchor = null
                lastSeen = null
                jumpedTo = null
                recent.clear()
            }

            RunEventKind.RESUME -> if (paused) {
                paused = false
                resumedAt = event.epochMillis
                pauses += Pause(pausedAt / 1_000, event.epochMillis / 1_000)
            }

            RunEventKind.FIX -> event.fix?.let(::take)
        }
    }

    private fun take(fix: GpsFix) {
        if (fix.accuracyMeters > WORST_ACCURACY_METERS) return
        lastGoodAt = fix.epochMillis
        if (paused) return
        val from = anchor
        val seen = lastSeen
        if (from == null || seen == null) {
            start(fix)
            return
        }
        val millis = fix.epochMillis - seen.epochMillis
        if (millis <= 0) return
        val jumped = jumpedTo
        val stillJumped = jumped != null && metersBetween(jumped, fix) < metersBetween(seen, fix)
        if (stillJumped || metersBetween(seen, fix) / (millis / 1_000.0) > FASTEST_METERS_PER_SECOND) {
            // A jump, or the GPS still where it jumped to. Started again from if nothing in line with the run has come
            // for a while: then it is the run's last position that was wrong.
            if (millis >= REANCHOR_MILLIS) start(fix) else jumpedTo = fix
            return
        }
        jumpedTo = null
        lastSeen = fix
        val step = metersBetween(from, fix)
        if (step < maxOf(LEAST_STEP_METERS, (from.accuracyMeters + fix.accuracyMeters) / 4)) return
        meters += step
        if (pendingMeters == 0.0) pendingFrom = from.epochMillis
        pendingTo = fix.epochMillis
        pendingMeters += step
        if (pendingTo - pendingFrom >= SPAN_MILLIS) flush()
        recent.addLast(fix.epochMillis to step)
        while (recent.isNotEmpty() && fix.epochMillis - recent.first().first > PACE_WINDOW_MILLIS * 2) recent.removeFirst()
        anchor = fix
        addRoutePoint(fix)
    }

    private fun start(fix: GpsFix) {
        anchor = fix
        lastSeen = fix
        jumpedTo = null
        addRoutePoint(fix)
    }

    private fun addRoutePoint(fix: GpsFix) {
        val last = routePoints.lastOrNull()
        if (last == null || metersBetween(last.latitude, last.longitude, fix.latitude, fix.longitude) >= ROUTE_STEP_METERS) {
            routePoints += RoutePoint(fix.latitude, fix.longitude)
        }
    }

    private fun pendingSpan(): DistanceSpan? {
        if (pendingMeters <= 0.0) return null
        val from = pendingFrom / 1_000
        // A stretch of under a second still covers a second, so that splits don't pass it over.
        return DistanceSpan(from, maxOf(pendingTo / 1_000, from + 1), pendingMeters)
    }

    private fun flush() {
        pendingSpan()?.let { spans += it }
        pendingMeters = 0.0
    }

    /** What the screens show of it at [nowMillis]. */
    fun recording(id: String, nowMillis: Long): Recording = Recording(
        id = id,
        startEpochMillis = startEpochMillis,
        paused = paused,
        meters = meters,
        movingMillis = movingMillisAt(nowMillis),
        asOfMillis = nowMillis,
        paceSeconds = paceSecondsAt(nowMillis),
        signal = signalAt(nowMillis),
        route = routePoints.toList(),
    )

    /** The run it adds up to, ended at [endMillis]. */
    fun finished(id: String, endMillis: Long, utcOffsetSeconds: Int): RecordedRun =
        RecordedRun(id, startEpochMillis, maxOf(endMillis, startEpochMillis), utcOffsetSeconds, meters, movingMillisAt(endMillis))

    /** As a [RunTrack], for the run's own page. */
    fun track(heart: List<HealthSample> = emptyList()): RunTrack =
        RunTrack(heart, distance, routePoints.toList(), pauses + listOfNotNull(if (paused) Pause(pausedAt / 1_000, latestAt / 1_000) else null))

    companion object {
        /** A position less sure than this (in meters) is no use for measuring a run by. */
        const val WORST_ACCURACY_METERS = 30.0

        /** The least step counted, however sure the GPS is. */
        const val LEAST_STEP_METERS = 3.0

        /** About a 2:40 mile: past anyone's sprint, so a step that fast is the GPS jumping. */
        const val FASTEST_METERS_PER_SECOND = 10.0

        /** A jump that stands this long is taken as where the runner really is. */
        const val REANCHOR_MILLIS = 30_000L

        /** Steps are kept in stretches of at least this long, plenty for splits and few enough to keep. */
        const val SPAN_MILLIS = 5_000L

        /** The route is drawn through a point every this many meters. */
        const val ROUTE_STEP_METERS = 5.0

        /** The pace just now is over about this long. */
        const val PACE_WINDOW_MILLIS = 30_000L

        private const val LEAST_PACE_METERS = 30.0

        /** With no good position for this long, the screen says the GPS has gone. */
        const val LOST_AFTER_MILLIS = 20_000L

        /** Works out a whole run from its [events], in the order they were recorded. */
        fun replay(startEpochMillis: Long, events: List<RunEvent>): RunTracker =
            RunTracker(startEpochMillis).apply { events.forEach(::apply) }

        private const val EARTH_RADIUS_METERS = 6_371_008.8

        fun metersBetween(a: GpsFix, b: GpsFix): Double = metersBetween(a.latitude, a.longitude, b.latitude, b.longitude)

        /** Along the ground, by the haversine: near enough at a run's scale. */
        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1.toRadians()
            val p2 = lat2.toRadians()
            val dp = p2 - p1
            val dl = (lon2 - lon1).toRadians()
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        private fun Double.toRadians(): Double = this * kotlin.math.PI / 180
    }
}

/**
 * The phone's GPS, for recording a run: Android's fused location while a foreground service
 * keeps the app running with the screen off. iOS answers "unsupported" for now.
 */
interface RunLocation {
    val isSupported: Boolean

    fun hasPermission(): Boolean

    /** Asks the user to let the app know where the phone is, and waits. Whether it may now. */
    suspend fun requestPermission(): Boolean

    /**
     * Positions for as long as this is collected, about one a second. Meanwhile the app keeps
     * running when the screen goes off, with an ongoing notification saying what [notify] last said.
     */
    fun fixes(): Flow<GpsFix>

    /** What the ongoing notification says while [fixes] is collected. */
    fun notify(recording: Recording)
}

/** No GPS to record a run with. */
object UnsupportedRunLocation : RunLocation {
    override val isSupported = false

    override fun hasPermission() = false

    override suspend fun requestPermission() = false

    override fun fixes(): Flow<GpsFix> = emptyFlow()

    override fun notify(recording: Recording) = Unit
}

/** What the recorder has to say beyond the run itself. */
enum class RecorderProblem {
    /** The user wouldn't let the app know where the phone is. */
    LOCATION_REFUSED,
}

/**
 * Records a run with the phone's GPS: one at a time, kept as it goes, so a run the app was
 * stopped in the middle of is still there (ended where its positions stopped) the next time it
 * is opened.
 */
interface RunRecorder {
    val isSupported: Boolean

    /** The run being recorded; null when there is none. */
    val recording: StateFlow<Recording?>

    val problem: StateFlow<RecorderProblem?>

    /** Asks for the phone's location if it has to, then starts recording. Nothing when a run is already being recorded. */
    fun start()

    fun pause()

    fun resume()

    /** Ends the run and keeps it. */
    fun finish()

    /** Ends the run and throws it away. */
    fun discard()
}

/** The runs recorded here, kept on the phone. */
interface RecordedRuns {
    /** Every finished run, the latest first. */
    val runs: Flow<List<RecordedRun>>

    suspend fun begin(id: String, startEpochMillis: Long, utcOffsetSeconds: Int)

    /** Adds [events] to the run [id], after those it has. */
    suspend fun append(id: String, events: List<RunEvent>)

    suspend fun finish(run: RecordedRun)

    suspend fun events(id: String): List<RunEvent>

    suspend fun delete(id: String)

    /** Runs begun and never finished, by when they began: the app was stopped while one was being recorded. */
    suspend fun unfinished(): List<Pair<String, Long>>

    /** The offset a run was begun in, for one being finished after the app was stopped. */
    suspend fun offsetOf(id: String): Int?
}
