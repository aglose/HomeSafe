package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.fitness.domain.GpsSignal
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RunDetail
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_history_none
import homesafe.shared.generated.resources.fitness_run_discard
import homesafe.shared.generated.resources.fitness_run_discard_confirm
import homesafe.shared.generated.resources.fitness_run_finish
import homesafe.shared.generated.resources.fitness_run_location_refused
import homesafe.shared.generated.resources.fitness_run_open_recording
import homesafe.shared.generated.resources.fitness_run_pause
import homesafe.shared.generated.resources.fitness_run_recording_banner
import homesafe.shared.generated.resources.fitness_run_recording_paused
import homesafe.shared.generated.resources.fitness_run_resume
import homesafe.shared.generated.resources.fitness_run_route
import homesafe.shared.generated.resources.fitness_run_row_timed
import homesafe.shared.generated.resources.fitness_run_signal_good
import homesafe.shared.generated.resources.fitness_run_signal_lost
import homesafe.shared.generated.resources.fitness_run_signal_searching
import homesafe.shared.generated.resources.fitness_run_start
import homesafe.shared.generated.resources.fitness_run_stat_distance
import homesafe.shared.generated.resources.fitness_run_stat_pace_average
import homesafe.shared.generated.resources.fitness_run_stat_pace_now
import homesafe.shared.generated.resources.fitness_run_stat_time
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * The clock the run screens tick by, a second at a time. In a preview it stands still at
 * [stillAt], so that what is drawn is the same each time.
 */
@Composable
private fun rememberTicking(stillAt: Long): Long {
    val still = LocalInspectionMode.current
    var now by remember { mutableLongStateOf(if (still) stillAt else Clock.System.now().toEpochMilliseconds()) }
    if (!still) {
        LaunchedEffect(Unit) {
            while (true) {
                now = Clock.System.now().toEpochMilliseconds()
                delay(1_000 - now % 1_000)
            }
        }
    }
    return now
}

/**
 * The way to record a run, at the head of the runs page: a button to start one, or the run being
 * recorded (how far, how long) to go back to. [refused] says the location was refused last time.
 */
@Composable
internal fun RecordRunCard(recording: Recording?, refused: Boolean, onStart: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    if (recording == null) {
        Column(modifier) {
            ForgeButton(stringResource(Res.string.fitness_run_start), onStart, Modifier.fillMaxWidth().testTag("fitness_run_start"), colors = colors.run, icon = Icons.AutoMirrored.Filled.DirectionsRun)
            if (refused) {
                Text(stringResource(Res.string.fitness_run_location_refused), style = type.label, color = colors.textMuted, modifier = Modifier.padding(top = 8.dp).testTag("fitness_run_location_refused"))
            }
        }
        return
    }
    val now = rememberTicking(recording.asOfMillis)
    Row(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(Brush.horizontalGradient(listOf(colors.run.second.copy(alpha = 0.35f), colors.surface)))
            .border(1.dp, colors.run.first.copy(alpha = 0.6f), FitnessCardShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_run_open_recording), onClick = onOpen)
            .testTag("fitness_run_recording")
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (recording.paused) colors.textFaint else colors.run.first))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (recording.paused) Res.string.fitness_run_recording_paused else Res.string.fitness_run_recording_banner),
                style = type.headline,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(Res.string.fitness_run_row_timed, RunFormat.distance(recording.meters).resolve(), RunFormat.clock(recording.movingMillisAt(now) / 1_000)),
                style = type.bodyStrong,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(22.dp))
    }
}

/**
 * A run being recorded: how long it has been moving, large, as a stopwatch; how far and how fast
 * under it; the route so far; and pausing, going on and finishing. Throwing it away takes a
 * second tap. [recording] is null for the moment between finishing and the page going.
 */
@Composable
internal fun RecordingScreen(
    recording: Recording?,
    padding: PaddingValues,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recording == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val now = rememberTicking(recording.asOfMillis)
    val none = stringResource(Res.string.fitness_history_none)
    var discarding by rememberSaveable(recording.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_recording"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SignalLine(recording)

        Text(
            RunFormat.clock(recording.movingMillisAt(now) / 1_000),
            style = TextStyle(fontFamily = type.hero.fontFamily, fontWeight = type.hero.fontWeight, fontSize = 72.sp, lineHeight = 80.sp, fontFeatureSettings = type.hero.fontFeatureSettings),
            color = if (recording.paused) colors.textMuted else colors.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("fitness_recording_time"),
        )

        FitnessCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordingStat(RunFormat.distance(recording.meters).resolve(), stringResource(Res.string.fitness_run_stat_distance), Modifier.weight(1f), colors.run.first)
                RecordingStat(recording.paceSeconds?.let { RunFormat.pace(it).resolve() } ?: none, stringResource(Res.string.fitness_run_stat_pace_now), Modifier.weight(1f))
                RecordingStat(RunFormat.pace(recording.averagePaceSeconds(now)).resolve(), stringResource(Res.string.fitness_run_stat_pace_average), Modifier.weight(1f))
            }
        }

        if (recording.route.size > 1) {
            FitnessCard(Modifier.testTag("fitness_recording_route"), title = stringResource(Res.string.fitness_run_route)) {
                RouteSketch(RunDetail(runId = recording.id, route = recording.route), Modifier.fillMaxWidth().height(220.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recording.paused) {
                ForgeButton(stringResource(Res.string.fitness_run_resume), onResume, Modifier.weight(1f).testTag("fitness_recording_resume"), colors = colors.run, icon = Icons.Filled.PlayArrow)
            } else {
                GhostButton(stringResource(Res.string.fitness_run_pause), onPause, Modifier.weight(1f).testTag("fitness_recording_pause"), icon = Icons.Filled.Pause)
            }
            GhostButton(stringResource(Res.string.fitness_run_finish), onFinish, Modifier.weight(1f).testTag("fitness_recording_finish"), icon = Icons.Filled.Flag, tint = colors.run.first)
        }
        // Only once it is paused: nothing to throw away by mistake mid-stride.
        if (recording.paused) {
            GhostButton(
                stringResource(if (discarding) Res.string.fitness_run_discard_confirm else Res.string.fitness_run_discard),
                { if (discarding) onDiscard() else discarding = true },
                Modifier.fillMaxWidth().testTag("fitness_recording_discard"),
                icon = Icons.Filled.Delete,
                tint = colors.danger,
            )
        }
    }
}

/** Whether the GPS is there, as a dot and a word: the run's colour while it is, quieter while it is paused or still looking. */
@Composable
private fun SignalLine(recording: Recording, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val (text, dot) = when {
        recording.paused -> Res.string.fitness_run_recording_paused to colors.textFaint
        recording.signal == GpsSignal.SEARCHING -> Res.string.fitness_run_signal_searching to colors.amber
        recording.signal == GpsSignal.LOST -> Res.string.fitness_run_signal_lost to colors.danger
        else -> Res.string.fitness_run_signal_good to colors.run.first
    }
    Row(modifier.testTag("fitness_recording_signal"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(text), style = FitnessTheme.type.label, color = colors.textMuted)
    }
}

/** One of the run's numbers under the stopwatch. */
@Composable
private fun RecordingStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = FitnessTheme.colors.text) {
    Column(modifier) {
        Text(value, style = FitnessTheme.type.title, color = color, maxLines = 1, softWrap = false)
        Text(label, style = FitnessTheme.type.label, color = FitnessTheme.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
