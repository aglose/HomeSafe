package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.NOW
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.FitnessTestData.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProgressionTest {

    private val bench = exercise()
    private val pullUps = exercise(id = "back/pull-ups", name = "Pull-ups", bodyPart = BodyPart.BACK, loadKind = LoadKind.BODYWEIGHT, primary = Muscle.BACK)

    /** A set [daysAgo] days before the test's "now". */
    private fun logged(id: Long, weight: Double, reps: Int, daysAgo: Long, imported: Boolean = false, bodyweight: Double? = null) =
        set(id, weight, reps, NOW - daysAgo * DAY, imported = imported, bodyweight = bodyweight)

    /** A rung from the notes: no date, imported. */
    private fun benchmark(id: Long, weight: Double, reps: Int) = set(id, weight, reps, 0, imported = true)

    private fun target(exercise: Exercise, sets: List<LoggedSet>, phase: PhaseKind = PhaseKind.BULK) =
        assertNotNull(Progression.nextTarget(exercise, sets, phase, NOW))

    // ---- Nothing to go on ---------------------------------------------------------------------

    @Test
    fun withNoSetsThereIsNoTarget() {
        assertNull(Progression.nextTarget(bench, emptyList(), PhaseKind.BULK, NOW))
    }

    @Test
    fun withOnlySetsOfNoRepsThereIsNoTarget() {
        assertNull(Progression.nextTarget(bench, listOf(logged(1, 100.0, 0, 1)), PhaseKind.BULK, NOW))
    }

    // ---- From the notes alone -----------------------------------------------------------------

    @Test
    fun theNotesAloneStartFromTheHeaviestRungWhoseRepsWereInsideTheBand() {
        val result = target(bench, listOf(benchmark(1, 100.0, 14), benchmark(2, 110.0, 12), benchmark(3, 120.0, 9)))
        assertEquals(Target(120.0, 9, TargetReason.BENCHMARK, toBeat = result.toBeat, allTime = result.allTime), result)
        assertEquals(3L, result.toBeat.id)
        assertEquals(3L, result.allTime?.id)
    }

    @Test
    fun aRungAboveTheBandStillCountsAsInsideIt() {
        val result = target(bench, listOf(benchmark(1, 100.0, 20), benchmark(2, 110.0, 15)))
        assertEquals(110.0, result.weight)
        assertEquals(15, result.reps)
    }

    @Test
    fun aHeavierRungUnderTheBandIsPassedOverForTheOneBelowIt() {
        val result = target(bench, listOf(benchmark(1, 100.0, 14), benchmark(2, 110.0, 10), benchmark(3, 120.0, 5)))
        assertEquals(110.0, result.weight)
        assertEquals(10, result.reps)
        assertEquals(TargetReason.BENCHMARK, result.reason)
    }

    @Test
    fun withNoRungInTheBandTheNotesBestSetIsTheStart() {
        val result = target(bench, listOf(benchmark(1, 100.0, 6), benchmark(2, 110.0, 4)))
        assertEquals(110.0, result.weight)
        assertEquals(4, result.reps)
        assertEquals(TargetReason.BENCHMARK, result.reason)
    }

    @Test
    fun severalSetsAtTheChosenRungStartFromTheOneWithMostReps() {
        val result = target(bench, listOf(benchmark(1, 100.0, 9), benchmark(2, 100.0, 11), benchmark(3, 110.0, 4)))
        assertEquals(2L, result.toBeat.id)
        assertEquals(100.0, result.weight)
        assertEquals(11, result.reps)
    }

    @Test
    fun aSetTheNotesDatedLongAgoIsHistoryNotALastSession() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 400, imported = true)))
        assertEquals(TargetReason.BENCHMARK, result.reason)
        assertEquals(100.0, result.weight)
        assertEquals(10, result.reps)
    }

    @Test
    fun aSetImportedAsRecentlyStandsAsTheLastSession() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 2, imported = true)))
        assertEquals(TargetReason.ADD_REP, result.reason)
        assertEquals(11, result.reps)
    }

    @Test
    fun theNotesBestIsTheAllTimeBestAHardSessionLaterIsMeasuredAgainst() {
        val result = target(bench, listOf(benchmark(1, 120.0, 14), logged(2, 100.0, 8, daysAgo = 3)))
        assertEquals(Target(100.0, 10, TargetReason.REBUILD, toBeat = result.toBeat, allTime = result.allTime, ofBest = result.ofBest), result)
        assertEquals(1L, result.allTime?.id)
        assertEquals(2L, result.toBeat.id)
        // 126.7 against 176.
        assertEquals(0.72f, result.ofBest, 0.01f)
    }

    // ---- Which sets say where the lifter is now -----------------------------------------------

    @Test
    fun currentLeavesOutBenchmarksAndSetsWithNoReps() {
        val sets = listOf(benchmark(1, 100.0, 10), logged(2, 100.0, 0, 1), logged(3, 100.0, 10, 1))
        assertEquals(listOf(3L), Progression.current(sets, NOW).map { it.id })
    }

    @Test
    fun currentKeepsEverythingLoggedInTheAppHoweverOld() {
        val sets = listOf(logged(1, 100.0, 10, daysAgo = 900))
        assertEquals(listOf(1L), Progression.current(sets, NOW).map { it.id })
    }

    @Test
    fun currentKeepsAnImportedSetOnlyWhileItIsRecent() {
        val sets = listOf(
            logged(1, 100.0, 10, daysAgo = 44, imported = true),
            logged(2, 100.0, 10, daysAgo = 45, imported = true),
            logged(3, 100.0, 10, daysAgo = 200, imported = true),
        )
        assertEquals(listOf(1L), Progression.current(sets, NOW).map { it.id })
    }

    // ---- Last session -------------------------------------------------------------------------

    @Test
    fun theLastSessionBestOfNothingIsNothing() {
        assertNull(Progression.lastSessionBest(bench, emptyList()))
    }

    @Test
    fun theLastSessionIsTheDayOfTheLatestSetNotTheHeaviestEverDone() {
        val sets = listOf(logged(1, 110.0, 8, daysAgo = 7), logged(2, 100.0, 8, daysAgo = 2))
        assertEquals(2L, Progression.lastSessionBest(bench, sets)?.id)
    }

    @Test
    fun setsWithinHalfADayOfTheLastOneAreTheSameSession() {
        val latest = NOW - DAY
        val sets = listOf(
            set(1, 110.0, 5, latest - 11 * 3_600L),
            set(2, 100.0, 8, latest),
        )
        // 128.3 beats 126.7, and was done eleven hours before.
        assertEquals(1L, Progression.lastSessionBest(bench, sets)?.id)
    }

    @Test
    fun aSetHalfADayBeforeTheLastOneIsAnotherSession() {
        val latest = NOW - DAY
        val sets = listOf(
            set(1, 110.0, 5, latest - DAY / 2),
            set(2, 100.0, 8, latest),
        )
        assertEquals(2L, Progression.lastSessionBest(bench, sets)?.id)
    }

    // ---- Double progression -------------------------------------------------------------------

    @Test
    fun insideTheBandTheTargetIsOneMoreRepAtTheSameWeight() {
        val result = target(bench, listOf(logged(1, 100.0, 9, daysAgo = 3)))
        assertEquals(Target(100.0, 10, TargetReason.ADD_REP, toBeat = result.toBeat, allTime = result.allTime, ofBest = 1f), result)
        assertEquals(1L, result.toBeat.id)
    }

    @Test
    fun theTargetIsWorkedFromTheBestSetOfTheLastDay() {
        val sets = listOf(logged(1, 100.0, 9, daysAgo = 3), set(2, 100.0, 11, NOW - 3 * DAY + 600))
        val result = target(bench, sets)
        assertEquals(12, result.reps)
        assertEquals(2L, result.toBeat.id)
    }

    @Test
    fun atTheTopOfTheBandTheTargetIsMoreWeightAndFewerReps() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 3)))
        assertEquals(105.0, result.weight)
        // 105 x 11 is the first to beat 100 x 12.
        assertEquals(11, result.reps)
        assertEquals(TargetReason.ADD_WEIGHT, result.reason)
    }

    @Test
    fun theHeavierWeightIsARungAlreadyOnTheLadderWhenOneIsNear() {
        val result = target(bench, listOf(logged(1, 107.5, 6, daysAgo = 10), logged(2, 100.0, 12, daysAgo = 3)))
        assertEquals(107.5, result.weight)
        assertEquals(10, result.reps)
        assertEquals(TargetReason.ADD_WEIGHT, result.reason)
    }

    @Test
    fun aRungFartherThanAJumpAndAHalfIsIgnoredForOneIncrementOn() {
        val result = target(bench, listOf(logged(1, 120.0, 3, daysAgo = 10), logged(2, 100.0, 12, daysAgo = 3)))
        assertEquals(105.0, result.weight)
    }

    @Test
    fun theRepsAtTheHeavierWeightAreHeldInsideTheBand() {
        val result = target(exercise(increment = 20.0), listOf(logged(1, 100.0, 12, daysAgo = 3)))
        assertEquals(120.0, result.weight)
        // Only six reps at 120 would beat 100 x 12, but the band starts at eight.
        assertEquals(8, result.reps)
    }

    @Test
    fun aBodyweightLiftMovesUpByWhatIsAddedWithTheBodyCounted() {
        val result = target(pullUps, listOf(logged(1, 10.0, 12, daysAgo = 3, bodyweight = 180.0)))
        assertEquals(15.0, result.weight)
        // 195 x 11 is the first to beat 190 x 12.
        assertEquals(11, result.reps)
        assertEquals(TargetReason.ADD_WEIGHT, result.reason)
    }

    @Test
    fun aBodyweightLiftWithNothingAddedKeepsAddingRepsPastTheTopOfTheBand() {
        val result = target(pullUps, listOf(logged(1, 0.0, 15, daysAgo = 3, bodyweight = 180.0)))
        assertEquals(0.0, result.weight)
        assertEquals(16, result.reps)
        assertEquals(TargetReason.ADD_REP, result.reason)
    }

    // ---- A cut --------------------------------------------------------------------------------

    @Test
    fun onACutTheTargetMatchesLastTime() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 3)), PhaseKind.CUT)
        assertEquals(Target(100.0, 10, TargetReason.HOLD, toBeat = result.toBeat, allTime = result.allTime), result)
    }

    @Test
    fun onACutTheTopOfTheBandHoldsInsteadOfAddingWeight() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 3)), PhaseKind.CUT)
        assertEquals(100.0, result.weight)
        assertEquals(12, result.reps)
        assertEquals(TargetReason.HOLD, result.reason)
    }

    @Test
    fun onACutRepsUnderTheBandStepTheWeightDown() {
        val result = target(bench, listOf(logged(1, 100.0, 6, daysAgo = 3)), PhaseKind.CUT)
        assertEquals(95.0, result.weight)
        assertEquals(8, result.reps)
        assertEquals(TargetReason.BACK_OFF, result.reason)
    }

    @Test
    fun theStepDownIsARungAlreadyOnTheLadderWhenOneIsNear() {
        val result = target(bench, listOf(logged(1, 92.5, 8, daysAgo = 20), logged(2, 100.0, 6, daysAgo = 3)), PhaseKind.CUT)
        assertEquals(92.5, result.weight)
        assertEquals(TargetReason.BACK_OFF, result.reason)
    }

    @Test
    fun theRepsAfterABackOffAreHeldInsideTheBand() {
        val result = target(exercise(increment = 20.0), listOf(logged(1, 100.0, 7, daysAgo = 3)), PhaseKind.CUT)
        assertEquals(80.0, result.weight)
        // 80 would take sixteen to beat 100 x 7; the band stops at twelve.
        assertEquals(12, result.reps)
        assertEquals(TargetReason.BACK_OFF, result.reason)
    }

    @Test
    fun aBodyweightLiftWithNothingAddedHasNothingToBackOffFromOnACut() {
        val result = target(pullUps, listOf(logged(1, 0.0, 5, daysAgo = 3, bodyweight = 180.0)), PhaseKind.CUT)
        assertEquals(TargetReason.HOLD, result.reason)
        assertEquals(5, result.reps)
    }

    // ---- Climbing back ------------------------------------------------------------------------

    @Test
    fun underNinetySevenPercentOfTheBestTheClimbIsARebuildWithBiggerSteps() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 30), logged(2, 95.0, 10, daysAgo = 2)))
        assertEquals(TargetReason.REBUILD, result.reason)
        assertEquals(95.0, result.weight)
        // Two more reps, held at the top of the band.
        assertEquals(12, result.reps)
        assertEquals(0.905f, result.ofBest, 0.001f)
    }

    @Test
    fun aRebuildAddsTwoRepsWhenTheBandHasRoom() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 30), logged(2, 80.0, 8, daysAgo = 2)))
        assertEquals(TargetReason.REBUILD, result.reason)
        assertEquals(80.0, result.weight)
        assertEquals(10, result.reps)
    }

    @Test
    fun aRebuildCapsTheRepsAtTheTopOfTheBand() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 30), logged(2, 90.0, 11, daysAgo = 2)))
        assertEquals(TargetReason.REBUILD, result.reason)
        assertEquals(12, result.reps)
    }

    @Test
    fun aRebuildAtTheTopOfTheBandAddsWeightAndSaysItIsARebuild() {
        val result = target(bench, listOf(logged(1, 100.0, 12, daysAgo = 30), logged(2, 95.0, 12, daysAgo = 2)))
        assertEquals(TargetReason.REBUILD, result.reason)
        assertEquals(100.0, result.weight)
        assertEquals(10, result.reps)
    }

    @Test
    fun aBodyweightRebuildWithNothingAddedKeepsAddingRepsPastTheBand() {
        val result = target(pullUps, listOf(logged(1, 0.0, 16, daysAgo = 30, bodyweight = 180.0), logged(2, 0.0, 14, daysAgo = 2, bodyweight = 180.0)))
        assertEquals(TargetReason.REBUILD, result.reason)
        assertEquals(0.0, result.weight)
        assertEquals(15, result.reps)
    }

    @Test
    fun within97PercentOfTheBestIsStillOrdinaryProgress() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 30), logged(2, 99.0, 10, daysAgo = 2)))
        assertEquals(TargetReason.ADD_REP, result.reason)
        assertEquals(11, result.reps)
    }

    // ---- Back after a break -------------------------------------------------------------------

    @Test
    fun layoffFactorIsNullUnderEighteenDays() {
        assertNull(Progression.layoffFactor(0))
        assertNull(Progression.layoffFactor(17))
    }

    @Test
    fun layoffFactorFallsInStepsAsTheBreakGrows() {
        assertEquals(0.90, Progression.layoffFactor(18))
        assertEquals(0.90, Progression.layoffFactor(31))
        assertEquals(0.82, Progression.layoffFactor(32))
        assertEquals(0.82, Progression.layoffFactor(60))
        assertEquals(0.72, Progression.layoffFactor(61))
        assertEquals(0.72, Progression.layoffFactor(120))
        assertEquals(0.65, Progression.layoffFactor(121))
        assertEquals(0.65, Progression.layoffFactor(900))
    }

    @Test
    fun seventeenDaysAwayIsNotYetABreak() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 17)))
        assertEquals(TargetReason.ADD_REP, result.reason)
    }

    @Test
    fun eighteenDaysAwayEasesBackInAtNinetyPercentOfTheLastNumbers() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 18)))
        assertEquals(TargetReason.EASE_IN, result.reason)
        // 100 x 10 is 133.3; 90 percent of it is 120, which ten reps reach at 90.
        assertEquals(90.0, result.weight)
        assertEquals(10, result.reps)
        assertEquals(1L, result.toBeat.id)
    }

    @Test
    fun aLongerBreakEasesInLower() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 40)))
        assertEquals(TargetReason.EASE_IN, result.reason)
        assertEquals(80.0, result.weight)
    }

    @Test
    fun theEaseInWeightIsARungAlreadyLiftedWhenOneSitsAboveTheRoundNumber() {
        val result = target(exercise(increment = 10.0), listOf(logged(1, 81.0, 8, daysAgo = 50), logged(2, 100.0, 10, daysAgo = 40)))
        assertEquals(81.0, result.weight)
    }

    @Test
    fun theEaseInRepsAreTheMiddleOfTheBand() {
        val result = target(exercise(repLow = 6, repHigh = 10), listOf(logged(1, 100.0, 8, daysAgo = 25)))
        assertEquals(8, result.reps)
        assertEquals(TargetReason.EASE_IN, result.reason)
    }

    @Test
    fun aBreakEasesInOnACutToo() {
        val result = target(bench, listOf(logged(1, 100.0, 10, daysAgo = 20)), PhaseKind.CUT)
        assertEquals(TargetReason.EASE_IN, result.reason)
    }

    @Test
    fun aBodyweightLiftEasesInOnTheWeightAddedWithTheBodyLeftOut() {
        val result = target(pullUps, listOf(logged(1, 25.0, 10, daysAgo = 20, bodyweight = 180.0)))
        assertEquals(TargetReason.EASE_IN, result.reason)
        // 205 x 10 is 273.3; 90 percent of it needs less than the body alone to lift for ten reps.
        assertEquals(0.0, result.weight)
        assertEquals(10, result.reps)
    }

    @Test
    fun aBreakIsCountedFromTheLastSetLoggedNotFromAnImportedOne() {
        val sets = listOf(logged(1, 100.0, 10, daysAgo = 3), logged(2, 100.0, 10, daysAgo = 60, imported = true))
        val result = target(bench, sets)
        assertEquals(TargetReason.ADD_REP, result.reason)
        assertEquals(1L, result.toBeat.id)
    }

    // ---- Steps --------------------------------------------------------------------------------

    @Test
    fun stepUpWithNothingNearIsOneIncrementOn() {
        assertEquals(105.0, Progression.stepUp(100.0, bench, listOf(logged(1, 100.0, 10, 1))))
    }

    @Test
    fun stepUpTakesTheNearestHeavierRungWithinAJumpAndAHalf() {
        val sets = listOf(logged(1, 100.0, 10, 1), logged(2, 107.0, 10, 1), logged(3, 102.5, 10, 1), logged(4, 108.0, 10, 1))
        assertEquals(102.5, Progression.stepUp(100.0, bench, sets))
    }

    @Test
    fun stepUpIgnoresARungJustPastAJumpAndAHalf() {
        assertEquals(105.0, Progression.stepUp(100.0, bench, listOf(logged(1, 108.0, 10, 1))))
    }

    @Test
    fun stepDownWithNothingNearIsOneIncrementBack() {
        assertEquals(95.0, Progression.stepDown(100.0, bench, listOf(logged(1, 100.0, 10, 1))))
    }

    @Test
    fun stepDownTakesTheNearestLighterRungWithinAJumpAndAHalf() {
        val sets = listOf(logged(1, 100.0, 10, 1), logged(2, 93.0, 10, 1), logged(3, 97.5, 10, 1), logged(4, 92.0, 10, 1))
        assertEquals(97.5, Progression.stepDown(100.0, bench, sets))
    }

    @Test
    fun stepDownNeverGoesBelowNothing() {
        assertEquals(0.0, Progression.stepDown(2.0, bench, emptyList()))
    }

    // ---- The band, read off the ladder --------------------------------------------------------

    @Test
    fun withFewerThanThreeRungsTheBandIsTheFallback() {
        assertEquals(8..12, Progression.inferRepBand(listOf(14, 12), 8..12))
        assertEquals(8..12, Progression.inferRepBand(emptyList(), 8..12))
    }

    @Test
    fun rungsWithNoRepsDoNotCountTowardTheThree() {
        assertEquals(6..10, Progression.inferRepBand(listOf(12, 0, 10, 0), 6..10))
    }

    @Test
    fun theBandTopsOutAtTheMedianOfTheRungsReps() {
        // Median 13, a four-rep band under it.
        assertEquals(9..13, Progression.inferRepBand(listOf(14, 13, 12), 8..12))
    }

    @Test
    fun theMedianOfAnEvenCountIsTheUpperMiddle() {
        assertEquals(8..12, Progression.inferRepBand(listOf(14, 8, 12, 10), 6..10))
    }

    @Test
    fun aBandTopFromFifteenIsAFiveRepBand() {
        assertEquals(10..15, Progression.inferRepBand(listOf(15, 15, 15), 8..12))
    }

    @Test
    fun aBandTopFromTwentyFiveIsASixRepBand() {
        assertEquals(22..28, Progression.inferRepBand(listOf(30, 28, 25), 8..12))
        assertEquals(19..25, Progression.inferRepBand(listOf(25, 25, 25), 8..12))
    }

    @Test
    fun aBandTopUnderEightIsRaisedToEight() {
        assertEquals(4..8, Progression.inferRepBand(listOf(5, 6, 4), 8..12))
    }

    @Test
    fun aBandTopOverThirtyIsLoweredToThirty() {
        assertEquals(24..30, Progression.inferRepBand(listOf(45, 40, 35), 8..12))
    }

    // ---- The increment, read off the ladder ---------------------------------------------------

    @Test
    fun withFewerThanTwoGapsTheIncrementIsTheFallback() {
        assertEquals(5.0, Progression.inferIncrement(listOf(100.0, 110.0), 5.0))
        assertEquals(5.0, Progression.inferIncrement(emptyList(), 5.0))
        assertEquals(5.0, Progression.inferIncrement(listOf(100.0, 100.0, 100.0), 5.0))
    }

    @Test
    fun theIncrementIsTheMedianGapOnTheLadder() {
        assertEquals(10.0, Progression.inferIncrement(listOf(120.0, 130.0, 140.0), 5.0))
        assertEquals(20.0, Progression.inferIncrement(listOf(180.0, 200.0, 220.0, 300.0), 5.0))
    }

    @Test
    fun theIncrementIsSnappedToASizePlatesAndStacksComeIn() {
        assertEquals(2.5, Progression.inferIncrement(listOf(10.0, 12.5, 15.0), 5.0))
        assertEquals(5.0, Progression.inferIncrement(listOf(100.0, 107.0, 114.0), 10.0))
        assertEquals(2.5, Progression.inferIncrement(listOf(100.0, 103.5, 107.0), 10.0))
    }

    @Test
    fun aMachinesPinNumbersStepByOne() {
        assertEquals(1.0, Progression.inferIncrement(listOf(14.0, 15.0, 16.0), 5.0))
    }

    @Test
    fun theIncrementIgnoresRepeatedWeightsAndTheOrderTheyAreGivenIn() {
        assertEquals(10.0, Progression.inferIncrement(listOf(140.0, 120.0, 130.0, 120.0, 140.0), 5.0))
    }

    @Test
    fun aHugeGapSnapsDownToTheLargestStep() {
        assertEquals(50.0, Progression.inferIncrement(listOf(0.5, 100.5, 200.5), 5.0))
    }

    @Test
    fun aMedianExactlyBetweenTwoStepsSnapsToTheSmallerOne() {
        assertEquals(5.0, Progression.inferIncrement(listOf(100.0, 107.5, 115.0), 5.0))
    }

    @Test
    fun theUsualJumpIsTheCommonestGapAndATieGoesToTheFallback() {
        assertEquals(20.0, Progression.inferIncrement(listOf(180.0, 200.0, 220.0, 230.0, 250.0, 270.0, 290.0, 320.0), 10.0))
        assertEquals(10.0, Progression.inferIncrement(listOf(180.0, 190.0, 210.0), 10.0))
        assertEquals(5.0, Progression.inferIncrement(listOf(44.0, 45.0, 49.5), 5.0))
    }
}
