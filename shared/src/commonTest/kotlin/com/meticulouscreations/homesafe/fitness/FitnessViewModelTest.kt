package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOTES
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.data.FitnessRepositoryImpl
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightTrend
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.ExerciseClassifier
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.NotesParser
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.weather.data.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FitnessViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Harness {
        val repository = FitnessRepositoryImpl(InMemoryFitnessDao())
        val clock = MutableClock(NOW)

        private val made = mutableListOf<FitnessViewModel>()

        fun viewModel() = FitnessViewModel(repository, FakeHeartRateMonitor(), clock).also { made += it }

        /** Stops the view models' minute tick, which would otherwise keep a finished test's clock turning for ever. */
        fun stop() = made.forEach { it.setActive(false) }
    }

    private fun runFitnessTest(body: suspend TestScope.(Harness) -> Unit) = runTest(dispatcher) {
        val h = Harness()
        try {
            body(h)
        } finally {
            h.stop()
        }
    }

    private val benchId = Exercise.idFor(BodyPart.CHEST, "Bench press")

    /** A view model that is active and has read the log, with a bench press added to it. */
    private fun TestScope.activeWithBench(h: Harness): FitnessViewModel {
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("Bench press", BodyPart.CHEST)
        runCurrent()
        return vm
    }

    private fun restSecondsOf(vm: FitnessViewModel, id: String = benchId) = assertNotNull(vm.uiState.value.board(id)).exercise.restSeconds

    // ---- Loading ------------------------------------------------------------------------------

    @Test
    fun nothingIsReadBeforeTheViewModelIsActive() = runFitnessTest { h ->
        h.repository.saveExercises(listOf(exercise()))
        val vm = h.viewModel()
        runCurrent()
        assertFalse(vm.uiState.value.loaded)
        assertEquals(emptyList(), vm.uiState.value.boards)
        assertFalse(vm.uiState.value.isEmpty)
        assertEquals(NOW, vm.uiState.value.nowEpochSeconds)
    }

    @Test
    fun onceActiveTheLogIsReadAndThenFollowed() = runFitnessTest { h ->
        h.repository.saveExercises(listOf(exercise()))
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.loaded)
        assertEquals(listOf(benchId), vm.uiState.value.boards.map { it.exercise.id })

        h.repository.saveExercises(listOf(exercise(id = "legs/squat", name = "Squat", bodyPart = BodyPart.LEGS)))
        runCurrent()
        assertEquals(listOf(benchId, "legs/squat"), vm.uiState.value.boards.map { it.exercise.id })
    }

    @Test
    fun anEmptyLogReadIsEmptyNotStillComing() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun theFullAppMovesTheClockOnEachMinute() = runFitnessTest { h ->
        val started = h.repository.startWorkout(WorkoutFocus.CHEST, NOW - 3_600)
        h.repository.addSets(listOf(SetDraft(benchId, 135.0, 8, NOW - 3_000, workoutId = started.id)))
        h.repository.finishWorkout(started.id, NOW - 1_800)
        h.repository.saveExercises(listOf(exercise()))
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(0, vm.uiState.value.days.single { it.focus == WorkoutFocus.CHEST }.daysAgo)

        h.clock.seconds = NOW + DAY
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(NOW + DAY, vm.uiState.value.nowEpochSeconds)
        assertEquals(1, vm.uiState.value.days.single { it.focus == WorkoutFocus.CHEST }.daysAgo)
    }

    @Test
    fun theDrawerAloneReadsTheLogButDoesNotTick() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        assertTrue(vm.uiState.value.loaded)

        h.clock.seconds = NOW + DAY
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(NOW, vm.uiState.value.nowEpochSeconds)
    }

    @Test
    fun goingInactiveStopsTheTick() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setActive(false)

        h.clock.seconds = NOW + DAY
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(NOW, vm.uiState.value.nowEpochSeconds)
    }

    // ---- The notes import ---------------------------------------------------------------------

    @Test
    fun pastingNotesWorksOutWhatImportingThemWouldDo() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        val state = vm.uiState.value.import
        assertEquals(NOTES, state.text)
        val plan = assertNotNull(state.plan)
        assertEquals(32, plan.newExercises)
        assertEquals(NotesParser.parse(NOTES).setCount, plan.newSets)
        assertEquals(emptyList(), plan.skipped)
        assertNull(state.importedExercises)
    }

    @Test
    fun blankTextHasNoPlan() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        vm.setImportText("   \n ")
        assertNull(vm.uiState.value.import.plan)
    }

    @Test
    fun confirmingTheImportSavesItAndReportsTheCounts() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        val plan = assertNotNull(vm.uiState.value.import.plan)

        vm.confirmImport()
        runCurrent()

        assertEquals(plan.newExercises, h.repository.exercises.first().size)
        assertEquals(plan.newSets, h.repository.sets.first().size)
        assertEquals(plan.newExercises, vm.uiState.value.boards.size)
        assertEquals(ImportState(importedExercises = plan.newExercises, importedSets = plan.newSets), vm.uiState.value.import)
    }

    @Test
    fun theImportedSetsAreMarkedImportedAndInNoWorkout() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        vm.confirmImport()
        runCurrent()
        val sets = h.repository.sets.first()
        assertTrue(sets.all { it.imported && it.workoutId == null })
    }

    @Test
    fun theTextIsClearedOnceTheImportIsConfirmed() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        vm.confirmImport()
        runCurrent()
        assertEquals("", vm.uiState.value.import.text)
        assertNull(vm.uiState.value.import.plan)
    }

    @Test
    fun importingTheSameNotesAgainPlansNothingAndConfirmingDoesNothing() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText(NOTES)
        vm.confirmImport()
        runCurrent()
        val sets = h.repository.sets.first().size

        vm.setImportText(NOTES)
        val plan = assertNotNull(vm.uiState.value.import.plan)
        assertTrue(plan.isEmpty)

        vm.confirmImport()
        runCurrent()
        assertEquals(sets, h.repository.sets.first().size)
        assertNull(vm.uiState.value.import.importedExercises)
    }

    @Test
    fun confirmingWithNothingPastedDoesNothing() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.confirmImport()
        runCurrent()
        assertEquals(emptyList(), h.repository.exercises.first())
        assertEquals(ImportState(), vm.uiState.value.import)
    }

    @Test
    fun theShelfChosenForTheNotesDecidesWhereTheirExercisesGo() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText("Bench press\n- 135lbs - 10 reps")
        assertEquals(BodyPart.CHEST, assertNotNull(vm.uiState.value.import.plan).exercises.single().exercise.bodyPart)

        vm.setImportPart(BodyPart.BACK)
        assertEquals(BodyPart.BACK, assertNotNull(vm.uiState.value.import.plan).exercises.single().exercise.bodyPart)
        assertEquals(BodyPart.BACK, vm.uiState.value.import.part)

        vm.setImportPart(null)
        assertEquals(BodyPart.CHEST, assertNotNull(vm.uiState.value.import.plan).exercises.single().exercise.bodyPart)
    }

    @Test
    fun thePlanIsWorkedOutAgainWhenTheLogChanges() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText("Bench press\n- 135lbs - 10 reps")
        val planned = assertNotNull(vm.uiState.value.import.plan).exercises.single()
        assertTrue(planned.isNew)

        vm.saveExercise(planned.exercise)
        runCurrent()
        assertFalse(assertNotNull(vm.uiState.value.import.plan).exercises.single().isNew)
    }

    @Test
    fun clearingTheImportPutsTheStateBack() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportPart(BodyPart.LEGS)
        vm.setImportText(NOTES)
        vm.clearImport()
        assertEquals(ImportState(), vm.uiState.value.import)
    }

    // ---- Workouts -----------------------------------------------------------------------------

    @Test
    fun startingAWorkoutOpensIt() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.LEGS)
        runCurrent()
        val workout = assertNotNull(vm.uiState.value.workout)
        assertEquals(WorkoutFocus.LEGS, workout.workout.focus)
        assertEquals(NOW, workout.workout.startedAtEpochSeconds)
        assertTrue(vm.uiState.value.days.none { it.due })
    }

    @Test
    fun startingAWorkoutWhileOneIsOpenDoesNothing() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.LEGS)
        runCurrent()
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        assertEquals(listOf(WorkoutFocus.LEGS), h.repository.workouts.first().map { it.focus })
    }

    @Test
    fun aSetLoggedInAWorkoutCarriesItsId() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        val workoutId = assertNotNull(vm.uiState.value.workout).workout.id

        vm.logSet(benchId, 135.0, 8)
        runCurrent()

        assertEquals(workoutId, h.repository.sets.first().single().workoutId)
        assertEquals(1, assertNotNull(vm.uiState.value.workout).setCount)
    }

    @Test
    fun aSetLoggedWithNoWorkoutOpenBelongsToNone() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNull(h.repository.sets.first().single().workoutId)
    }

    @Test
    fun aLoggedSetIsStampedWithNowAndShowsOnTheBoard() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        val set = h.repository.sets.first().single()
        assertEquals(NOW, set.epochSeconds)
        assertEquals(135.0, set.weight)
        assertEquals(8, set.reps)
        assertFalse(set.imported)
        assertEquals(1, assertNotNull(vm.uiState.value.board(benchId)).setCount)
    }

    @Test
    fun aSetWithNoRepsIsNotLogged() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 0)
        runCurrent()
        assertEquals(emptyList(), h.repository.sets.first())
        assertNull(vm.uiState.value.rest)
    }

    @Test
    fun aSetOfAnExerciseThatIsNotThereIsNotLogged() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet("chest/nothing", 135.0, 8)
        runCurrent()
        assertEquals(emptyList(), h.repository.sets.first())
    }

    @Test
    fun aNegativeWeightIsLoggedAsNone() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, -20.0, 8)
        runCurrent()
        assertEquals(0.0, h.repository.sets.first().single().weight)
    }

    @Test
    fun finishingAWorkoutStampsItsEndAndClosesIt() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        vm.logSet(benchId, 135.0, 8)
        runCurrent()

        h.clock.seconds = NOW + 3_000
        vm.finishWorkout()
        runCurrent()

        assertNull(vm.uiState.value.workout)
        assertEquals(NOW + 3_000, h.repository.workouts.first().single().finishedAtEpochSeconds)
        assertEquals(1, h.repository.sets.first().size)
    }

    @Test
    fun finishingAWorkoutEndsTheRestTimer() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNotNull(vm.uiState.value.rest)

        vm.finishWorkout()
        runCurrent()
        assertNull(vm.uiState.value.rest)
    }

    @Test
    fun finishingAnEmptyWorkoutDiscardsItWithoutATrace() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()

        vm.finishWorkout()
        runCurrent()

        assertNull(vm.uiState.value.workout)
        assertEquals(emptyList(), h.repository.workouts.first())
    }

    @Test
    fun finishingWithNoWorkoutOpenDoesNothing() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.finishWorkout()
        runCurrent()
        assertEquals(emptyList(), h.repository.workouts.first())
    }

    @Test
    fun deletingASetRemovesItFromTheBoard() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.deleteSet(h.repository.sets.first().single().id)
        runCurrent()
        assertEquals(0, assertNotNull(vm.uiState.value.board(benchId)).setCount)
    }

    // ---- Records and the rest timer -----------------------------------------------------------

    @Test
    fun aFirstEverSetIsNoRecordSoThereIsNoFlash() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNull(vm.uiState.value.flash)
    }

    @Test
    fun aRecordSettingSetFlashesWithTheExercisesNameAndTheRecord() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.logSet(benchId, 140.0, 8)
        runCurrent()

        val flash = assertNotNull(vm.uiState.value.flash)
        assertEquals(1, flash.token)
        assertEquals("Bench press", flash.exerciseName)
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.WEIGHT), flash.record)
        assertEquals(LoadKind.WEIGHT, flash.loadKind)
        assertEquals(140.0, flash.set.weight)
        assertEquals(h.repository.sets.first().last().id, flash.set.id)
    }

    @Test
    fun eachFlashHasAHigherTokenThanTheLast() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.logSet(benchId, 140.0, 8)
        runCurrent()
        val first = assertNotNull(vm.uiState.value.flash).token
        vm.logSet(benchId, 145.0, 8)
        runCurrent()
        val second = assertNotNull(vm.uiState.value.flash).token
        assertTrue(second > first)
    }

    @Test
    fun aSetThatIsNoRecordLeavesTheFlashAsItWas() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.logSet(benchId, 140.0, 8)
        runCurrent()
        val flash = assertNotNull(vm.uiState.value.flash)

        vm.logSet(benchId, 100.0, 5)
        runCurrent()
        assertEquals(flash, vm.uiState.value.flash)
    }

    @Test
    fun dismissingTheFlashClearsItAndTheNextRecordGetsANewToken() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.logSet(benchId, 140.0, 8)
        runCurrent()
        val first = assertNotNull(vm.uiState.value.flash).token

        vm.dismissFlash()
        assertNull(vm.uiState.value.flash)

        vm.logSet(benchId, 145.0, 8)
        runCurrent()
        assertTrue(assertNotNull(vm.uiState.value.flash).token > first)
    }

    @Test
    fun aSetStartsTheExercisesRestTimer() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        val rest = restSecondsOf(vm)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertEquals(RestTimer(NOW * 1000 + rest * 1000L, rest, benchId), vm.uiState.value.rest)
    }

    @Test
    fun skippingTheRestClearsTheTimer() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.skipRest()
        assertNull(vm.uiState.value.rest)
    }

    @Test
    fun adjustingTheRestMovesItsEndAndLengthTogether() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        val rest = restSecondsOf(vm)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()

        vm.adjustRest(30)
        assertEquals(RestTimer(NOW * 1000 + (rest + 30) * 1000L, rest + 30, benchId), vm.uiState.value.rest)

        vm.adjustRest(-45)
        assertEquals(RestTimer(NOW * 1000 + (rest - 15) * 1000L, rest - 15, benchId), vm.uiState.value.rest)
    }

    @Test
    fun theRestLengthNeverFallsBelowASecond() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.adjustRest(-10_000)
        assertEquals(1, assertNotNull(vm.uiState.value.rest).totalSeconds)
    }

    @Test
    fun adjustingWithNoRestCountingDoesNothing() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.adjustRest(30)
        assertNull(vm.uiState.value.rest)
    }

    @Test
    fun aFinishedRestLingersForFiveMinutesAndThenGoes() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        val rest = restSecondsOf(vm)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()

        h.clock.seconds = NOW + rest + 299
        advanceTimeBy(60_000)
        runCurrent()
        assertNotNull(vm.uiState.value.rest)

        h.clock.seconds = NOW + rest + 301
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(vm.uiState.value.rest)
    }

    // ---- Bodyweight ---------------------------------------------------------------------------

    private fun localDay(epochSeconds: Long) = (epochSeconds + localUtcOffsetSeconds(epochSeconds.toDouble())).floorDiv(SECONDS_PER_DAY)

    @Test
    fun aWeighInIsTodaysAndFeedsTheTrend() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(180.0)
        runCurrent()
        val latest = assertNotNull(vm.uiState.value.bodyweight.latest)
        assertEquals(localDay(NOW), latest.epochDay)
        assertEquals(180.0, latest.pounds)
        assertEquals(180.0, latest.trend)
    }

    @Test
    fun weighingAgainTodayReplacesTheEarlierWeighIn() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(180.0)
        runCurrent()
        vm.logBodyweight(181.0)
        runCurrent()
        assertEquals(listOf(181.0), vm.uiState.value.bodyweight.points.map { it.pounds })
    }

    @Test
    fun weighingTheNextDayAddsAPointAndMovesTheTrend() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(180.0)
        runCurrent()
        h.clock.seconds = NOW + DAY
        vm.logBodyweight(182.0)
        runCurrent()
        val points = vm.uiState.value.bodyweight.points
        assertEquals(listOf(localDay(NOW), localDay(NOW + DAY)), points.map { it.epochDay })
        assertEquals(180.2, points[1].trend, 1e-9)
    }

    @Test
    fun aWeighInOfNothingIsIgnored() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(0.0)
        vm.logBodyweight(-5.0)
        runCurrent()
        assertEquals(BodyweightTrend(), vm.uiState.value.bodyweight)
    }

    @Test
    fun deletingAWeighInRemovesItFromTheTrend() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(180.0)
        runCurrent()
        vm.deleteBodyweight(localDay(NOW))
        runCurrent()
        assertEquals(BodyweightTrend(), vm.uiState.value.bodyweight)
    }

    @Test
    fun aBodyweightSetKeepsTheWeighInItWasDoneAt() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("Pull-ups", BodyPart.BACK)
        vm.logBodyweight(180.0)
        runCurrent()
        val pullUps = Exercise.idFor(BodyPart.BACK, "Pull-ups")

        vm.logSet(pullUps, 0.0, 10)
        runCurrent()

        assertEquals(180.0, h.repository.sets.first().single().bodyweight)
    }

    @Test
    fun aSetOfAnythingElseNotesNoBodyweight() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logBodyweight(180.0)
        runCurrent()
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNull(h.repository.sets.first().single().bodyweight)
    }

    @Test
    fun aBodyweightSetWithNoWeighInNotesNone() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("Pull-ups", BodyPart.BACK)
        runCurrent()
        vm.logSet(Exercise.idFor(BodyPart.BACK, "Pull-ups"), 0.0, 10)
        runCurrent()
        assertNull(h.repository.sets.first().single().bodyweight)
    }

    // ---- Exercises ----------------------------------------------------------------------------

    @Test
    fun anExerciseAddedByNameIsFilledInFromTheName() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("  Bench press ", BodyPart.CHEST)
        runCurrent()

        val guess = ExerciseClassifier.classify("Bench press", BodyPart.CHEST)
        val added = h.repository.exercises.first().single()
        assertEquals(benchId, added.id)
        assertEquals("Bench press", added.name)
        assertEquals(BodyPart.CHEST, added.bodyPart)
        assertEquals(guess.equipment, added.equipment)
        assertEquals(guess.loadKind, added.loadKind)
        assertEquals(guess.primary, added.primary)
        assertEquals(guess.secondary, added.secondary)
        assertEquals(guess.repBand.first, added.repLow)
        assertEquals(guess.repBand.last, added.repHigh)
        assertEquals(guess.increment, added.increment)
        assertEquals(guess.restSeconds, added.restSeconds)
    }

    @Test
    fun addingAnExerciseTwiceOnTheSameShelfLeavesTheFirstAsItWas() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.saveExercise(assertNotNull(vm.uiState.value.board(benchId)).exercise.copy(note = "seat on four", repLow = 5))
        runCurrent()

        vm.addExercise("bench  PRESS", BodyPart.CHEST)
        runCurrent()

        val exercises = h.repository.exercises.first()
        assertEquals(1, exercises.size)
        assertEquals("seat on four", exercises.single().note)
        assertEquals(5, exercises.single().repLow)
    }

    @Test
    fun theSameNameOnAnotherShelfIsAnotherExercise() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.addExercise("Bench press", BodyPart.TRICEPS)
        runCurrent()
        assertEquals(2, h.repository.exercises.first().size)
    }

    @Test
    fun aBlankNameAddsNothing() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("   ", BodyPart.CHEST)
        vm.addExercise("", BodyPart.CHEST)
        runCurrent()
        assertEquals(emptyList(), h.repository.exercises.first())
    }

    @Test
    fun anExerciseAddedWithALevelLoadStepsByOne() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addExercise("Calf machine", BodyPart.LEGS, LoadKind.LEVEL)
        runCurrent()
        val added = h.repository.exercises.first().single()
        assertEquals(LoadKind.LEVEL, added.loadKind)
        assertEquals(1.0, added.increment)
    }

    @Test
    fun anArchivedExerciseOfThatNameCanBeAddedAgain() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.saveExercise(assertNotNull(vm.uiState.value.board(benchId)).exercise.copy(archived = true))
        runCurrent()
        assertTrue(vm.uiState.value.boards.isEmpty())

        vm.addExercise("Bench press", BodyPart.CHEST)
        runCurrent()
        assertFalse(h.repository.exercises.first().single().archived)
        assertEquals(1, vm.uiState.value.boards.size)
    }

    @Test
    fun deletingAnExerciseRemovesItAndItsSets() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        vm.deleteExercise(benchId)
        runCurrent()
        assertEquals(emptyList(), h.repository.exercises.first())
        assertEquals(emptyList(), h.repository.sets.first())
        assertTrue(vm.uiState.value.isEmpty)
    }

    // ---- Phases -------------------------------------------------------------------------------

    @Test
    fun startingAPhaseBeginsItNow() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startPhase(PhaseKind.CUT)
        runCurrent()
        assertEquals(PhaseKind.CUT, vm.uiState.value.phase.kind)
        assertEquals(NOW, vm.uiState.value.phase.startedAtEpochSeconds)
        assertEquals(listOf(Phase(1, PhaseKind.CUT, NOW)), vm.uiState.value.phases)
    }

    @Test
    fun choosingThePhaseAlreadyInForceChangesNothing() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startPhase(PhaseKind.CUT)
        runCurrent()
        h.clock.seconds = NOW + 2 * DAY
        vm.startPhase(PhaseKind.CUT)
        runCurrent()
        assertEquals(listOf(Phase(1, PhaseKind.CUT, NOW)), h.repository.phases.first())
    }

    @Test
    fun maintenanceCanBeStartedWhenNoPhaseWasEverSet() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startPhase(PhaseKind.MAINTAIN)
        runCurrent()
        assertEquals(listOf(Phase(1, PhaseKind.MAINTAIN, NOW)), h.repository.phases.first())
    }

    @Test
    fun aDifferentPhaseLaterBeginsAfterTheFirst() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startPhase(PhaseKind.CUT)
        runCurrent()
        h.clock.seconds = NOW + 20 * DAY
        vm.startPhase(PhaseKind.BULK)
        runCurrent()
        assertEquals(PhaseKind.BULK, vm.uiState.value.phase.kind)
        assertEquals(listOf(PhaseKind.CUT, PhaseKind.BULK), vm.uiState.value.phases.map { it.kind })
    }

    // ---- A workout walked away from -----------------------------------------------------------

    @Test
    fun anAbandonedWorkoutIsClosedAtItsLastSet() = runFitnessTest { h ->
        h.repository.saveExercises(listOf(exercise()))
        val old = h.repository.startWorkout(WorkoutFocus.CHEST, NOW - 9 * 3_600)
        h.repository.addSets(
            listOf(
                SetDraft(benchId, 135.0, 8, NOW - 8 * 3_600 - 1_800, workoutId = old.id),
                SetDraft(benchId, 135.0, 7, NOW - 8 * 3_600 - 1_200, workoutId = old.id),
            ),
        )
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()

        assertEquals(NOW - 8 * 3_600 - 1_200, h.repository.workouts.first().single().finishedAtEpochSeconds)
        assertNull(vm.uiState.value.workout)
        assertEquals(2, h.repository.sets.first().size)
    }

    @Test
    fun anAbandonedWorkoutWithNoSetsIsDropped() = runFitnessTest { h ->
        h.repository.saveExercises(listOf(exercise()))
        h.repository.startWorkout(WorkoutFocus.BACK, NOW - 10 * 3_600)
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(emptyList(), h.repository.workouts.first())
    }

    @Test
    fun aWorkoutStillWithinItsHoursIsLeftOpen() = runFitnessTest { h ->
        h.repository.saveExercises(listOf(exercise()))
        val fresh = h.repository.startWorkout(WorkoutFocus.LEGS, NOW - 3_600)
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(fresh.id, vm.uiState.value.workout?.workout?.id)
        assertNull(h.repository.workouts.first().single().finishedAtEpochSeconds)
    }

    @Test
    fun aWorkoutLeftOpenPastEightHoursStopsBeingTheActiveOneOnTheNextTick() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        vm.logSet(benchId, 135.0, 8)
        runCurrent()
        assertNotNull(vm.uiState.value.workout)

        h.clock.seconds = NOW + 9 * 3_600
        advanceTimeBy(60_000)
        runCurrent()

        assertNull(vm.uiState.value.workout)
    }

    // ---- Two taps, and things asked for at once ------------------------------------------------

    @Test
    fun twoTapsOnStartBeforeTheFirstIsReadBackOpenOneWorkout() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.LEGS)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        assertEquals(listOf(WorkoutFocus.LEGS), h.repository.workouts.first().map { it.focus })
    }

    @Test
    fun finishingOnTheHeelsOfASetKeepsTheWorkoutAndTheSet() = runFitnessTest { h ->
        val vm = activeWithBench(h)
        vm.startWorkout(WorkoutFocus.CHEST)
        runCurrent()
        // The set has not been read back when Finish is pressed: the screen still says the workout is empty.
        vm.logSet(benchId, 135.0, 8)
        vm.finishWorkout()
        runCurrent()
        val workout = h.repository.workouts.first().single()
        assertNotNull(workout.finishedAtEpochSeconds)
        assertEquals(listOf(workout.id), h.repository.sets.first().map { it.workoutId })
    }

    @Test
    fun twoTapsOnImportBringTheNotesInOnce() = runFitnessTest { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setImportText("Hack squat\n- 290lbs - 12 reps\n- 320lbs - 10 reps")
        vm.confirmImport()
        vm.confirmImport()
        runCurrent()
        assertEquals(2, h.repository.sets.first().size)
        assertEquals(2, vm.uiState.value.import.importedSets)
    }
}
