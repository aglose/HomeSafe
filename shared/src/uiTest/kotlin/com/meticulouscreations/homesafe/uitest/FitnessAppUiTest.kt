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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.ImportState
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.LogMerge
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
        var importCleared = 0
        var copiesSent = 0

        /** Whether there is a share sheet to send a copy of the log through, as there is on Android. */
        var canSendCopy = false
        var restSkipped = 0
        var flashDismissed = 0
        var heartConnects = 0
        var heartForgotten = 0
        var heartSearchStops = 0
        var noticesDismissed = 0
        val chosenSensors = mutableListOf<HeartSensor>()
        val heartProfiles = mutableListOf<HeartProfile>()
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
            onClearImport = { importCleared++ },
            onSendCopy = if (canSendCopy) ({ copiesSent++ }) else null,
            onConnectHeart = { heartConnects++ },
            onChooseHeartSensor = { chosenSensors += it },
            onForgetHeartSensor = { heartForgotten++ },
            onStopHeartSearch = { heartSearchStops++ },
            onSaveHeartProfile = { heartProfiles += it },
            onDismissZoneNotice = { noticesDismissed++ },
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
        heart: HeartUiState = HeartUiState(),
    ) {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalFitnessShaders provides shaders) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(360.dp, if (tall) 2_400.dp else 720.dp)) {
                            FitnessAppContent(state, calls.actions(), initialTab = tab, initialPage = page, heart = heart)
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

    // ---- Heart rate ---------------------------------------------------------------------------

    /** Inside something tappable, whose parts a screen reader hears as one: looked for among the parts. */
    private fun ComposeUiTest.assertShownWithin(tag: String) {
        onNodeWithTag(tag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun whereThereIsNoSensorToBeHadAWorkoutShowsNothingOfTheHeart() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(working = true), Calls(), page = FitnessPage.Workout)
        assertNotShown("fitness_heart")
        assertNotShown("fitness_heart_open")
        assertNotShown("fitness_rest_recovery")
    }

    @Test
    fun withNoSensorChosenAWorkoutOffersTheWayToConnectOne() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls, page = FitnessPage.Workout, heart = FitnessFixtures.heartUnset())
        assertNotShown("fitness_heart")
        tap("fitness_heart_open")
        assertShown("fitness_heart_page")
        tap("fitness_heart_connect")
        assertEquals(1, calls.heartConnects)
        assertNotShown("fitness_heart_forget")
    }

    @Test
    fun aReadingIsShownWithItsZoneAndTheTimeInEach() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(working = true), Calls(), page = FitnessPage.Workout, heart = FitnessFixtures.heart(bpm = 142))
        assertNotShown("fitness_heart_open")
        onNodeWithTag("fitness_heart").assert(hasContentDescription("Heart rate 142 beats a minute, zone 3, Moderate"))
        assertShownWithin("fitness_heart_strip")
        onNode(hasContentDescription("Zone 2, Light: 9 min"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theHeartPanelOpensThePageWhereTheSensorCanBeForgotten() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true), calls, page = FitnessPage.Workout, heart = FitnessFixtures.heart())
        tap("fitness_heart")
        assertShown("fitness_heart_page")
        // Linked already: nothing to connect, only to forget.
        assertNotShown("fitness_heart_connect")
        tap("fitness_heart_forget")
        assertEquals(1, calls.heartForgotten)
    }

    @Test
    fun aLostLinkSaysSoInPlaceOfTheReading() = runComposeUiTest(testTimeout = 5.minutes) {
        val lost = FitnessFixtures.heart(bpm = null, sensor = HeartSensorState.Lost(FitnessFixtures.band))
        show(FitnessFixtures.state(working = true), Calls(), page = FitnessPage.Workout, heart = lost)
        onNodeWithTag("fitness_heart_status", useUnmergedTree = true).assertTextEquals("Lost Fitbit Air. Trying again…")
    }

    @Test
    fun aChangeOfZoneIsWrittenOnThePanelAndTakesItselfAway() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(working = true, resting = false), calls, page = FitnessPage.Workout, heart = FitnessFixtures.heart(bpm = 156, notice = true))
        onNodeWithTag("fitness_heart_notice", useUnmergedTree = true).assertExists()
        onNode(hasText("Up to zone 4 · Hard"), useUnmergedTree = true).assertExists()
        assertEquals(0, calls.noticesDismissed)
        mainClock.advanceTimeBy(4_000, ignoreFrameDuration = true)
        assertEquals(1, calls.noticesDismissed)
    }

    @Test
    fun aRestShowsTheHeartComingDown() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(working = true), Calls(), page = FitnessPage.Workout, heart = FitnessFixtures.heart(bpm = 131, resting = true))
        onNodeWithTag("fitness_rest_recovery").assert(hasContentDescription("Heart rate 131, down 24 since the set"))
    }

    @Test
    fun aSensorIsChosenFromTheOnesFoundAndLeavingStopsTheLooking() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, page = FitnessPage.Heart, heart = FitnessFixtures.heartSearching())
        assertShown("fitness_heart_found_0")
        tap("fitness_heart_found_1")
        assertEquals(listOf(FitnessFixtures.strap), calls.chosenSensors)
        tap("fitness_heart_stop")
        assertEquals(1, calls.heartSearchStops)
        tap("fitness_back")
        assertEquals(2, calls.heartSearchStops)
    }

    @Test
    fun theZonesAreShownForTheNumbersOnTheSteppersAndSavedOnlyWhenAsked() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, page = FitnessPage.Heart, heart = FitnessFixtures.heart(), tall = true)
        // A maximum of 190 is what is saved: zone 5 is its top tenth.
        onNodeWithTag("fitness_heart_range_5").assertTextEquals("171–190 bpm")
        onNode(hasContentDescription("Higher max")).tap()
        settle()
        onNodeWithTag("fitness_heart_range_5").assertTextEquals("172–191 bpm")
        assertEquals(emptyList(), calls.heartProfiles)
        tap("fitness_heart_save")
        assertEquals(listOf(HeartProfile(maxBpm = 191)), calls.heartProfiles)
    }

    @Test
    fun anAgeEstimatesTheMaximumAndARestingRateMovesTheZonesUp() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.state(), calls, page = FitnessPage.Heart, heart = FitnessFixtures.heartUnset(), tall = true)
        // Nothing saved: the age stepper stands at a round 30, which would make the maximum 208 - 21.
        assertShown("fitness_heart_age")
        onNodeWithTag("fitness_heart_range_5").assertTextEquals("168–187 bpm")
        tap("fitness_heart_use_resting")
        assertShown("fitness_heart_resting")
        // From a resting 60: 60 + 0.9 x (187 - 60).
        onNodeWithTag("fitness_heart_range_5").assertTextEquals("174–187 bpm")
        tap("fitness_heart_save")
        assertEquals(listOf(HeartProfile(age = 30, restingBpm = 60)), calls.heartProfiles)
    }

    @Test
    fun todayLooksBackOnTheLastWorkoutsHeartButNotWhileOneIsOpen() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(), Calls(), heart = FitnessFixtures.heartAtRest(), tall = true)
        assertShown("fitness_heart_last")
        onNode(hasContentDescription("Zone 2, Light: 9 min")).assertExists()
    }

    @Test
    fun todayKeepsTheLastHeartOutOfTheWayOfAWorkoutInProgress() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(working = true), Calls(), heart = FitnessFixtures.heartAtRest(), tall = true)
        assertNotShown("fitness_heart_last")
    }

    @Test
    fun progressLeadsToTheHeartPageOnlyWhereThereIsASensorToBeHad() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(), Calls(), tab = FitnessTab.PROGRESS, heart = FitnessFixtures.heartUnset(), tall = true)
        tap("fitness_progress_heart")
        assertShown("fitness_heart_page")
    }

    @Test
    fun progressSaysNothingOfTheHeartWithoutBluetooth() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(), Calls(), tab = FitnessTab.PROGRESS, tall = true)
        assertNotShown("fitness_progress_heart")
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
        // Set straight through the field's text action, not typed: typing focuses the field and
        // opens an input session, and its keyboard hide when the page moves on can land off the
        // main thread on an emulator. What's checked is that the text reaches the app, not the IME.
        onNodeWithTag("fitness_import_text").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("x")) }
        settle()
        assertEquals(listOf("x"), calls.importTexts)
        tap("fitness_import_confirm")
        assertEquals(1, calls.importConfirmed)
    }

    @Test
    fun aCopyOfALogShowsWhatItWouldAddInPlaceOfTheBoxToPasteInto() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.copying(), calls, page = FitnessPage.Import, tall = true)
        assertShown("fitness_import_copy")
        assertNotShown("fitness_import_text")
        assertNotShown("fitness_import_preview")
        tap("fitness_import_confirm")
        assertEquals(1, calls.importConfirmed)
    }

    @Test
    fun aCopyCanBePutDownWithoutBringingItIn() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(FitnessFixtures.copying(), calls, page = FitnessPage.Import, tall = true)
        tap("fitness_import_copy_cancel")
        assertEquals(1, calls.importCleared)
        assertEquals(0, calls.importConfirmed)
        assertNotShown("fitness_import")
    }

    @Test
    fun aCopyWithNothingNewInItHasNothingToImport() = runComposeUiTest(testTimeout = 5.minutes) {
        val copying = FitnessFixtures.copying()
        val nothing = copying.copy(import = copying.import.copy(logCopy = copying.import.logCopy!!.copy(merge = LogMerge())))
        show(nothing, Calls(), page = FitnessPage.Import, tall = true)
        assertShown("fitness_import_copy")
        assertNotShown("fitness_import_confirm")
        assertShown("fitness_import_copy_cancel")
    }

    @Test
    fun aCopyThatCannotBeReadSaysSoAndOffersNothingToImport() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.empty.copy(import = ImportState(text = "{\"percysafeTrainingLog\":1,", copyUnreadable = true)), Calls(), page = FitnessPage.Import, tall = true)
        assertShown("fitness_import_copy_unreadable")
        assertNotShown("fitness_import_confirm")
        assertNotShown("fitness_import_copy")
    }

    @Test
    fun liftsOffersToSendACopyOfTheLogOnlyWhereThereIsSomewhereToSendIt() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls().apply { canSendCopy = true }
        show(FitnessFixtures.state(), calls, tab = FitnessTab.LIFTS, tall = true)
        tap("fitness_lifts_send_copy")
        assertEquals(1, calls.copiesSent)
    }

    @Test
    fun liftsHasNoCopyToSendWithoutAShareSheet() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.state(), Calls(), tab = FitnessTab.LIFTS, tall = true)
        assertShown("fitness_lifts_import")
        assertNotShown("fitness_lifts_send_copy")
    }

    @Test
    fun aLogWithNoExercisesButSomethingElseToCarryCanStillBeSent() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls().apply { canSendCopy = true }
        show(FitnessFixtures.empty.copy(copyable = true), calls, tab = FitnessTab.LIFTS, tall = true)
        tap("fitness_lifts_send_copy")
        assertEquals(1, calls.copiesSent)
    }

    @Test
    fun aCopyThatWasSentSaysNothingUnderTheButton() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls().apply { canSendCopy = true }
        show(FitnessFixtures.state(), calls, tab = FitnessTab.LIFTS, tall = true)
        assertNotShown("fitness_lifts_send_copy_unsent")
    }

    @Test
    fun aCopyThatCouldNotBeSentSaysSoUnderTheButton() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls().apply { canSendCopy = true }
        show(FitnessFixtures.state().copy(copyUnsent = true), calls, tab = FitnessTab.LIFTS, tall = true)
        assertShown("fitness_lifts_send_copy")
        assertShown("fitness_lifts_send_copy_unsent")
    }

    @Test
    fun anEmptyLogHasNoCopyToSend() = runComposeUiTest(testTimeout = 5.minutes) {
        show(FitnessFixtures.empty, Calls().apply { canSendCopy = true }, tab = FitnessTab.LIFTS, tall = true)
        assertShown("fitness_lifts_import")
        assertNotShown("fitness_lifts_send_copy")
    }

    @Test
    fun unreadLinesPastTheFirstFewAreCountedNotDropped() = runComposeUiTest(testTimeout = 5.minutes) {
        val few = FitnessFixtures.importing()
        show(few, Calls(), page = FitnessPage.Import, tall = true)
        assertNotShown("fitness_import_skipped_more")
    }

    @Test
    fun aLongListOfUnreadLinesSaysHowManyMoreThereAre() = runComposeUiTest(testTimeout = 5.minutes) {
        val few = FitnessFixtures.importing()
        val many = few.copy(import = few.import.copy(plan = few.import.plan!!.copy(skipped = List(15) { "line $it" })))
        show(many, Calls(), page = FitnessPage.Import, tall = true)
        assertShown("fitness_import_skipped_more")
    }
}
