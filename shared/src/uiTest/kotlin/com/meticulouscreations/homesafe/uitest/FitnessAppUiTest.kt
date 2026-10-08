package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.ui.FitnessActions
import com.meticulouscreations.homesafe.fitness.ui.FitnessAppContent
import com.meticulouscreations.homesafe.fitness.ui.FitnessFixtures
import com.meticulouscreations.homesafe.fitness.ui.FitnessPage
import com.meticulouscreations.homesafe.fitness.ui.FitnessTab
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The fitness app's screens, drawn from the made-up training log and driven through their test
 * tags: the tabs, the pages pushed over them, the set logger, and the way each reports a choice
 * back through [FitnessActions]. The shaders are off but for one test: the JVM draws them in
 * software, seconds a frame, and what these tests check is the same over the plain gradients.
 *
 * Every test sets `mainClock.autoAdvance = false` and steps the clock itself, as the other screen
 * tests do: the app has animations that never end, and an auto-advancing clock would wait on them.
 * A tap goes by its click action rather than a touch, so it doesn't matter whether the control is
 * inside the small window the tests use.
 */
@OptIn(ExperimentalTestApi::class)
class FitnessAppUiTest {

    private val hackSquat = FitnessFixtures.exercise("Hack squat")

    /** What the screens asked of the app. */
    private class Calls {
        var closed = 0
        var finished = 0
        var importConfirmed = 0
        var restSkipped = 0
        var flashDismissed = 0
        val started = mutableListOf<WorkoutFocus>()
        val logged = mutableListOf<Triple<String, Double, Int>>()
        val phases = mutableListOf<PhaseKind>()
        val weighed = mutableListOf<Double>()
        val added = mutableListOf<Pair<String, BodyPart>>()
        val importTexts = mutableListOf<String>()
        val deletedExercises = mutableListOf<String>()
        val saved = mutableListOf<Exercise>()

        fun actions() = FitnessActions(
            onClose = { closed++ },
            onStartWorkout = { started += it },
            onFinishWorkout = { finished++ },
            onLogSet = { id, weight, reps -> logged += Triple(id, weight, reps) },
            onSkipRest = { restSkipped++ },
            onDismissFlash = { flashDismissed++ },
            onAddExercise = { name, part -> added += name to part },
            onSaveExercise = { saved += it },
            onDeleteExercise = { deletedExercises += it },
            onStartPhase = { phases += it },
            onLogBodyweight = { weighed += it },
            onImportText = { importTexts += it },
            onConfirmImport = { importConfirmed++ },
        )
    }

    /**
     * The app on a small phone, whatever the test window is; [tall] makes it a very long one, for
     * a test that needs the foot of a lazy list composed.
     */
    private fun ComposeUiTest.show(
        state: FitnessUiState,
        calls: Calls,
        tab: FitnessTab = FitnessTab.TODAY,
        page: FitnessPage? = null,
        shaders: Boolean = false,
        tall: Boolean = false,
    ) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalFitnessShaders provides shaders) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(360.dp, if (tall) 2_400.dp else 720.dp)) {
                            FitnessAppContent(state, calls.actions(), initialTab = tab, initialPage = page)
                        }
                    }
                }
            }
        }
        settle()
    }

    /** Past the page transitions and the nav sliding in or out. */
    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).tap()
        settle()
    }

    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun ComposeUiTest.assertShown(tag: String) {
        onNodeWithTag(tag).assertExists()
    }

    private fun ComposeUiTest.assertNotShown(tag: String) {
        assertEquals(0, onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size)
    }

    // ---- Today --------------------------------------------------------------------------------

    @Test
    fun todayDrawsThroughItsShadersAndOffersTheThreeDays() = runComposeUiTest(testTimeout = 5.minutes) {
        // The one test here drawn through the real shaders: they compile, take their uniforms and draw.
        show(FitnessFixtures.state(), Calls(), shaders = true)
        assertShown("fitness_app")
        assertShown("fitness_today")
        assertShown("fitness_day_legs")
        assertShown("fitness_day_chest")
        assertShown("fitness_day_back")
    }

    @Test
    fun aDayStartsItsWorkout() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls)
        tap("fitness_day_legs")
        assertEquals(listOf(WorkoutFocus.LEGS), calls.started)
    }

    @Test
    fun withNothingLoggedTodayInvitesTheNotesIn() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.empty, Calls())
        assertShown("fitness_today_empty")
        assertNotShown("fitness_day_legs")
        tap("fitness_today_import")
        assertShown("fitness_import")
    }

    @Test
    fun closeLeavesTheAppFromATabAndOnlyPopsFromAPage() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, page = FitnessPage.Import)
        assertShown("fitness_import")
        tap("fitness_back")
        assertEquals(0, calls.closed)
        assertShown("fitness_today")
        tap("fitness_back")
        assertEquals(1, calls.closed)
    }

    // ---- A workout ----------------------------------------------------------------------------

    @Test
    fun aWorkoutInProgressIsResumedFromToday() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls)
        assertNotShown("fitness_day_legs")
        tap("fitness_today_resume")
        assertShown("fitness_workout")
        assertEquals(emptyList(), calls.started)
    }

    @Test
    fun theLoggerOpensOnTheTargetAndLogsItInOneTap() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        val state = FitnessFixtures.state(working = true)
        show(state, calls, page = FitnessPage.Workout)
        // Resting from the hack squat, so it is the one that is open.
        assertShown("fitness_logger")
        tap("fitness_logger_log")
        val last = state.workout!!.entry(hackSquat.id)!!.sets.last()
        assertEquals(listOf(Triple(hackSquat.id, last.weight, last.reps)), calls.logged)
    }

    @Test
    fun theSteppersChangeWhatIsLogged() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        val state = FitnessFixtures.state(working = true)
        show(state, calls, page = FitnessPage.Workout)
        val last = state.workout!!.entry(hackSquat.id)!!.sets.last()
        onNode(hasContentDescription("One rep more") and hasAnyAncestor(hasTestTag("fitness_logger"))).tap()
        onNode(hasContentDescription("More weight") and hasAnyAncestor(hasTestTag("fitness_logger"))).tap()
        settle()
        tap("fitness_logger_log")
        assertEquals(listOf(Triple(hackSquat.id, last.weight + hackSquat.increment, last.reps + 1)), calls.logged)
    }

    @Test
    fun anExerciseOpensInPlaceAndTheRestCanBeSkipped() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls, page = FitnessPage.Workout)
        assertShown("fitness_rest")
        tap("fitness_rest_skip")
        assertEquals(1, calls.restSkipped)
        // Shutting the open exercise takes its logger away; opening another brings one back.
        tap("fitness_exercise_row_${hackSquat.id}")
        assertNotShown("fitness_logger")
        tap("fitness_exercise_row_${FitnessFixtures.exercise("Leg curl").id}")
        assertShown("fitness_logger")
    }

    @Test
    fun finishingAWorkoutSaysSoAndLeavesItsPage() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls, page = FitnessPage.Workout, tall = true)
        tap("fitness_workout_finish")
        assertEquals(1, calls.finished)
        assertNotShown("fitness_workout")
    }

    @Test
    fun aRecordIsAnnouncedAndATapSendsItAway() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true, flash = true), calls, page = FitnessPage.Workout)
        assertShown("fitness_record_banner")
        tap("fitness_record_banner")
        assertEquals(1, calls.flashDismissed)
    }

    // ---- Lifts --------------------------------------------------------------------------------

    @Test
    fun aLiftOpensItsPageAndThatOpensItsEditor() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, tab = FitnessTab.LIFTS)
        assertShown("fitness_lifts")
        tap("fitness_lifts_legs")
        tap("fitness_lift_${hackSquat.id}")
        assertShown("fitness_exercise")
        tap("fitness_exercise_edit")
        assertShown("fitness_edit")
        // Deleting takes two taps: the first only asks.
        tap("fitness_edit_delete")
        assertEquals(emptyList(), calls.deletedExercises)
        tap("fitness_edit_delete")
        assertEquals(listOf(hackSquat.id), calls.deletedExercises)
        assertNotShown("fitness_edit")
        assertNotShown("fitness_exercise")
    }

    @Test
    fun anExerciseOnTheWrongShelfIsMovedFromTheWorkoutAndItsMuscleFollows() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls, page = FitnessPage.Workout)
        tap("fitness_exercise_edit_${hackSquat.id}")
        assertShown("fitness_edit")
        tap("fitness_edit_shelf_chest")
        tap("fitness_edit_save")
        val saved = calls.saved.single()
        assertEquals(hackSquat.id, saved.id)
        assertEquals(BodyPart.CHEST, saved.bodyPart)
        assertEquals(Muscle.CHEST, saved.primary)
        // Back where the edit was opened from.
        assertShown("fitness_workout")
    }

    @Test
    fun theMuscleAnExerciseIsForCanBeSetOnItsOwn() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, page = FitnessPage.Edit(hackSquat.id))
        tap("fitness_edit_muscle_glutes")
        tap("fitness_edit_save")
        val saved = calls.saved.single()
        assertEquals(BodyPart.LEGS, saved.bodyPart)
        assertEquals(Muscle.GLUTES, saved.primary)
        assertEquals(emptyList(), saved.secondary)
    }

    // ---- Progress -----------------------------------------------------------------------------

    @Test
    fun aNewPhaseTakesASecondDeliberateTap() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, tab = FitnessTab.PROGRESS)
        assertShown("fitness_progress")
        tap("fitness_phase_bulk")
        assertEquals(emptyList(), calls.phases)
        tap("fitness_phase_confirm")
        assertEquals(listOf(PhaseKind.BULK), calls.phases)
    }

    @Test
    fun aWeighInIsSavedFromWhereTheLastOneStood() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        val state = FitnessFixtures.state()
        show(state, calls, tab = FitnessTab.PROGRESS)
        tap("fitness_weight_log")
        tap("fitness_weight_save")
        assertEquals(listOf(state.bodyweight.latest!!.pounds), calls.weighed)
    }

    // ---- Bringing notes in --------------------------------------------------------------------

    @Test
    fun typedNotesAreHandedOverAndAPlanCanBeImported() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.importing(), calls, page = FitnessPage.Import)
        assertShown("fitness_import_preview")
        onNodeWithTag("fitness_import_text").performTextInput("x")
        settle()
        assertEquals(1, calls.importTexts.size)
        tap("fitness_import_confirm")
        assertEquals(1, calls.importConfirmed)
    }
}
