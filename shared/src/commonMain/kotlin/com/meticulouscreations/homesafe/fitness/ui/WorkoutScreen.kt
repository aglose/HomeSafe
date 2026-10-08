package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.ActiveWorkout
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.FitnessBoardBuilder
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.WorkoutEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.ui.shader.PlasmaRing
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_rest_go
import homesafe.shared.generated.resources.fitness_rest_label
import homesafe.shared.generated.resources.fitness_rest_minus
import homesafe.shared.generated.resources.fitness_rest_minus_short
import homesafe.shared.generated.resources.fitness_rest_plus
import homesafe.shared.generated.resources.fitness_rest_plus_short
import homesafe.shared.generated.resources.fitness_rest_remaining
import homesafe.shared.generated.resources.fitness_rest_skip
import homesafe.shared.generated.resources.fitness_workout_add
import homesafe.shared.generated.resources.fitness_workout_add_confirm
import homesafe.shared.generated.resources.fitness_workout_add_hint
import homesafe.shared.generated.resources.fitness_workout_cancel
import homesafe.shared.generated.resources.fitness_workout_done
import homesafe.shared.generated.resources.fitness_workout_elapsed
import homesafe.shared.generated.resources.fitness_workout_empty_shelf
import homesafe.shared.generated.resources.fitness_workout_expand
import homesafe.shared.generated.resources.fitness_workout_finish
import homesafe.shared.generated.resources.fitness_workout_from_notes
import homesafe.shared.generated.resources.fitness_workout_history
import homesafe.shared.generated.resources.fitness_workout_last
import homesafe.shared.generated.resources.fitness_workout_new
import homesafe.shared.generated.resources.fitness_workout_records
import homesafe.shared.generated.resources.fitness_workout_sets
import homesafe.shared.generated.resources.fitness_workout_target
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.time.Clock

/**
 * A workout in progress: the shelves it is for as chips, and under them every exercise on those
 * shelves with last time's peak set and what to aim for today. A tap opens an exercise into its
 * logger, in place; logging a set starts the rest clock, which rides along the top. Exercises
 * already done today rise to the head of the list, the rest follow in the order they were most
 * recently trained.
 */
@Composable
internal fun WorkoutScreen(
    state: FitnessUiState,
    workout: ActiveWorkout,
    padding: PaddingValues,
    actions: FitnessActions,
    onOpenExercise: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    /** The exercise whose logger is open to begin with. */
    initiallyOpen: String? = null,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val focus = workout.workout.focus
    val tints = colors.focus(focus)
    var shelves by rememberSaveable(workout.workout.id) { mutableStateOf(focus.parts.map { it.name }) }
    var open by rememberSaveable(workout.workout.id) { mutableStateOf(initiallyOpen) }
    val listed = remember(state.boards, workout, shelves) {
        val done = workout.entries.mapNotNull { entry -> state.board(entry.exerciseId) }
        val doneIds = done.mapTo(HashSet()) { it.exercise.id }
        done + FitnessBoardBuilder.workoutOrder(state.boards.filter { it.exercise.bodyPart.name in shelves && it.exercise.id !in doneIds })
    }

    LazyColumn(
        modifier.fillMaxSize().testTag("fitness_workout"),
        contentPadding = PaddingValues(start = FitnessGutter, end = FitnessGutter, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    val minutes = ((state.nowEpochSeconds - workout.workout.startedAtEpochSeconds) / 60).coerceAtLeast(0).toInt()
                    Stat(minutes.toString(), stringResource(Res.string.fitness_workout_elapsed))
                    Stat(workout.setCount.toString(), pluralStringResource(Res.plurals.fitness_workout_sets, workout.setCount))
                    Stat(workout.recordCount.toString(), pluralStringResource(Res.plurals.fitness_workout_records, workout.recordCount), color = if (workout.recordCount > 0) colors.gold else colors.text)
                }
                AnimatedVisibility(state.rest != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    state.rest?.let { rest ->
                        RestBar(rest, state.nowEpochSeconds * 1000, tints, actions.onAdjustRest, actions.onSkipRest, Modifier.padding(top = 14.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (focus.parts + focus.extras).forEach { part ->
                        val on = part.name in shelves
                        ChoiceChip(
                            stringResource(part.label),
                            on,
                            { shelves = if (on) shelves - part.name else shelves + part.name },
                            Modifier.testTag("fitness_shelf_${part.name.lowercase()}"),
                            tint = tints.first,
                        )
                    }
                }
            }
        }
        if (listed.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(Res.string.fitness_workout_empty_shelf), style = type.body, color = colors.textMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        items(listed, key = { it.exercise.id }) { board ->
            WorkoutExercise(
                board = board,
                entry = workout.entry(board.exercise.id),
                state = state,
                expanded = open == board.exercise.id,
                tints = tints,
                actions = actions,
                onToggle = { open = if (open == board.exercise.id) null else board.exercise.id },
                onOpenExercise = onOpenExercise,
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "add") {
            AddExerciseCard(
                parts = (focus.parts + focus.extras).filter { it.name in shelves }.ifEmpty { focus.parts },
                onAdd = actions.onAddExercise,
                tint = tints.first,
            )
        }
        item(key = "finish") {
            ForgeButton(
                stringResource(if (workout.setCount == 0) Res.string.fitness_workout_cancel else Res.string.fitness_workout_finish),
                onFinish,
                Modifier.fillMaxWidth().padding(top = 8.dp).testTag("fitness_workout_finish"),
                colors = if (workout.setCount == 0) colors.surfaceRaised to colors.surfaceRaised else tints,
                icon = if (workout.setCount == 0) null else Icons.Filled.Check,
            )
        }
    }
}

/** One exercise in the workout: a row saying where it stands, opening in place into its [SetLogger]. */
@Composable
private fun WorkoutExercise(
    board: ExerciseBoard,
    entry: WorkoutEntry?,
    state: FitnessUiState,
    expanded: Boolean,
    tints: Pair<Color, Color>,
    actions: FitnessActions,
    onToggle: () -> Unit,
    onOpenExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = board.exercise
    val sets = entry?.sets.orEmpty()
    val done = sets.isNotEmpty()
    val edge by animateColorAsState(
        if (expanded) {
            tints.first
        } else if (done) {
            colors.good.copy(alpha = 0.5f)
        } else {
            colors.hairline
        },
        tween(250),
        label = "exerciseEdge",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, edge, FitnessCardShape)
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 500f))
            .testTag("fitness_exercise_${exercise.id}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_workout_expand, exercise.name), onClick = onToggle)
                .testTag("fitness_exercise_row_${exercise.id}")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (done) colors.good.copy(alpha = 0.18f) else colors.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Text(sets.size.toString(), style = type.label, color = colors.good)
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tints.first.copy(alpha = 0.7f)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val last = board.last
                Text(
                    when {
                        done -> pluralStringResource(Res.plurals.fitness_workout_done, sets.size, sets.size)

                        last != null -> stringResource(
                            Res.string.fitness_workout_last,
                            FitnessFormat.set(exercise.loadKind, last.weight, last.reps).resolve(),
                            FitnessFormat.whenText(last.epochSeconds, state.nowEpochSeconds, state.utcOffsetSeconds).resolve(),
                        )

                        board.setCount > 0 -> stringResource(Res.string.fitness_workout_from_notes)

                        else -> stringResource(Res.string.fitness_workout_new)
                    },
                    style = type.label,
                    color = if (done) colors.good else colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val target = board.target
            if (target != null && !expanded) {
                val line = FitnessFormat.set(exercise.loadKind, target.weight, target.reps).resolve()
                val described = stringResource(Res.string.fitness_workout_target, line)
                Row(
                    Modifier
                        .clip(CircleShape)
                        .border(1.dp, colors.gold.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = described },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Flag, contentDescription = null, tint = colors.gold, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(line, style = type.label, color = colors.gold, maxLines = 1)
                }
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = colors.textFaint,
                modifier = Modifier.padding(start = 6.dp).size(22.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (board.ladder.size > 1) {
                    LadderChart(board.ladder, tints.first, tints.second, Modifier.fillMaxWidth().height(96.dp), targetWeight = board.target?.weight, targetReps = board.target?.reps)
                    Spacer(Modifier.height(12.dp))
                }
                SetLogger(
                    board = board,
                    todaysSets = sets,
                    records = entry?.records.orEmpty(),
                    phase = state.phase.kind,
                    colors = tints,
                    onLog = { weight, reps -> actions.onLogSet(exercise.id, weight, reps) },
                    onDeleteSet = actions.onDeleteSet,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(stringResource(Res.string.fitness_workout_history), { onOpenExercise(exercise.id) }, Modifier.fillMaxWidth(), icon = Icons.Filled.Insights, tint = colors.textMuted)
            }
        }
    }
}

/**
 * The rest between sets: a ring draining as the seconds go, the time left, and a way to add or
 * take fifteen seconds or skip it. When it runs out the ring turns gold and says go, with a tap
 * of the motor for a phone lying face up on the bench.
 */
@Composable
internal fun RestBar(rest: RestTimer, fallbackNowMillis: Long, tints: Pair<Color, Color>, onAdjust: (Int) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val remaining = rememberRestSeconds(rest, fallbackNowMillis)
    val seconds by remember(rest) { derivedStateOf { ceil(remaining.floatValue).toInt() } }
    val over = seconds <= 0
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(over, rest.exerciseId) { if (over) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val spoken = stringResource(Res.string.fitness_rest_remaining, FitnessFormat.clock(seconds))
    Row(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, if (over) colors.gold else colors.hairline, FitnessCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("fitness_rest"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            PlasmaRing(
                level = if (over) 1f else (remaining.floatValue / rest.totalSeconds).coerceIn(0f, 1f),
                tint = if (over) colors.gold else tints.first,
                tint2 = if (over) colors.amber else tints.second,
                track = colors.surfaceRaised,
                modifier = Modifier.matchParentSize(),
                thickness = 0.2f,
                sweepMillis = 0,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = spoken }) {
            Text(stringResource(Res.string.fitness_rest_label).uppercase(), style = type.micro, color = colors.textFaint)
            Text(if (over) stringResource(Res.string.fitness_rest_go) else FitnessFormat.clock(seconds), style = type.title, color = if (over) colors.gold else colors.text)
        }
        if (!over) {
            RestNudge(stringResource(Res.string.fitness_rest_minus_short), stringResource(Res.string.fitness_rest_minus)) { onAdjust(-15) }
            Spacer(Modifier.width(6.dp))
            RestNudge(stringResource(Res.string.fitness_rest_plus_short), stringResource(Res.string.fitness_rest_plus)) { onAdjust(15) }
            Spacer(Modifier.width(2.dp))
        }
        RoundIconButton(Icons.Filled.SkipNext, stringResource(Res.string.fitness_rest_skip), onSkip, Modifier.testTag("fitness_rest_skip"), tint = colors.textMuted)
    }
}

@Composable
private fun RestNudge(text: String, label: String, onClick: () -> Unit) {
    val colors = FitnessTheme.colors
    Box(
        Modifier.height(36.dp).clip(CircleShape).background(colors.surfaceRaised).clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FitnessTheme.type.label, color = colors.textMuted, modifier = Modifier.semantics { contentDescription = label })
    }
}

/**
 * The seconds left of [rest], read off the real clock every frame. In a preview there is one
 * frame and no clock worth reading, so it is what was left at [fallbackNowMillis].
 */
@Composable
private fun rememberRestSeconds(rest: RestTimer, fallbackNowMillis: Long): FloatState {
    val still = LocalInspectionMode.current
    val seconds = remember(rest) { mutableFloatStateOf((rest.endsAtEpochMillis - fallbackNowMillis) / 1000f) }
    LaunchedEffect(rest, still) {
        if (still) return@LaunchedEffect
        // An infinite animation's frames, so anything waiting for animations to settle doesn't wait on the countdown.
        while (true) {
            withInfiniteAnimationFrameNanos { seconds.floatValue = (rest.endsAtEpochMillis - Clock.System.now().toEpochMilliseconds()) / 1000f }
        }
    }
    return seconds
}

/** A new exercise, added without leaving the workout: its name and which shelf it goes on. Everything else is guessed from the name. */
@Composable
internal fun AddExerciseCard(parts: List<BodyPart>, onAdd: (String, BodyPart) -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var part by rememberSaveable(parts) { mutableStateOf(parts.first().name) }
    if (!adding) {
        GhostButton(stringResource(Res.string.fitness_workout_add), { adding = true }, modifier.fillMaxWidth().testTag("fitness_add_exercise"), icon = Icons.Filled.Add, tint = colors.textMuted)
        return
    }
    val submit = {
        val chosen = parts.firstOrNull { it.name == part } ?: parts.first()
        if (name.isNotBlank()) onAdd(name, chosen)
        name = ""
        adding = false
    }
    FitnessCard(modifier, title = stringResource(Res.string.fitness_workout_add)) {
        FitnessTextField(name, { name = it }, stringResource(Res.string.fitness_workout_add_hint), Modifier.fillMaxWidth().testTag("fitness_add_exercise_name"), onDone = submit)
        if (parts.size > 1) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                parts.forEach { ChoiceChip(stringResource(it.label), it.name == part, { part = it.name }, tint = tint) }
            }
        }
        Spacer(Modifier.height(12.dp))
        ForgeButton(stringResource(Res.string.fitness_workout_add_confirm), submit, Modifier.fillMaxWidth().testTag("fitness_add_exercise_confirm"), colors = tint to tint, enabled = name.isNotBlank(), height = 48.dp)
    }
}

/** A text field in the app's own dress: a dark slab, the hint in it until something is typed. */
@Composable
internal fun FitnessTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    onDone: (() -> Unit)? = null,
) {
    val colors = FitnessTheme.colors
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.clip(shape).background(colors.surfaceRaised).border(1.dp, colors.hairline, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = FitnessTheme.type.body.copy(color = colors.text),
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(colors.amber),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = FitnessTheme.type.body, color = colors.textFaint)
                inner()
            }
        },
    )
}
