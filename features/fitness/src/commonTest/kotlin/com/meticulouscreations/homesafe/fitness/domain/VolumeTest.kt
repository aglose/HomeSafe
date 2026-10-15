package com.meticulouscreations.homesafe.fitness.domain

import com.meticulouscreations.homesafe.fitness.FitnessTestData.DAY
import com.meticulouscreations.homesafe.fitness.FitnessTestData.exercise
import com.meticulouscreations.homesafe.fitness.FitnessTestData.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolumeTest {

    private val bench = exercise(secondary = listOf(Muscle.FRONT_DELTS, Muscle.TRICEPS))
    private val curl = exercise(id = "biceps/curl", name = "Curl", bodyPart = BodyPart.BICEPS, primary = Muscle.BICEPS)
    private val exercises = listOf(bench, curl).associateBy { it.id }

    private val from = 100 * DAY
    private val to = 107 * DAY

    private fun week(vararg sets: LoggedSet): Map<Muscle, Double> =
        Volume.weekly(exercises, sets.toList(), from, to).associate { it.muscle to it.sets }

    private fun benchSet(id: Long, at: Long, reps: Int = 8, imported: Boolean = false, exerciseId: String = bench.id) =
        set(id, 100.0, reps, at, exerciseId = exerciseId, imported = imported)

    // ---- A week's volume ----------------------------------------------------------------------

    @Test
    fun aSetCountsWholeForItsPrimaryMuscleAndHalfForEachHelper() {
        val totals = week(benchSet(1, from + DAY), benchSet(2, from + 2 * DAY), benchSet(3, from + 3 * DAY))
        assertEquals(3.0, totals.getValue(Muscle.CHEST))
        assertEquals(1.5, totals.getValue(Muscle.FRONT_DELTS))
        assertEquals(1.5, totals.getValue(Muscle.TRICEPS))
    }

    @Test
    fun everyMuscleIsListedInOrderWithNothingForThoseNotTrained() {
        val result = Volume.weekly(exercises, listOf(benchSet(1, from + DAY)), from, to)
        assertEquals(Muscle.entries, result.map { it.muscle })
        assertEquals(0.0, result.single { it.muscle == Muscle.QUADS }.sets)
    }

    @Test
    fun eachMuscleCarriesItsOwnBand() {
        val result = Volume.weekly(exercises, emptyList(), from, to)
        assertTrue(result.all { it.band == Volume.band(it.muscle) })
    }

    @Test
    fun setsOnTheEdgesOfTheWeekAreCounted() {
        val totals = week(benchSet(1, from), benchSet(2, to))
        assertEquals(2.0, totals.getValue(Muscle.CHEST))
    }

    @Test
    fun setsJustOutsideTheWeekAreNot() {
        val totals = week(benchSet(1, from - 1), benchSet(2, to + 1))
        assertEquals(0.0, totals.getValue(Muscle.CHEST))
    }

    @Test
    fun aBenchmarkFromTheNotesIsNotAWeeksWork() {
        // A window wide enough to take in the epoch itself, so only the set being a benchmark leaves it out.
        val totals = Volume.weekly(exercises, listOf(benchSet(1, 0, imported = true)), 0, to).associate { it.muscle to it.sets }
        assertEquals(0.0, totals.getValue(Muscle.CHEST))
    }

    @Test
    fun aSetBroughtInFromTheNotesIsNotAWeeksWorkEvenWhenStampedWithinIt() {
        assertEquals(0.0, week(benchSet(1, from + DAY, imported = true)).getValue(Muscle.CHEST))
    }

    @Test
    fun aSetWithNoRepsIsNotCounted() {
        assertEquals(0.0, week(benchSet(1, from + DAY, reps = 0)).getValue(Muscle.CHEST))
    }

    @Test
    fun aSetOfAnExerciseThatIsNotKnownIsLeftOut() {
        assertEquals(0.0, week(benchSet(1, from + DAY, exerciseId = "chest/gone")).getValue(Muscle.CHEST))
    }

    @Test
    fun setsOfDifferentExercisesAddUpOnASharedMuscle() {
        val pressdown = exercise(id = "triceps/pushdown", name = "Pushdown", bodyPart = BodyPart.TRICEPS, primary = Muscle.TRICEPS)
        val both = (exercises + (pressdown.id to pressdown))
        val sets = listOf(benchSet(1, from + DAY), benchSet(2, from + DAY, exerciseId = pressdown.id))
        val totals = Volume.weekly(both, sets, from, to).associate { it.muscle to it.sets }
        // Half a set from the press, one whole one from the pushdown.
        assertEquals(1.5, totals.getValue(Muscle.TRICEPS))
    }

    // ---- Against its band ---------------------------------------------------------------------

    private val chestBand = VolumeBand(4.0, 8.0, 12.0)

    private fun status(sets: Double) = MuscleVolume(Muscle.CHEST, sets, chestBand).status

    @Test
    fun noSetsIsNone() {
        assertEquals(VolumeStatus.NONE, status(0.0))
    }

    @Test
    fun underTheMinimumIsLow() {
        assertEquals(VolumeStatus.LOW, status(0.5))
        assertEquals(VolumeStatus.LOW, status(3.5))
    }

    @Test
    fun fromTheMinimumUpToTheTargetIsBuilding() {
        assertEquals(VolumeStatus.BUILDING, status(4.0))
        assertEquals(VolumeStatus.BUILDING, status(7.5))
    }

    @Test
    fun theTargetBandIsOnTargetInclusiveOfBothEnds() {
        assertEquals(VolumeStatus.ON_TARGET, status(8.0))
        assertEquals(VolumeStatus.ON_TARGET, status(12.0))
    }

    @Test
    fun pastTheTargetIsHigh() {
        assertEquals(VolumeStatus.HIGH, status(12.5))
    }

    @Test
    fun theFillIsTheSetsAgainstTheTopOfTheTargetBand() {
        assertEquals(0.0f, MuscleVolume(Muscle.CHEST, 0.0, chestBand).fill)
        assertEquals(0.5f, MuscleVolume(Muscle.CHEST, 6.0, chestBand).fill)
        assertEquals(1.0f, MuscleVolume(Muscle.CHEST, 12.0, chestBand).fill)
        assertEquals(1.25f, MuscleVolume(Muscle.CHEST, 15.0, chestBand).fill)
    }

    // ---- The bands ----------------------------------------------------------------------------

    @Test
    fun everyMuscleHasTheBandItsSizeCallsFor() {
        val large = VolumeBand(4.0, 8.0, 12.0)
        val medium = VolumeBand(3.0, 6.0, 12.0)
        val small = VolumeBand(2.0, 6.0, 10.0)
        val tiny = VolumeBand(2.0, 4.0, 8.0)
        val expected = mapOf(
            Muscle.CHEST to large, Muscle.BACK to large, Muscle.QUADS to large,
            Muscle.HAMSTRINGS to medium, Muscle.GLUTES to medium, Muscle.SIDE_DELTS to medium, Muscle.BICEPS to medium, Muscle.TRICEPS to medium,
            Muscle.FRONT_DELTS to small, Muscle.REAR_DELTS to small, Muscle.CALVES to small, Muscle.ABS to small, Muscle.TRAPS to small,
            Muscle.FOREARMS to tiny, Muscle.ADDUCTORS to tiny,
        )
        assertEquals(Muscle.entries.toSet(), expected.keys)
        for ((muscle, band) in expected) assertEquals(band, Volume.band(muscle), muscle.name)
    }

    // ---- Bodyweight trend ---------------------------------------------------------------------

    private fun daily(vararg pounds: Double, from: Long = 20_000) =
        pounds.mapIndexed { index, value -> BodyweightEntry(from + index, value) }

    @Test
    fun noWeighInsIsNoTrend() {
        val trend = BodyweightTrend.of(emptyList())
        assertEquals(BodyweightTrend(), trend)
        assertNull(trend.latest)
    }

    @Test
    fun aSingleWeighInIsItsOwnTrendWithNoRateYet() {
        val trend = BodyweightTrend.of(listOf(BodyweightEntry(20_000, 180.0)))
        assertEquals(listOf(TrendPoint(20_000, 180.0, 180.0)), trend.points)
        assertNull(trend.weeklyChange)
    }

    @Test
    fun theTrendMovesATenthOfTheWayToEachNewDaysReading() {
        val trend = BodyweightTrend.of(daily(180.0, 181.0))
        assertEquals(180.1, trend.points[1].trend, 1e-9)
        assertEquals(181.0, trend.points[1].pounds)
    }

    @Test
    fun aGapOfSeveralDaysCountsAsThatManyDaysOfSmoothing() {
        val trend = BodyweightTrend.of(listOf(BodyweightEntry(20_000, 180.0), BodyweightEntry(20_003, 183.0)))
        // 1 - 0.9^3 of the three pounds.
        assertEquals(180.0 + (1 - 0.9 * 0.9 * 0.9) * 3.0, trend.points[1].trend, 1e-9)
    }

    @Test
    fun weighInsGivenOutOfOrderAreReadInDateOrder() {
        val trend = BodyweightTrend.of(listOf(BodyweightEntry(20_002, 182.0), BodyweightEntry(20_000, 180.0), BodyweightEntry(20_001, 181.0)))
        assertEquals(listOf(20_000L, 20_001L, 20_002L), trend.points.map { it.epochDay })
        assertEquals(180.0, trend.points.first().trend)
    }

    @Test
    fun latestIsTheMostRecentPoint() {
        val trend = BodyweightTrend.of(daily(180.0, 181.0, 182.0))
        assertEquals(20_002L, trend.latest?.epochDay)
        assertEquals(182.0, trend.latest?.pounds)
    }

    @Test
    fun withLessThanAWeekOfWeighInsThereIsNoRate() {
        assertNull(BodyweightTrend.of(daily(180.0, 180.0, 180.0, 180.0, 180.0, 180.0, 180.0)).weeklyChange)
    }

    @Test
    fun aWeekOfSteadyWeighInsIsARateOfZero() {
        assertEquals(0.0, BodyweightTrend.of(daily(180.0, 180.0, 180.0, 180.0, 180.0, 180.0, 180.0, 180.0)).weeklyChange)
    }

    @Test
    fun aFallingWeightIsANegativeRateAsAFractionOfBodyweightAWeek() {
        val trend = BodyweightTrend.of(daily(*DoubleArray(15) { 200.0 - 2 * it }))
        val rate = trend.weeklyChange
        // The trend lags the scale, so it has fallen about fourteen pounds of the twenty-eight the scale has.
        assertTrue(rate != null && rate < -0.03 && rate > -0.04, "rate was $rate")
    }

    @Test
    fun theRateIsReadOverTheLastFortnightOfTheTrend() {
        val trend = BodyweightTrend.of(daily(*DoubleArray(21) { 200.0 - it }))
        val from = trend.points[6]
        val last = trend.points.last()
        val expected = (last.trend - from.trend) / from.trend / 14 * 7
        assertEquals(expected, trend.weeklyChange ?: Double.NaN, 1e-12)
    }

    @Test
    fun withBetweenAWeekAndAFortnightTheRateIsReadFromTheFirstWeighIn() {
        val trend = BodyweightTrend.of(daily(*DoubleArray(9) { 180.0 + it }))
        val first = trend.points.first()
        val last = trend.points.last()
        val expected = (last.trend - first.trend) / first.trend / 8 * 7
        assertEquals(expected, trend.weeklyChange ?: Double.NaN, 1e-12)
    }

    // ---- Pace ---------------------------------------------------------------------------------

    @Test
    fun aCutLosingMoreThanAboutAPercentAWeekIsTooFast() {
        assertEquals(PaceVerdict.TOO_FAST, Pace.verdict(PhaseKind.CUT, -0.011))
    }

    @Test
    fun aCutBetweenAHalfAndOnePercentAWeekIsOnPaceAtBothEnds() {
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.CUT, -0.0105))
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.CUT, -0.007))
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.CUT, -0.004))
    }

    @Test
    fun aCutLosingLessThanThatOrGainingIsTooSlow() {
        assertEquals(PaceVerdict.TOO_SLOW, Pace.verdict(PhaseKind.CUT, -0.0039))
        assertEquals(PaceVerdict.TOO_SLOW, Pace.verdict(PhaseKind.CUT, 0.0))
        assertEquals(PaceVerdict.TOO_SLOW, Pace.verdict(PhaseKind.CUT, 0.01))
    }

    @Test
    fun aBulkGainingMoreThanAHalfPercentAWeekIsTooFast() {
        assertEquals(PaceVerdict.TOO_FAST, Pace.verdict(PhaseKind.BULK, 0.0056))
    }

    @Test
    fun aBulkBetweenAQuarterAndAHalfPercentAWeekIsOnPaceAtBothEnds() {
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.BULK, 0.0055))
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.BULK, 0.003))
        assertEquals(PaceVerdict.ON_PACE, Pace.verdict(PhaseKind.BULK, 0.0015))
    }

    @Test
    fun aBulkGainingLessThanThatOrLosingIsTooSlow() {
        assertEquals(PaceVerdict.TOO_SLOW, Pace.verdict(PhaseKind.BULK, 0.0014))
        assertEquals(PaceVerdict.TOO_SLOW, Pace.verdict(PhaseKind.BULK, -0.01))
    }

    @Test
    fun maintainingIsSteadyWhateverTheScaleSays() {
        for (change in listOf(-0.02, 0.0, 0.02)) assertEquals(PaceVerdict.STEADY, Pace.verdict(PhaseKind.MAINTAIN, change))
    }
}
