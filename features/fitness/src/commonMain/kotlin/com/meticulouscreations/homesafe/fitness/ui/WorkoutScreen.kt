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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.ActiveWorkout
import com.meticulouscreations.homesafe.fitness.ExerciseBoard
import com.meticulouscreations.homesafe.fitness.FitnessBoardBuilder
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.RestTimer
import com.meticulouscreations.homesafe.fitness.WorkoutEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.ExerciseClassifier
import com.meticulouscreations.homesafe.fitness.domain.MovementClassifier
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.movement.ExerciseFigure
import com.meticulouscreations.homesafe.fitness.ui.movement.FigureSize
import com.meticulouscreations.homesafe.fitness.ui.movement.NearestFigureNote
import com.meticulouscreations.homesafe.fitness.ui.shader.PlasmaRing
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.rememberKeyboardClearance
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_close
import homesafe.shared.generated.resources.fitness_exercise_edit
import homesafe.shared.generated.resources.fitness_heart_open
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
import homesafe.shared.generated.resources.fitness_workout_cancel_confirm
import homesafe.shared.generated.resources.fitness_workout_create
import homesafe.shared.generated.resources.fitness_workout_done
import homesafe.shared.generated.resources.fitness_workout_elapsed
import homesafe.shared.generated.resources.fitness_workout_empty_shelf
import homesafe.shared.generated.resources.fitness_workout_expand
import homesafe.shared.generated.resources.fitness_workout_find
import homesafe.shared.generated.resources.fitness_workout_find_hint
import homesafe.shared.generated.resources.fitness_workout_find_none
import homesafe.shared.generated.resources.fitness_workout_finish
import homesafe.shared.generated.resources.fitness_workout_from_notes
import homesafe.shared.generated.resources.fitness_workout_history
import homesafe.shared.generated.resources.fitness_workout_last
import homesafe.shared.generated.resources.fitness_workout_new
import homesafe.shared.generated.resources.fitness_workout_pull
import homesafe.shared.generated.resources.fitness_workout_records
import homesafe.shared.generated.resources.fitness_workout_regulars
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
 * recently trained. Anything off those shelves is added at the foot: the core and calf work that
 * goes along with any day is there as chips ([ActiveWorkout.regulars]), and any other exercise is
 * found by name ([WorkoutAddCard]). With a heart-rate sensor chosen, the heart rate and its zone sit over it all
 * ([HeartPanel]), and the rest clock shows the heart coming back down.
 *
 * At the foot it is finished, which keeps it, or cancelled, which throws it away with the sets
 * logged in it and so takes a second tap. One with nothing logged in it yet has only the one
 * button, which closes it and leaves no trace.
 */
@Composable
internal fun WorkoutScreen(
    state: FitnessUiState,
    workout: ActiveWorkout,
    padding: PaddingValues,
    actions: FitnessActions,
    onOpenExercise: (String) -> Unit,
    onEditExercise: (String) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** The exercise whose logger is open to begin with. */
    initiallyOpen: String? = null,
    /** The heart rate, read only where it is shown, so the rest of the page isn't redrawn on every beat. */
    heart: () -> HeartUiState = { HeartUiState() },
    onOpenHeart: () -> Unit = {},
    /** Exercises added from off the picked shelves, in the order they were added; held by the caller, so they outlast this page. */
    pulled: List<String> = emptyList(),
    onPulledChange: (List<String>) -> Unit = {},
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val focus = workout.workout.focus
    val tints = colors.focus(focus)
    var shelves by rememberSaveable(workout.workout.id) { mutableStateOf(focus.parts.map { it.name }) }
    var open by rememberSaveable(workout.workout.id) { mutableStateOf(initiallyOpen) }
    var discarding by rememberSaveable(workout.workout.id) { mutableStateOf(false) }

    /** One just added, to be scrolled to once it is in the list (a new exercise takes a moment to arrive). */
    var reveal by rememberSaveable(workout.workout.id) { mutableStateOf<String?>(null) }
    // Shelf by shelf, the workout's own first: within each, what has been done today leads (in the order it was done),
    // then what was added to it, then the rest as the routine has them. A shelf that isn't picked still shows anything
    // logged from it today, or added.
    val sections = remember(state.boards, workout, shelves, pulled) {
        val doneIds = workout.entries.map { it.exerciseId }
        val order = (focus.parts + focus.extras + BodyPart.entries).distinct()
        order.mapNotNull { part ->
            val (done, rest) = state.boards.filter { it.exercise.bodyPart == part }.partition { it.exercise.id in doneIds }
            val (added, others) = rest.partition { it.exercise.id in pulled }
            val shown = done.sortedBy { doneIds.indexOf(it.exercise.id) } +
                added.sortedBy { pulled.indexOf(it.exercise.id) } +
                if (part.name in shelves) FitnessBoardBuilder.workoutOrder(others) else emptyList()
            if (shown.isEmpty()) null else part to shown
        }
    }
    val listed = remember(sections) { sections.flatMap { (_, boards) -> boards.map { it.exercise.id } }.toSet() }
    val pull: (String) -> Unit = { id ->
        // One already in the list stays where it is, and is only opened and scrolled to.
        if (id !in pulled && id !in listed) onPulledChange(pulled + id)
        open = id
        reveal = id
    }
    val list = rememberLazyListState()
    LaunchedEffect(reveal, sections) {
        val id = reveal ?: return@LaunchedEffect
        // Counted as the list below lays them out: the header, then each shelf's heading (when there is more than one) and its exercises.
        var index = 1
        for ((_, boards) in sections) {
            if (sections.size > 1) index++
            val at = boards.indexOfFirst { it.exercise.id == id }
            if (at >= 0) {
                // Let go of it only once there: clearing it restarts this effect, which would stop the scroll.
                list.animateScrollToItem(index + at)
                reveal = null
                return@LaunchedEffect
            }
            index += boards.size
        }
    }

    LazyColumn(
        modifier.fillMaxSize().testTag("fitness_workout"),
        state = list,
        contentPadding = PaddingValues(
            start = FitnessGutter + padding.calculateStartPadding(LocalLayoutDirection.current),
            end = FitnessGutter + padding.calculateEndPadding(LocalLayoutDirection.current),
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            val beat = heart()
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    val minutes = ((state.nowEpochSeconds - workout.workout.startedAtEpochSeconds) / 60).coerceAtLeast(0).toInt()
                    Stat(minutes.toString(), stringResource(Res.string.fitness_workout_elapsed))
                    Stat(workout.setCount.toString(), pluralStringResource(Res.plurals.fitness_workout_sets, workout.setCount))
                    Stat(workout.recordCount.toString(), pluralStringResource(Res.plurals.fitness_workout_records, workout.recordCount), color = if (workout.recordCount > 0) colors.gold else colors.text)
                    // With no sensor chosen yet the heart rate is only a way in, kept out of the way.
                    if (beat.supported && !beat.wanted) {
                        Spacer(Modifier.weight(1f))
                        RoundIconButton(
                            Icons.Filled.MonitorHeart,
                            stringResource(Res.string.fitness_heart_open),
                            onOpenHeart,
                            Modifier.testTag("fitness_heart_open"),
                            tint = colors.textMuted,
                            fill = colors.surfaceRaised,
                        )
                    }
                }
                if (beat.wanted) HeartPanel(beat, onOpenHeart, actions.onDismissZoneNotice, Modifier.padding(top = 14.dp))
                AnimatedVisibility(state.rest != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    state.rest?.let { rest ->
                        RestBar(rest, state.nowEpochSeconds * 1000, tints, actions.onAdjustRest, actions.onSkipRest, Modifier.padding(top = 14.dp)) {
                            beat.recovery?.let { RecoveryLine(it, beat.zone, zoned = beat.bounds != null, Modifier.padding(top = 10.dp)) }
                        }
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
        if (sections.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(Res.string.fitness_workout_empty_shelf), style = type.body, color = colors.textMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        sections.forEach { (part, boards) ->
            if (sections.size > 1) {
                item(key = "shelf_${part.name}") { CardLabel(stringResource(part.label), Modifier.padding(top = 8.dp, start = 4.dp)) }
            }
            items(boards, key = { it.exercise.id }) { board ->
                WorkoutExercise(
                    board = board,
                    entry = workout.entry(board.exercise.id),
                    state = state,
                    expanded = open == board.exercise.id,
                    tints = tints,
                    actions = actions,
                    onToggle = { open = if (open == board.exercise.id) null else board.exercise.id },
                    onOpenExercise = onOpenExercise,
                    onEditExercise = onEditExercise,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "add") {
            WorkoutAddCard(
                focus = focus,
                regulars = workout.regulars.filter { it !in listed }.mapNotNull { state.board(it) },
                boards = state.boards,
                listed = listed,
                parts = ((focus.parts + focus.extras).filter { it.name in shelves } + focus.parts + BodyPart.entries).distinct(),
                onPull = pull,
                onCreate = { name, part ->
                    actions.onAddExercise(name, part)
                    pull(Exercise.idFor(part, name.trim()))
                },
                tint = tints.first,
                clearAbove = padding.calculateTopPadding(),
            )
        }
        item(key = "finish") {
            ForgeButton(
                stringResource(if (workout.setCount == 0) Res.string.fitness_workout_cancel else Res.string.fitness_workout_finish),
                // Empty or not, this one closes it: whether there is anything in it to keep is the log's to say, since a set may be on its way in.
                onFinish,
                Modifier.fillMaxWidth().padding(top = 8.dp).testTag("fitness_workout_finish"),
                colors = if (workout.setCount == 0) colors.surfaceRaised to colors.surfaceRaised else tints,
                icon = if (workout.setCount == 0) null else Icons.Filled.Check,
            )
        }
        if (workout.setCount > 0) {
            item(key = "cancel") {
                // The sets logged so far go with it, so it asks once more before it does.
                GhostButton(
                    if (discarding) pluralStringResource(Res.plurals.fitness_workout_cancel_confirm, workout.setCount, workout.setCount) else stringResource(Res.string.fitness_workout_cancel),
                    { if (discarding) onCancel() else discarding = true },
                    Modifier.fillMaxWidth().testTag("fitness_workout_cancel"),
                    icon = Icons.Filled.Close,
                    tint = if (discarding) colors.danger else colors.textMuted,
                )
            }
        }
    }
}

/**
 * One exercise in the workout: a row saying where it stands, opening in place into its
 * [SetLogger]. The row opens on a still of the movement ([ExerciseFigure]), with how many sets
 * have been done today on its corner; opened, the figure performs it above the ladder.
 */
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
    onEditExercise: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = board.exercise
    val sets = entry?.sets.orEmpty()
    val done = sets.isNotEmpty()
    val match = remember(exercise.name, exercise.bodyPart, exercise.primary) { MovementClassifier.match(exercise) }
    val movement = match.kind
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExerciseFigure(movement, state.figureStyle, tints, size = FigureSize.BADGE)
                if (done) {
                    // Today's sets, counted on the figure's corner; the ring is the card's own colour, so the count sits clear of the picture.
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 5.dp, y = 5.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.surface)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(colors.good),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(sets.size.toString(), style = type.micro, color = colors.background)
                    }
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
                Spacer(Modifier.width(8.dp))
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
                // Only an open card has the figure moving, and only it holds a rendered rep in memory.
                ExerciseFigure(movement, state.figureStyle, tints)
                NearestFigureNote(match, Modifier.padding(start = 4.dp, top = 6.dp))
                Spacer(Modifier.height(12.dp))
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
                    deleted = state.deletedSet,
                    onUndoDelete = actions.onUndoDeleteSet,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(stringResource(Res.string.fitness_workout_history), { onOpenExercise(exercise.id) }, Modifier.weight(1f), icon = Icons.Filled.Insights, tint = colors.textMuted)
                    // Its shelf, what it works, its rep range: put right from here when the guess was wrong.
                    RoundIconButton(
                        Icons.Filled.Edit,
                        stringResource(Res.string.fitness_exercise_edit),
                        { onEditExercise(exercise.id) },
                        Modifier.testTag("fitness_exercise_edit_${exercise.id}"),
                        tint = colors.textMuted,
                        fill = colors.surfaceRaised,
                    )
                }
            }
        }
    }
}

/**
 * The rest between sets: a ring draining as the seconds go, the time left, and a way to add or
 * take fifteen seconds or skip it. When it runs out the ring turns gold and says go, with a tap
 * of the motor for a phone lying face up on the bench. [below] goes under it, inside the same
 * card: the heart's recovery, when a sensor is on.
 */
@Composable
internal fun RestBar(
    rest: RestTimer,
    fallbackNowMillis: Long,
    tints: Pair<Color, Color>,
    onAdjust: (Int) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    below: @Composable () -> Unit = {},
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val remaining = rememberRestSeconds(rest, fallbackNowMillis)
    val seconds by remember(rest) { derivedStateOf { ceil(remaining.floatValue).toInt() } }
    val over = seconds <= 0
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(over, rest.exerciseId) { if (over) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val spoken = stringResource(Res.string.fitness_rest_remaining, FitnessFormat.clock(seconds))
    Column(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, if (over) colors.gold else colors.hairline, FitnessCardShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("fitness_rest"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                // Read while drawing: the ring drains every frame, and the bar around it need only change once a second.
                PlasmaRing(
                    level = { if (over) 1f else (remaining.floatValue / rest.totalSeconds).coerceIn(0f, 1f) },
                    tint = if (over) colors.gold else tints.first,
                    tint2 = if (over) colors.amber else tints.second,
                    track = colors.surfaceRaised,
                    modifier = Modifier.matchParentSize(),
                    thickness = 0.2f,
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
        below()
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

/**
 * The foot of the workout, for what is off its picked shelves. Its [regulars] sit as chips, each a
 * tap from being added; the button under them opens a search of every exercise there is, on any
 * shelf ([boards]; those [listed] already are only opened), where a name nothing is called yet
 * can be made a new exercise on any of [parts], the shelf the name suggests picked to begin with.
 * Whatever is added ([onPull], [onCreate]) joins the list open, ready for its first set.
 * [clearAbove] is how far the bar over the top of the page reaches into it, for bringing the
 * search up above the keyboard (see rememberKeyboardClearance).
 */
@Composable
private fun WorkoutAddCard(
    focus: WorkoutFocus,
    regulars: List<ExerciseBoard>,
    boards: List<ExerciseBoard>,
    listed: Set<String>,
    parts: List<BodyPart>,
    onPull: (String) -> Unit,
    onCreate: (String, BodyPart) -> Unit,
    tint: Color,
    clearAbove: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    /** The shelf picked for a new one by hand; until then, the one its name suggests. */
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val close = {
        finding = false
        query = ""
        chosen = null
    }
    Column(modifier.fillMaxWidth()) {
        if (regulars.isNotEmpty()) {
            CardLabel(stringResource(Res.string.fitness_workout_regulars, stringResource(focus.label)), Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp))
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                regulars.forEach { board -> PullChip(board.exercise.name, { onPull(board.exercise.id) }, tint, Modifier.testTag("fitness_regular_${board.exercise.id}")) }
            }
        }
        if (!finding) {
            GhostButton(stringResource(Res.string.fitness_workout_find), { finding = true }, Modifier.fillMaxWidth().testTag("fitness_add_exercise"), icon = Icons.Filled.Add, tint = colors.textMuted)
            return@Column
        }
        val name = query.trim()
        val matches = remember(boards, query) { FitnessBoardBuilder.find(boards, query).take(MAX_MATCHES) }
        // Something by that very name is already there, on this shelf or another: it is found, not made again.
        val exact = remember(boards, name) { boards.firstOrNull { it.exercise.name.equals(name, ignoreCase = true) } }
        val known = exact != null || name.isEmpty()
        val pick = { board: ExerciseBoard ->
            onPull(board.exercise.id)
            close()
        }
        // The matches come in under the field as it is typed in: the whole card comes up above the keyboard, not only the field.
        var typing by remember { mutableStateOf(false) }
        val clearance = rememberKeyboardClearance(typing, matches, known, above = clearAbove)
        FitnessCard(clearance.modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardLabel(stringResource(Res.string.fitness_workout_find), Modifier.weight(1f))
                RoundIconButton(Icons.Filled.Close, stringResource(Res.string.common_close), close, Modifier.testTag("fitness_add_exercise_close"), size = 32.dp, tint = colors.textMuted)
            }
            Spacer(Modifier.height(8.dp))
            FitnessTextField(
                query,
                { query = it },
                stringResource(Res.string.fitness_workout_find_hint),
                Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }.testTag("fitness_add_exercise_name"),
                onDone = { (exact ?: matches.singleOrNull())?.let(pick) },
            )
            matches.forEach { board ->
                val exercise = board.exercise
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (exercise.id in listed) Res.string.fitness_workout_expand else Res.string.fitness_workout_pull, exercise.name)) { pick(board) }
                        .testTag("fitness_add_match_${exercise.id}")
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(exercise.name, style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(exercise.bodyPart.label), style = type.label, color = colors.textFaint, maxLines = 1)
                    }
                    Icon(if (exercise.id in listed) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
            if (!known) {
                val guess = remember(name, parts) { ExerciseClassifier.shelfFor(name)?.takeIf { it in parts } ?: parts.first() }
                val part = parts.firstOrNull { it.name == chosen } ?: guess
                if (matches.isEmpty()) {
                    Text(stringResource(Res.string.fitness_workout_find_none), style = type.label, color = colors.textFaint, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    parts.forEach { ChoiceChip(stringResource(it.label), it == part, { chosen = it.name }, Modifier.testTag("fitness_add_exercise_part_${it.name.lowercase()}"), tint = tint) }
                }
                Spacer(Modifier.height(12.dp))
                ForgeButton(
                    stringResource(Res.string.fitness_workout_create, name),
                    {
                        onCreate(name, part)
                        close()
                    },
                    Modifier.fillMaxWidth().testTag("fitness_add_exercise_confirm"),
                    colors = tint to tint,
                    height = 48.dp,
                )
            }
        }
    }
}

/** An exercise a tap from being added to the workout. */
@Composable
private fun PullChip(name: String, onClick: () -> Unit, tint: Color, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    Row(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_workout_pull, name), onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, style = FitnessTheme.type.label, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How many of the exercises a search finds are listed: enough to pick from, few enough to leave the keyboard room. */
private const val MAX_MATCHES = 5

/**
 * A new exercise: its name and which shelf it goes on. Everything else is guessed from the name.
 * [clearAbove] is how far the bar over the top of the page reaches into it (see rememberKeyboardClearance).
 */
@Composable
internal fun AddExerciseCard(parts: List<BodyPart>, onAdd: (String, BodyPart) -> Unit, tint: Color, clearAbove: Dp, modifier: Modifier = Modifier) {
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
    // The shelves and the button under the field come up above the keyboard with it.
    var typing by remember { mutableStateOf(false) }
    val clearance = rememberKeyboardClearance(typing, above = clearAbove)
    FitnessCard(modifier.then(clearance.modifier), title = stringResource(Res.string.fitness_workout_add)) {
        FitnessTextField(
            name,
            { name = it },
            stringResource(Res.string.fitness_workout_add_hint),
            Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }.testTag("fitness_add_exercise_name"),
            onDone = submit,
        )
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
