package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.DayCard
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.PhaseStatus
import com.meticulouscreations.homesafe.fitness.RecordEvent
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.shader.FiberField
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_phase_day
import homesafe.shared.generated.resources.fitness_today_add_button
import homesafe.shared.generated.resources.fitness_today_exercises
import homesafe.shared.generated.resources.fitness_today_headline_due
import homesafe.shared.generated.resources.fitness_today_headline_empty
import homesafe.shared.generated.resources.fitness_today_headline_idle
import homesafe.shared.generated.resources.fitness_today_headline_working
import homesafe.shared.generated.resources.fitness_today_import_body
import homesafe.shared.generated.resources.fitness_today_import_button
import homesafe.shared.generated.resources.fitness_today_import_title
import homesafe.shared.generated.resources.fitness_today_last
import homesafe.shared.generated.resources.fitness_today_lift_line
import homesafe.shared.generated.resources.fitness_today_never
import homesafe.shared.generated.resources.fitness_today_no_records
import homesafe.shared.generated.resources.fitness_today_records
import homesafe.shared.generated.resources.fitness_today_resume
import homesafe.shared.generated.resources.fitness_today_resume_detail
import homesafe.shared.generated.resources.fitness_today_start
import homesafe.shared.generated.resources.fitness_today_start_focus
import homesafe.shared.generated.resources.fitness_today_up_next
import homesafe.shared.generated.resources.fitness_today_week
import homesafe.shared.generated.resources.fitness_today_week_records
import homesafe.shared.generated.resources.fitness_today_week_sessions
import homesafe.shared.generated.resources.fitness_today_week_sets
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The fitness app's front page: what stretch of the year it is, which workout has waited
 * longest, the three days as doors to start one, and the records lately set. With nothing in
 * the log yet it is one invitation: bring the notes in.
 */
@Composable
internal fun TodayScreen(
    state: FitnessUiState,
    padding: PaddingValues,
    onStart: (WorkoutFocus) -> Unit,
    onResume: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenLifts: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val workout = state.workout
    val due = state.days.firstOrNull { it.due }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_today"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PhasePill(state.phase, onOpenProgress)
        Column {
            Text(
                when {
                    workout != null -> stringResource(Res.string.fitness_today_headline_working, stringResource(workout.workout.focus.label))
                    state.isEmpty -> stringResource(Res.string.fitness_today_headline_empty)
                    due != null -> stringResource(Res.string.fitness_today_headline_due, stringResource(due.focus.label))
                    else -> stringResource(Res.string.fitness_today_headline_idle)
                },
                style = type.hero,
                color = colors.text,
            )
            if (due != null && workout == null) {
                Text(
                    due.lastEpochSeconds?.let { stringResource(Res.string.fitness_today_last, FitnessFormat.whenText(it, state.nowEpochSeconds, state.utcOffsetSeconds).resolve()) }
                        ?: stringResource(Res.string.fitness_today_never),
                    style = type.body,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (state.isEmpty) {
            FitnessCard(Modifier.testTag("fitness_today_empty")) {
                Text(stringResource(Res.string.fitness_today_import_title), style = type.title, color = colors.text)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.fitness_today_import_body), style = type.body, color = colors.textMuted)
                Spacer(Modifier.height(16.dp))
                ForgeButton(stringResource(Res.string.fitness_today_import_button), onOpenImport, Modifier.fillMaxWidth().testTag("fitness_today_import"), icon = Icons.AutoMirrored.Filled.NoteAdd)
                Spacer(Modifier.height(10.dp))
                GhostButton(stringResource(Res.string.fitness_today_add_button), onOpenLifts, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
            }
            return@Column
        }

        CardLabel(stringResource(Res.string.fitness_today_week), Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(state.week.sessions.toString(), stringResource(Res.string.fitness_today_week_sessions), Modifier.weight(1f))
            Stat(state.week.sets.toString(), stringResource(Res.string.fitness_today_week_sets), Modifier.weight(1f))
            Stat(state.week.records.toString(), stringResource(Res.string.fitness_today_week_records), Modifier.weight(1f), color = if (state.week.records > 0) colors.gold else colors.text)
        }

        if (workout != null) {
            val tints = colors.focus(workout.workout.focus)
            DoorCard(tints, seed = 0.9f, energy = 1f, animated = animated, height = 132.dp, onClickLabel = stringResource(Res.string.fitness_today_resume), onClick = onResume, modifier = Modifier.testTag("fitness_today_resume")) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.fitness_today_resume), style = type.title, color = Color.White)
                    Text(pluralStringResource(Res.plurals.fitness_today_resume_detail, workout.setCount, workout.setCount), style = type.body, color = Color(0xDDFFFFFF))
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tints.second, modifier = Modifier.size(30.dp))
                }
            }
        } else {
            CardLabel(stringResource(Res.string.fitness_today_start), Modifier.padding(top = 6.dp))
            // The one that has waited longest leads; the other two follow in their usual order.
            val main = state.days.filter { it.focus in WorkoutFocus.MAIN }.sortedByDescending { it.due }
            main.forEachIndexed { index, day -> DayDoor(day, state, seed = 0.17f + index * 0.31f, animated = animated, onStart = onStart) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.days.filter { it.focus !in WorkoutFocus.MAIN }.forEachIndexed { index, day ->
                    DayDoor(day, state, seed = 0.55f + index * 0.27f, animated = animated, onStart = onStart, modifier = Modifier.weight(1f), small = true)
                }
            }
        }

        FitnessCard(Modifier.padding(top = 6.dp), title = stringResource(Res.string.fitness_today_records)) {
            if (state.recentRecords.isEmpty()) {
                Text(stringResource(Res.string.fitness_today_no_records), style = type.body, color = colors.textMuted)
            } else {
                state.recentRecords.take(6).forEachIndexed { index, event ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    RecordRow(event, state, onOpenExercise)
                }
            }
        }
    }
}

/** The phase in force, as a pill: its sign, its name and how long it has run. A tap goes to where it is changed. */
@Composable
internal fun PhasePill(phase: PhaseStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val tint = colors.phase(phase.kind).first
    Row(
        modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("fitness_phase_pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(phaseIcon(phase.kind), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (phase.startedAtEpochSeconds > 0) stringResource(Res.string.fitness_phase_day, stringResource(phase.kind.label), phase.days + 1) else stringResource(phase.kind.label),
            style = FitnessTheme.type.label,
            color = colors.text,
        )
    }
}

internal fun phaseIcon(kind: PhaseKind): ImageVector = when (kind) {
    PhaseKind.BULK -> Icons.Filled.LocalFireDepartment
    PhaseKind.CUT -> Icons.Filled.AcUnit
    PhaseKind.MAINTAIN -> Icons.Filled.Balance
}

/** A workout to walk into: its name over a field of fibres in its colours, when it was last done, and for the one that's due, what it opens on. */
@Composable
private fun DayDoor(day: DayCard, state: FitnessUiState, seed: Float, animated: Boolean, onStart: (WorkoutFocus) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val name = stringResource(day.focus.label)
    DoorCard(
        colors.focus(day.focus),
        seed = seed,
        energy = if (day.due) 0.8f else 0.15f,
        animated = animated,
        height = if (small) {
            96.dp
        } else if (day.due && day.lifts.isNotEmpty()) {
            176.dp
        } else {
            112.dp
        },
        onClickLabel = stringResource(Res.string.fitness_today_start_focus, name),
        onClick = { onStart(day.focus) },
        modifier = modifier.testTag("fitness_day_${day.focus.name.lowercase()}"),
    ) {
        Column(Modifier.weight(1f)) {
            if (day.due) {
                Text(stringResource(Res.string.fitness_today_up_next).uppercase(), style = type.micro, color = Color.White)
                Spacer(Modifier.height(4.dp))
            }
            Text(name, style = if (small) type.title else type.hero, color = Color.White, maxLines = 1)
            Text(
                day.lastEpochSeconds?.let { FitnessFormat.whenText(it, state.nowEpochSeconds, state.utcOffsetSeconds).resolve() }
                    ?: pluralStringResource(Res.plurals.fitness_today_exercises, day.exercises, day.exercises),
                style = type.label,
                color = Color(0xDDFFFFFF),
                maxLines = 1,
            )
            if (day.due && !small) {
                Spacer(Modifier.height(10.dp))
                day.lifts.forEach { lift ->
                    val target = lift.target ?: return@forEach
                    Text(
                        stringResource(Res.string.fitness_today_lift_line, lift.exercise.name, FitnessFormat.set(lift.exercise.loadKind, target.weight, target.reps).resolve()),
                        style = type.label,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (!small) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/** A card whose background is muscle fibre in [tints], quickening under a finger. */
@Composable
private fun DoorCard(
    tints: Pair<Color, Color>,
    seed: Float,
    energy: Float,
    animated: Boolean,
    height: Dp,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(FitnessCardShape)
            .border(1.dp, Color(0x33FFFFFF), FitnessCardShape)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
    ) {
        FiberField(tints.first, tints.second, seed, Modifier.matchParentSize(), energy = if (pressed) 1f else energy, running = animated)
        // Shade under the words, so white reads on the brightest strand.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0x99000000), Color(0x33000000), Color.Transparent))))
        Row(Modifier.matchParentSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun RecordRow(event: RecordEvent, state: FitnessUiState, onOpenExercise: (String) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val tint = if (event.record.scope == RecordScope.ALL_TIME) colors.gold else colors.phase(state.phase.kind).first
    Row(
        Modifier.fillMaxWidth().clip(FitnessCardShape).clickable(role = Role.Button) { onOpenExercise(event.exercise.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.exercise.name, style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(FitnessFormat.record(event.record, state.phase.kind)), style = type.label, color = tint, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(FitnessFormat.set(event.exercise.loadKind, event.set.weight, event.set.reps).resolve(), style = type.bodyStrong, color = colors.text, maxLines = 1)
            Text(FitnessFormat.whenText(event.set.epochSeconds, state.nowEpochSeconds, state.utcOffsetSeconds).resolve(), style = type.label, color = colors.textFaint, maxLines = 1)
        }
    }
}
