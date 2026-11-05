package com.meticulouscreations.homesafe.fitness.live

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.fitness.ActiveWorkout
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.FitnessBoardBuilder
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.FitnessFormat

/** A set as the live surfaces write it: [weight] for [reps], counted the way its exercise is ([LoadKind]). */
@Immutable
data class LiveSet(val weight: Double, val reps: Int)

/**
 * An exercise as the live surfaces name it: what it is called, how many sets of it are logged in
 * the workout so far, the last of them, and what today's target is.
 */
@Immutable
data class LiveLift(
    val exerciseId: String,
    val name: String,
    val loadKind: LoadKind,
    val setsToday: Int = 0,
    val last: LiveSet? = null,
    val target: LiveSet? = null,
)

/** The heart as the live surfaces show it: the reading, the zone it has settled in, and during a rest the highest it read since the set. */
@Immutable
data class LiveHeart(val bpm: Int, val zone: HeartZone? = null, val peakBpm: Int? = null) {
    /** How far it has come down since the set, during a rest. */
    val drop: Int? get() = peakBpm?.let { (it - bpm).coerceAtLeast(0) }
}

/**
 * The workout in progress as everything outside the app's own screen shows it: Android's
 * ongoing notification and its chip in the status bar, the iPhone's Live Activity on the Lock
 * Screen and in the Dynamic Island, the Mac's island at the top of the screen and its menu-bar
 * item.
 *
 * [lift] is the exercise in hand: the one being rested from while a [rest] counts, or else the one
 * a set was logged of last. [next] is the first exercise on the workout's own shelves that hasn't
 * been done today, in the order the workout lists them. [onScreen] is whether the fitness app is
 * in front, where it says all of this itself: a rest running out then needs no alert.
 */
@Immutable
data class LiveWorkout(
    val workoutId: Long,
    val focus: WorkoutFocus,
    val startedAtEpochMillis: Long,
    val sets: Int = 0,
    val records: Int = 0,
    val exercisesDone: Int = 0,
    val lift: LiveLift? = null,
    val next: LiveLift? = null,
    val rest: RestTimer? = null,
    val heart: LiveHeart? = null,
    val onScreen: Boolean = false,
) {
    /** Where the workout stands at [nowMillis]: resting, the rest just over, or between rests. */
    fun phase(nowMillis: Long): LivePhase {
        val rest = rest ?: return LivePhase.TRAINING
        return if (rest.endsAtEpochMillis > nowMillis) LivePhase.RESTING else LivePhase.REST_OVER
    }

    /** How much of the rest is still to go at [nowMillis], 1 just after the set and 0 when it is over. */
    fun restLeft(nowMillis: Long): Float {
        val rest = rest ?: return 0f
        val total = rest.totalSeconds * 1000f
        return ((rest.endsAtEpochMillis - nowMillis) / total).coerceIn(0f, 1f)
    }

    /** Whole seconds of the rest still to go at [nowMillis], rounded up as a countdown shows them; 0 with none, or once it is over. */
    fun restSecondsLeft(nowMillis: Long): Int {
        val rest = rest ?: return 0
        return ((rest.endsAtEpochMillis - nowMillis + 999) / 1000).toInt().coerceAtLeast(0)
    }

    /** The rest still to go at [nowMillis] as its countdown reads: "1:12". */
    fun restClock(nowMillis: Long): String = FitnessFormat.clock(restSecondsLeft(nowMillis))

    /** When this workout stops being the one in progress, if it is never finished (see [Workout.STALE_AFTER_SECONDS]). */
    val staleAtEpochMillis: Long get() = startedAtEpochMillis + Workout.STALE_AFTER_SECONDS * 1000

    companion object {
        /**
         * The workout open in [state], as the live surfaces show it, with the [heart] beside it;
         * null with none open. The rest is left out: it is the hub's own ([LiveWorkouts]), and
         * added there.
         */
        fun of(state: FitnessUiState, heart: HeartUiState, onScreen: Boolean): LiveWorkout? {
            val open = state.workout ?: return null
            val workout = open.workout
            val inHand = state.rest?.exerciseId ?: lastLogged(open)
            return LiveWorkout(
                workoutId = workout.id,
                focus = workout.focus,
                startedAtEpochMillis = workout.startedAtEpochSeconds * 1000,
                sets = open.setCount,
                records = open.recordCount,
                exercisesDone = open.entries.count { it.sets.isNotEmpty() },
                lift = inHand?.let { id -> state.board(id)?.let { lift(it, open) } },
                next = nextUp(state, open, inHand)?.let { lift(it, open) },
                heart = heart.bpm?.let { bpm -> LiveHeart(bpm, heart.zone, heart.recovery?.peakBpm?.takeIf { state.rest != null }) },
                onScreen = onScreen,
            )
        }

        private fun lastLogged(open: ActiveWorkout): String? =
            open.entries.mapNotNull { entry -> entry.sets.maxOfOrNull { it.epochSeconds }?.let { entry.exerciseId to it } }.maxByOrNull { it.second }?.first

        /** The first exercise on the workout's own shelves not done today, as the workout lists them. */
        private fun nextUp(state: FitnessUiState, open: ActiveWorkout, inHand: String?): ExerciseBoard? {
            val done = open.entries.filter { it.sets.isNotEmpty() }.map { it.exerciseId }.toSet()
            return open.workout.focus.parts.asSequence()
                .flatMap { part -> FitnessBoardBuilder.workoutOrder(state.boards.filter { it.exercise.bodyPart == part && !it.exercise.archived }).asSequence() }
                .firstOrNull { it.exercise.id !in done && it.exercise.id != inHand }
        }

        private fun lift(board: ExerciseBoard, open: ActiveWorkout): LiveLift {
            val sets = open.entry(board.exercise.id)?.sets.orEmpty()
            return LiveLift(
                exerciseId = board.exercise.id,
                name = board.exercise.name,
                loadKind = board.exercise.loadKind,
                setsToday = sets.size,
                last = sets.maxByOrNull { it.epochSeconds }?.let { LiveSet(it.weight, it.reps) },
                target = board.target?.let { LiveSet(it.weight, it.reps) },
            )
        }
    }
}

/** Where a workout stands, for the live surfaces: each looks and reads differently. */
enum class LivePhase {
    /** Between rests: a set is being done, or the next one set up. */
    TRAINING,

    /** A rest is counting down. */
    RESTING,

    /** The rest has run out, and the next set is waiting. */
    REST_OVER,
}
