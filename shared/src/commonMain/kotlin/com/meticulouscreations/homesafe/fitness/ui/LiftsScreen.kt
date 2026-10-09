package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.ChartShader
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.LineSharpness
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.LocalChartStyle
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.ChartPeriod
import com.meticulouscreations.homesafe.finance.ui.components.ChartRule
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.ExerciseClassifier
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Strength
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_edit_delete
import homesafe.shared.generated.resources.fitness_edit_delete_confirm
import homesafe.shared.generated.resources.fitness_edit_load
import homesafe.shared.generated.resources.fitness_edit_muscle
import homesafe.shared.generated.resources.fitness_edit_name
import homesafe.shared.generated.resources.fitness_edit_note
import homesafe.shared.generated.resources.fitness_edit_note_hint
import homesafe.shared.generated.resources.fitness_edit_reps
import homesafe.shared.generated.resources.fitness_edit_reps_band
import homesafe.shared.generated.resources.fitness_edit_rest
import homesafe.shared.generated.resources.fitness_edit_save
import homesafe.shared.generated.resources.fitness_edit_shelf
import homesafe.shared.generated.resources.fitness_edit_step
import homesafe.shared.generated.resources.fitness_exercise_best
import homesafe.shared.generated.resources.fitness_exercise_chart
import homesafe.shared.generated.resources.fitness_exercise_chart_caption
import homesafe.shared.generated.resources.fitness_exercise_chart_described
import homesafe.shared.generated.resources.fitness_exercise_edit
import homesafe.shared.generated.resources.fitness_exercise_kind
import homesafe.shared.generated.resources.fitness_exercise_ladder
import homesafe.shared.generated.resources.fitness_exercise_ladder_caption
import homesafe.shared.generated.resources.fitness_exercise_ladder_described
import homesafe.shared.generated.resources.fitness_exercise_log
import homesafe.shared.generated.resources.fitness_exercise_none
import homesafe.shared.generated.resources.fitness_exercise_phase_best
import homesafe.shared.generated.resources.fitness_exercise_sessions
import homesafe.shared.generated.resources.fitness_exercise_standing
import homesafe.shared.generated.resources.fitness_exercise_target
import homesafe.shared.generated.resources.fitness_lifts_all
import homesafe.shared.generated.resources.fitness_lifts_empty
import homesafe.shared.generated.resources.fitness_lifts_import
import homesafe.shared.generated.resources.fitness_lifts_of_best
import homesafe.shared.generated.resources.fitness_lifts_send_copy
import homesafe.shared.generated.resources.fitness_lifts_untrained
import homesafe.shared.generated.resources.fitness_today_exercises
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Every exercise in the log, shelf by shelf: its best set, and where it stands against that
 * now. A new one is added at the foot, where the notes can be brought in (again) and, where
 * the platform has somewhere to send it ([onSendCopy]), a copy of the whole log sent out.
 */
@Composable
internal fun LiftsScreen(
    state: FitnessUiState,
    padding: PaddingValues,
    onOpenExercise: (String) -> Unit,
    onOpenImport: () -> Unit,
    onAddExercise: (String, BodyPart) -> Unit,
    modifier: Modifier = Modifier,
    onSendCopy: (() -> Unit)? = null,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    var shelf by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(state.boards, shelf) { state.boards.filter { shelf == null || it.exercise.bodyPart.name == shelf } }
    LazyColumn(
        modifier.fillMaxSize().testTag("fitness_lifts"),
        contentPadding = PaddingValues(start = FitnessGutter, end = FitnessGutter, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(stringResource(Res.string.fitness_lifts_all), shelf == null, { shelf = null })
                BodyPart.entries.forEach { part ->
                    ChoiceChip(stringResource(part.label), shelf == part.name, { shelf = part.name }, Modifier.testTag("fitness_lifts_${part.name.lowercase()}"))
                }
            }
        }
        if (state.isEmpty) {
            item(key = "empty") { Text(stringResource(Res.string.fitness_lifts_empty), style = type.body, color = colors.textMuted, modifier = Modifier.padding(vertical = 8.dp)) }
        }
        var lastPart: BodyPart? = null
        shown.forEach { board ->
            if (board.exercise.bodyPart != lastPart) {
                val part = board.exercise.bodyPart
                lastPart = part
                item(key = "head_${part.name}") {
                    val count = shown.count { it.exercise.bodyPart == part }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(part.label), style = type.title, color = colors.text, modifier = Modifier.weight(1f))
                        Text(pluralStringResource(Res.plurals.fitness_today_exercises, count, count), style = type.label, color = colors.textFaint)
                    }
                }
            }
            item(key = board.exercise.id) { LiftRow(board, onOpenExercise) }
        }
        item(key = "add") {
            Spacer(Modifier.height(8.dp))
            AddExerciseCard(shelf?.let { name -> BodyPart.entries.filter { it.name == name } } ?: BodyPart.entries, onAddExercise, colors.ember)
        }
        item(key = "import") {
            GhostButton(stringResource(Res.string.fitness_lifts_import), onOpenImport, Modifier.fillMaxWidth().testTag("fitness_lifts_import"), icon = Icons.AutoMirrored.Filled.NoteAdd, tint = colors.textMuted)
        }
        if (onSendCopy != null && !state.isEmpty) {
            item(key = "send_copy") {
                GhostButton(stringResource(Res.string.fitness_lifts_send_copy), onSendCopy, Modifier.fillMaxWidth().testTag("fitness_lifts_send_copy"), icon = Icons.Filled.IosShare, tint = colors.textMuted)
            }
        }
    }
}

@Composable
private fun LiftRow(board: ExerciseBoard, onOpen: (String) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = board.exercise
    Row(
        Modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, FitnessCardShape)
            .clickable(role = Role.Button) { onOpen(exercise.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("fitness_lift_${exercise.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(exercise.name, style = type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val best = board.best
            Text(
                if (best != null) FitnessFormat.set(exercise.loadKind, best.weight, best.reps).resolve() else stringResource(Res.string.fitness_exercise_none),
                style = type.label,
                color = colors.textMuted,
                maxLines = 1,
            )
            if (board.last != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StandingMeter(board.standing, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(Res.string.fitness_lifts_of_best, (board.standing * 100).roundToInt()), style = type.micro, color = if (board.standing >= 0.995f) colors.gold else colors.ice)
                }
            } else if (best != null) {
                Text(stringResource(Res.string.fitness_lifts_untrained), style = type.micro, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (board.ladder.size > 1) {
            val tints = colors.focus(focusOf(exercise.bodyPart))
            LadderChart(board.ladder, tints.first, tints.second, Modifier.padding(start = 12.dp).width(72.dp).height(40.dp), maxRungs = 8, labels = false)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp).size(22.dp))
    }
}

/** The workout a shelf belongs to, for its colours. */
internal fun focusOf(part: BodyPart): WorkoutFocus = when (part) {
    BodyPart.CHEST -> WorkoutFocus.CHEST
    BodyPart.BACK -> WorkoutFocus.BACK
    BodyPart.LEGS, BodyPart.CORE -> WorkoutFocus.LEGS
    BodyPart.SHOULDERS -> WorkoutFocus.SHOULDERS
    BodyPart.BICEPS, BodyPart.TRICEPS -> WorkoutFocus.ARMS
}

/**
 * One exercise in full: what to aim for next and why, its ladder, the logger, how its strength
 * has run over time with the phases shaded behind it, and its numbers.
 */
@Composable
internal fun ExerciseScreen(
    state: FitnessUiState,
    board: ExerciseBoard,
    padding: PaddingValues,
    actions: FitnessActions,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = board.exercise
    val kind = exercise.loadKind
    val tints = colors.focus(focusOf(exercise.bodyPart))
    val entry = state.workout?.entry(exercise.id)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_exercise"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = type.hero, color = colors.text)
                Text(
                    stringResource(Res.string.fitness_exercise_kind, stringResource(exercise.equipment.label), stringResource(exercise.primary.label)),
                    style = type.label,
                    color = colors.textFaint,
                )
                if (exercise.note.isNotBlank()) Text(exercise.note, style = type.body, color = colors.textMuted, modifier = Modifier.padding(top = 4.dp))
            }
            RoundIconButton(Icons.Filled.Edit, stringResource(Res.string.fitness_exercise_edit), onEdit, Modifier.testTag("fitness_exercise_edit"), tint = colors.textMuted, fill = colors.surfaceRaised)
        }

        board.target?.let { target ->
            FitnessCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    CardLabel(stringResource(Res.string.fitness_exercise_target), color = colors.gold)
                }
                Text(FitnessFormat.set(kind, target.weight, target.reps).resolve(), style = type.hero, color = colors.text, modifier = Modifier.padding(top = 6.dp))
                Text(FitnessFormat.reason(target).resolve(), style = type.body, color = colors.textMuted)
            }
        }

        if (board.ladder.isNotEmpty()) {
            FitnessCard(title = stringResource(Res.string.fitness_exercise_ladder)) {
                LadderChart(
                    board.ladder,
                    tints.first,
                    tints.second,
                    Modifier.fillMaxWidth().height(170.dp),
                    targetWeight = board.target?.weight,
                    targetReps = board.target?.reps,
                    contentDescription = pluralStringResource(Res.plurals.fitness_exercise_ladder_described, board.ladder.size, board.ladder.size),
                )
                Text(stringResource(Res.string.fitness_exercise_ladder_caption), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 8.dp))
            }
        }

        FitnessCard(title = stringResource(Res.string.fitness_exercise_log)) {
            SetLogger(
                board = board,
                todaysSets = entry?.sets.orEmpty(),
                records = entry?.records.orEmpty(),
                phase = state.phase.kind,
                colors = tints,
                onLog = { weight, reps -> actions.onLogSet(exercise.id, weight, reps) },
                onDeleteSet = actions.onDeleteSet,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val none = stringResource(Res.string.fitness_exercise_none)
            FitnessCard(Modifier.weight(1f), padding = 14.dp) {
                Stat(board.best?.let { FitnessFormat.set(kind, it.weight, it.reps).resolve() } ?: none, stringResource(Res.string.fitness_exercise_best), color = colors.gold)
            }
            FitnessCard(Modifier.weight(1f), padding = 14.dp) {
                Stat(
                    board.phaseBest?.let { FitnessFormat.set(kind, it.weight, it.reps).resolve() } ?: none,
                    stringResource(Res.string.fitness_exercise_phase_best, stringResource(state.phase.kind.label)),
                    color = colors.phase(state.phase.kind).first,
                )
            }
        }
        if (board.last != null) {
            FitnessCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.fitness_exercise_standing, (board.standing * 100).roundToInt()), style = type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
                    Text(pluralStringResource(Res.plurals.fitness_exercise_sessions, board.history.size, board.history.size), style = type.label, color = colors.textFaint)
                }
                Spacer(Modifier.height(10.dp))
                StandingMeter(board.standing, height = 8.dp)
            }
        }

        if (board.history.size >= 2) {
            FitnessCard(title = stringResource(Res.string.fitness_exercise_chart)) {
                StrengthChart(board, state.phases, tints.first, Modifier.fillMaxWidth().height(180.dp))
                Text(stringResource(Res.string.fitness_exercise_chart_caption), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A lift's strength a session at a time, as the estimate its peak set works out to, with each phase shaded behind it and the all-time best ruled across. */
@Composable
private fun StrengthChart(board: ExerciseBoard, phases: List<Phase>, tint: Color, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val series = remember(board.history) { Series(LongArray(board.history.size) { board.history[it].epochSeconds }, DoubleArray(board.history.size) { board.history[it].score }) }
    val best = board.best?.let { Strength.score(board.exercise, it) }
    val periods = phaseBands(phases, series.times.firstOrNull() ?: 0L, series.lastTime ?: 0L)
    CompositionLocalProvider(LocalChartStyle provides FitnessChartStyle) {
        LineChart(
            lines = listOf(ChartLine(series, tint, fill = true)),
            modifier = modifier,
            rules = listOfNotNull(best?.let { ChartRule(it, colors.gold) }),
            timeAxis = true,
            periods = periods,
            contentDescription = stringResource(Res.string.fitness_exercise_chart_described, board.exercise.name),
        )
    }
}

/** How the fitness charts are drawn: a bright line through each session's own point. */
internal val FitnessChartStyle = ChartStyle.DEFAULT.copy(shader = ChartShader.NEON, sharpness = LineSharpness.POINTS)

/** The cuts within a chart's span, to shade behind its line: the stretches where holding steady is the win. */
@Composable
internal fun phaseBands(phases: List<Phase>, fromEpochSeconds: Long, toEpochSeconds: Long): List<ChartPeriod> {
    val sorted = phases.sortedBy { it.startedAtEpochSeconds }
    val label = stringResource(PhaseKind.CUT.label)
    return sorted.mapIndexedNotNull { index, phase ->
        if (phase.kind != PhaseKind.CUT) return@mapIndexedNotNull null
        val end = sorted.getOrNull(index + 1)?.startedAtEpochSeconds ?: toEpochSeconds
        val start = phase.startedAtEpochSeconds.coerceAtLeast(fromEpochSeconds)
        if (end <= start) null else ChartPeriod(start, end.coerceAtMost(toEpochSeconds), label)
    }
}

/** Changing what an exercise is: its name, its shelf and the muscle it is mainly for, how its weight is counted, its rep band, its usual jump, its rest, and a note. */
@Composable
internal fun EditExerciseScreen(exercise: Exercise, padding: PaddingValues, onSave: (Exercise) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    var name by rememberSaveable(exercise.id) { mutableStateOf(exercise.name) }
    var part by rememberSaveable(exercise.id) { mutableStateOf(exercise.bodyPart.name) }
    var muscle by rememberSaveable(exercise.id) { mutableStateOf(exercise.primary.name) }
    var load by rememberSaveable(exercise.id) { mutableStateOf(exercise.loadKind.name) }
    var low by rememberSaveable(exercise.id) { mutableIntStateOf(exercise.repLow) }
    var high by rememberSaveable(exercise.id) { mutableIntStateOf(exercise.repHigh) }
    var step by rememberSaveable(exercise.id) { mutableDoubleStateOf(exercise.increment) }
    var rest by rememberSaveable(exercise.id) { mutableIntStateOf(exercise.restSeconds) }
    var note by rememberSaveable(exercise.id) { mutableStateOf(exercise.note) }
    var deleting by rememberSaveable(exercise.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_edit"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FitnessCard(title = stringResource(Res.string.fitness_edit_name)) {
            FitnessTextField(name, { name = it }, exercise.name, Modifier.fillMaxWidth().testTag("fitness_edit_name"))
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_shelf)) {
            ChipRow(BodyPart.entries.map { it.name to stringResource(it.label) }, part, tag = "fitness_edit_shelf") { chosen ->
                part = chosen
                // A new shelf brings its own muscle along, as the name reads there; the row below can still say otherwise.
                BodyPart.entries.firstOrNull { it.name == chosen }?.let { muscle = ExerciseClassifier.classify(name, it).primary.name }
            }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_muscle)) {
            ChipRow(Muscle.entries.map { it.name to stringResource(it.label) }, muscle, tag = "fitness_edit_muscle") { muscle = it }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_load)) {
            ChipRow(LoadKind.entries.map { it.name to stringResource(it.label) }, load) { load = it }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_reps)) {
            Text(stringResource(Res.string.fitness_edit_reps_band, low, high), style = type.title, color = colors.text)
            Spacer(Modifier.height(10.dp))
            ChipRow(REP_BANDS.map { "${it.first}-${it.last}" to stringResource(Res.string.fitness_edit_reps_band, it.first, it.last) }, "$low-$high") { chosen ->
                REP_BANDS.firstOrNull { "${it.first}-${it.last}" == chosen }?.let {
                    low = it.first
                    high = it.last
                }
            }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_step)) {
            ChipRow(STEPS.map { it.toString() to FitnessFormat.number(it) }, step.toString()) { chosen -> chosen.toDoubleOrNull()?.let { step = it } }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_rest)) {
            ChipRow(RESTS.map { it.toString() to FitnessFormat.clock(it) }, rest.toString()) { chosen -> chosen.toIntOrNull()?.let { rest = it } }
        }
        FitnessCard(title = stringResource(Res.string.fitness_edit_note)) {
            FitnessTextField(note, { note = it }, stringResource(Res.string.fitness_edit_note_hint), Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        }
        ForgeButton(
            stringResource(Res.string.fitness_edit_save),
            {
                val newName = name.trim().ifEmpty { exercise.name }
                val newPart = BodyPart.entries.firstOrNull { it.name == part } ?: exercise.bodyPart
                val primary = Muscle.entries.firstOrNull { it.name == muscle } ?: exercise.primary
                // The helpers go with the main muscle: the usual ones when it is what the name suggests, otherwise the ones it had.
                val guess = ExerciseClassifier.classify(newName, newPart)
                onSave(
                    exercise.copy(
                        name = newName,
                        bodyPart = newPart,
                        primary = primary,
                        secondary = if (primary == guess.primary) guess.secondary else exercise.secondary.filter { it != primary },
                        loadKind = LoadKind.entries.firstOrNull { it.name == load } ?: exercise.loadKind,
                        repLow = low,
                        repHigh = high,
                        increment = step,
                        restSeconds = rest,
                        note = note.trim(),
                    ),
                )
            },
            Modifier.fillMaxWidth().testTag("fitness_edit_save"),
        )
        GhostButton(
            stringResource(if (deleting) Res.string.fitness_edit_delete_confirm else Res.string.fitness_edit_delete),
            { if (deleting) onDelete() else deleting = true },
            Modifier.fillMaxWidth().testTag("fitness_edit_delete"),
            icon = Icons.Filled.Delete,
            tint = colors.danger,
        )
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, tag: String = "", onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            ChoiceChip(label, key == selected, { onSelect(key) }, if (tag.isEmpty()) Modifier else Modifier.testTag("${tag}_${key.lowercase()}"))
        }
    }
}

private val REP_BANDS = listOf(4..8, 6..10, 8..12, 10..15, 12..17, 15..20, 20..26, 23..29)
private val STEPS = listOf(1.0, 2.5, 5.0, 10.0, 20.0, 50.0)
private val RESTS = listOf(60, 90, 120, 150, 180, 240)
