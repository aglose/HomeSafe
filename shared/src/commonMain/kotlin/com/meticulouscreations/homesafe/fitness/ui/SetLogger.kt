package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_logger_added
import homesafe.shared.generated.resources.fitness_logger_aim
import homesafe.shared.generated.resources.fitness_logger_at_weight_best
import homesafe.shared.generated.resources.fitness_logger_at_weight_new
import homesafe.shared.generated.resources.fitness_logger_at_weight_phase
import homesafe.shared.generated.resources.fitness_logger_delete_set
import homesafe.shared.generated.resources.fitness_logger_keypad_backspace
import homesafe.shared.generated.resources.fitness_logger_keypad_done
import homesafe.shared.generated.resources.fitness_logger_less_reps
import homesafe.shared.generated.resources.fitness_logger_less_weight
import homesafe.shared.generated.resources.fitness_logger_log
import homesafe.shared.generated.resources.fitness_logger_log_another
import homesafe.shared.generated.resources.fitness_logger_more_reps
import homesafe.shared.generated.resources.fitness_logger_more_weight
import homesafe.shared.generated.resources.fitness_logger_pin
import homesafe.shared.generated.resources.fitness_logger_reps
import homesafe.shared.generated.resources.fitness_logger_rung
import homesafe.shared.generated.resources.fitness_logger_set_record
import homesafe.shared.generated.resources.fitness_logger_step
import homesafe.shared.generated.resources.fitness_logger_weight
import homesafe.shared.generated.resources.fitness_logger_weight_each
import org.jetbrains.compose.resources.stringResource

/**
 * Where a set is written down: the weight and the reps, each a big number between two buttons,
 * already set to what to aim for, and one button to log it. Built to be used one-handed with a
 * minute on the clock:
 *
 * - it opens on the target, so matching the plan is one tap;
 * - the weights on the exercise's ladder are a row of chips, each with the reps to beat there;
 *   a tap loads that rung with one more rep than its best;
 * - the buttons step the weight by the exercise's usual jump and hold to run, and a tap on
 *   either number brings up a keypad for anything else;
 * - after a set is logged the numbers stay, so a second set is one tap again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetLogger(
    board: ExerciseBoard,
    todaysSets: List<LoggedSet>,
    records: Map<Long, Record>,
    phase: PhaseKind,
    colors: Pair<Color, Color>,
    onLog: (weight: Double, reps: Int) -> Unit,
    onDeleteSet: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = board.exercise
    val kind = exercise.loadKind
    val target = board.target
    val startWeight = todaysSets.lastOrNull()?.weight ?: target?.weight ?: board.last?.weight ?: board.ladder.lastOrNull()?.weight ?: 0.0
    val startReps = todaysSets.lastOrNull()?.reps ?: target?.reps ?: board.last?.reps ?: exercise.repLow
    var weight by rememberSaveable(exercise.id) { mutableDoubleStateOf(startWeight) }
    var reps by rememberSaveable(exercise.id) { mutableIntStateOf(startReps) }
    var editing by rememberSaveable(exercise.id) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(exercise.id) { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val rung = board.ladder.firstOrNull { it.weight == weight }

    Column(modifier.fillMaxWidth().testTag("fitness_logger")) {
        if (target != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.gold.copy(alpha = 0.10f))
                    .clickable(role = Role.Button) {
                        weight = target.weight
                        reps = target.reps
                        editing = null
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("fitness_logger_target"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Flag, contentDescription = null, tint = palette.gold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.fitness_logger_aim, FitnessFormat.set(kind, target.weight, target.reps).resolve()),
                        style = type.bodyStrong,
                        color = palette.gold,
                    )
                    Text(FitnessFormat.reason(target).resolve(), style = type.label, color = palette.textMuted)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberStepper(
                label = stringResource(
                    when (kind) {
                        LoadKind.LEVEL -> Res.string.fitness_logger_pin
                        LoadKind.BODYWEIGHT -> Res.string.fitness_logger_added
                        LoadKind.PER_HAND -> Res.string.fitness_logger_weight_each
                        else -> Res.string.fitness_logger_weight
                    },
                ),
                value = if (editing == FIELD_WEIGHT) draft.ifEmpty { "0" } else FitnessFormat.number(weight),
                order = weight,
                onStep = { direction ->
                    editing = null
                    weight = (weight + direction * exercise.increment).coerceAtLeast(0.0)
                },
                minusLabel = stringResource(Res.string.fitness_logger_less_weight),
                plusLabel = stringResource(Res.string.fitness_logger_more_weight),
                modifier = Modifier.weight(1f).testTag("fitness_logger_weight"),
                tint = colors.first,
                caption = if (kind == LoadKind.PLATES) FitnessFormat.weight(kind, weight).resolve() else stringResource(Res.string.fitness_logger_step, FitnessFormat.step(exercise.increment)),
                onEdit = {
                    draft = ""
                    editing = FIELD_WEIGHT
                },
            )
            NumberStepper(
                label = stringResource(Res.string.fitness_logger_reps),
                value = if (editing == FIELD_REPS) draft.ifEmpty { "0" } else reps.toString(),
                order = reps.toDouble(),
                onStep = { direction ->
                    editing = null
                    reps = (reps + direction).coerceIn(1, 99)
                },
                minusLabel = stringResource(Res.string.fitness_logger_less_reps),
                plusLabel = stringResource(Res.string.fitness_logger_more_reps),
                modifier = Modifier.weight(1f).testTag("fitness_logger_reps"),
                tint = colors.first,
                caption = when {
                    rung == null -> stringResource(Res.string.fitness_logger_at_weight_new)
                    rung.phaseReps != null && rung.phaseReps < rung.reps -> stringResource(Res.string.fitness_logger_at_weight_phase, rung.reps, rung.phaseReps)
                    else -> stringResource(Res.string.fitness_logger_at_weight_best, rung.reps)
                },
                onEdit = {
                    draft = ""
                    editing = FIELD_REPS
                },
            )
        }

        AnimatedVisibility(editing != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            NumberPad(
                allowDecimal = editing == FIELD_WEIGHT,
                onKey = { key ->
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    draft = when {
                        key == KEY_BACKSPACE -> draft.dropLast(1)
                        key == "." && draft.contains('.') -> draft
                        draft.length >= 6 -> draft
                        else -> draft + key
                    }
                    if (editing == FIELD_WEIGHT) draft.toDoubleOrNull()?.let { weight = it } else draft.toIntOrNull()?.let { reps = it.coerceIn(1, 99) }
                },
                onDone = { editing = null },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (board.ladder.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Heaviest first: the top of the ladder is where the work is.
                board.ladder.asReversed().take(8).forEach { step ->
                    val selected = step.weight == weight
                    val label = stringResource(Res.string.fitness_logger_rung, FitnessFormat.short(step.weight), step.reps)
                    Box(
                        Modifier
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.first.copy(alpha = 0.24f) else palette.surfaceRaised)
                            .border(1.dp, if (selected) colors.first else palette.hairline, CircleShape)
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                editing = null
                                weight = step.weight
                                // One more than has ever been done there: the rung's whole point.
                                reps = (step.reps + 1).coerceIn(1, 99)
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = type.label, color = if (selected) palette.text else palette.textMuted, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ForgeButton(
            text = stringResource(if (todaysSets.isEmpty()) Res.string.fitness_logger_log else Res.string.fitness_logger_log_another),
            onClick = {
                editing = null
                onLog(weight, reps)
            },
            modifier = Modifier.fillMaxWidth().testTag("fitness_logger_log"),
            colors = colors,
            icon = Icons.Filled.Check,
            enabled = reps > 0,
            height = 60.dp,
        )

        if (todaysSets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                todaysSets.forEach { set ->
                    val record = records[set.id]
                    val line = FitnessFormat.set(kind, set.weight, set.reps).resolve()
                    val tint = when (record?.scope) {
                        RecordScope.ALL_TIME -> palette.gold
                        RecordScope.PHASE -> palette.phase(phase).first
                        null -> palette.textMuted
                    }
                    val description = if (record != null) stringResource(Res.string.fitness_logger_set_record, line, stringResource(FitnessFormat.record(record, phase))) else line
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.12f))
                            .border(1.dp, tint.copy(alpha = 0.4f), CircleShape)
                            .padding(start = 12.dp, end = 4.dp)
                            .semantics(mergeDescendants = true) { contentDescription = description },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (record != null) {
                            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(line, style = type.label, color = palette.text, maxLines = 1)
                        RoundIconButton(Icons.Filled.Close, stringResource(Res.string.fitness_logger_delete_set), { onDeleteSet(set.id) }, size = 30.dp, tint = palette.textFaint)
                    }
                }
            }
        }
    }
}

private const val FIELD_WEIGHT = "weight"
private const val FIELD_REPS = "reps"
private const val KEY_BACKSPACE = "back"

/** A keypad of its own, so the system keyboard never slides up over the logger: digits, a point for a half-plate, and done. */
@Composable
private fun NumberPad(allowDecimal: Boolean, onKey: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val palette = FitnessTheme.colors
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (allowDecimal) "." else "", "0", KEY_BACKSPACE))
    Column(modifier.fillMaxWidth().testTag("fitness_keypad"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val backspace = stringResource(Res.string.fitness_logger_keypad_backspace)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (key.isEmpty()) Color.Transparent else palette.surfaceRaised)
                            .then(if (key.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) { onKey(key) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "" -> Unit
                            KEY_BACKSPACE -> Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = backspace, tint = palette.textMuted, modifier = Modifier.size(20.dp))
                            else -> Text(key, style = FitnessTheme.type.title, color = palette.text)
                        }
                    }
                }
            }
        }
        GhostButton(stringResource(Res.string.fitness_logger_keypad_done), onDone, Modifier.fillMaxWidth(), icon = Icons.Filled.Check)
    }
}
