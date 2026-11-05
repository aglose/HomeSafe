package com.meticulouscreations.homesafe.fitness.live

import com.meticulouscreations.homesafe.text.TextLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * One of the places outside the app's own screen that a workout in progress is shown: Android's
 * ongoing notification, the iPhone's Live Activity, the Mac's island. Each is handed the whole
 * picture whenever anything in it changes, and draws what changes with time by itself (the
 * stopwatch, the rest counting down, the rest running out), so nothing has to wake it every
 * second.
 */
interface LiveWorkoutSurface {
    /**
     * Whether, while it shows a workout, the surface keeps the app running with its screen away
     * (Android's foreground service does): the heart-rate sensor can then stay linked through the
     * workout instead of only while the app is in front.
     */
    val keepsAppRunning: Boolean get() = false

    fun show(workout: LiveWorkout, words: LiveWorkoutWords)

    /** The workout is over (finished, thrown away, or left open so long it no longer counts): whatever was shown goes. */
    fun end()
}

/**
 * Hands the hub's workout ([LiveWorkouts.workout]) to the platform's [LiveWorkoutSurface] for as
 * long as the process lives, in words loaded from the resources. Installed once at start-up by
 * each platform's entry point ([install]).
 *
 * It also does what would otherwise need a screen: a rest that ran out a while ago is let go of
 * ([REST_LINGER_MILLIS], as the workout's page does), and a workout left open past the point it
 * counts as in progress is taken off the surface.
 */
class LiveWorkoutPresenter(
    private val hub: LiveWorkouts,
    private val surface: LiveWorkoutSurface,
    private val clock: Clock = Clock.System,
    private val text: TextLoader = TextLoader.Resources,
) {
    private var labels: LiveWorkoutLabels? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        launch { forgetRestsThatRanOut() }
        var showing = false
        hub.workout.collectLatest { workout ->
            if (workout == null) {
                if (showing) surface.end()
                showing = false
                return@collectLatest
            }
            val words = LiveWorkoutWords.load(LiveWorkoutLines.of(workout), labels ?: LiveWorkoutLabels.load(text).also { labels = it }, text)
            // Asked each time: on Android it turns on as soon as a permission it needs is given.
            hub.keepsAppRunning = surface.keepsAppRunning
            surface.show(workout, words)
            showing = true
            // Left open this long, it stops being the workout in progress; the app closes it the next time it reads the log.
            val stale = workout.staleAtEpochMillis - clock.now().toEpochMilliseconds()
            delay(stale.coerceAtLeast(0))
            surface.end()
            showing = false
        }
    }

    private suspend fun forgetRestsThatRanOut() {
        hub.rest.map { it?.endsAtEpochMillis }.distinctUntilChanged().collectLatest { endsAt ->
            if (endsAt == null) return@collectLatest
            delay((endsAt + REST_LINGER_MILLIS - clock.now().toEpochMilliseconds()).coerceAtLeast(0))
            hub.forgetRestOverBefore(clock.now().toEpochMilliseconds() - REST_LINGER_MILLIS)
        }
    }

    companion object {
        /** How long a rest that ran out goes on saying so, as the workout's own page does. */
        const val REST_LINGER_MILLIS = 5 * 60_000L

        private var installed: LiveWorkoutSurface? = null

        /** The surface installed for this process, if any: whether it keeps the app running is asked of it. */
        val surface: LiveWorkoutSurface? get() = installed

        /**
         * Shows the process's workout on [surface] from now on. Once a process: a second call (an
         * entry point run again) changes nothing.
         */
        fun install(surface: LiveWorkoutSurface, hub: LiveWorkouts = LiveWorkouts.process) {
            if (installed != null) return
            installed = surface
            LiveWorkoutPresenter(hub, surface).start(CoroutineScope(SupervisorJob() + Dispatchers.Main))
        }
    }
}
