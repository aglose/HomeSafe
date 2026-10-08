package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlin.math.floor

/**
 * How sets of different weights and reps are put on one scale, and what counts as a record.
 *
 * The scale is the Epley estimate of a one-rep max, `load × (1 + reps / 30)`: simple, and it
 * can be turned round to say how many reps at a weight would beat a number. It is only ever
 * compared within one exercise, where it ranks sets well enough; it is not a promise of what
 * would go up for a single.
 */
object Strength {
    /** What a body is taken to weigh for a bodyweight movement when no weigh-in says otherwise. It only has to be the same for every set compared. */
    const val ASSUMED_BODYWEIGHT = 170.0

    /** The Epley estimate for [reps] at [load]; one rep is the load itself. */
    fun e1rm(load: Double, reps: Int): Double = when {
        reps <= 0 || load <= 0.0 -> 0.0
        reps == 1 -> load
        else -> load * (1.0 + reps / 30.0)
    }

    /** The reps at [load] that would beat an estimate of [e1rm], never fewer than one. */
    fun repsToBeat(e1rm: Double, load: Double): Int {
        if (load <= 0.0) return 1
        var reps = (floor(30.0 * (e1rm / load - 1.0) + 1e-9).toInt() + 1).coerceAtLeast(1)
        // A single counts as the load itself, a step off the formula's line: make sure of the answer against it.
        while (e1rm(load, reps) <= e1rm + 1e-9) reps++
        return reps
    }

    /**
     * What was actually moved in [set]: the weight written down, plus the body for a bodyweight
     * movement ([bodyweight] being the lifter's weight at the time when the set didn't note it).
     */
    fun load(exercise: Exercise, set: LoggedSet, bodyweight: Double? = null): Double = when (exercise.loadKind) {
        LoadKind.BODYWEIGHT -> (set.bodyweight ?: bodyweight ?: ASSUMED_BODYWEIGHT) + set.weight
        else -> set.weight
    }

    fun score(exercise: Exercise, set: LoggedSet, bodyweight: Double? = null): Double = e1rm(load(exercise, set, bodyweight), set.reps)

    /** The best of [sets] by [score]; the later one when two tie, since that is the one to beat. */
    fun best(exercise: Exercise, sets: List<LoggedSet>, bodyweight: Double? = null): LoggedSet? =
        sets.filter { it.reps > 0 }.maxWithOrNull(compareBy<LoggedSet>({ score(exercise, it, bodyweight) }, { it.epochSeconds }, { it.id }))

    /**
     * The exercise's ladder: one [Rung] for each weight ever used, lightest first, with the most
     * reps done at it. That is the notes' own shape (a weight, and the reps to beat at it), and
     * the reps done since [phaseStartEpochSeconds] sit beside the all-time ones so a cut's numbers
     * can be read against what they were.
     */
    fun ladder(sets: List<LoggedSet>, phaseStartEpochSeconds: Long = 0): List<Rung> =
        sets.filter { it.reps > 0 }
            .groupBy { it.weight }
            .map { (weight, at) ->
                val recent = at.filter { !it.isBenchmark && it.epochSeconds >= phaseStartEpochSeconds }
                Rung(
                    weight = weight,
                    reps = at.maxOf { it.reps },
                    phaseReps = recent.maxOfOrNull { it.reps },
                    lastEpochSeconds = at.maxOf { it.epochSeconds },
                )
            }
            .sortedBy { it.weight }

    /**
     * What [set] is a record of, against everything [before] it on the same exercise. Nothing on
     * a first-ever set (there is nothing to have beaten), and nothing for a tie.
     *
     * A record is all-time when no earlier set stands above it: more weight than was ever lifted,
     * or more reps than were ever done at this weight or a heavier one (a rung of the ladder
     * beaten). Short of that, it is a record for the phase when it is the best since [phaseStartEpochSeconds]:
     * the number worth chasing on a cut, when the all-time ones are out of reach for now.
     */
    fun record(exercise: Exercise, set: LoggedSet, before: List<LoggedSet>, phaseStartEpochSeconds: Long, bodyweight: Double? = null): Record? {
        val earlier = before.filter { it.id != set.id && it.reps > 0 }
        if (earlier.isEmpty() || set.reps <= 0) return null
        val mine = score(exercise, set, bodyweight)
        val heaviest = earlier.maxOf { load(exercise, it, bodyweight) }
        val myLoad = load(exercise, set, bodyweight)
        if (myLoad > heaviest + EPSILON) return Record(RecordScope.ALL_TIME, RecordKind.WEIGHT)
        if (earlier.none { load(exercise, it, bodyweight) >= myLoad - EPSILON && it.reps >= set.reps }) {
            return Record(RecordScope.ALL_TIME, RecordKind.REPS)
        }
        // From here an earlier set stands at least as heavy with at least as many reps, so nothing all-time is left to claim.
        val inPhase = earlier.filter { !it.isBenchmark && it.epochSeconds >= phaseStartEpochSeconds }
        // The first set of a phase is where it starts from, not a record in it.
        if (inPhase.isEmpty()) return null
        if (mine > inPhase.maxOf { score(exercise, it, bodyweight) } + EPSILON) return Record(RecordScope.PHASE, RecordKind.ESTIMATE)
        return null
    }

    private const val EPSILON = 1e-6
}

/** One weight on an exercise's ladder: the most [reps] ever done at it, and the most this phase if it has been used in it. */
@Immutable
data class Rung(val weight: Double, val reps: Int, val phaseReps: Int? = null, val lastEpochSeconds: Long = 0)

enum class RecordScope { ALL_TIME, PHASE }

enum class RecordKind { WEIGHT, REPS, ESTIMATE }

@Immutable
data class Record(val scope: RecordScope, val kind: RecordKind)
