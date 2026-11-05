package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.live.LiveHeart
import com.meticulouscreations.homesafe.fitness.live.LiveLift
import com.meticulouscreations.homesafe.fitness.live.LiveSet
import com.meticulouscreations.homesafe.fitness.live.LiveWorkout

private const val PREVIEW_NOW = 1_790_000_000_000L

/** A chest day 24 minutes in, six sets logged, as the previews show it in each phase. Made-up numbers. */
private fun previewWorkout(rest: RestTimer? = null, heart: LiveHeart? = null, focus: WorkoutFocus = WorkoutFocus.CHEST) = LiveWorkout(
    workoutId = 7,
    focus = focus,
    startedAtEpochMillis = PREVIEW_NOW - 24 * 60_000L - 7_000L,
    sets = 6,
    records = 2,
    exercisesDone = 3,
    lift = LiveLift("chest/bench_press", "Bench press", LoadKind.WEIGHT, setsToday = 2, last = LiveSet(185.0, 8), target = LiveSet(190.0, 8)),
    next = LiveLift("chest/incline_press", "Incline press", LoadKind.PER_HAND, target = LiveSet(70.0, 10)),
    rest = rest,
    heart = heart,
)

private val RESTING = RestTimer(endsAtEpochMillis = PREVIEW_NOW + 72_000L, totalSeconds = 120, exerciseId = "chest/bench_press")
private val RAN_OUT = RestTimer(endsAtEpochMillis = PREVIEW_NOW - 4_000L, totalSeconds = 120, exerciseId = "chest/bench_press")

@Composable
private fun IslandStack(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF2B3140)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Preview(widthDp = 384, heightDp = 420)
@Composable
private fun LiveWorkoutIslandSmallPreview() = IslandStack {
    LiveWorkoutIsland(previewWorkout(), expanded = false, onOpen = {}, onSkipRest = {}, onAdjustRest = {}, onHide = {}, nowMillis = { PREVIEW_NOW })
    LiveWorkoutIsland(previewWorkout(RESTING), expanded = false, onOpen = {}, onSkipRest = {}, onAdjustRest = {}, onHide = {}, nowMillis = { PREVIEW_NOW })
    LiveWorkoutIsland(previewWorkout(RAN_OUT), expanded = false, onOpen = {}, onSkipRest = {}, onAdjustRest = {}, onHide = {}, nowMillis = { PREVIEW_NOW })
    LiveWorkoutIsland(previewWorkout(RESTING, focus = WorkoutFocus.BACK), expanded = false, onOpen = {}, onSkipRest = {}, onAdjustRest = {}, onHide = {}, nowMillis = { PREVIEW_NOW })
    LiveWorkoutIsland(previewWorkout(RESTING, focus = WorkoutFocus.LEGS), expanded = false, onOpen = {}, onSkipRest = {}, onAdjustRest = {}, onHide = {}, nowMillis = { PREVIEW_NOW })
}

@Preview(widthDp = 384, heightDp = 420)
@Preview(name = "Opened out, in Spanish", widthDp = 384, heightDp = 420, locale = "es")
@Composable
private fun LiveWorkoutIslandOpenPreview() = IslandStack {
    LiveWorkoutIsland(
        previewWorkout(RESTING, heart = LiveHeart(128, HeartZone.LIGHT, peakBpm = 152)),
        expanded = true,
        onOpen = {},
        onSkipRest = {},
        onAdjustRest = {},
        onHide = {},
        nowMillis = { PREVIEW_NOW },
    )
    LiveWorkoutIsland(
        previewWorkout(heart = LiveHeart(141, HeartZone.MODERATE)),
        expanded = true,
        onOpen = {},
        onSkipRest = {},
        onAdjustRest = {},
        onHide = {},
        nowMillis = { PREVIEW_NOW },
    )
}
