package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.FitnessTestData.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrengthTest {

    private val bench = exercise()
    private val pullUps = exercise(id = "back/pull-ups", name = "Pull-ups", bodyPart = BodyPart.BACK, loadKind = LoadKind.BODYWEIGHT, primary = Muscle.BACK)

    private val phaseStart = 100 * DAY

    private fun benchSet(id: Long, weight: Double, reps: Int, at: Long, imported: Boolean = false) =
        set(id, weight, reps, at, imported = imported)

    private fun pullUpSet(id: Long, added: Double, reps: Int, at: Long, bodyweight: Double? = 180.0) =
        set(id, added, reps, at, exerciseId = pullUps.id, bodyweight = bodyweight)

    // ---- Epley --------------------------------------------------------------------------------

    @Test
    fun oneRepIsTheLoadItself() {
        assertEquals(225.0, Strength.e1rm(225.0, 1))
    }

    @Test
    fun moreRepsRaiseTheEstimateByAThirtiethOfTheLoadEach() {
        assertEquals(200.0, Strength.e1rm(100.0, 30), 1e-9)
        assertEquals(100.0 * (1 + 10 / 30.0), Strength.e1rm(100.0, 10), 1e-9)
    }

    @Test
    fun noRepsOrNoLoadIsNoEstimate() {
        assertEquals(0.0, Strength.e1rm(100.0, 0))
        assertEquals(0.0, Strength.e1rm(100.0, -3))
        assertEquals(0.0, Strength.e1rm(0.0, 10))
        assertEquals(0.0, Strength.e1rm(-5.0, 10))
    }

    @Test
    fun repsToBeatTurnsTheEstimateRoundToTheRepsThatWouldExceedIt() {
        for (weight in listOf(45.0, 62.5, 100.0, 135.0, 225.0)) {
            for (reps in 2..25) {
                assertEquals(reps + 1, Strength.repsToBeat(Strength.e1rm(weight, reps), weight), "$weight x $reps")
            }
        }
    }

    @Test
    fun repsToBeatASingleAtTheSameWeightIsTwo() {
        // A single is the load itself, so another single ties it: it takes two to beat.
        assertEquals(2, Strength.repsToBeat(Strength.e1rm(225.0, 1), 225.0))
    }

    @Test
    fun repsToBeatAtAHeavierWeightNeedsFewer() {
        val estimate = Strength.e1rm(100.0, 12)
        assertTrue(Strength.repsToBeat(estimate, 105.0) < 12)
    }

    @Test
    fun repsToBeatIsNeverFewerThanOne() {
        assertEquals(1, Strength.repsToBeat(50.0, 100.0))
    }

    @Test
    fun repsToBeatWithNoLoadIsOne() {
        assertEquals(1, Strength.repsToBeat(200.0, 0.0))
        assertEquals(1, Strength.repsToBeat(200.0, -10.0))
    }

    // ---- Load and score -----------------------------------------------------------------------

    @Test
    fun aWeightedLiftsLoadIsTheWeightWritten() {
        assertEquals(135.0, Strength.load(bench, benchSet(1, 135.0, 8, phaseStart)))
    }

    @Test
    fun aBodyweightLiftsLoadIsTheBodyPlusWhatWasAdded() {
        assertEquals(205.0, Strength.load(pullUps, pullUpSet(1, 25.0, 8, phaseStart)))
    }

    @Test
    fun aBodyweightNotedOnTheSetBeatsTheOneGivenForTheDay() {
        assertEquals(205.0, Strength.load(pullUps, pullUpSet(1, 25.0, 8, phaseStart, bodyweight = 180.0), bodyweight = 150.0))
    }

    @Test
    fun aBodyweightLiftUsesTheGivenBodyweightWhenTheSetHasNone() {
        assertEquals(175.0, Strength.load(pullUps, pullUpSet(1, 0.0, 8, phaseStart, bodyweight = null), bodyweight = 175.0))
    }

    @Test
    fun aBodyweightLiftWithNoBodyweightAnywhereAssumesTheDefault() {
        assertEquals(Strength.ASSUMED_BODYWEIGHT + 10.0, Strength.load(pullUps, pullUpSet(1, 10.0, 8, phaseStart, bodyweight = null)))
    }

    @Test
    fun theScoreOfABodyweightSetCountsTheBody() {
        assertEquals(Strength.e1rm(205.0, 11), Strength.score(pullUps, pullUpSet(1, 25.0, 11, phaseStart)), 1e-9)
    }

    // ---- Best ---------------------------------------------------------------------------------

    @Test
    fun theBestSetIsTheOneWithTheHighestEstimate() {
        val sets = listOf(benchSet(1, 100.0, 10, 1), benchSet(2, 120.0, 5, 2), benchSet(3, 110.0, 6, 3))
        // 133.3, 140, 132
        assertEquals(2L, Strength.best(bench, sets)?.id)
    }

    @Test
    fun theBestOfNothingIsNothing() {
        assertNull(Strength.best(bench, emptyList()))
    }

    @Test
    fun aSetWithNoRepsIsNeverTheBest() {
        assertNull(Strength.best(bench, listOf(benchSet(1, 300.0, 0, 1))))
    }

    @Test
    fun whenTwoSetsTieTheLaterOneIsTheBest() {
        val sets = listOf(benchSet(1, 100.0, 10, 5 * DAY), benchSet(2, 100.0, 10, 9 * DAY))
        assertEquals(2L, Strength.best(bench, sets)?.id)
    }

    @Test
    fun whenTwoSetsTieOnTheSameSecondTheHigherIdIsTheBest() {
        val sets = listOf(benchSet(7, 100.0, 10, 5 * DAY), benchSet(3, 100.0, 10, 5 * DAY))
        assertEquals(7L, Strength.best(bench, sets)?.id)
    }

    @Test
    fun theBestBodyweightSetCountsTheBodyOnEachSet() {
        val lighter = pullUpSet(1, 0.0, 12, 1, bodyweight = 160.0)
        val heavier = pullUpSet(2, 0.0, 12, 2, bodyweight = 190.0)
        assertEquals(2L, Strength.best(pullUps, listOf(lighter, heavier))?.id)
    }

    // ---- Ladder -------------------------------------------------------------------------------

    @Test
    fun theLadderHasOneRungForEachWeightLightestFirst() {
        val sets = listOf(benchSet(1, 120.0, 8, 1), benchSet(2, 100.0, 10, 2), benchSet(3, 110.0, 9, 3))
        assertEquals(listOf(100.0, 110.0, 120.0), Strength.ladder(sets).map { it.weight })
    }

    @Test
    fun aRungHoldsTheMostRepsEverDoneAtItsWeight() {
        val sets = listOf(benchSet(1, 100.0, 8, 1), benchSet(2, 100.0, 11, 2), benchSet(3, 100.0, 9, 3))
        assertEquals(11, Strength.ladder(sets).single().reps)
    }

    @Test
    fun aRungRemembersWhenItWasLastUsed() {
        val sets = listOf(benchSet(1, 100.0, 8, 3 * DAY), benchSet(2, 100.0, 11, 9 * DAY), benchSet(3, 100.0, 9, 5 * DAY))
        assertEquals(9 * DAY, Strength.ladder(sets).single().lastEpochSeconds)
    }

    @Test
    fun aRungsPhaseRepsAreTheMostSinceThePhaseBegan() {
        val sets = listOf(benchSet(1, 100.0, 12, phaseStart - DAY), benchSet(2, 100.0, 9, phaseStart + DAY), benchSet(3, 100.0, 10, phaseStart + 2 * DAY))
        val rung = Strength.ladder(sets, phaseStart).single()
        assertEquals(12, rung.reps)
        assertEquals(10, rung.phaseReps)
    }

    @Test
    fun aRungNotUsedSinceThePhaseBeganHasNoPhaseReps() {
        val sets = listOf(benchSet(1, 100.0, 12, phaseStart - DAY))
        assertNull(Strength.ladder(sets, phaseStart).single().phaseReps)
    }

    @Test
    fun aBenchmarkFromTheNotesBelongsToNoPhase() {
        val sets = listOf(benchSet(1, 100.0, 12, 0, imported = true))
        val rung = Strength.ladder(sets, 0).single()
        assertEquals(12, rung.reps)
        assertNull(rung.phaseReps)
    }

    @Test
    fun theLadderLeavesOutSetsWithNoReps() {
        val sets = listOf(benchSet(1, 100.0, 0, 1), benchSet(2, 110.0, 8, 2))
        assertEquals(listOf(110.0), Strength.ladder(sets).map { it.weight })
    }

    @Test
    fun theLadderOfNothingIsEmpty() {
        assertEquals(emptyList(), Strength.ladder(emptyList()))
    }

    // ---- Records ------------------------------------------------------------------------------

    @Test
    fun aFirstEverSetIsNoRecord() {
        assertNull(Strength.record(bench, benchSet(1, 135.0, 8, phaseStart), emptyList(), phaseStart))
    }

    @Test
    fun aSetListedAmongItsOwnPredecessorsIsStillAFirstSet() {
        val first = benchSet(1, 135.0, 8, phaseStart)
        assertNull(Strength.record(bench, first, listOf(first), phaseStart))
    }

    @Test
    fun aFirstSetAfterOnlyZeroRepSetsIsNoRecord() {
        assertNull(Strength.record(bench, benchSet(2, 135.0, 8, phaseStart), listOf(benchSet(1, 300.0, 0, phaseStart - DAY)), phaseStart))
    }

    @Test
    fun aSetWithNoRepsIsNoRecord() {
        assertNull(Strength.record(bench, benchSet(2, 400.0, 0, phaseStart), listOf(benchSet(1, 100.0, 10, phaseStart - DAY)), phaseStart))
    }

    @Test
    fun moreWeightThanEverLiftedIsAnAllTimeWeightRecord() {
        val before = listOf(benchSet(1, 135.0, 8, phaseStart - 5 * DAY), benchSet(2, 145.0, 5, phaseStart - 3 * DAY))
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.WEIGHT), Strength.record(bench, benchSet(3, 150.0, 1, phaseStart), before, phaseStart))
    }

    @Test
    fun aHeavierSetIsAWeightRecordEvenWithFewerRepsAndALowerEstimate() {
        val before = listOf(benchSet(1, 100.0, 20, phaseStart - DAY))
        // 105 x 1 is 105 against 166.7, but it is more weight than was ever moved.
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.WEIGHT), Strength.record(bench, benchSet(2, 105.0, 1, phaseStart), before, phaseStart))
    }

    @Test
    fun moreRepsAtAnOldWeightIsAnAllTimeRepsRecord() {
        val before = listOf(benchSet(1, 135.0, 8, phaseStart - 5 * DAY), benchSet(2, 145.0, 6, phaseStart - 3 * DAY))
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.REPS), Strength.record(bench, benchSet(3, 135.0, 9, phaseStart), before, phaseStart))
    }

    @Test
    fun moreRepsThanAnythingAtThatWeightIsARepsRecordEvenWhenHeavierSetsHadFewer() {
        val before = listOf(benchSet(1, 100.0, 8, phaseStart - DAY), benchSet(2, 120.0, 5, phaseStart - DAY))
        // 110 x 6 is under 120 x 5 in weight but over nothing: no set at 110 or more reached 6.
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.REPS), Strength.record(bench, benchSet(3, 110.0, 6, phaseStart), before, phaseStart))
    }

    @Test
    fun matchingTheBestSetIsNoRecord() {
        val before = listOf(benchSet(1, 135.0, 8, phaseStart - 5 * DAY))
        assertNull(Strength.record(bench, benchSet(2, 135.0, 8, phaseStart), before, phaseStart))
    }

    @Test
    fun matchingAnOldBestInsideThePhaseIsNoRecordEither() {
        val before = listOf(benchSet(1, 135.0, 8, phaseStart + DAY))
        assertNull(Strength.record(bench, benchSet(2, 135.0, 8, phaseStart + 3 * DAY), before, phaseStart))
    }

    @Test
    fun fewerRepsAtAnOldWeightIsNoRecord() {
        val before = listOf(benchSet(1, 135.0, 8, phaseStart - DAY), benchSet(2, 135.0, 9, phaseStart + DAY))
        assertNull(Strength.record(bench, benchSet(3, 135.0, 7, phaseStart + 2 * DAY), before, phaseStart))
    }

    @Test
    fun theBestOfThePhaseBelowAnOldBestIsAPhaseRecord() {
        val before = listOf(
            benchSet(1, 100.0, 10, phaseStart - 20 * DAY),
            benchSet(2, 90.0, 8, phaseStart + 2 * DAY),
        )
        // 90 x 9 is 117 against 114 this phase, and well under the 133 of before it began.
        assertEquals(Record(RecordScope.PHASE, RecordKind.ESTIMATE), Strength.record(bench, benchSet(3, 90.0, 9, phaseStart + 5 * DAY), before, phaseStart))
    }

    @Test
    fun theFirstSetOfAPhaseIsWhereItStartsFromNotARecordInIt() {
        val before = listOf(benchSet(1, 100.0, 10, phaseStart - 20 * DAY))
        assertNull(Strength.record(bench, benchSet(2, 90.0, 8, phaseStart + DAY), before, phaseStart))
    }

    @Test
    fun aBenchmarkFromTheNotesDoesNotCountAsAnythingInThePhase() {
        val before = listOf(benchSet(1, 100.0, 10, 0, imported = true))
        assertNull(Strength.record(bench, benchSet(2, 90.0, 9, phaseStart + DAY), before, phaseStart))
    }

    @Test
    fun aPhaseRecordNeedsToBeatTheBestOfThePhaseNotJustThePhasesFirstSet() {
        val before = listOf(
            benchSet(1, 100.0, 10, phaseStart - 20 * DAY),
            benchSet(2, 90.0, 10, phaseStart + DAY),
            benchSet(3, 90.0, 8, phaseStart + 2 * DAY),
        )
        assertNull(Strength.record(bench, benchSet(4, 90.0, 9, phaseStart + 3 * DAY), before, phaseStart))
    }

    @Test
    fun aSetThatBeatsTheAllTimeBestIsAnAllTimeRecordNotAPhaseOne() {
        val before = listOf(
            benchSet(1, 100.0, 10, phaseStart - 20 * DAY),
            benchSet(2, 90.0, 8, phaseStart + 2 * DAY),
        )
        val record = Strength.record(bench, benchSet(3, 100.0, 11, phaseStart + 5 * DAY), before, phaseStart)
        assertEquals(RecordScope.ALL_TIME, record?.scope)
    }

    @Test
    fun aSetBelowEveryOldSetWithAnEmptyPhaseIsNoRecord() {
        val before = listOf(benchSet(1, 100.0, 10, phaseStart - 20 * DAY), benchSet(2, 120.0, 6, phaseStart - 10 * DAY))
        assertNull(Strength.record(bench, benchSet(3, 100.0, 9, phaseStart + DAY), before, phaseStart))
    }

    @Test
    fun aSetIsNeverComparedWithItself() {
        val later = benchSet(9, 300.0, 10, phaseStart + 10 * DAY)
        val before = listOf(benchSet(1, 100.0, 10, phaseStart - DAY), later)
        // The log hands over every set; the one being judged is dropped from what it is judged against.
        assertEquals(RecordScope.ALL_TIME, Strength.record(bench, benchSet(9, 150.0, 5, phaseStart), before, phaseStart)?.scope)
    }

    @Test
    fun aBodyweightLiftAddingLoadIsAWeightRecord() {
        val before = listOf(pullUpSet(1, 0.0, 12, phaseStart - DAY))
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.WEIGHT), Strength.record(pullUps, pullUpSet(2, 25.0, 5, phaseStart), before, phaseStart))
    }

    @Test
    fun aBodyweightLiftWithMoreRepsAtTheSameBodyIsARepsRecord() {
        val before = listOf(pullUpSet(1, 0.0, 12, phaseStart - DAY))
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.REPS), Strength.record(pullUps, pullUpSet(2, 0.0, 13, phaseStart), before, phaseStart))
    }

    @Test
    fun aBodyweightLiftDoneLighterThanBeforeWithTheSameRepsIsNoRecord() {
        val before = listOf(pullUpSet(1, 0.0, 12, phaseStart - DAY, bodyweight = 180.0))
        assertNull(Strength.record(pullUps, pullUpSet(2, 0.0, 12, phaseStart, bodyweight = 170.0), before, phaseStart))
    }

    @Test
    fun aBodyweightLiftWithMoreRepsAtAHeavierBodyIsARecord() {
        val before = listOf(pullUpSet(1, 0.0, 12, phaseStart - DAY, bodyweight = 170.0))
        assertEquals(Record(RecordScope.ALL_TIME, RecordKind.WEIGHT), Strength.record(pullUps, pullUpSet(2, 0.0, 12, phaseStart, bodyweight = 180.0), before, phaseStart))
    }

    @Test
    fun theBodyweightGivenFillsInForSetsThatNotedNone() {
        val before = listOf(pullUpSet(1, 0.0, 12, phaseStart - DAY, bodyweight = null))
        assertEquals(
            Record(RecordScope.ALL_TIME, RecordKind.WEIGHT),
            Strength.record(pullUps, pullUpSet(2, 10.0, 5, phaseStart, bodyweight = null), before, phaseStart, bodyweight = 175.0),
        )
    }
}
