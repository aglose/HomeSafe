package com.meticulouscreations.homesafe.desktop

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.meticulouscreations.homesafe.desktop.DesktopNotice
import com.meticulouscreations.homesafe.desktop.DesktopNotifications
import com.meticulouscreations.homesafe.fitness.live.LivePhase
import com.meticulouscreations.homesafe.fitness.live.LiveWorkout
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutSurface
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutWords
import com.meticulouscreations.homesafe.fitness.live.LiveWorkouts
import com.meticulouscreations.homesafe.fitness.ui.LiveWorkoutIsland
import com.meticulouscreations.homesafe.fitness.ui.LiveWorkoutIslandWidth
import com.meticulouscreations.homesafe.navigation.FitnessDeepLink
import com.meticulouscreations.homesafe.navigation.FitnessDeepLinks
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_live_island
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment

/**
 * The workout in progress on the Mac, beyond the island and the menu-bar item (which read the
 * process's workout themselves, [LiveWorkouts.workout]): the moment a rest runs out with the app's
 * window away, a notification says so in Notification Center, and a click on it opens the
 * workout. With the window in front the workout's own page says it.
 */
object DesktopLiveWorkoutSurface : LiveWorkoutSurface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var waiting: Job? = null
    private var waitingFor: Long? = null

    /** Whether the app's window is the one in front; kept by the window itself. */
    val windowFocused = MutableStateFlow(true)

    override fun show(workout: LiveWorkout, words: LiveWorkoutWords) {
        val endsAt = workout.rest?.endsAtEpochMillis
        if (endsAt == waitingFor) return
        waiting?.cancel()
        waitingFor = endsAt
        if (endsAt == null) return
        waiting = scope.launch {
            delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            val now = LiveWorkouts.process.workout.value ?: return@launch
            if (now.phase(System.currentTimeMillis()) != LivePhase.REST_OVER || windowFocused.value) return@launch
            DesktopNotifications.post(DesktopNotice(words.restOver, words.detail(LivePhase.REST_OVER), urgent = false, link = FitnessDeepLink.Workout.toUri()))
        }
    }

    override fun end() {
        waiting?.cancel()
        waiting = null
        waitingFor = null
    }
}

/**
 * The workout in progress as an island at the top of the screen, under the menu bar where a
 * MacBook's camera notch is: a small dark pill with the rest counting down (or the workout's time)
 * that opens out under the pointer into the rest's buttons, as the iPhone's Dynamic Island does.
 * It floats over every app, and is there only while the app's own window is away (in the
 * background, minimised or closed): in front, the workout's page says all of it. A click opens
 * that page; "hide" puts the island away until the next rest begins.
 */
// A window of its own, not an element laid out in a parent, so there is nothing for a Modifier to do.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun ApplicationScope.WorkoutIsland(onOpen: () -> Unit) {
    val workout by LiveWorkouts.process.workout.collectAsState()
    val focused by DesktopLiveWorkoutSurface.windowFocused.collectAsState()
    // Put away by hand, until the next rest begins or another workout does.
    var hiddenAt by remember { mutableStateOf<Pair<Long, Long?>?>(null) }
    val current = workout
    LaunchedEffect(current?.workoutId, current?.rest?.endsAtEpochMillis) {
        val hidden = hiddenAt ?: return@LaunchedEffect
        val rest = current?.rest?.endsAtEpochMillis
        if (current == null || hidden.first != current.workoutId || (rest != null && rest != hidden.second)) hiddenAt = null
    }
    if (current == null || focused || hiddenAt != null) return

    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val size = DpSize(LiveWorkoutIslandWidth + MARGIN * 2, if (hovered) EXPANDED_HEIGHT else COMPACT_HEIGHT)
    val state = rememberWindowState(position = topCentre(size), size = size)
    LaunchedEffect(size) { state.size = size }

    Window(
        onCloseRequest = { hiddenAt = current.workoutId to current.rest?.endsAtEpochMillis },
        state = state,
        title = stringResource(Res.string.fitness_live_island),
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        Box(Modifier.fillMaxWidth().padding(MARGIN).hoverable(hover), contentAlignment = Alignment.TopCenter) {
            LiveWorkoutIsland(
                workout = current,
                expanded = hovered,
                onOpen = {
                    onOpen()
                    FitnessDeepLinks.open(FitnessDeepLink.Workout)
                },
                onSkipRest = LiveWorkouts.process::skipRest,
                onAdjustRest = LiveWorkouts.process::adjustRest,
                onHide = { hiddenAt = current.workoutId to current.rest?.endsAtEpochMillis },
            )
        }
    }
}

/** Centred under the menu bar of the main screen. */
private fun topCentre(size: DpSize): WindowPosition {
    val usable = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull() ?: return WindowPosition(Alignment.TopCenter)
    return WindowPosition(((usable.x + (usable.width - size.width.value) / 2)).dp, (usable.y + 6).dp)
}

private val MARGIN = 6.dp
private val COMPACT_HEIGHT = 68.dp
private val EXPANDED_HEIGHT = 170.dp
