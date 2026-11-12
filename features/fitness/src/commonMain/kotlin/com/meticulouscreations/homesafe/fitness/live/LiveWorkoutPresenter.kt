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
 * counts as in progress is taken off the surface, as is one left untouched for [IDLE_MILLIS]
 * where the surface keeps the app running for it.
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
        // The workout as last handed over, but for its heart, and when it last differed: a set logged, a rest begun or
        // ended, the app brought in front or left. The heart alone, read every second, is nobody doing anything.
        var picture: LiveWorkout? = null
        var activeAt = 0L
        fun end() {
            if (showing) surface.end()
            showing = false
            // Nothing keeps the app running for a workout that isn't shown: the heart sensor's link is let go of with it.
            hub.keepsAppRunning = false
        }
        hub.workout.collectLatest { workout ->
            if (workout == null) {
                picture = null
                end()
                return@collectLatest
            }
            val now = clock.now().toEpochMilliseconds()
            val keeps = surface.keepsAppRunning
            val untimed = workout.copy(heart = null)
            if (untimed != picture) {
                picture = untimed
                activeAt = now
            }
            // Left open this long, it stops being the workout in progress; the app closes it the next time it reads the log.
            // And where showing it keeps the app running, with the phone's radios and this process awake for it, a workout
            // nobody has touched for [IDLE_MILLIS] is taken off sooner. It is still open, and comes back with the app.
            val endAt = if (keeps && !workout.onScreen) minOf(workout.staleAtEpochMillis, activeAt + IDLE_MILLIS) else workout.staleAtEpochMillis
            if (now >= endAt) {
                end()
                return@collectLatest
            }
            val words = LiveWorkoutWords.load(LiveWorkoutLines.of(workout), labels ?: LiveWorkoutLabels.load(text).also { labels = it }, text)
            // Asked each time: on Android it turns on as soon as a permission it needs is given.
            hub.keepsAppRunning = keeps
            surface.show(workout, words)
            showing = true
            delay(endAt - now)
            end()
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

        /**
         * How long a workout nobody has touched (no set, no rest, the app not in front) is still shown where showing it
         * keeps the app running. Long enough for a stretch on a treadmill between lifts; short of the eight hours a
         * workout forgotten in a pocket would otherwise hold the phone for.
         */
        const val IDLE_MILLIS = 60 * 60_000L

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
