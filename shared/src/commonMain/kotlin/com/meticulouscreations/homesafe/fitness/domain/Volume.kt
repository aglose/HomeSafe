package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlin.math.exp
import kotlin.math.ln

/**
 * Where a week's sets for a muscle stand: under [minimum] little is being asked of it, between
 * [targetLow] and [targetHigh] is where most of the growth is to be had from one hard session a
 * week, and past [targetHigh] more sets in that session add little (the per-session ceiling is
 * about eleven; more wants a second day).
 */
@Immutable
data class VolumeBand(val minimum: Double, val targetLow: Double, val targetHigh: Double)

/** A muscle's week: [sets] counted toward it, against its [band]. */
@Immutable
data class MuscleVolume(val muscle: Muscle, val sets: Double, val band: VolumeBand) {
    /** 0 at nothing, 1 at the top of the target band, and past 1 beyond it. */
    val fill: Float get() = (sets / band.targetHigh).toFloat()

    val status: VolumeStatus
        get() = when {
            sets <= 0.0 -> VolumeStatus.NONE
            sets < band.minimum -> VolumeStatus.LOW
            sets < band.targetLow -> VolumeStatus.BUILDING
            sets <= band.targetHigh -> VolumeStatus.ON_TARGET
            else -> VolumeStatus.HIGH
        }
}

enum class VolumeStatus { NONE, LOW, BUILDING, ON_TARGET, HIGH }

/**
 * The week's training counted by muscle. A set counts whole toward the muscle its exercise is
 * for and half toward the ones that help, which is the counting that tracked growth best across
 * the studies pooled by Pelland et al. Only sets that were logged are counted: someone who
 * writes down one peak set per exercise will see their sessions' shape here, not their true set
 * count.
 */
object Volume {
    const val SECONDARY_CREDIT = 0.5

    fun weekly(exercises: Map<String, Exercise>, sets: List<LoggedSet>, fromEpochSeconds: Long, toEpochSeconds: Long): List<MuscleVolume> {
        val totals = HashMap<Muscle, Double>()
        for (set in sets) {
            if (set.isBenchmark || set.imported || set.epochSeconds < fromEpochSeconds || set.epochSeconds > toEpochSeconds || set.reps <= 0) continue
            val exercise = exercises[set.exerciseId] ?: continue
            totals[exercise.primary] = (totals[exercise.primary] ?: 0.0) + 1.0
            for (muscle in exercise.secondary) totals[muscle] = (totals[muscle] ?: 0.0) + SECONDARY_CREDIT
        }
        return Muscle.entries.map { MuscleVolume(it, totals[it] ?: 0.0, band(it)) }
    }

    /** Hard sets a week, counted as above, for a muscle trained once a week. */
    fun band(muscle: Muscle): VolumeBand = when (muscle) {
        Muscle.CHEST, Muscle.BACK, Muscle.QUADS -> VolumeBand(4.0, 8.0, 12.0)
        Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.SIDE_DELTS, Muscle.BICEPS, Muscle.TRICEPS -> VolumeBand(3.0, 6.0, 12.0)
        Muscle.FRONT_DELTS, Muscle.REAR_DELTS, Muscle.CALVES, Muscle.ABS, Muscle.TRAPS -> VolumeBand(2.0, 6.0, 10.0)
        Muscle.FOREARMS, Muscle.ADDUCTORS -> VolumeBand(2.0, 4.0, 8.0)
    }
}

/** A day's place on the bodyweight trend: what the scale said and the smoothed weight under it. */
@Immutable
data class TrendPoint(val epochDay: Long, val pounds: Double, val trend: Double)

/**
 * The weigh-ins with the noise taken out: [points] in date order, and [weeklyChange], how fast
 * the trend has been moving over the last fortnight as a fraction of bodyweight a week (-0.007
 * is losing 0.7% a week), or null with too little to tell.
 */
@Immutable
data class BodyweightTrend(val points: List<TrendPoint> = emptyList(), val weeklyChange: Double? = null) {
    val latest: TrendPoint? get() = points.lastOrNull()

    companion object {
        /** How much of each day's difference from the trend is believed: the Hacker's Diet's tenth. */
        private const val ALPHA = 0.1

        /**
         * A scale reading swings a pound or two a day on water alone, so what is tracked is an
         * exponentially smoothed trend. A gap of several days counts as that many days of
         * smoothing, so the trend catches up after a break in weighing.
         */
        fun of(entries: List<BodyweightEntry>): BodyweightTrend {
            val sorted = entries.sortedBy { it.epochDay }
            if (sorted.isEmpty()) return BodyweightTrend()
            val points = ArrayList<TrendPoint>(sorted.size)
            var trend = sorted.first().pounds
            var lastDay = sorted.first().epochDay
            for (entry in sorted) {
                val days = (entry.epochDay - lastDay).coerceAtLeast(1)
                val alpha = 1.0 - exp(days * ln(1.0 - ALPHA))
                trend += alpha * (entry.pounds - trend)
                lastDay = entry.epochDay
                points += TrendPoint(entry.epochDay, entry.pounds, trend)
            }
            val last = points.last()
            val from = points.lastOrNull { last.epochDay - it.epochDay >= 14 } ?: points.first()
            val span = last.epochDay - from.epochDay
            val weekly = if (span >= 7 && from.trend > 0.0) (last.trend - from.trend) / from.trend / span * 7 else null
            return BodyweightTrend(points, weekly)
        }
    }
}

/** How a cut's or a bulk's pace reads against what keeps muscle on or fat off. */
enum class PaceVerdict { TOO_FAST, ON_PACE, TOO_SLOW, STEADY }

object Pace {
    /**
     * A cut is paced to lose half a percent to one percent of bodyweight a week: faster costs
     * muscle and strength, more so the leaner the lifter (Helms et al. 2014; Garthe et al.
     * 2011). A bulk gains at a quarter to a half percent a week, beyond which the extra is
     * mostly fat (Iraki et al. 2019).
     */
    fun verdict(phase: PhaseKind, weeklyChange: Double): PaceVerdict = when (phase) {
        PhaseKind.CUT -> when {
            weeklyChange < -0.0105 -> PaceVerdict.TOO_FAST
            weeklyChange <= -0.004 -> PaceVerdict.ON_PACE
            else -> PaceVerdict.TOO_SLOW
        }

        PhaseKind.BULK -> when {
            weeklyChange > 0.0055 -> PaceVerdict.TOO_FAST
            weeklyChange >= 0.0015 -> PaceVerdict.ON_PACE
            else -> PaceVerdict.TOO_SLOW
        }

        PhaseKind.MAINTAIN -> PaceVerdict.STEADY
    }
}
