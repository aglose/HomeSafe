package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Runs are written in miles, as the weights are in pounds. */
const val METERS_PER_MILE = 1_609.344
const val FEET_PER_METER = 3.280_84

/** The part of the day a run began in, which is what it is called by. */
enum class RunTime {
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    NIGHT,
    ;

    companion object {
        /** By the hour on the runner's own clock: morning from four, midday from eleven, afternoon from two, evening from five, night from nine. */
        fun of(epochSeconds: Long, utcOffsetSeconds: Int): RunTime = when (((epochSeconds + utcOffsetSeconds).mod(SECONDS_PER_DAY) / 3_600).toInt()) {
            in 4..10 -> MORNING
            in 11..13 -> MIDDAY
            in 14..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * A run, as the screens show it. [seconds] is the time spent moving (what it was paused for is
 * left out, when the tracker says). [meters] is 0 when no distance was recorded for it, and then
 * it has no pace. [utcOffsetSeconds] is the offset it was run in, so that its day and its hour
 * stay what they were.
 */
@Immutable
data class Run(
    val id: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val utcOffsetSeconds: Int = 0,
    val treadmill: Boolean = false,
    val meters: Double = 0.0,
    val seconds: Long = 0,
    val calories: Int? = null,
    val averageBpm: Int? = null,
    val peakBpm: Int? = null,
    val steps: Long? = null,
    val climbMeters: Double? = null,
    val route: RouteAccess = RouteAccess.NONE,
) {
    val miles: Double get() = meters / METERS_PER_MILE

    /** Seconds a mile; null for a run too short for that to mean anything. */
    val paceSeconds: Int? get() = if (meters >= Runs.LEAST_PACED_METERS && seconds > 0) (seconds / miles).roundToInt() else null

    /** Steps a minute. */
    val cadence: Int? get() = steps?.takeIf { it > 0 && seconds >= 60 }?.let { (it * 60.0 / seconds).roundToInt() }

    val epochDay: Long get() = (startEpochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)

    val time: RunTime get() = RunTime.of(startEpochSeconds, utcOffsetSeconds)
}

/** One mile of a run, or the part of one it ended on ([miles] under 1): how long it took, and the heart's average through it. */
@Immutable
data class RunSplit(val number: Int, val miles: Double, val seconds: Long, val averageBpm: Int? = null) {
    val paceSeconds: Int get() = (seconds / miles).roundToInt()

    val whole: Boolean get() = miles > 0.999
}

/** A week of running, from the Monday that is [startEpochDay]. */
@Immutable
data class RunWeek(val startEpochDay: Long, val meters: Double = 0.0, val seconds: Long = 0, val runs: Int = 0) {
    val miles: Double get() = meters / METERS_PER_MILE
}

/**
 * Every run there is to show, the latest first, with the last [Runs.WEEKS] weeks added up
 * (oldest first, this week last) and the two that stand out: the longest, and the quickest over
 * a mile or more. Neither is named until there are two runs to choose between.
 */
@Immutable
data class RunsBoard(
    val runs: List<Run> = emptyList(),
    val weeks: List<RunWeek> = emptyList(),
    val longestId: String? = null,
    val fastestId: String? = null,
) {
    val thisWeek: RunWeek? get() = weeks.lastOrNull()

    fun run(id: String): Run? = runs.firstOrNull { it.id == id }
}

/**
 * A run opened up: mile by mile ([splits], empty when the distance wasn't recorded finely enough
 * to say), the heart rate through it ([heart]) and what that added up to in zones ([summary],
 * null with no zones set or no heart rate), and the [route] where there is one to draw.
 */
@Immutable
data class RunDetail(
    val runId: String,
    val splits: List<RunSplit> = emptyList(),
    val heart: List<HealthSample> = emptyList(),
    val summary: HeartSummary? = null,
    val route: List<RoutePoint> = emptyList(),
)

object Runs {
    /** How many weeks the runs page adds up. */
    const val WEEKS = 8

    /** Under this far (a tenth of a mile) a pace is noise. */
    const val LEAST_PACED_METERS = 160.0

    /** The part of a mile a run ended on is its own split from this far up. */
    private const val LEAST_PART_MILES = 0.05

    /**
     * Splits are only worked out when the distance came in at least this many stretches a mile:
     * with fewer, where a mile began is a guess and every split would come out the same.
     */
    private const val LEAST_SPANS_PER_MILE = 3

    /** A heart-rate reading stands for the time until the next one, up to this long; a longer gap is the band having lost the wrist. */
    const val HEART_GAP_SECONDS = 300L

    /** The run a [record] is. [offsetAt] gives the offset from UTC at a moment, for a tracker that didn't keep one. */
    fun of(record: RunRecord, offsetAt: (Long) -> Int): Run {
        val elapsed = (record.endEpochSeconds - record.startEpochSeconds).coerceAtLeast(0)
        return Run(
            id = record.id,
            startEpochSeconds = record.startEpochSeconds,
            endEpochSeconds = record.endEpochSeconds,
            utcOffsetSeconds = record.utcOffsetSeconds ?: offsetAt(record.startEpochSeconds),
            treadmill = record.treadmill,
            meters = record.meters?.coerceAtLeast(0.0) ?: 0.0,
            // A moving time longer than the run itself is the tracker's mistake.
            seconds = record.activeSeconds?.takeIf { it in 1..elapsed } ?: elapsed,
            calories = record.calories?.takeIf { it > 0 }?.roundToInt(),
            averageBpm = record.averageBpm?.takeIf { it in HeartZones.PLAUSIBLE },
            peakBpm = record.peakBpm?.takeIf { it in HeartZones.PLAUSIBLE },
            steps = record.steps?.takeIf { it > 0 },
            climbMeters = record.climbMeters?.takeIf { it > 0 },
            route = record.route,
        )
    }

    /** The runs page's state from the [records] read, on the local day [todayEpochDay]. */
    fun board(records: List<RunRecord>, todayEpochDay: Long, offsetAt: (Long) -> Int): RunsBoard {
        val runs = records.filter { it.endEpochSeconds > it.startEpochSeconds }.map { of(it, offsetAt) }.sortedByDescending { it.startEpochSeconds }
        val measured = runs.filter { it.meters > 0 }
        val paced = runs.filter { it.meters >= METERS_PER_MILE && it.paceSeconds != null }
        return RunsBoard(
            runs = runs,
            weeks = weeks(runs, todayEpochDay),
            longestId = measured.takeIf { it.size > 1 }?.maxBy { it.meters }?.id,
            fastestId = paced.takeIf { it.size > 1 }?.minBy { it.paceSeconds ?: Int.MAX_VALUE }?.id,
        )
    }

    /** The last [count] weeks, Monday to Sunday, oldest first, ending with the week [todayEpochDay] is in. */
    fun weeks(runs: List<Run>, todayEpochDay: Long, count: Int = WEEKS): List<RunWeek> {
        val thisMonday = mondayOf(todayEpochDay)
        val byWeek = runs.groupBy { mondayOf(it.epochDay) }
        return (count - 1 downTo 0).map { back ->
            val monday = thisMonday - back * 7L
            val inWeek = byWeek[monday].orEmpty()
            RunWeek(monday, inWeek.sumOf { it.meters }, inWeek.sumOf { it.seconds }, inWeek.size)
        }
    }

    /** The Monday of the week [epochDay] is in: 1970-01-01 was a Thursday. */
    fun mondayOf(epochDay: Long): Long = epochDay - (epochDay + 3).mod(7)

    /**
     * [run] mile by mile, from the stretches of distance in [track]. Where a mile mark falls
     * inside a stretch, the moment is taken as that far through it. The first mile is timed from
     * the start of the run and the last part from the end of the last stretch, so standing about
     * afterwards isn't in it. A mile's heart rate is the mean of the readings inside it.
     */
    fun splits(run: Run, track: RunTrack): List<RunSplit> {
        val spans = track.distance.filter { it.meters > 0 && it.endEpochSeconds > it.startEpochSeconds }.sortedBy { it.startEpochSeconds }
        val total = spans.sumOf { it.meters }
        if (total < METERS_PER_MILE || spans.size < LEAST_SPANS_PER_MILE * total / METERS_PER_MILE) return emptyList()
        // The moment each mile mark was passed.
        val marks = mutableListOf<Double>()
        var covered = 0.0
        for (span in spans) {
            while (covered + span.meters >= (marks.size + 1) * METERS_PER_MILE) {
                val into = ((marks.size + 1) * METERS_PER_MILE - covered) / span.meters
                marks += span.startEpochSeconds + into * (span.endEpochSeconds - span.startEpochSeconds)
            }
            covered += span.meters
        }
        val heart = track.heart.filter { it.value.roundToInt() in HeartZones.PLAUSIBLE }

        fun split(number: Int, miles: Double, from: Double, to: Double): RunSplit {
            val within = heart.filter { it.epochSeconds >= from && it.epochSeconds < to }
            return RunSplit(number, miles, (to - from).roundToLong().coerceAtLeast(1), if (within.isEmpty()) null else within.map { it.value }.average().roundToInt())
        }

        val splits = mutableListOf<RunSplit>()
        var from = minOf(run.startEpochSeconds, spans.first().startEpochSeconds).toDouble()
        marks.forEachIndexed { index, at ->
            splits += split(index + 1, 1.0, from, at)
            from = at
        }
        val part = total / METERS_PER_MILE - marks.size
        val end = spans.last().endEpochSeconds.toDouble()
        if (part >= LEAST_PART_MILES && end > from) splits += split(marks.size + 1, part, from, end)
        return splits
    }

    /**
     * What a run's heart rate added up to in the zones of [bounds]: each reading stands until the
     * next one (the last, until [endEpochSeconds]), but for no longer than [HEART_GAP_SECONDS].
     * Null when there is nothing to add up.
     */
    fun heartSummary(heart: List<HealthSample>, bounds: ZoneBounds, endEpochSeconds: Long): HeartSummary? {
        val readings = heart.filter { it.value.roundToInt() in HeartZones.PLAUSIBLE }.sortedBy { it.epochSeconds }
        var below = 0L
        val zones = LongArray(HeartZone.entries.size)
        var weighted = 0L
        var peak = 0
        readings.forEachIndexed { index, reading ->
            val bpm = reading.value.roundToInt()
            peak = maxOf(peak, bpm)
            val stood = (readings.getOrNull(index + 1)?.epochSeconds ?: endEpochSeconds) - reading.epochSeconds
            if (stood <= 0 || stood > HEART_GAP_SECONDS) return@forEachIndexed
            val millis = stood * 1_000
            val zone = bounds.zoneOf(bpm)
            if (zone == null) below += millis else zones[zone.ordinal] += millis
            weighted += bpm * millis
        }
        return HeartSummary(below, zones.toList(), weighted, peak).takeIf { !it.isEmpty }
    }

    /** What opening [run] shows, from its [track] and the zones in force ([bounds], null when none are set). */
    fun detail(run: Run, track: RunTrack, bounds: ZoneBounds?): RunDetail = RunDetail(
        runId = run.id,
        splits = splits(run, track),
        heart = track.heart.filter { it.value.roundToInt() in HeartZones.PLAUSIBLE }.sortedBy { it.epochSeconds },
        summary = bounds?.let { heartSummary(track.heart, it, run.endEpochSeconds) },
        route = track.route,
    )
}
