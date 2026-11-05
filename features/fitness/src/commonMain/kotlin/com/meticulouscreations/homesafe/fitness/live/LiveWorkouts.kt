package com.meticulouscreations.homesafe.fitness.live

import com.meticulouscreations.homesafe.fitness.RestTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The workout in progress and its rest, for the whole process: what the fitness app's screens,
 * Android's ongoing notification, the iPhone's Live Activity and the Mac's island all show, and
 * what each of their buttons acts on.
 *
 * The rest lives here rather than in [com.meticulouscreations.homesafe.fitness.FitnessViewModel]
 * because those buttons are pressed when there may be no screen at all: Android builds the view
 * model again with the Activity, and the notification outlives both; an iPhone runs a Live
 * Activity's button in an app that may have no window. A rest skipped from the Lock Screen is
 * skipped in the app as well, since there is only the one.
 *
 * The view model hands over the rest of the picture ([show]: the workout, the exercise in hand,
 * the heart) whenever it changes; with no screen to follow the log, the last picture stands, and
 * the rest still counts and can still be changed. [process] is the one the app uses; a test
 * makes its own.
 */
class LiveWorkouts {
    private val shown = MutableStateFlow<LiveWorkout?>(null)
    private val _rest = MutableStateFlow<RestTimer?>(null)
    private val _workout = MutableStateFlow<LiveWorkout?>(null)

    /** The rest counting, or just run out; null between rests. */
    val rest: StateFlow<RestTimer?> = _rest.asStateFlow()

    /** The workout in progress with its rest, as the live surfaces show it; null with none open. */
    val workout: StateFlow<LiveWorkout?> = _workout.asStateFlow()

    /**
     * Whether, while a workout is shown, the platform keeps the app running with its screen away
     * (Android's foreground service): kept by [LiveWorkoutPresenter] as it hands the workout over.
     */
    var keepsAppRunning: Boolean = false

    /**
     * The workout open now, as the app sees it, without its rest; null once there is none. The
     * rest is left alone: one counts after a set logged outside a workout too, and a workout the
     * app closes skips its own. Only a rest from another workout than this one goes.
     */
    fun show(workout: LiveWorkout?) {
        val before = shown.value
        shown.value = workout?.copy(rest = null)
        if (before != null && workout != null && before.workoutId != workout.workoutId) _rest.value = null
        merge()
    }

    /** A set was just logged: [rest] starts counting. */
    fun startRest(rest: RestTimer) {
        _rest.value = rest
        merge()
    }

    fun skipRest() {
        _rest.value = null
        merge()
    }

    /** Lengthens (or with a negative [seconds], shortens) the rest that is counting; nothing without one. */
    fun adjustRest(seconds: Int) {
        _rest.update { rest -> rest?.copy(endsAtEpochMillis = rest.endsAtEpochMillis + seconds * 1000L, totalSeconds = (rest.totalSeconds + seconds).coerceAtLeast(1)) }
        merge()
    }

    /** A rest that ran out before [cutoffMillis] has said so for long enough, and goes. */
    fun forgetRestOverBefore(cutoffMillis: Long) {
        _rest.update { rest -> rest?.takeIf { it.endsAtEpochMillis > cutoffMillis } }
        merge()
    }

    private fun merge() {
        _workout.value = shown.value?.copy(rest = _rest.value)
    }

    companion object {
        /** The process's own: the one every screen and surface in the app shares. */
        val process = LiveWorkouts()
    }
}
