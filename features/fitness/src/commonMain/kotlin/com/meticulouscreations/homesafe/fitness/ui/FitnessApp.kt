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
import androidx.compose.material.icons.filled.CalendarMonth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
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
import com.meticulouscreations.homesafe.fitness.HealthUiState
import com.meticulouscreations.homesafe.fitness.HealthViewModel
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.RecordFlash
import com.meticulouscreations.homesafe.fitness.WorkoutSession
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.shader.ForgeBackground
import com.meticulouscreations.homesafe.fitness.ui.shader.RecordBurst
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.assistant.AssistantScreenContext
import com.meticulouscreations.homesafe.ui.assistant.LocalAssistant
import com.meticulouscreations.homesafe.ui.assistant.assistantTrigger
import com.meticulouscreations.homesafe.ui.components.rememberShareText
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.fitness_close
import homesafe.shared.generated.resources.fitness_copy_share_title
import homesafe.shared.generated.resources.fitness_heart_title
import homesafe.shared.generated.resources.fitness_run_page_title
import homesafe.shared.generated.resources.fitness_run_title
import homesafe.shared.generated.resources.fitness_tab_history
import homesafe.shared.generated.resources.fitness_tab_lifts
import homesafe.shared.generated.resources.fitness_tab_progress
import homesafe.shared.generated.resources.fitness_tab_today
import homesafe.shared.generated.resources.fitness_title
import homesafe.shared.generated.resources.fitness_title_edit
import homesafe.shared.generated.resources.fitness_title_exercise
import homesafe.shared.generated.resources.fitness_title_import
import homesafe.shared.generated.resources.fitness_title_import_copy
import homesafe.shared.generated.resources.fitness_title_session
import homesafe.shared.generated.resources.fitness_workout_title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The fitness app's four tabs, in nav order. */
enum class FitnessTab(val label: StringResource, val icon: ImageVector) {
    TODAY(Res.string.fitness_tab_today, Icons.Filled.Bolt),
    LIFTS(Res.string.fitness_tab_lifts, Icons.Filled.FitnessCenter),
    HISTORY(Res.string.fitness_tab_history, Icons.Filled.CalendarMonth),
    PROGRESS(Res.string.fitness_tab_progress, Icons.Filled.Insights),
}

/** A page pushed over the tabs. */
@Immutable
sealed interface FitnessPage {
    /** The workout in progress. */
    data object Workout : FitnessPage

    data class Lift(val exerciseId: String) : FitnessPage

    data class Edit(val exerciseId: String) : FitnessPage

    data object Import : FitnessPage

    /** The heart rate: its sensor and its zones. */
    data object Heart : FitnessPage

    /** A session from the history, looked back on: a workout, or with no [workoutId] the sets logged outside one on [epochDay]. */
    data class Session(val workoutId: Long?, val epochDay: Long) : FitnessPage

    /** Every run the health store holds. */
    data object Runs : FitnessPage

    /** One run, opened up. */
    data class Run(val id: String) : FitnessPage
}

/** Everything the fitness screens can ask for, as one thing to hand down: the view model's side of the app. */
@Stable
class FitnessActions(
    val onClose: () -> Unit = {},
    val onStartWorkout: (WorkoutFocus) -> Unit = {},
    val onFinishWorkout: () -> Unit = {},
    /** Throws the workout in progress away, with the sets logged in it. */
    val onCancelWorkout: () -> Unit = {},
    /** Takes a session out of the history for good. */
    val onDeleteSession: (WorkoutSession) -> Unit = {},
    val onLogSet: (String, Double, Int) -> Unit = { _, _, _ -> },
    val onDeleteSet: (Long) -> Unit = {},
    /** Puts back the set deleted last, while it is still offered ([FitnessUiState.deletedSet]). */
    val onUndoDeleteSet: () -> Unit = {},
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
    val onDismissZoneNotice: () -> Unit = {},
    val onFigureStyle: (FigureStyle) -> Unit = {},
    /** Asks to read the device's health store, or opens the place to get it. */
    val onConnectHealth: () -> Unit = {},
    /** Opens the health store's own page for the app. */
    val onManageHealth: () -> Unit = {},
    val onOpenRun: (String) -> Unit = {},
    /** Asks the user for the open run's route. */
    val onAskRunRoute: () -> Unit = {},
    /** Sends a copy of the whole log out through the platform's share sheet; null where there is none to send it through. */
    val onSendCopy: (() -> Unit)? = null,
)

/**
 * The fitness app: an app of its own inside PercySafe, opened from the drawer, as Finance and
 * Weather are. A training log built round the way its owner already trains: one peak set per
 * exercise, beaten week on week. Today picks the workout, a workout lists its exercises with
 * what to aim for and logs sets in a tap or two, Lifts keeps every exercise's ladder, History
 * is the months gone by as a calendar with each workout and what it came to, and Progress shows
 * the phase, bodyweight and the week's work on the body. Back pops a page, then leaves the app
 * ([onClose]).
 */
@Composable
fun FitnessApp(onClose: () -> Unit, modifier: Modifier = Modifier, active: Boolean = true) {
    // The activity's instance, the one the drawer's card shares.
    val viewModel: FitnessViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val heart by viewModel.heart.collectAsStateWithLifecycle()
    val healthViewModel: HealthViewModel = metroViewModel()
    val health by healthViewModel.uiState.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val share = rememberShareText()
    val shareTitle by rememberUpdatedState(stringResource(Res.string.fitness_copy_share_title))
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel, healthViewModel, share) {
        FitnessActions(
            onClose = { close() },
            onStartWorkout = viewModel::startWorkout,
            onFinishWorkout = viewModel::finishWorkout,
            onCancelWorkout = viewModel::cancelWorkout,
            onDeleteSession = viewModel::deleteSession,
            onLogSet = viewModel::logSet,
            onDeleteSet = viewModel::deleteSet,
            onUndoDeleteSet = viewModel::undoDeleteSet,
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
            onDismissZoneNotice = viewModel::dismissZoneNotice,
            onFigureStyle = viewModel::setFigureStyle,
            onConnectHealth = healthViewModel::connect,
            onManageHealth = healthViewModel::manage,
            onOpenRun = healthViewModel::openRun,
            onAskRunRoute = healthViewModel::askForRoute,
            onSendCopy = share?.let { send -> { scope.launch { viewModel.sendLogCopy { text -> send(text, shareTitle) } } } },
        )
    }
    FitnessAppContent(state, actions, modifier, active, heart = heart, health = health)
}

/**
 * [FitnessApp] without its view models: everything on screen from one [state], for previews and
 * tests as much as for the app. The [heart] rate is beside it, since it alone changes every
 * second; left out, the app is as it is where there is no sensor to be had. So is what the
 * device's health store says ([health]), which comes from elsewhere and at its own pace; left
 * out, the app is as it is where there is no such store.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FitnessAppContent(
    state: FitnessUiState,
    actions: FitnessActions,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    initialTab: FitnessTab = FitnessTab.TODAY,
    initialPage: FitnessPage? = null,
    heart: HeartUiState = HeartUiState(),
    health: HealthUiState = HealthUiState(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val pages = remember { mutableStateListOf<FitnessPage>().apply { initialPage?.let(::add) } }
    // Here rather than in the bars, which go while a page is open.
    AssistantScreenContext { "Fitness app, ${tab.name.lowercase()} tab" + (pages.lastOrNull()?.let { ", $it page open" } ?: "") }
    val haptics = LocalHapticFeedback.current
    // Read through this where the page around it shouldn't redraw every time the heart beats.
    val latestHeart = rememberUpdatedState(heart)

    fun pop() {
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) else actions.onClose()
    }
    // Back with a page to give back is this stack's, and scrubs that page's pop under the finger;
    // with none it leaves the app, which the overlay around it drains predictively (InnerAppOverlay).
    // Off while the app animates closed ([active] false), so that Back reaches what's beneath.
    val back = rememberPredictiveBack(enabled = active && pages.isNotEmpty()) { pop() }

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
        // A workout is read between sets with the phone put down: the screen stays on while one is open and the app is up.
        if (active && workout != null && !LocalInspectionMode.current) KeepScreenOn()
        // What has been added to the open workout from off its shelves, kept up here above the pages: opening one of
        // those exercises' own pages and coming back must not lose it.
        var pulled by rememberSaveable(workout?.workout?.id) { mutableStateOf(emptyList<String>()) }
        // The forge takes the colours of what is on screen: the workout's while one is open, the phase's otherwise.
        val heatOf = when {
            page == FitnessPage.Workout && workout != null -> palette.focus(workout.workout.focus)
            page is FitnessPage.Lift -> state.board(page.exerciseId)?.let { palette.focus(focusOf(it.exercise.bodyPart)) } ?: palette.phase(state.phase.kind)
            page is FitnessPage.Session -> state.session(page.workoutId, page.epochDay)?.focus?.let { palette.focus(it) } ?: palette.phase(state.phase.kind)
            page == FitnessPage.Runs || page is FitnessPage.Run -> palette.run
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
            val previous: Any? = if (pages.isEmpty()) null else pages.getOrNull(pages.lastIndex - 1) ?: tab
            // A back swipe scrubs the pop it would make, so it slides the way a pop does.
            rememberPredictiveBackTransition(target, previous, back, label = "fitnessPage").AnimatedContent(
                transitionSpec = { fitnessTransition(initialState, targetState, forward = depth[1] > 0 && !back.inProgress) },
            ) { shown ->
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
                        health = health,
                        onConnectHealth = actions.onConnectHealth,
                        onManageHealth = actions.onManageHealth,
                        onOpenRuns = { pages += FitnessPage.Runs },
                        heart = if (heart.supported) ({ latestHeart.value }) else null,
                        onOpenHeart = { pages += FitnessPage.Heart },
                    )

                    FitnessTab.LIFTS -> LiftsScreen(
                        state = state,
                        padding = tabPadding,
                        onOpenExercise = { pages += FitnessPage.Lift(it) },
                        onOpenImport = { pages += FitnessPage.Import },
                        onAddExercise = actions.onAddExercise,
                        onSendCopy = actions.onSendCopy,
                    )

                    FitnessTab.HISTORY -> HistoryScreen(
                        state = state,
                        padding = tabPadding,
                        // The one in progress is carried on with; any other is looked back on.
                        onOpenSession = { session -> pages += if (session.inProgress) FitnessPage.Workout else FitnessPage.Session(session.workout?.id, session.epochDay) },
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
                            onCancel = {
                                actions.onCancelWorkout()
                                pages.remove(FitnessPage.Workout)
                            },
                            // Coming back mid-rest, the exercise being rested from is the one to carry on with.
                            initiallyOpen = state.rest?.exerciseId,
                            heart = { latestHeart.value },
                            onOpenHeart = { pages += FitnessPage.Heart },
                            pulled = pulled,
                            onPulledChange = { pulled = it },
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

                    is FitnessPage.Session -> {
                        val session = state.session(shown.workoutId, shown.epochDay)
                        if (session != null) {
                            SessionScreen(
                                session = session,
                                state = state,
                                padding = pagePadding,
                                onOpenExercise = { pages += FitnessPage.Lift(it) },
                                onDelete = {
                                    actions.onDeleteSession(session)
                                    pages.remove(shown)
                                },
                                heart = session.workout?.let { heart.summaries[it.id] },
                            )
                        } else {
                            // Just deleted: the page under this one shows through.
                            Box(Modifier.fillMaxSize())
                        }
                    }

                    FitnessPage.Runs -> RunsScreen(
                        health = health,
                        padding = pagePadding,
                        onOpenRun = { id ->
                            actions.onOpenRun(id)
                            pages += FitnessPage.Run(id)
                        },
                        onManage = actions.onManageHealth,
                    )

                    is FitnessPage.Run -> {
                        val run = health.runs.run(shown.id)
                        if (run != null) {
                            RunScreen(
                                run = run,
                                // Another run's track, still on its way out, isn't this one's.
                                detail = health.detail?.takeIf { it.runId == run.id },
                                board = health.runs,
                                todayEpochDay = (health.nowEpochSeconds + health.utcOffsetSeconds).floorDiv(SECONDS_PER_DAY),
                                padding = pagePadding,
                                onAskRoute = actions.onAskRunRoute,
                                bounds = heart.bounds,
                                onOpenHeart = if (heart.supported) ({ pages += FitnessPage.Heart }) else null,
                            )
                        } else {
                            // Deleted in the health store since: the page under this one shows through.
                            Box(Modifier.fillMaxSize())
                        }
                    }

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
                    FitnessPage.Import -> stringResource(if (state.import.logCopy != null) Res.string.fitness_title_import_copy else Res.string.fitness_title_import)
                    FitnessPage.Heart -> stringResource(Res.string.fitness_heart_title)
                    is FitnessPage.Session -> stringResource(Res.string.fitness_title_session)
                    FitnessPage.Runs -> stringResource(Res.string.fitness_run_title)
                    is FitnessPage.Run -> stringResource(Res.string.fitness_run_page_title)
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
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.92f)
            // Held, as the shell's own bar is, it opens the assistant.
            .assistantTrigger(LocalAssistant.current)
            .clip(CircleShape)
            .background(colors.surfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, colors.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitnessTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Column(
                // Each a quarter of the pill, so four words fit on a narrow phone and none is cramped by a longer neighbour.
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.ember.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                    .semantics { this.selected = isSelected }
                    .testTag("fitness_tab_${tab.name.lowercase()}")
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(tab.icon, contentDescription = null, tint = if (isSelected) colors.amber else colors.textFaint, modifier = Modifier.size(22.dp))
                Text(stringResource(tab.label), style = FitnessTheme.type.micro, color = if (isSelected) colors.text else colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
