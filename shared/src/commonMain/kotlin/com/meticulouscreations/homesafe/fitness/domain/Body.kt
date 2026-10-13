package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The steps counted on one local day. */
@Immutable
data class DaySteps(val epochDay: Long, val steps: Long)

/**
 * The week's steps: [days] is the seven days ending today, oldest first, a day nothing was
 * counted on being 0. [average] is what the days before today came to on average, counting only
 * days the tracker was worn; null when it wasn't worn on any of them.
 */
@Immutable
data class StepsBoard(val days: List<DaySteps>, val average: Long? = null) {
    val today: Long get() = days.lastOrNull()?.steps ?: 0
}

/**
 * A night's sleep, filed under the local day it ended on ([epochDay]), with the offsets from UTC
 * it ended in ([utcOffsetSeconds]) and began in ([startUtcOffsetSeconds]), so each of its times
 * reads as the clock by the bed did: they differ across a change of the clocks. [asleepSeconds]
 * leaves out the time awake in bed. The three depths are 0 when the tracker kept no stages.
 */
@Immutable
data class SleepNight(
    val epochDay: Long,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val utcOffsetSeconds: Int = 0,
    val asleepSeconds: Long,
    val awakeSeconds: Long = 0,
    val lightSeconds: Long = 0,
    val deepSeconds: Long = 0,
    val remSeconds: Long = 0,
    val startUtcOffsetSeconds: Int = utcOffsetSeconds,
) {
    /** The tracker said how deep the sleep was, not only that it was sleep. */
    val staged: Boolean get() = lightSeconds + deepSeconds + remSeconds > 0
}

/** Sleep sessions turned into nights. */
object SleepNights {
    /** A sleep shorter than this is a nap, and isn't anyone's night. */
    const val LEAST_NIGHT_SECONDS = 3_600L

    /**
     * One night for each local day a sleep ended on, oldest first: the longest sleep that ended
     * that day, so a nap in the afternoon doesn't stand for the night before it. A sleep is read
     * in the offsets it was recorded in; [offsetAt] is the offset from UTC at a moment where
     * this is being read, for a tracker that kept none.
     */
    fun of(sessions: List<SleepSession>, offsetAt: (Long) -> Int): List<SleepNight> =
        sessions.filter { it.endEpochSeconds > it.startEpochSeconds }
            .map { night(it, it.endUtcOffsetSeconds ?: offsetAt(it.endEpochSeconds), it.startUtcOffsetSeconds ?: offsetAt(it.startEpochSeconds)) }
            .filter { it.asleepSeconds >= LEAST_NIGHT_SECONDS }
            .groupBy { it.epochDay }
            .map { (_, sameDay) -> sameDay.maxBy { it.asleepSeconds } }
            .sortedBy { it.epochDay }

    private fun night(session: SleepSession, offset: Int, startOffset: Int): SleepNight {
        // A stage is counted only for the part of it inside the sleep it belongs to.
        fun spent(kind: SleepStageKind): Long = session.stages.filter { it.kind == kind }.sumOf {
            (minOf(it.endEpochSeconds, session.endEpochSeconds) - maxOf(it.startEpochSeconds, session.startEpochSeconds)).coerceAtLeast(0)
        }
        val awake = spent(SleepStageKind.AWAKE)
        val light = spent(SleepStageKind.LIGHT)
        val deep = spent(SleepStageKind.DEEP)
        val rem = spent(SleepStageKind.REM)
        val whole = session.endEpochSeconds - session.startEpochSeconds
        return SleepNight(
            epochDay = (session.endEpochSeconds + offset).floorDiv(SECONDS_PER_DAY),
            startEpochSeconds = session.startEpochSeconds,
            endEpochSeconds = session.endEpochSeconds,
            utcOffsetSeconds = offset,
            // With no stages the whole of it is taken as sleep; with them, whatever wasn't spent awake.
            asleepSeconds = if (session.stages.isEmpty()) whole else light + deep + rem + spent(SleepStageKind.ASLEEP),
            awakeSeconds = awake,
            lightSeconds = light,
            deepSeconds = deep,
            remSeconds = rem,
            startUtcOffsetSeconds = startOffset,
        )
    }
}

/** One of the things readiness is read from. */
enum class ReadinessFactor {
    /** Heart-rate variability overnight, against what is usual for this person. */
    VARIABILITY,

    /** The resting heart rate, against what is usual for this person. */
    RESTING,

    /** How long last night's sleep was. */
    SLEEP,
}

enum class ReadinessLevel { LOW, MODERATE, HIGH }

/**
 * One factor's share of the score: its own [score] out of 100, the [value] it was read from
 * (milliseconds, beats a minute, or seconds asleep) and what is [usual] for this person, which a
 * night's sleep has none of: it is held against a fixed need.
 */
@Immutable
data class ReadinessPart(val factor: ReadinessFactor, val score: Int, val value: Double, val usual: Double? = null)

/**
 * How ready the body looks for hard training today, out of 100, and what that was read from.
 * [learning] when the band has given a reading that couldn't be used yet, there being too few
 * nights before it to say what is usual.
 */
@Immutable
data class Readiness(val score: Int, val parts: List<ReadinessPart>, val learning: Boolean = false) {
    val level: ReadinessLevel
        get() = when {
            score >= ReadinessScore.HIGH_FROM -> ReadinessLevel.HIGH
            score >= ReadinessScore.MODERATE_FROM -> ReadinessLevel.MODERATE
            else -> ReadinessLevel.LOW
        }

    fun part(factor: ReadinessFactor): ReadinessPart? = parts.firstOrNull { it.factor == factor }
}

/**
 * A plain readiness score, worked out here from what the band gives. It is this app's own and
 * not Fitbit's "Daily Readiness", which never leaves Google's servers.
 *
 * **Three parts**, each out of 100, weighted and averaged over whichever are to be had:
 *
 * - **Heart-rate variability** (RMSSD overnight, 40%) and **resting heart rate** (30%) are each
 *   held against the same person's last [BASELINE_DAYS] days: [AT_USUAL] points when today is
 *   what is usual, and [PER_DEVIATION] more or fewer for each standard deviation better or worse
 *   (variability higher, resting rate lower). Variability is compared on its logarithm, since it
 *   is spread that way (Plews et al. 2013, Sports Med 43(9):773–81, who also make the case for
 *   reading it against one's own rolling baseline and not a population's). A deviation is never
 *   taken as smaller than a floor ([VARIABILITY_FLOOR], [RESTING_FLOOR]), so that a week of
 *   near-identical nights doesn't turn one ordinary night into an alarm. Neither is scored until
 *   there are [LEAST_BASELINE] days to compare with.
 * - **Sleep** (30%): last night's time asleep, full marks at [SLEEP_FULL_SECONDS] and none at
 *   [SLEEP_NONE_SECONDS]. Seven hours is the least recommended for an adult (Watson et al. 2015,
 *   Sleep 38(6):843–4); eight is used as the mark for someone training.
 *
 * It needs something from last night (the sleep itself, or the variability measured in it): a
 * resting rate alone says nothing about today. It is a guide. Nothing in the progression rules
 * reads it.
 */
object ReadinessScore {
    const val BASELINE_DAYS = 28
    const val LEAST_BASELINE = 7
    const val AT_USUAL = 75.0
    const val PER_DEVIATION = 25.0

    /** The least a standard deviation of ln(RMSSD) is taken to be: about eight percent. */
    const val VARIABILITY_FLOOR = 0.08

    /** The least a standard deviation of the resting heart rate is taken to be, in beats a minute. */
    const val RESTING_FLOOR = 1.5

    const val SLEEP_FULL_SECONDS = 8 * 3_600L
    const val SLEEP_NONE_SECONDS = 4 * 3_600L

    const val HIGH_FROM = 80
    const val MODERATE_FROM = 55

    private const val VARIABILITY_WEIGHT = 0.4
    private const val RESTING_WEIGHT = 0.3
    private const val SLEEP_WEIGHT = 0.3

    /** Variability measured before this hour of the evening belongs to the night before; after it, to the night ahead. */
    private const val NIGHT_TURNS_SECONDS = 18 * 3_600L

    /**
     * Today's readiness, [todayEpochDay] being the local day; null when nothing from last night
     * has arrived yet. [variability] and [resting] are every reading of the last few weeks,
     * [lastNight] the night that ended today.
     */
    fun of(
        todayEpochDay: Long,
        variability: List<HealthSample>,
        resting: List<HealthSample>,
        lastNight: SleepNight?,
        offsetAt: (Long) -> Int,
    ): Readiness? {
        // A reading late in the evening is of the night that is beginning, which ends tomorrow.
        val nights = daily(variability) { (it + offsetAt(it) + SECONDS_PER_DAY - NIGHT_TURNS_SECONDS).floorDiv(SECONDS_PER_DAY) }
        val days = daily(resting) { (it + offsetAt(it)).floorDiv(SECONDS_PER_DAY) }
        val variabilityNow = nights[todayEpochDay]?.takeIf { it > 0 }
        // The band works the resting rate out once a day, and today's may not be in yet.
        val restingDay = listOf(todayEpochDay, todayEpochDay - 1).firstOrNull { days[it] != null }
        val restingNow = restingDay?.let { days.getValue(it) }
        val sleep = lastNight?.takeIf { it.epochDay == todayEpochDay }
        if (variabilityNow == null && sleep == null) return null

        val parts = mutableListOf<ReadinessPart>()
        var learning = false
        if (variabilityNow != null) {
            val usual = before(nights, todayEpochDay).filter { it > 0 }.map(::ln)
            if (usual.size >= LEAST_BASELINE) {
                parts += ReadinessPart(ReadinessFactor.VARIABILITY, against(ln(variabilityNow), usual, VARIABILITY_FLOOR), variabilityNow, exp(usual.average()))
            } else {
                learning = true
            }
        }
        if (restingDay != null && restingNow != null) {
            val usual = before(days, restingDay)
            if (usual.size >= LEAST_BASELINE) {
                // Lower is better, so the comparison is turned over.
                parts += ReadinessPart(ReadinessFactor.RESTING, against(-restingNow, usual.map { -it }, RESTING_FLOOR), restingNow, usual.average())
            } else {
                learning = true
            }
        }
        if (sleep != null) parts += ReadinessPart(ReadinessFactor.SLEEP, sleepScore(sleep.asleepSeconds), sleep.asleepSeconds.toDouble())
        if (parts.isEmpty()) return null
        val weight = parts.sumOf { weightOf(it.factor) }
        return Readiness((parts.sumOf { it.score * weightOf(it.factor) } / weight).roundToInt(), parts, learning)
    }

    /** A night's length as a score: none at four hours or under, full at eight or over, even between. */
    fun sleepScore(asleepSeconds: Long): Int =
        (100.0 * (asleepSeconds - SLEEP_NONE_SECONDS) / (SLEEP_FULL_SECONDS - SLEEP_NONE_SECONDS)).roundToInt().coerceIn(0, 100)

    /** [value] against the [usual] ones, higher being better: [AT_USUAL] when level with their mean. */
    internal fun against(value: Double, usual: List<Double>, floor: Double): Int {
        val mean = usual.average()
        val spread = if (usual.size > 1) sqrt(usual.sumOf { (it - mean) * (it - mean) } / (usual.size - 1)) else 0.0
        return (AT_USUAL + PER_DEVIATION * (value - mean) / max(spread, floor)).roundToInt().coerceIn(0, 100)
    }

    private fun weightOf(factor: ReadinessFactor): Double = when (factor) {
        ReadinessFactor.VARIABILITY -> VARIABILITY_WEIGHT
        ReadinessFactor.RESTING -> RESTING_WEIGHT
        ReadinessFactor.SLEEP -> SLEEP_WEIGHT
    }

    /** The mean of the readings that fall on each day. */
    private fun daily(samples: List<HealthSample>, dayOf: (Long) -> Long): Map<Long, Double> =
        samples.groupBy { dayOf(it.epochSeconds) }.mapValues { (_, sameDay) -> sameDay.map { it.value }.average() }

    /** What there was on the [BASELINE_DAYS] days before [day]. */
    private fun before(series: Map<Long, Double>, day: Long): List<Double> = (day - BASELINE_DAYS until day).mapNotNull { series[it] }
}

/**
 * What the band says about the body today, as Today shows it. Each of [steps] and [sleep] is
 * null when that kind isn't shared with the app, which is a different thing from there being
 * nothing to show: [SleepBoard.lastNight] is null when the night simply hasn't arrived.
 */
@Immutable
data class BodyBoard(
    val steps: StepsBoard? = null,
    val sleep: SleepBoard? = null,
    val readiness: Readiness? = null,
    /** Whether readiness could be read at all: sleep, or heart-rate variability, is shared. */
    val readinessShared: Boolean = false,
)

/** Last night, when it has arrived, and what the nights of the week before it came to on average. */
@Immutable
data class SleepBoard(val lastNight: SleepNight? = null, val averageSeconds: Long? = null)

object BodyBoards {
    /** How many days of steps Today draws. */
    const val STEP_DAYS = 7

    /** How many nights the average sleep is taken over. */
    private const val SLEEP_AVERAGE_NIGHTS = 7

    /**
     * Today's [BodyBoard] at [nowEpochSeconds]. Each input is null when its kind isn't shared:
     * [steps] by local day, and the sleeps and readings of the last few weeks.
     */
    fun build(
        nowEpochSeconds: Long,
        offsetAt: (Long) -> Int,
        steps: Map<Long, Long>? = null,
        sleep: List<SleepSession>? = null,
        resting: List<HealthSample>? = null,
        variability: List<HealthSample>? = null,
    ): BodyBoard {
        val today = (nowEpochSeconds + offsetAt(nowEpochSeconds)).floorDiv(SECONDS_PER_DAY)
        val nights = sleep?.let { SleepNights.of(it, offsetAt) }
        val lastNight = nights?.lastOrNull { it.epochDay == today }
        return BodyBoard(
            steps = steps?.let { counted ->
                val days = (today - STEP_DAYS + 1..today).map { DaySteps(it, counted[it] ?: 0) }
                val worn = days.dropLast(1).filter { it.steps > 0 }
                StepsBoard(days, if (worn.isEmpty()) null else worn.map { it.steps }.average().roundToLong())
            },
            sleep = nights?.let { all ->
                val earlier = all.filter { it.epochDay in today - SLEEP_AVERAGE_NIGHTS until today }
                SleepBoard(lastNight, if (earlier.isEmpty()) null else earlier.map { it.asleepSeconds }.average().roundToLong())
            },
            readiness = ReadinessScore.of(today, variability.orEmpty(), resting.orEmpty(), lastNight, offsetAt),
            readinessShared = sleep != null || variability != null,
        )
    }
}
