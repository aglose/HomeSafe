package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [Thresholds] turns a raw reading into a [Signal] and a 0-1 [Thresholds.stress] for the Risk
 * tab's gauge, in both directions ("higher is worse" and "lower is worse"). [StressScore]
 * aggregates readings into the composite gauge, and [IndicatorReading] derives the two "how has
 * this moved" numbers the detail pages show.
 */
class IndicatorsTest {

    private fun indicator(id: String = "x", thresholds: Thresholds? = null, weight: Double = 1.0) = Indicator(
        id = id,
        title = id,
        shortTitle = id,
        fredIds = listOf(id.uppercase()),
        unit = IndicatorUnit.PERCENT,
        group = IndicatorGroup.RECESSION,
        thresholds = thresholds,
        why = "test fixture",
        weight = weight,
    )

    private fun reading(value: Double, thresholds: Thresholds? = null, weight: Double = 1.0, id: String = "x") =
        IndicatorReading(indicator(id, thresholds, weight), Series.of(listOf(0L to value)))

    @Test
    fun signalAndStressWhenHigherIsWorse() {
        val t = Thresholds(watch = 10.0, danger = 20.0, higherIsWorse = true)
        assertEquals(Signal.CALM, t.signal(0.0))
        assertEquals(0.0, t.stress(0.0), 1e-9, "well inside calm bottoms out at 0")
        assertEquals(Signal.WATCH, t.signal(10.0))
        assertEquals(0.5, t.stress(10.0), 1e-9, "the watch line is always 0.5")
        assertEquals(Signal.DANGER, t.signal(20.0))
        assertEquals(1.0, t.stress(20.0), 1e-9, "the danger line is always 1.0")
        assertEquals(0.75, t.stress(15.0), 1e-9, "linear halfway between watch and danger")
        assertEquals(Signal.DANGER, t.signal(30.0))
        assertEquals(1.0, t.stress(30.0), 1e-9, "stress never exceeds 1, even past danger")
    }

    @Test
    fun signalAndStressWhenLowerIsWorse() {
        val t = Thresholds(watch = 10.0, danger = 0.0, higherIsWorse = false)
        assertEquals(Signal.CALM, t.signal(20.0))
        assertEquals(0.0, t.stress(20.0), 1e-9, "well inside calm bottoms out at 0")
        assertEquals(Signal.WATCH, t.signal(10.0))
        assertEquals(0.5, t.stress(10.0), 1e-9, "the watch line is always 0.5")
        assertEquals(Signal.DANGER, t.signal(0.0))
        assertEquals(1.0, t.stress(0.0), 1e-9, "the danger line is always 1.0")
        assertEquals(0.75, t.stress(5.0), 1e-9, "linear halfway between watch and danger")
        assertEquals(Signal.DANGER, t.signal(-10.0))
        assertEquals(1.0, t.stress(-10.0), 1e-9, "stress never exceeds 1, even past danger")
    }

    @Test
    fun stressWithNoGapBetweenWatchAndDangerStillOnlyAnswersCalmOrDanger() {
        // danger wins ties, so there is no way to land exactly on "watch" when the two coincide.
        val t = Thresholds(watch = 5.0, danger = 5.0, higherIsWorse = true)
        assertEquals(0.0, t.stress(4.9), 1e-9)
        assertEquals(1.0, t.stress(5.0), 1e-9)
    }

    @Test
    fun stressScoreOfWeighsEachReadingAndCountsDangersAndWatches() {
        val danger = reading(value = 20.0, thresholds = Thresholds(10.0, 20.0, true), weight = 1.0, id = "danger")
        val watch = reading(value = 10.0, thresholds = Thresholds(10.0, 20.0, true), weight = 2.0, id = "watch")
        val calm = reading(value = 0.0, thresholds = Thresholds(10.0, 20.0, true), weight = 1.0, id = "calm")
        val noThresholds = reading(value = 999.0, thresholds = null, id = "untracked")

        val score = assertNotNull(StressScore.of(listOf(danger, watch, calm, noThresholds)))

        assertEquals(50.0, score.score, 1e-9, "(1.0*1 + 0.5*2 + 0.0*1) / (1+2+1) * 100")
        assertEquals(3, score.counted, "the reading with no thresholds is left out")
        assertEquals(1, score.dangers)
        assertEquals(1, score.watches)
        assertEquals("High", score.label)
    }

    @Test
    fun stressScoreOfIsNullWhenNothingHasThresholds() {
        val readings = listOf(reading(value = 1.0, thresholds = null), reading(value = 2.0, thresholds = null, id = "y"))
        assertNull(StressScore.of(readings))
    }

    @Test
    fun stressScoreLabelBoundaries() {
        assertEquals("Calm", StressScore(score = 29.9, counted = 1, dangers = 0, watches = 0).label)
        assertEquals("Elevated", StressScore(score = 30.0, counted = 1, dangers = 0, watches = 0).label)
        assertEquals("High", StressScore(score = 50.0, counted = 1, dangers = 0, watches = 0).label)
        assertEquals("Severe", StressScore(score = 70.0, counted = 1, dangers = 0, watches = 0).label)
    }

    @Test
    fun yearChangeComparesTheLatestReadingToAYearBefore() {
        val history = Series.of(listOf(0L to 100.0, Series.YEAR_SECONDS to 150.0))
        val r = IndicatorReading(indicator(), history)
        assertEquals(50.0, r.yearChange)
    }

    @Test
    fun yearChangeIsNullWhenTheSeriesDoesNotReachBackAYear() {
        val history = Series.of(listOf(Series.YEAR_SECONDS to 150.0))
        val r = IndicatorReading(indicator(), history)
        assertNull(r.yearChange)
    }

    @Test
    fun lastChangeComparesTheLastTwoReadings() {
        val r = IndicatorReading(indicator(), Series.of(listOf(0L to 100.0, 10L to 120.0)))
        assertEquals(20.0, r.lastChange)
    }

    @Test
    fun lastChangeIsNullWithFewerThanTwoReadings() {
        val r = IndicatorReading(indicator(), Series.of(listOf(0L to 100.0)))
        assertNull(r.lastChange)
    }
}
