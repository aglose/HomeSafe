package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.FitnessViewModel
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.RecordFlash
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.shader.ForgeBackground
import com.meticulouscreations.homesafe.fitness.ui.shader.RecordBurst
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.fitness_close
import homesafe.shared.generated.resources.fitness_heart_title
import homesafe.shared.generated.resources.fitness_tab_lifts
import homesafe.shared.generated.resources.fitness_tab_progress
import homesafe.shared.generated.resources.fitness_tab_today
import homesafe.shared.generated.resources.fitness_title
import homesafe.shared.generated.resources.fitness_title_edit
import homesafe.shared.generated.resources.fitness_title_exercise
import homesafe.shared.generated.resources.fitness_title_import
import homesafe.shared.generated.resources.fitness_workout_title
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The fitness app's three tabs, in nav order. */
enum class FitnessTab(val label: StringResource, val icon: ImageVector) {
    TODAY(Res.string.fitness_tab_today, Icons.Filled.Bolt),
    LIFTS(Res.string.fitness_tab_lifts, Icons.Filled.FitnessCenter),
    PROGRESS(Res.string.fitness_tab_progress, Icons.Filled.Insights),
}

/** A page pushed over the tabs. */
@Immutable
internal sealed interface FitnessPage {
    /** The workout in progress. */
    data object Workout : FitnessPage

    data class Lift(val exerciseId: String) : FitnessPage

    data class Edit(val exerciseId: String) : FitnessPage

    data object Import : FitnessPage

    /** The heart rate: its sensor and its zones. */
    data object Heart : FitnessPage
}

/** Everything the fitness screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
internal class FitnessActions(
    val onClose: () -> Unit = {},
    val onStartWorkout: (WorkoutFocus) -> Unit = {},
    val onFinishWorkout: () -> Unit = {},
    val onLogSet: (String, Double, Int) -> Unit = { _, _, _ -> },
    val onDeleteSet: (Long) -> Unit = {},
    val onSkipRest: () -> Unit = {},
    val onAdjustRest: (Int) -> Unit = {},
    val onDismissFlash: () -> Unit = {},
    val onAddExercise: (String, BodyPart) -> Unit = { _, _ -> },
    val onSaveExercise: (Exercise) -> Unit = {},
    val onDeleteExercise: (String) -> Unit = {},
    val onStartPhase: (PhaseKind) -> Unit = {},
    val onLogBodyweight: (Double) -> Unit = {},
    val onImportText: (String) -> Unit = {},
    val onImportPart: (BodyPart?) -> Unit = {},
    val onConfirmImport: () -> Unit = {},
    val onClearImport: () -> Unit = {},
    val onConnectHeart: () -> Unit = {},
    val onChooseHeartSensor: (HeartSensor) -> Unit = {},
    val onForgetHeartSensor: () -> Unit = {},
    val onStopHeartSearch: () -> Unit = {},
    val onSaveHeartProfile: (HeartProfile) -> Unit = {},
)

/**
 * The fitness app: an app of its own inside PercySafe, opened from the drawer, as Finance and
 * Weather are. A training log built round the way its owner already trains: one peak set per
 * exercise, beaten week on week. Today picks the workout, a workout lists its exercises with
 * what to aim for and logs sets in a tap or two, Lifts keeps every exercise's ladder, and
 * Progress shows the phase, bodyweight and the week's work on the body. Back pops a page, then
 * leaves the app ([onClose]).
 */
@Composable
fun FitnessApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    // The activity's instance, the one the drawer's card shares.
    val viewModel: FitnessViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val heart by viewModel.heart.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val actions = remember(viewModel) {
        FitnessActions(
            onClose = { close() },
            onStartWorkout = viewModel::startWorkout,
            onFinishWorkout = viewModel::finishWorkout,
            onLogSet = viewModel::logSet,
            onDeleteSet = viewModel::deleteSet,
            onSkipRest = viewModel::skipRest,
            onAdjustRest = viewModel::adjustRest,
            onDismissFlash = viewModel::dismissFlash,
            onAddExercise = { name, part -> viewModel.addExercise(name, part) },
            onSaveExercise = viewModel::saveExercise,
            onDeleteExercise = viewModel::deleteExercise,
            onStartPhase = viewModel::startPhase,
            onLogBodyweight = viewModel::logBodyweight,
            onImportText = viewModel::setImportText,
            onImportPart = viewModel::setImportPart,
            onConfirmImport = viewModel::confirmImport,
            onClearImport = viewModel::clearImport,
            onConnectHeart = viewModel::connectHeart,
            onChooseHeartSensor = viewModel::chooseHeartSensor,
            onForgetHeartSensor = viewModel::forgetHeartSensor,
            onStopHeartSearch = viewModel::stopHeartSearch,
            onSaveHeartProfile = viewModel::saveHeartProfile,
        )
    }
    FitnessAppContent(state, actions, modifier, active, heart = heart)
}

/**
 * [FitnessApp] without its view model: everything on screen from one [state], for previews and
 * tests as much as for the app. The [heart] rate is beside it, since it alone changes every
 * second; left out, the app is as it is where there is no sensor to be had.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FitnessAppContent(
    state: FitnessUiState,
    actions: FitnessActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: FitnessTab = FitnessTab.TODAY,
    initialPage: FitnessPage? = null,
    heart: HeartUiState = HeartUiState(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val pages = remember { mutableStateListOf<FitnessPage>().apply { initialPage?.let(::add) } }
    val haptics = LocalHapticFeedback.current

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }
    // Off while the app animates closed ([active] false), so that Back reaches what's beneath.
    BackHandler(enabled = active) { pop() }

    // Notes handed over from outside (shared into the app) open on the page that reads them.
    LaunchedEffect(state.import.text.isNotBlank()) {
        if (state.import.text.isNotBlank() && pages.lastOrNull() != FitnessPage.Import) pages += FitnessPage.Import
    }

    val palette = remember { FitnessPalette() }
    // Remembered: a new instance through the static local would recompose the whole tree on every state change.
    val fontFamily = albertSansFontFamily()
    val type = remember(fontFamily) { FitnessTypography(fontFamily) }
    CompositionLocalProvider(LocalFitnessPalette provides palette, LocalFitnessTypography provides type) {
        val status = WindowInsets.statusBars.asPaddingValues()
        val nav = WindowInsets.navigationBars.asPaddingValues()
        val top = status.calculateTopPadding() + 62.dp
        val tabPadding = PaddingValues(top = top, bottom = nav.calculateBottomPadding() + 108.dp)
        val pagePadding = PaddingValues(top = top, bottom = nav.calculateBottomPadding() + 28.dp)
        val page = pages.lastOrNull()
        val workout = state.workout
        // The forge takes the colours of what is on screen: the workout's while one is open, the phase's otherwise.
        val heatOf = when {
            page == FitnessPage.Workout && workout != null -> palette.focus(workout.workout.focus)
            page is FitnessPage.Lift -> state.board(page.exerciseId)?.let { palette.focus(focusOf(it.exercise.bodyPart)) } ?: palette.phase(state.phase.kind)
            else -> palette.phase(state.phase.kind)
        }

        Box(modifier.fillMaxSize().background(palette.background).testTag("fitness_app")) {
            ForgeBackground(
                tint = heatOf.first,
                tint2 = heatOf.second,
                modifier = Modifier.fillMaxWidth().height(420.dp),
                heat = if (page == FitnessPage.Workout) {
                    0.85f
                } else if (state.phase.kind == PhaseKind.CUT) {
                    0.3f
                } else {
                    0.55f
                },
                running = active,
            )

            val target: Any = page ?: tab
            // Which way the stack last moved, for the slide's direction; outside the snapshot system since it only steers an animation.
            val depth = remember { intArrayOf(pages.size, 1) }
            if (pages.size != depth[0]) {
                depth[1] = if (pages.size > depth[0]) 1 else -1
                depth[0] = pages.size
            }
            AnimatedContent(targetState = target, transitionSpec = { fitnessTransition(initialState, targetState, forward = depth[1] > 0) }, label = "fitnessPage") { shown ->
                when (shown) {
                    FitnessTab.TODAY -> TodayScreen(
                        state = state,
                        padding = tabPadding,
                        onStart = { focus ->
                            actions.onStartWorkout(focus)
                            pages += FitnessPage.Workout
                        },
                        onResume = { pages += FitnessPage.Workout },
                        onOpenImport = { pages += FitnessPage.Import },
                        onOpenLifts = { tab = FitnessTab.LIFTS },
                        onOpenProgress = { tab = FitnessTab.PROGRESS },
                        onOpenExercise = { pages += FitnessPage.Lift(it) },
                        animated = active,
                        lastHeart = heart.last,
                    )

                    FitnessTab.LIFTS -> LiftsScreen(
                        state = state,
                        padding = tabPadding,
                        onOpenExercise = { pages += FitnessPage.Lift(it) },
                        onOpenImport = { pages += FitnessPage.Import },
                        onAddExercise = actions.onAddExercise,
                    )

                    FitnessTab.PROGRESS -> ProgressScreen(
                        state,
                        tabPadding,
                        actions,
                        onOpenExercise = { pages += FitnessPage.Lift(it) },
                        // Only where there is a sensor to be had.
                        onOpenHeart = if (heart.supported) ({ pages += FitnessPage.Heart }) else null,
                    )

                    FitnessPage.Workout -> if (workout != null) {
                        WorkoutScreen(
                            state = state,
                            workout = workout,
                            padding = pagePadding,
                            actions = actions,
                            onOpenExercise = { pages += FitnessPage.Lift(it) },
                            onEditExercise = { pages += FitnessPage.Edit(it) },
                            onFinish = {
                                actions.onFinishWorkout()
                                pages.remove(FitnessPage.Workout)
                            },
                            // Coming back mid-rest, the exercise being rested from is the one to carry on with.
                            initiallyOpen = state.rest?.exerciseId,
                            heart = heart,
                            onOpenHeart = { pages += FitnessPage.Heart },
                        )
                    } else {
                        // Just started and not yet read back, or just finished: the page under this one shows through.
                        Box(Modifier.fillMaxSize())
                    }

                    is FitnessPage.Lift -> {
                        val board = state.board(shown.exerciseId)
                        if (board != null) {
                            ExerciseScreen(state, board, pagePadding, actions, onEdit = { pages += FitnessPage.Edit(shown.exerciseId) })
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }

                    is FitnessPage.Edit -> {
                        val exercise = state.board(shown.exerciseId)?.exercise
                        if (exercise != null) {
                            EditExerciseScreen(
                                exercise = exercise,
                                padding = pagePadding,
                                onSave = { edited ->
                                    actions.onSaveExercise(edited)
                                    pop()
                                },
                                onDelete = {
                                    actions.onDeleteExercise(exercise.id)
                                    pages.removeAll { it == shown || it == FitnessPage.Lift(exercise.id) }
                                },
                            )
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }

                    FitnessPage.Heart -> HeartScreen(heart, pagePadding, actions)

                    FitnessPage.Import -> ImportScreen(
                        import = state.import,
                        padding = pagePadding,
                        onText = actions.onImportText,
                        onPart = actions.onImportPart,
                        onConfirm = actions.onConfirmImport,
                        onDone = {
                            actions.onClearImport()
                            pages.remove(FitnessPage.Import)
                        },
                    )
                }
            }

            FitnessTopBar(
                title = when (page) {
                    null -> stringResource(Res.string.fitness_title)
                    FitnessPage.Workout -> workout?.let { stringResource(Res.string.fitness_workout_title, stringResource(it.workout.focus.label)) }.orEmpty()
                    is FitnessPage.Lift -> stringResource(Res.string.fitness_title_exercise)
                    is FitnessPage.Edit -> stringResource(Res.string.fitness_title_edit)
                    FitnessPage.Import -> stringResource(Res.string.fitness_title_import)
                    FitnessPage.Heart -> stringResource(Res.string.fitness_heart_title)
                },
                paged = page != null,
                onBack = ::pop,
            )

            AnimatedVisibility(
                visible = pages.isEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            ) {
                FitnessNav(tab) { picked ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    tab = picked
                }
            }

            RecordCelebration(state.flash, state.phase.kind, actions.onDismissFlash)
            ZoneBuzz(heart.notice)
        }
    }
}

/** Pages pushed over the tabs slide in from the right and back out the way they came; the tabs fade through one another. */
private fun fitnessTransition(from: Any, to: Any, forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
    return when {
        from !is FitnessPage && to !is FitnessPage -> fadeIn(tween(260)) togetherWith fadeOut(tween(200))
        forward -> (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
        else -> (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
    }
}

/** The bar across the top: the way out (or back), and where this is. */
@Composable
private fun FitnessTopBar(title: String, paged: Boolean, onBack: () -> Unit) {
    val colors = FitnessTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to colors.background.copy(alpha = 0.92f), 0.7f to colors.background.copy(alpha = 0.6f), 1f to Color.Transparent))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(
            if (paged) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
            stringResource(if (paged) Res.string.common_back else Res.string.fitness_close),
            onBack,
            Modifier.testTag("fitness_back"),
        )
        Text(title, style = FitnessTheme.type.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
    }
}

/** The tabs, as a pill of dark iron along the foot. */
@Composable
private fun FitnessNav(selected: FitnessTab, onSelect: (FitnessTab) -> Unit) {
    val colors = FitnessTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .widthIn(max = 380.dp)
            .fillMaxWidth(0.86f)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitnessTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) colors.ember.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .semantics { this.selected = isSelected }
                    .testTag("fitness_tab_${tab.name.lowercase()}")
                    .padding(horizontal = 20.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.amber else colors.textFaint, modifier = Modifier.size(22.dp))
                Text(stringResource(tab.label), style = FitnessTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, maxLines = 1)
            }
        }
    }
}

/**
 * A record, celebrated: the burst across the whole screen and a banner dropping in from the top
 * with what it was, gold for an all-time one and the phase's colour for the best of a cut or a
 * bulk. It takes itself away after a few seconds, or at a tap.
 */
@Composable
private fun RecordCelebration(flash: RecordFlash?, phase: PhaseKind, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val haptics = LocalHapticFeedback.current
    val dismiss by rememberUpdatedState(onDismiss)
    // The last one, kept while the banner slides away so that it leaves with its words still on it.
    val held = remember { arrayOfNulls<RecordFlash>(1) }
    if (flash != null) held[0] = flash
    val shown = flash ?: held[0]
    LaunchedEffect(flash?.token) {
        if (flash == null) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(3600)
        dismiss()
    }
    val allTime = shown?.record?.scope == RecordScope.ALL_TIME
    val tint = if (allTime) colors.gold else colors.phase(phase).first
    Box(modifier.fillMaxSize()) {
        RecordBurst(flash?.token ?: 0, tint, if (allTime) colors.ember else colors.phase(phase).second, Modifier.matchParentSize())
        AnimatedVisibility(
            visible = flash != null,
            modifier = Modifier.fillMaxWidth(),
            enter = slideInVertically(spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(200)),
        ) {
            val last = shown ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surfaceRaised)
                    .border(1.5.dp, tint, shape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("fitness_record_banner"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(FitnessFormat.record(last.record, phase)).uppercase(), style = FitnessTheme.type.micro, color = tint)
                    Text(last.exerciseName, style = FitnessTheme.type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(FitnessFormat.set(last.loadKind, last.set.weight, last.set.reps).resolve(), style = FitnessTheme.type.title, color = colors.text, maxLines = 1)
            }
        }
    }
}
