package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.FitnessExerciseEntity
import com.meticulouscreations.homesafe.data.FitnessPhaseEntity
import com.meticulouscreations.homesafe.data.FitnessSetEntity
import com.meticulouscreations.homesafe.data.FitnessWorkoutEntity
import com.meticulouscreations.homesafe.data.InMemoryFitnessDao
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.Equipment
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSettings
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LogCopy
import com.meticulouscreations.homesafe.fitness.domain.LogMerge
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitnessRepositoryImplTest {

    private val dao = InMemoryFitnessDao()
    private val repository = FitnessRepositoryImpl(dao)

    private fun draft(epochSeconds: Long = 1_000, exerciseId: String = "chest/bench-press", workoutId: Long? = null, reps: Int = 8, weight: Double = 135.0) =
        SetDraft(exerciseId, weight, reps, epochSeconds, workoutId = workoutId)

    // ---- Sets ---------------------------------------------------------------------------------

    @Test
    fun setIdsRiseWithinACallAndAcrossCalls() = runTest {
        val first = repository.addSets(listOf(draft(), draft(), draft()))
        val second = repository.addSets(listOf(draft()))
        assertEquals(listOf(1L, 2L, 3L), first.map { it.id })
        assertEquals(listOf(4L), second.map { it.id })
    }

    @Test
    fun setIdsStartAfterTheHighestOneThatIsThere() = runTest {
        dao.upsertSets(listOf(FitnessSetEntity(41, "chest/bench-press", null, 5, 100.0, 5, null, "", false)))
        assertEquals(42L, repository.addSets(listOf(draft())).single().id)
    }

    @Test
    fun setsAddedAtTheSameTimeGetDistinctIdsEvenWhenAddedTogether() = runTest {
        val added = coroutineScope { (1..20).map { async { repository.addSets(listOf(draft())).single().id } }.awaitAll() }
        assertEquals(20, added.toSet().size)
        assertEquals(20, repository.sets.first().size)
    }

    @Test
    fun addingNoSetsAddsNothing() = runTest {
        assertEquals(emptyList(), repository.addSets(emptyList()))
        assertEquals(emptyList(), repository.sets.first())
    }

    @Test
    fun aSetCarriesEverythingItsDraftDid() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.LEGS, 70)
        val added = repository.addSets(listOf(SetDraft("legs/squat", 225.0, 5, 77, bodyweight = 180.0, note = "belt", imported = true, workoutId = workout.id)))
        val expected = LoggedSet(1, "legs/squat", 225.0, 5, 77, workoutId = workout.id, bodyweight = 180.0, note = "belt", imported = true)
        assertEquals(listOf(expected), added)
        assertEquals(listOf(expected), repository.sets.first())
    }

    @Test
    fun setsAreReadInTimeOrderThenIdOrder() = runTest {
        repository.addSets(listOf(draft(epochSeconds = 50), draft(epochSeconds = 10), draft(epochSeconds = 50)))
        assertEquals(listOf(2L, 1L, 3L), repository.sets.first().map { it.id })
    }

    @Test
    fun updatingASetReplacesItInPlace() = runTest {
        val added = repository.addSets(listOf(draft(), draft())).first()
        repository.updateSet(added.copy(reps = 12, note = "paused"))
        val read = repository.sets.first()
        assertEquals(2, read.size)
        assertEquals(12, read.single { it.id == added.id }.reps)
        assertEquals("paused", read.single { it.id == added.id }.note)
    }

    @Test
    fun deletingASetRemovesOnlyThatOne() = runTest {
        val added = repository.addSets(listOf(draft(), draft()))
        repository.deleteSet(added[0].id)
        assertEquals(listOf(added[1].id), repository.sets.first().map { it.id })
    }

    // ---- Exercises ----------------------------------------------------------------------------

    @Test
    fun anExerciseReadsBackAsItWasSaved() = runTest {
        val saved = exercise(
            id = "legs/hack-squat",
            name = "Hack squat",
            bodyPart = BodyPart.LEGS,
            equipment = Equipment.MACHINE,
            loadKind = LoadKind.PLATES,
            primary = Muscle.QUADS,
            secondary = listOf(Muscle.GLUTES, Muscle.ADDUCTORS),
            repLow = 6,
            repHigh = 10,
            increment = 50.0,
            restSeconds = 150,
            note = "feet low",
            archived = true,
        )
        repository.saveExercises(listOf(saved))
        assertEquals(listOf(saved), repository.exercises.first())
    }

    @Test
    fun anExerciseWithNoSecondaryMusclesReadsBackWithNone() = runTest {
        repository.saveExercises(listOf(exercise(secondary = emptyList())))
        assertEquals(emptyList(), repository.exercises.first().single().secondary)
    }

    @Test
    fun savingAnExerciseAgainReplacesItById() = runTest {
        repository.saveExercises(listOf(exercise(note = "")))
        repository.saveExercises(listOf(exercise(note = "seat on four")))
        assertEquals(listOf("seat on four"), repository.exercises.first().map { it.note })
    }

    @Test
    fun savingNoExercisesChangesNothing() = runTest {
        repository.saveExercises(listOf(exercise()))
        repository.saveExercises(emptyList())
        assertEquals(1, repository.exercises.first().size)
    }

    @Test
    fun exercisesAreReadInNameOrder() = runTest {
        repository.saveExercises(listOf(exercise(id = "a/z", name = "Zottman curl"), exercise(id = "a/a", name = "Arnold press")))
        assertEquals(listOf("Arnold press", "Zottman curl"), repository.exercises.first().map { it.name })
    }

    @Test
    fun deletingAnExerciseRemovesItAndEverySetOfIt() = runTest {
        repository.saveExercises(listOf(exercise(), exercise(id = "legs/squat", name = "Squat")))
        repository.addSets(listOf(draft(), draft(), draft(exerciseId = "legs/squat")))
        repository.deleteExercise("chest/bench-press")
        assertEquals(listOf("legs/squat"), repository.exercises.first().map { it.id })
        assertEquals(listOf("legs/squat"), repository.sets.first().map { it.exerciseId })
    }

    @Test
    fun deletingAnExerciseThatIsNotThereChangesNothing() = runTest {
        repository.saveExercises(listOf(exercise()))
        repository.addSets(listOf(draft()))
        repository.deleteExercise("chest/nothing")
        assertEquals(1, repository.exercises.first().size)
        assertEquals(1, repository.sets.first().size)
    }

    // ---- Workouts -----------------------------------------------------------------------------

    @Test
    fun workoutIdsRiseAndAWorkoutStartsOpen() = runTest {
        val first = repository.startWorkout(WorkoutFocus.CHEST, 100)
        repository.addSets(listOf(draft(workoutId = first.id)))
        repository.finishWorkout(first.id, 150)
        val second = repository.startWorkout(WorkoutFocus.LEGS, 200)
        assertEquals(Workout(1, WorkoutFocus.CHEST, 100), first)
        assertEquals(Workout(2, WorkoutFocus.LEGS, 200), second)
        assertEquals(listOf(first.copy(finishedAtEpochSeconds = 150), second), repository.workouts.first())
    }

    @Test
    fun startingAWorkoutWhileOneIsInProgressHandsThatOneBack() = runTest {
        val first = repository.startWorkout(WorkoutFocus.CHEST, 100)
        assertEquals(first, repository.startWorkout(WorkoutFocus.LEGS, 200))
        // Two asked for at once are still one.
        val both = coroutineScope { (1..2).map { async { repository.startWorkout(WorkoutFocus.BACK, 300) } }.awaitAll() }
        assertEquals(listOf(first, first), both)
        assertEquals(listOf(first), repository.workouts.first())
    }

    @Test
    fun aWorkoutLeftOpenTooLongDoesNotStopANewOne() = runTest {
        val old = repository.startWorkout(WorkoutFocus.CHEST, 100)
        val fresh = repository.startWorkout(WorkoutFocus.LEGS, 100 + Workout.STALE_AFTER_SECONDS)
        assertEquals(listOf(old.id, fresh.id), repository.workouts.first().map { it.id })
    }

    @Test
    fun finishingAWorkoutStampsItsEnd() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.BACK, 100)
        repository.addSets(listOf(draft(workoutId = workout.id)))
        repository.finishWorkout(workout.id, 4_000)
        assertEquals(listOf(Workout(workout.id, WorkoutFocus.BACK, 100, 4_000)), repository.workouts.first())
    }

    @Test
    fun finishingAWorkoutWithNothingInItRemovesIt() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.BACK, 100)
        repository.finishWorkout(workout.id, 4_000)
        assertEquals(emptyList(), repository.workouts.first())
    }

    @Test
    fun finishingAWorkoutThatIsNotThereMakesNoWorkout() = runTest {
        repository.finishWorkout(77, 4_000)
        assertEquals(emptyList(), repository.workouts.first())
    }

    @Test
    fun aSetLoggedAsItsWorkoutIsFinishedIsNeitherLostNorLeftPointingAtNothing() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.CHEST, 100)
        // The set and the finish are asked for together: whichever goes first, the set is kept.
        coroutineScope {
            listOf(async { repository.addSets(listOf(draft(workoutId = workout.id))) }, async { repository.finishWorkout(workout.id, 500) }).awaitAll()
        }
        assertEquals(listOf(Workout(workout.id, WorkoutFocus.CHEST, 100, 500)), repository.workouts.first())
        assertEquals(listOf(workout.id), repository.sets.first().map { it.workoutId })
    }

    @Test
    fun aSetForAWorkoutThatHasGoneIsKeptOnItsOwn() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.CHEST, 100)
        repository.finishWorkout(workout.id, 200)
        val saved = repository.addSets(listOf(draft(workoutId = workout.id)))
        assertNull(saved.single().workoutId)
    }

    // ---- Imports ------------------------------------------------------------------------------

    @Test
    fun anImportConfirmedTwiceAddsItsSetsOnce() = runTest {
        val bench = Exercise(id = "chest/bench-press", name = "Bench press", bodyPart = BodyPart.CHEST, primary = Muscle.CHEST)
        val drafts = listOf(draft(epochSeconds = 0, reps = 8), draft(epochSeconds = 0, reps = 10), draft(epochSeconds = 0, reps = 8))
        val added = coroutineScope { (1..2).map { async { repository.importNotes(listOf(bench), drafts) } }.awaitAll() }
        // The plan's own repeat of a set is dropped too.
        assertEquals(listOf(2, 0), added)
        assertEquals(listOf(8, 10), repository.sets.first().map { it.reps })
        assertEquals(listOf(bench), repository.exercises.first())
    }

    // ---- Phases and weigh-ins -----------------------------------------------------------------

    @Test
    fun phaseIdsRiseAndEachKeepsItsKindAndStart() = runTest {
        repository.startPhase(PhaseKind.BULK, 100)
        repository.startPhase(PhaseKind.CUT, 900)
        assertEquals(listOf(Phase(1, PhaseKind.BULK, 100), Phase(2, PhaseKind.CUT, 900)), repository.phases.first())
    }

    @Test
    fun aWeighInIsReplacedByAnotherOnTheSameDay() = runTest {
        repository.saveBodyweight(BodyweightEntry(20_000, 180.0))
        repository.saveBodyweight(BodyweightEntry(20_000, 181.5))
        assertEquals(listOf(BodyweightEntry(20_000, 181.5)), repository.bodyweights.first())
    }

    @Test
    fun weighInsAreReadInDateOrder() = runTest {
        repository.saveBodyweight(BodyweightEntry(20_002, 182.0))
        repository.saveBodyweight(BodyweightEntry(20_000, 180.0))
        assertEquals(listOf(20_000L, 20_002L), repository.bodyweights.first().map { it.epochDay })
    }

    @Test
    fun deletingAWeighInRemovesThatDay() = runTest {
        repository.saveBodyweight(BodyweightEntry(20_000, 180.0))
        repository.saveBodyweight(BodyweightEntry(20_001, 181.0))
        repository.deleteBodyweight(20_000)
        assertEquals(listOf(20_001L), repository.bodyweights.first().map { it.epochDay })
    }

    // ---- Names this build does not know -------------------------------------------------------

    @Test
    fun anUnknownExerciseEnumReadsBackAsItsDocumentedFallback() = runTest {
        dao.upsertExercises(
            listOf(
                FitnessExerciseEntity(
                    id = "x/y",
                    name = "Mystery",
                    bodyPart = "WINGS",
                    equipment = "JETPACK",
                    loadKind = "MAGIC",
                    primaryMuscle = "EARS",
                    secondaryMuscles = "CHEST,NOPE,,TRICEPS",
                    repLow = 8,
                    repHigh = 12,
                    increment = 5.0,
                    restSeconds = 90,
                    note = "",
                    archived = false,
                ),
            ),
        )
        val read = repository.exercises.first().single()
        assertEquals(BodyPart.CORE, read.bodyPart)
        assertEquals(Equipment.OTHER, read.equipment)
        assertEquals(LoadKind.WEIGHT, read.loadKind)
        assertEquals(Muscle.ABS, read.primary)
    }

    @Test
    fun unknownSecondaryMusclesAreDroppedAndTheKnownOnesKeptInOrder() = runTest {
        dao.upsertExercises(listOf(exerciseRow(secondaryMuscles = "CHEST,NOPE,,TRICEPS")))
        assertEquals(listOf(Muscle.CHEST, Muscle.TRICEPS), repository.exercises.first().single().secondary)
    }

    @Test
    fun anEmptySecondaryMusclesColumnReadsAsNone() = runTest {
        dao.upsertExercises(listOf(exerciseRow(secondaryMuscles = "")))
        assertEquals(emptyList(), repository.exercises.first().single().secondary)
    }

    @Test
    fun anUnknownWorkoutFocusReadsBackAsChest() = runTest {
        dao.upsertWorkout(FitnessWorkoutEntity(1, "CARDIO", 100, null))
        val read = repository.workouts.first().single()
        assertEquals(WorkoutFocus.CHEST, read.focus)
        assertNull(read.finishedAtEpochSeconds)
    }

    @Test
    fun anUnknownPhaseKindReadsBackAsMaintenance() = runTest {
        dao.upsertPhase(FitnessPhaseEntity(1, "RECOMP", 100))
        assertEquals(listOf(Phase(1, PhaseKind.MAINTAIN, 100)), repository.phases.first())
    }

    private fun exerciseRow(secondaryMuscles: String) = FitnessExerciseEntity(
        id = "x/y",
        name = "Mystery",
        bodyPart = "CHEST",
        equipment = "CABLE",
        loadKind = "WEIGHT",
        primaryMuscle = "CHEST",
        secondaryMuscles = secondaryMuscles,
        repLow = 8,
        repHigh = 12,
        increment = 5.0,
        restSeconds = 90,
        note = "",
        archived = false,
    )

    // ---- Heart rate ---------------------------------------------------------------------------

    private val band = HeartSensor("AA:BB:CC:00:00:01", "Test Band")
    private val heart = HeartSummary(belowMillis = 4_000, zoneMillis = listOf(1_000L, 2_000L, 3_000L, 0L, 500L), bpmMillis = 1_260_000, peakBpm = 171)

    @Test
    fun withNothingSavedTheHeartSettingsAreAllUnset() = runTest {
        assertEquals(HeartSettings(), repository.heartSettings.first())
        assertEquals(emptyMap(), repository.heartSummaries.first())
    }

    @Test
    fun theZonesAndTheSensorAreSavedApartAndNeitherWipesTheOther() = runTest {
        repository.saveHeartProfile(HeartProfile(maxBpm = 188, age = 41, restingBpm = 58))
        repository.saveHeartSensor(band)
        assertEquals(HeartSettings(HeartProfile(188, 41, 58), band), repository.heartSettings.first())

        repository.saveHeartProfile(HeartProfile(age = 41))
        assertEquals(HeartSettings(HeartProfile(age = 41), band), repository.heartSettings.first())

        repository.saveHeartSensor(null)
        assertEquals(HeartSettings(HeartProfile(age = 41), null), repository.heartSettings.first())
    }

    @Test
    fun aSensorThatGaveNoNameIsStillRemembered() = runTest {
        repository.saveHeartSensor(HeartSensor("AA:BB:CC:00:00:02", ""))
        assertEquals(HeartSensor("AA:BB:CC:00:00:02", ""), repository.heartSettings.first().sensor)
    }

    @Test
    fun aWorkoutsHeartIsReadBackAsItWasSavedAndSavedOverByTheNext() = runTest {
        val workout = repository.startWorkout(WorkoutFocus.LEGS, 1_000)
        repository.saveHeartSummary(workout.id, heart)
        assertEquals(mapOf(workout.id to heart), repository.heartSummaries.first())

        val later = heart.plus(150, null, 1_000)
        repository.saveHeartSummary(workout.id, later)
        assertEquals(mapOf(workout.id to later), repository.heartSummaries.first())
    }

    // ---- A copy of the log -------------------------------------------------------------------

    private val squat = exercise(id = "legs/squat", name = "Squat", bodyPart = BodyPart.LEGS)

    /** A log that has been used: notes brought in, a workout done with a sensor on, a phase, a weigh-in, zones set. */
    private suspend fun usedLog(): LogCopy {
        repository.saveExercises(listOf(exercise(), squat))
        repository.importNotes(emptyList(), listOf(draft(epochSeconds = 0).copy(imported = true)))
        val workout = repository.startWorkout(WorkoutFocus.LEGS, 5_000)
        repository.addSets(listOf(SetDraft(squat.id, 225.0, 5, 5_100, bodyweight = 180.0, note = "belt", workoutId = workout.id)))
        repository.saveHeartSummary(workout.id, heart)
        repository.finishWorkout(workout.id, 6_000)
        repository.startPhase(PhaseKind.CUT, 4_000)
        repository.saveBodyweight(BodyweightEntry(19_000, 180.0))
        repository.saveHeartProfile(HeartProfile(maxBpm = 188))
        repository.saveHeartSensor(HeartSensor("AA:BB:CC:00:00:01", "Band"))
        return repository.logCopy()
    }

    @Test
    fun aCopyHoldsEverythingTheLogDoes() = runTest {
        val copy = usedLog()
        assertEquals(repository.exercises.first(), copy.exercises)
        assertEquals(repository.sets.first(), copy.sets)
        assertEquals(repository.workouts.first(), copy.workouts)
        assertEquals(repository.phases.first(), copy.phases)
        assertEquals(repository.bodyweights.first(), copy.bodyweights)
        assertEquals(repository.heartSettings.first(), copy.heart)
        assertEquals(repository.heartSummaries.first(), copy.heartSummaries)
        assertEquals(2, copy.sets.size)
        assertEquals(1, copy.heartSummaries.size)
    }

    @Test
    fun aCopyBroughtIntoAnEmptyLogMakesItTheSameLog() = runTest {
        val copy = usedLog()
        val other = FitnessRepositoryImpl(InMemoryFitnessDao())
        val merge = other.bringIn(copy)
        assertEquals(2, merge.newExercises)
        assertEquals(2, merge.sets.size)
        assertEquals(copy, other.logCopy())
    }

    @Test
    fun bringingTheSameCopyInAgainWritesNothing() = runTest {
        val copy = usedLog()
        val other = FitnessRepositoryImpl(InMemoryFitnessDao())
        other.bringIn(copy)
        assertEquals(LogMerge(), other.bringIn(copy))
        assertEquals(copy, other.logCopy())
        // Nor does bringing a log's own copy into itself.
        assertTrue(repository.bringIn(copy).isEmpty)
    }

    @Test
    fun aCopyBroughtIntoALogInUseKeepsWhatWasThereAndGivesItsRowsNewIds() = runTest {
        val copy = usedLog()
        val other = FitnessRepositoryImpl(InMemoryFitnessDao())
        other.saveExercises(listOf(exercise()))
        val mine = other.startWorkout(WorkoutFocus.CHEST, 9_000)
        other.addSets(listOf(draft(epochSeconds = 9_100, workoutId = mine.id)))
        other.finishWorkout(mine.id, 9_500)
        other.startPhase(PhaseKind.BULK, 8_000)

        other.bringIn(copy)

        assertEquals(listOf(WorkoutFocus.LEGS to 2L, WorkoutFocus.CHEST to 1L), other.workouts.first().map { it.focus to it.id })
        val sets = other.sets.first()
        assertEquals(3, sets.size)
        assertEquals(3, sets.map { it.id }.toSet().size)
        // The copy's workout was number 1 where it came from; its set and its heart went with it to number 2.
        assertEquals(2L, sets.single { it.exerciseId == squat.id }.workoutId)
        assertEquals(1L, sets.single { it.epochSeconds == 9_100L }.workoutId)
        assertEquals(mapOf(2L to heart), other.heartSummaries.first())
        assertEquals(listOf(PhaseKind.CUT, PhaseKind.BULK), other.phases.first().map { it.kind })
        // The next set logged carries on after the ones brought in.
        assertEquals(4L, other.addSets(listOf(draft(epochSeconds = 9_900))).single().id)
    }

    @Test
    fun aCopyReadWhileTheLogIsBeingEditedHoldsEachEditWholeOrNotAtAll() = runTest {
        repository.saveExercises(listOf(exercise(), squat))
        repository.addSets(List(30) { draft(epochSeconds = 100L + it, exerciseId = squat.id) })
        val copies = coroutineScope {
            val edits = async { repeat(20) { repository.addSets(listOf(draft(epochSeconds = 9_000L + it))) } }
            val gone = async { repository.deleteExercise(squat.id) }
            val read = (1..20).map { async { repository.logCopy() } }
            edits.await()
            gone.await()
            read.awaitAll()
        }
        // An exercise is deleted together with its sets: no copy has the one without the other.
        for (copy in copies) {
            val known = copy.exercises.map { it.id }.toSet()
            assertTrue(copy.sets.all { it.exerciseId in known })
            assertEquals(squat.id in known, copy.sets.any { it.exerciseId == squat.id })
        }
    }

    @Test
    fun aCopySetsAnExerciseUpItsOwnWayAndFillsInOnlyTheHeartSettingsThatAreMissing() = runTest {
        val copy = usedLog()
        val other = FitnessRepositoryImpl(InMemoryFitnessDao())
        other.saveExercises(listOf(exercise(bodyPart = BodyPart.SHOULDERS, repLow = 4, repHigh = 6)))
        other.saveHeartProfile(HeartProfile(maxBpm = 195, age = 40))

        val merge = other.bringIn(copy)

        assertEquals(1, merge.changedExercises)
        assertEquals(exercise(), other.exercises.first().single { it.id == exercise().id })
        assertEquals(HeartSettings(HeartProfile(maxBpm = 195, age = 40), HeartSensor("AA:BB:CC:00:00:01", "Band")), other.heartSettings.first())
    }

    @Test
    fun aHeartForAWorkoutThatHasGoneIsNotKept() = runTest {
        repository.saveHeartSummary(99, heart)
        assertEquals(emptyMap(), repository.heartSummaries.first())
    }

    @Test
    fun aWorkoutClosedEmptyTakesItsHeartWithItAndOneWithSetsKeepsIt() = runTest {
        val empty = repository.startWorkout(WorkoutFocus.LEGS, 1_000)
        repository.saveHeartSummary(empty.id, heart)
        repository.finishWorkout(empty.id, 2_000)
        assertEquals(emptyMap(), repository.heartSummaries.first())

        val real = repository.startWorkout(WorkoutFocus.LEGS, 3_000)
        repository.addSets(listOf(draft(epochSeconds = 3_100, workoutId = real.id)))
        repository.saveHeartSummary(real.id, heart)
        repository.finishWorkout(real.id, 4_000)
        assertEquals(mapOf(real.id to heart), repository.heartSummaries.first())
    }
}
