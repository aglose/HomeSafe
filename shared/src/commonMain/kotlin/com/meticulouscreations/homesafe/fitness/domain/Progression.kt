package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.floor

/** Why a [Target] is what it is: the one line of coaching shown beside it. */
enum class TargetReason {
    /** Nothing dated yet: the number is the notes' own, to find the level again from. */
    BENCHMARK,

    /** One more rep at the same weight. */
    ADD_REP,

    /** The top of the rep band was reached: more weight, fewer reps. */
    ADD_WEIGHT,

    /** On a cut: match last time. Holding the load is what keeps the muscle. */
    HOLD,

    /** On a cut and the reps have fallen out of the band: a step down, to keep the sets productive. */
    BACK_OFF,

    /** Below an old best after a cut or a break, with the surplus to climb back: bigger steps, since regained strength comes quicker than new. */
    REBUILD,

    /** Back after weeks away: start under the last numbers and take a session or two to return to them. */
    EASE_IN,
}

/**
 * What to aim for on an exercise's peak set next time. [toBeat] is the set it was worked out
 * from (last session's best, or the notes' benchmark), [allTime] the best there has ever been,
 * and [ofBest] how last time stood against that, 1 being level with it.
 */
@Immutable
data class Target(
    val weight: Double,
    val reps: Int,
    val reason: TargetReason,
    val toBeat: LoggedSet,
    val allTime: LoggedSet? = null,
    val ofBest: Float = 1f,
)

/**
 * Turns an exercise's history into next session's [Target] by double progression: work the reps
 * up through the exercise's band at one weight, then add weight and start again lower in the
 * band. Both ways of progressing build muscle about equally (Plotkin et al. 2022), and this is
 * the one a single logged peak set can drive.
 *
 * The phase changes what "progress" asks for. On a cut the job is to hold the load (Spiering et
 * al. 2021): the target matches last time. Coming back up after one, or after a break, strength
 * that was there before returns faster than it was first built, so the steps are bigger until
 * the old best is level again.
 */
object Progression {
    /** How far under the all-time best last session has to be before the climb back is called a rebuild. */
    private const val REBUILD_BELOW = 0.97f

    fun nextTarget(
        exercise: Exercise,
        sets: List<LoggedSet>,
        phase: PhaseKind,
        nowEpochSeconds: Long,
        bodyweight: Double? = null,
    ): Target? {
        val done = sets.filter { it.reps > 0 }
        if (done.isEmpty()) return null
        val allTime = Strength.best(exercise, done, bodyweight)
        val dated = current(done, nowEpochSeconds)
        if (dated.isEmpty()) {
            val start = benchmarkToStartFrom(exercise, done, bodyweight) ?: return null
            return Target(start.weight, start.reps, TargetReason.BENCHMARK, start, allTime)
        }
        val last = lastSessionBest(exercise, dated, bodyweight) ?: return null
        val lastScore = Strength.score(exercise, last, bodyweight)
        val bestScore = allTime?.let { Strength.score(exercise, it, bodyweight) } ?: lastScore
        val ofBest = if (bestScore > 0.0) (lastScore / bestScore).toFloat().coerceIn(0f, 1f) else 1f
        val daysOff = (nowEpochSeconds - last.epochSeconds) / SECONDS_PER_DAY

        fun target(weight: Double, reps: Int, reason: TargetReason) = Target(weight, reps, reason, last, allTime, ofBest)

        layoffFactor(daysOff)?.let { factor ->
            // Back at the middle of the band, at a weight that makes that a set with reps to spare.
            val reps = (exercise.repLow + exercise.repHigh) / 2
            val bodyLoad = Strength.load(exercise, last, bodyweight) - last.weight
            val weight = roundDown(lastScore * factor / (1.0 + reps / 30.0) - bodyLoad, exercise, done).coerceAtMost(last.weight)
            return target(weight.coerceAtLeast(0.0), reps, TargetReason.EASE_IN)
        }

        if (phase == PhaseKind.CUT) {
            if (last.reps < exercise.repLow && last.weight > 0.0) {
                val lower = stepDown(last.weight, exercise, done)
                val reps = Strength.repsToBeat(lastScore, lower + Strength.load(exercise, last, bodyweight) - last.weight) - 1
                return target(lower, reps.coerceIn(exercise.repLow, exercise.repHigh), TargetReason.BACK_OFF)
            }
            return target(last.weight, last.reps, TargetReason.HOLD)
        }

        val rebuilding = ofBest < REBUILD_BELOW
        // A bodyweight movement with nothing hung from it yet just keeps adding reps.
        val canAddWeight = exercise.loadKind != LoadKind.BODYWEIGHT || last.weight > 0.0
        if (last.reps >= exercise.repHigh && canAddWeight) {
            val heavier = stepUp(last.weight, exercise, done)
            val bodyLoad = Strength.load(exercise, last, bodyweight) - last.weight
            val reps = Strength.repsToBeat(lastScore, heavier + bodyLoad).coerceIn(exercise.repLow, exercise.repHigh)
            return target(heavier, reps, if (rebuilding) TargetReason.REBUILD else TargetReason.ADD_WEIGHT)
        }
        return if (rebuilding) {
            target(last.weight, (last.reps + 2).coerceAtMost(maxOf(exercise.repHigh, last.reps + 1)), TargetReason.REBUILD)
        } else {
            target(last.weight, last.reps + 1, TargetReason.ADD_REP)
        }
    }

    /**
     * The sets that say where the lifter is now: everything logged in the app, and from the
     * notes only what was marked as recent when it was brought in. A set the notes dated years
     * back is history for the chart, not a last session to resume from; the notes' undated
     * ladder is the better guide to today, since it was being kept up until the switch.
     */
    fun current(sets: List<LoggedSet>, nowEpochSeconds: Long): List<LoggedSet> =
        sets.filter { !it.isBenchmark && it.reps > 0 && (!it.imported || nowEpochSeconds - it.epochSeconds < RECENT_IMPORT_SECONDS) }

    private const val RECENT_IMPORT_SECONDS = 45 * SECONDS_PER_DAY

    /**
     * The best set of the most recent day the exercise was trained: the peak set to beat. A day,
     * not a workout, so sets logged outside one still count as that day's.
     */
    fun lastSessionBest(exercise: Exercise, dated: List<LoggedSet>, bodyweight: Double? = null): LoggedSet? {
        val latest = dated.maxOfOrNull { it.epochSeconds } ?: return null
        // Anything within half a day of the last set is the same session.
        return Strength.best(exercise, dated.filter { latest - it.epochSeconds < SECONDS_PER_DAY / 2 }, bodyweight)
    }

    /**
     * Where to pick an exercise up from when all there is of it is the notes: the heaviest
     * weight whose reps were still inside the band, since the rungs above it were the ones being
     * worked toward. With none inside the band, the best set there is.
     */
    private fun benchmarkToStartFrom(exercise: Exercise, sets: List<LoggedSet>, bodyweight: Double?): LoggedSet? {
        val ladder = Strength.ladder(sets)
        val rung = ladder.lastOrNull { it.reps >= exercise.repLow } ?: return Strength.best(exercise, sets, bodyweight)
        return sets.filter { it.weight == rung.weight }.maxWithOrNull(compareBy({ it.reps }, { it.id }))
    }

    /**
     * What fraction of the last numbers to come back at after [daysOff], or null when it hasn't
     * been long enough to matter. Little is lost in a fortnight, about a tenth in a month, and it
     * returns in roughly half the time it was away (Ogasawara et al. 2013; Halonen et al. 2024);
     * the steps between are a coach's rule of thumb, not a measured curve.
     */
    fun layoffFactor(daysOff: Long): Double? = when {
        daysOff < 18 -> null
        daysOff <= 31 -> 0.90
        daysOff <= 60 -> 0.82
        daysOff <= 120 -> 0.72
        else -> 0.65
    }

    /** The next weight up from [weight]: a rung already on the ladder if one is near, otherwise one [Exercise.increment] on. */
    fun stepUp(weight: Double, exercise: Exercise, sets: List<LoggedSet>): Double {
        val known = sets.asSequence().map { it.weight }.filter { it > weight + 1e-6 && it <= weight + exercise.increment * 1.5 + 1e-6 }.minOrNull()
        return known ?: (weight + exercise.increment)
    }

    /** The next weight down from [weight], the same way. */
    fun stepDown(weight: Double, exercise: Exercise, sets: List<LoggedSet>): Double {
        val known = sets.asSequence().map { it.weight }.filter { it < weight - 1e-6 && it >= weight - exercise.increment * 1.5 - 1e-6 }.maxOrNull()
        return (known ?: (weight - exercise.increment)).coerceAtLeast(0.0)
    }

    /** [weight] brought down to something loadable: the nearest rung at or under it, or a whole number of increments. */
    private fun roundDown(weight: Double, exercise: Exercise, sets: List<LoggedSet>): Double {
        if (weight <= 0.0) return 0.0
        val step = exercise.increment.takeIf { it > 0.0 } ?: 5.0
        val stepped = floor(weight / step + 1e-9) * step
        val rung = sets.asSequence().map { it.weight }.filter { it <= weight + 1e-6 }.maxOrNull()
        return if (rung != null && rung > stepped) rung else stepped
    }

    /**
     * The rep band an exercise is worked in, read off its own ladder: the reps the lifter
     * usually reached at a weight before moving on are the top of it. Kept between 8 and 30,
     * the range that builds muscle about equally when sets are taken near failure (Schoenfeld
     * et al. 2017). Too few rungs to tell falls back to [fallback].
     */
    fun inferRepBand(rungReps: List<Int>, fallback: IntRange): IntRange {
        val reps = rungReps.filter { it > 0 }
        if (reps.size < 3) return fallback
        val median = reps.sorted()[reps.size / 2]
        val high = median.coerceIn(8, 30)
        return (
            high - if (high >= 25) {
                6
            } else if (high >= 15) {
                5
            } else {
                4
            }
            )..high
    }

    /**
     * The usual jump between weights on an exercise, read off the gaps in its ladder: each gap
     * snapped to a size plates and stacks come in, and the commonest taken (the one nearest
     * [fallback] when two are as common). Fewer than two gaps falls back to [fallback].
     */
    fun inferIncrement(weights: List<Double>, fallback: Double): Double {
        val sorted = weights.distinct().sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it > 0.0 }
        if (gaps.size < 2) return fallback
        val counts = gaps.groupingBy { gap -> NICE_STEPS.minBy { abs(it - gap) } }.eachCount()
        val most = counts.values.max()
        return counts.filterValues { it == most }.keys.minBy { abs(it - fallback) }
    }

    private val NICE_STEPS = listOf(1.0, 2.5, 5.0, 10.0, 20.0, 50.0)
}
