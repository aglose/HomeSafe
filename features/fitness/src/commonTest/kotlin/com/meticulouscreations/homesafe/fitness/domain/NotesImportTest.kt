package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData
import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.JUNE_2022
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOTES
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOV_2021
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.FitnessTestData.SEPT_2021
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesImportTest {

    private val parsed = NotesParser.parse(NOTES)
    private val plan = NotesImport.plan(parsed, emptyList(), emptyList(), NOW)

    private fun planned(id: String): ImportedExercise = plan.exercises.single { it.exercise.id == id }

    /** What saving [plan] leaves in the store: the new exercises, and every set with an id. */
    private fun saved(plan: ImportPlan, before: List<Exercise> = emptyList(), setsBefore: List<LoggedSet> = emptyList()): Pair<List<Exercise>, List<LoggedSet>> {
        val firstId = (setsBefore.maxOfOrNull { it.id } ?: 0L) + 1
        val sets = plan.exercises.flatMap { it.sets }.mapIndexed { index, d ->
            LoggedSet(firstId + index, d.exerciseId, d.weight, d.reps, d.epochSeconds, d.workoutId, d.bodyweight, d.note, d.imported)
        }
        return (before + plan.exercises.filter { it.isNew }.map { it.exercise }) to (setsBefore + sets)
    }

    // ---- Ids ----------------------------------------------------------------------------------

    @Test
    fun anIdIsTheShelfAndTheLowercasedNameWithItsWordsJoinedByDashes() {
        assertEquals("legs/leg-press", Exercise.idFor(BodyPart.LEGS, "Leg press"))
    }

    @Test
    fun anIdIgnoresCaseSpacingAndPunctuation() {
        assertEquals(Exercise.idFor(BodyPart.LEGS, "Leg press"), Exercise.idFor(BodyPart.LEGS, "  LEG   press "))
        assertEquals("back/pullups", Exercise.idFor(BodyPart.BACK, "Pull-ups"))
        assertEquals("back/lat-pulldown-other-machine", Exercise.idFor(BodyPart.BACK, "Lat pulldown (other machine)"))
    }

    @Test
    fun theSameNameOnAnotherShelfIsAnotherId() {
        assertTrue(Exercise.idFor(BodyPart.TRICEPS, "Single arm cable") != Exercise.idFor(BodyPart.BICEPS, "Single arm cable"))
    }

    @Test
    fun everyPlannedExerciseHasTheIdOfItsShelfAndName() {
        for (entry in plan.exercises) {
            assertEquals(Exercise.idFor(entry.exercise.bodyPart, entry.exercise.name), entry.exercise.id, entry.exercise.name)
        }
    }

    @Test
    fun noTwoPlannedExercisesShareAnId() {
        assertEquals(plan.exercises.size, plan.exercises.map { it.exercise.id }.toSet().size)
    }

    @Test
    fun theExercisesLandOnTheIdsTheirNamesMakeOnTheirShelves() {
        val ids = plan.exercises.map { it.exercise.id }.toSet()
        val expected = setOf(
            "shoulders/shoulder-press-machine",
            "shoulders/middle-delt-cable-strict-home",
            "legs/hack-squat",
            "legs/hack-squat-quad-focused",
            "legs/45-degree-leg-press-calf-raise",
            "back/lat-pulldown-strict",
            "back/lat-pulldown-other-machine",
            "back/lat-pulldown",
            "back/45-degree-row",
            "triceps/single-arm-cable",
            "biceps/single-arm-cable",
            "back/pullups",
            "legs/smith-squat",
            "chest/smith-bench",
        )
        assertTrue(ids.containsAll(expected), "missing ${expected - ids}")
    }

    // ---- The plan as a whole ------------------------------------------------------------------

    @Test
    fun theNotesPlanThirtyTwoNewExercisesBecauseLegPressIsOneOfThem() {
        assertEquals(parsed.exercises.size - 1, plan.exercises.size)
        assertEquals(32, plan.newExercises)
        assertTrue(plan.exercises.all { it.isNew })
    }

    @Test
    fun everySetInTheNotesIsPlannedExactlyOnce() {
        assertEquals(parsed.setCount, plan.newSets)
        assertEquals(0, plan.exercises.sumOf { it.known })
    }

    @Test
    fun theNotesPlanSkipsNothing() {
        assertEquals(emptyList(), plan.skipped)
    }

    @Test
    fun theLinesTheParserHandedBackAreHandedOnInThePlan() {
        val result = NotesImport.plan(NotesParser.parse("- 100lbs - 10 reps\nRow\n- 90lbs - 10 reps"), emptyList(), emptyList(), NOW)
        assertEquals(listOf("- 100lbs - 10 reps"), result.skipped)
    }

    @Test
    fun aPlanOfNothingIsEmpty() {
        assertTrue(ImportPlan().isEmpty)
        assertTrue(NotesImport.plan(NotesParser.parse(""), emptyList(), emptyList(), NOW).isEmpty)
    }

    // ---- Load kinds ---------------------------------------------------------------------------

    @Test
    fun exercisesWrittenAsAPairAreLoadedPerHand() {
        assertEquals(LoadKind.PER_HAND, planned("shoulders/shoulder-dumbbell-press").exercise.loadKind)
        assertEquals(LoadKind.PER_HAND, planned("biceps/ez-curl").exercise.loadKind)
    }

    @Test
    fun aPairOnADumbbellExerciseIsTheDumbbellEquipment() {
        assertEquals(Equipment.DUMBBELL, planned("shoulders/shoulder-dumbbell-press").exercise.equipment)
    }

    @Test
    fun aPairOnABarNamedForItKeepsTheEquipmentTheNameSays() {
        assertEquals(Equipment.BARBELL, planned("biceps/ez-curl").exercise.equipment)
    }

    @Test
    fun exercisesWrittenInPlatesAreLoadedInPlates() {
        assertEquals(LoadKind.PLATES, planned("legs/leg-press").exercise.loadKind)
        assertEquals(LoadKind.PLATES, planned("legs/45-degree-leg-press-calf-raise").exercise.loadKind)
    }

    @Test
    fun exercisesWrittenAsAPinNumberAreLoadedByLevel() {
        assertEquals(LoadKind.LEVEL, planned("legs/calf-machine").exercise.loadKind)
        assertEquals(LoadKind.LEVEL, planned("core/ab-machine").exercise.loadKind)
    }

    @Test
    fun bodyweightMovementsAreLoadedByWhatIsHungFromTheBody() {
        val ids = listOf("back/ring-pull-ups", "triceps/dips", "back/weighted-pullups", "legs/pistol-squats", "back/pullups", "triceps/ring-dips")
        for (id in ids) assertEquals(LoadKind.BODYWEIGHT, planned(id).exercise.loadKind, id)
    }

    @Test
    fun anExerciseWithOrdinaryPoundsIsLoadedByWeight() {
        for (id in listOf("legs/hack-squat", "back/lat-pulldown-strict", "legs/calf-dumbbell-2-legs", "shoulders/rear-delt-fly")) {
            assertEquals(LoadKind.WEIGHT, planned(id).exercise.loadKind, id)
        }
    }

    // ---- Band and increment -------------------------------------------------------------------

    @Test
    fun theBandIsReadOffTheLaddersMedianRepsAndTheJumpOffItsGaps() {
        val press = planned("shoulders/shoulder-press-machine").exercise
        assertEquals(9, press.repLow)
        assertEquals(13, press.repHigh)
        assertEquals(10.0, press.increment)
    }

    @Test
    fun aLargeGapOnTheLadderMakesItsMedianJumpTwenty() {
        val hack = planned("legs/hack-squat").exercise
        assertEquals(8, hack.repLow)
        assertEquals(12, hack.repHigh)
        assertEquals(20.0, hack.increment)
    }

    @Test
    fun aMachinesPinNumbersGiveAHighBandAndAJumpOfOne() {
        val calf = planned("legs/calf-machine").exercise
        assertEquals(23, calf.repLow)
        assertEquals(29, calf.repHigh)
        assertEquals(1.0, calf.increment)
    }

    @Test
    fun cableJumpsAreReadFromTheLadderToTheHalfPound() {
        val cable = planned("shoulders/middle-delt-cable").exercise
        assertEquals(2.5, cable.increment)
        assertEquals(15, cable.repLow)
        assertEquals(20, cable.repHigh)
    }

    @Test
    fun withTwoRungsTheBandIsTheNameBasedGuess() {
        val fly = planned("shoulders/rear-delt-fly").exercise
        assertEquals(15, fly.repLow)
        assertEquals(20, fly.repHigh)
    }

    @Test
    fun aWeightWrittenWithItsRepsBlankStillCountsTowardTheJump() {
        // 70 and 80 are the rungs done; 90 is the next, waiting.
        assertEquals(10.0, planned("shoulders/rear-delt-fly").exercise.increment)
    }

    @Test
    fun anExerciseWithOneRungTakesTheNameBasedBandAndJump() {
        val pullUps = planned("back/pullups").exercise
        assertEquals(8, pullUps.repLow)
        assertEquals(15, pullUps.repHigh)
        assertEquals(5.0, pullUps.increment)
    }

    @Test
    fun aLevelExerciseWithTwoRungsStillJumpsByOne() {
        val abs = planned("core/ab-machine").exercise
        assertEquals(1.0, abs.increment)
        assertEquals(15, abs.repLow)
        assertEquals(20, abs.repHigh)
    }

    @Test
    fun theRestOfAnExerciseIsTheNameBasedGuess() {
        val fly = planned("shoulders/rear-delt-fly").exercise
        assertEquals(Muscle.REAR_DELTS, fly.primary)
        assertEquals(90, fly.restSeconds)
        assertEquals("", fly.note)
        assertFalse(fly.archived)
    }

    // ---- Leg press: the Legs note and the one-line set are one exercise ------------------------

    @Test
    fun legPressFromTheLegsNoteAndFromThePhillyLineAreOneExercise() {
        assertEquals(1, plan.exercises.count { it.exercise.id == "legs/leg-press" })
    }

    @Test
    fun theMergedLegPressKeepsThePlatesDefinitionOfTheLegsNote() {
        val legPress = planned("legs/leg-press").exercise
        assertEquals(LoadKind.PLATES, legPress.loadKind)
        assertEquals(50.0, legPress.increment)
        assertEquals(9, legPress.repLow)
        assertEquals(13, legPress.repHigh)
    }

    @Test
    fun theMergedLegPressHasTheLadderAndTheDatedSet() {
        val sets = planned("legs/leg-press").sets
        assertEquals(listOf(270.0, 320.0, 410.0, 450.0, 235.0), sets.map { it.weight })
        assertEquals(listOf(0L, 0L, 0L, 0L, JUNE_2022), sets.map { it.epochSeconds })
        assertEquals(0, planned("legs/leg-press").known)
    }

    // ---- The sets -----------------------------------------------------------------------------

    @Test
    fun everyPlannedSetIsMarkedImportedAndInNoWorkout() {
        val drafts = plan.exercises.flatMap { it.sets }
        assertTrue(drafts.all { it.imported && it.workoutId == null })
    }

    @Test
    fun aSetTheNotesDatedKeepsItsDateAndAnUndatedOneIsABenchmark() {
        assertEquals(listOf(SEPT_2021, NOV_2021), planned("legs/smith-squat").sets.map { it.epochSeconds })
        assertTrue(planned("legs/hack-squat").sets.all { it.epochSeconds == 0L })
    }

    @Test
    fun aSetMarkedRecentlyIsStampedWithNowAndTheRestOfTheLineKeepsNoDate() {
        val pullUps = planned("back/pullups").sets
        assertEquals(listOf(0L, NOW), pullUps.map { it.epochSeconds })
        assertEquals(listOf(22, 17), pullUps.map { it.reps })
        assertEquals(listOf("all time", "recently"), pullUps.map { it.note })
    }

    @Test
    fun aBodyweightAndARemarkAreKeptOnTheirSets() {
        val weighted = planned("back/weighted-pullups").sets
        assertEquals(listOf(180.0, 176.0), weighted.map { it.bodyweight })
        assertEquals("3plates+15lbs", planned("legs/hack-squat").sets.last().note)
    }

    @Test
    fun singleArmCableUnderTrisAndBisAreTwoExercisesWithTheirOwnSets() {
        assertEquals(listOf(15.0, 17.5), planned("triceps/single-arm-cable").sets.map { it.weight })
        assertEquals(listOf(22.0, 27.5), planned("biceps/single-arm-cable").sets.map { it.weight })
    }

    @Test
    fun smithSquatUnderTwoDatedHeadingsIsOneExerciseWithTwoDatedSets() {
        val squat = planned("legs/smith-squat")
        assertEquals(listOf(225.0, 225.0), squat.sets.map { it.weight })
        assertEquals(listOf(3, 5), squat.sets.map { it.reps })
    }

    @Test
    fun theSameSetWrittenTwiceInOneNoteIsPlannedOnce() {
        val result = NotesImport.plan(NotesParser.parse("Row\n- 100lbs - 10 reps\n- 100lbs - 10 reps"), emptyList(), emptyList(), NOW)
        assertEquals(1, result.exercises.single().sets.size)
        assertEquals(1, result.exercises.single().known)
    }

    // ---- Importing again ----------------------------------------------------------------------

    @Test
    fun importingTheSameNotesAgainPlansNoNewExercisesAndNoNewSets() {
        val (exercises, sets) = saved(plan)
        val again = NotesImport.plan(parsed, exercises, sets, NOW)
        assertEquals(0, again.newExercises)
        assertEquals(0, again.newSets)
        assertTrue(again.isEmpty)
    }

    @Test
    fun importingAgainCountsEverySetAsAlreadyKnown() {
        val (exercises, sets) = saved(plan)
        val again = NotesImport.plan(parsed, exercises, sets, NOW)
        assertEquals(parsed.setCount, again.exercises.sumOf { it.known })
    }

    @Test
    fun importingAgainAfterARecentlySetWasSavedOnAnEarlierDayAddsNothing() {
        val (exercises, sets) = saved(plan)
        for (later in listOf(DAY, 3 * DAY, 400 * DAY)) {
            val again = NotesImport.plan(parsed, exercises, sets, NOW + later)
            assertEquals(0, again.newSets, "$later seconds later")
            assertEquals(0, again.newExercises, "$later seconds later")
        }
    }

    @Test
    fun aRecentlySetWithDifferentRepsIsANewSetOnAnotherDay() {
        val (exercises, sets) = saved(plan)
        val changed = NotesParser.parse("Max pull-ups\n22 all time\n18 recently")
        val again = NotesImport.plan(changed, exercises, sets, NOW + 3 * DAY)
        assertEquals(listOf(18), again.exercises.single().sets.map { it.reps })
        assertEquals(listOf(NOW + 3 * DAY), again.exercises.single().sets.map { it.epochSeconds })
    }

    @Test
    fun anExerciseTheAppAlreadyHasIsNeverOverwritten() {
        val mine = FitnessTestData.exercise(
            id = "legs/hack-squat",
            name = "Hack squat",
            bodyPart = BodyPart.LEGS,
            equipment = Equipment.PLATE_LOADED,
            primary = Muscle.QUADS,
            repLow = 5,
            repHigh = 7,
            increment = 2.5,
            note = "seat on four",
        )
        val result = NotesImport.plan(parsed, listOf(mine), emptyList(), NOW)
        val hack = result.exercises.single { it.exercise.id == mine.id }
        assertEquals(mine, hack.exercise)
        assertFalse(hack.isNew)
        assertEquals(31, result.newExercises)
    }

    @Test
    fun anExistingExerciseStillGetsTheSetsItLacks() {
        val mine = FitnessTestData.exercise(id = "legs/hack-squat", name = "Hack squat", bodyPart = BodyPart.LEGS)
        val result = NotesImport.plan(parsed, listOf(mine), emptyList(), NOW)
        assertEquals(4, result.exercises.single { it.exercise.id == mine.id }.sets.size)
    }

    @Test
    fun aSetAlreadyInTheStoreIsNotPlannedAgainWhileTheRestOfItsExerciseIs() {
        val have = LoggedSet(1, "legs/hack-squat", 180.0, 12, 0, imported = true)
        val result = NotesImport.plan(parsed, emptyList(), listOf(have), NOW)
        val hack = result.exercises.single { it.exercise.id == "legs/hack-squat" }
        assertEquals(listOf(200.0, 220.0, 300.0), hack.sets.map { it.weight })
        assertEquals(1, hack.known)
    }

    @Test
    fun aSetLoggedInTheAppOnADayIsNotTheSameSetAsTheNotesUndatedRung() {
        val have = LoggedSet(1, "legs/hack-squat", 180.0, 12, NOW - DAY)
        val result = NotesImport.plan(parsed, emptyList(), listOf(have), NOW)
        assertEquals(4, result.exercises.single { it.exercise.id == "legs/hack-squat" }.sets.size)
    }

    // ---- An exercise the app already has, under another shelf ---------------------------------

    @Test
    fun withNoHeadingAnExerciseTheAppHasByThatNameIsTheOneMeantWhereverItIsFiled() {
        val misfiled = Exercise(id = "back/chest-press-machine", name = "Chest press machine", bodyPart = BodyPart.BACK, primary = Muscle.BACK)
        val again = NotesImport.plan(NotesParser.parse("Chest press machine\n- 180lbs - 12 reps"), listOf(misfiled), emptyList(), NOW)
        val item = again.exercises.single()
        assertFalse(item.isNew)
        assertEquals(misfiled, item.exercise)
        assertEquals(listOf("back/chest-press-machine"), item.sets.map { it.exerciseId })
    }

    @Test
    fun underAHeadingAnExerciseOfTheSameNameOnAnotherShelfIsADifferentOne() {
        val tris = Exercise(id = "triceps/single-arm-cable", name = "Single arm cable", bodyPart = BodyPart.TRICEPS, primary = Muscle.TRICEPS)
        val item = NotesImport.plan(NotesParser.parse("Bis\nSingle arm cable\n- 22lbs - 17 reps"), listOf(tris), emptyList(), NOW).exercises.single()
        assertTrue(item.isNew)
        assertEquals("biceps/single-arm-cable", item.exercise.id)
    }

    @Test
    fun twoCloseWeightsInADumbbellLadderAreNotAOnePoundJump() {
        val item = NotesImport.plan(NotesParser.parse("Bis\nStanding dumbbell\n- 44lbs - 15 reps\n- 45lbs - 11 reps\n- 49.5lbs - 13 reps"), emptyList(), emptyList(), NOW).exercises.single()
        assertEquals(Equipment.DUMBBELL, item.exercise.equipment)
        assertEquals(5.0, item.exercise.increment)
    }
}
