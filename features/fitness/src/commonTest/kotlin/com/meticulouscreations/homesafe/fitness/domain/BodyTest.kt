package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nights out of sleep sessions, the readiness score, and Today's body board. All numbers made up, and all times UTC unless a test says otherwise. */
class BodyTest {

    private val utc: (Long) -> Int = { 0 }

    /** A day, counted from 1970, and the second it begins. */
    private val today = 20_000L
    private fun at(day: Long, hour: Int, minute: Int = 0): Long = day * DAY + hour * HOUR + minute * 60L

    /** A night with no stages that ended at seven on [day] after [hours] asleep. */
    private fun plainNight(day: Long, hours: Double): SleepSession = SleepSession(at(day, 7) - (hours * HOUR).toLong(), at(day, 7))

    private fun nightOf(day: Long, hours: Double): SleepNight = SleepNights.of(listOf(plainNight(day, hours)), utc).single()

    /** A reading at three in the morning of each of the [nights] before today. */
    private fun overnight(nights: Int, value: (Long) -> Double): List<HealthSample> = (today - nights until today).map { HealthSample(at(it, 3), value(it)) }

    /** A reading at eight in the morning of each of the [days] before today. */
    private fun mornings(days: Int, value: (Long) -> Double): List<HealthSample> = (today - days until today).map { HealthSample(at(it, 8), value(it)) }

    // ---- Nights -------------------------------------------------------------------------------

    @Test
    fun aNightIsAddedUpFromItsStages() {
        val session = SleepSession(
            at(today - 1, 23),
            at(today, 7),
            listOf(
                SleepStage(at(today - 1, 23), at(today, 1), SleepStageKind.LIGHT),
                SleepStage(at(today, 1), at(today, 2, 30), SleepStageKind.DEEP),
                SleepStage(at(today, 2, 30), at(today, 3), SleepStageKind.AWAKE),
                SleepStage(at(today, 3), at(today, 5), SleepStageKind.REM),
                SleepStage(at(today, 5), at(today, 7), SleepStageKind.LIGHT),
            ),
        )
        val night = SleepNights.of(listOf(session), utc).single()
        assertEquals(today, night.epochDay)
        assertEquals(7 * HOUR + 30 * 60, night.asleepSeconds)
        assertEquals(30 * 60L, night.awakeSeconds)
        assertEquals(4 * HOUR, night.lightSeconds)
        assertEquals(90 * 60L, night.deepSeconds)
        assertEquals(2 * HOUR, night.remSeconds)
        assertTrue(night.staged)
    }

    @Test
    fun aNightWithNoStagesIsAllTakenAsSleep() {
        val night = nightOf(today, 8.0)
        assertEquals(8 * HOUR, night.asleepSeconds)
        assertFalse(night.staged)
    }

    @Test
    fun sleepOfNoStatedDepthCountsAsSleepButIsNoStage() {
        val session = SleepSession(at(today, 0), at(today, 6), listOf(SleepStage(at(today, 0), at(today, 5), SleepStageKind.ASLEEP), SleepStage(at(today, 5), at(today, 6), SleepStageKind.AWAKE)))
        val night = SleepNights.of(listOf(session), utc).single()
        assertEquals(5 * HOUR, night.asleepSeconds)
        assertFalse(night.staged)
    }

    @Test
    fun aStageIsCountedOnlyForThePartInsideItsSleep() {
        val session = SleepSession(at(today, 0), at(today, 6), listOf(SleepStage(at(today - 1, 23), at(today, 8), SleepStageKind.LIGHT)))
        assertEquals(6 * HOUR, SleepNights.of(listOf(session), utc).single().asleepSeconds)
    }

    @Test
    fun theLongestSleepOfADayIsItsNight() {
        val nap = SleepSession(at(today, 14), at(today, 15, 30))
        val nights = SleepNights.of(listOf(nap, plainNight(today, 7.0)), utc)
        assertEquals(7 * HOUR, nights.single().asleepSeconds)
    }

    @Test
    fun aSleepUnderAnHourIsNobodysNight() {
        assertEquals(emptyList(), SleepNights.of(listOf(SleepSession(at(today, 14), at(today, 14, 40))), utc))
    }

    @Test
    fun aNightIsFiledUnderTheLocalDayItEndedOn() {
        // Two in the morning UTC is nine the evening before, five hours west.
        val session = SleepSession(at(today - 1, 18), at(today, 2))
        val night = SleepNights.of(listOf(session)) { -5 * HOUR.toInt() }.single()
        assertEquals(today - 1, night.epochDay)
        assertEquals(-5 * HOUR.toInt(), night.utcOffsetSeconds)
    }

    @Test
    fun aNightKeepsTheOffsetsItWasRecordedIn() {
        // Recorded where it was two hours east going to bed and one hour east on waking (the clocks went back in the night),
        // and read somewhere five hours west.
        val session = SleepSession(at(today - 1, 21), at(today, 5), startUtcOffsetSeconds = 2 * HOUR.toInt(), endUtcOffsetSeconds = HOUR.toInt())
        val night = SleepNights.of(listOf(session)) { -5 * HOUR.toInt() }.single()
        // Six in the morning there, on the day it was there.
        assertEquals(today, night.epochDay)
        assertEquals(HOUR.toInt(), night.utcOffsetSeconds)
        assertEquals(2 * HOUR.toInt(), night.startUtcOffsetSeconds)
    }

    @Test
    fun withNoOffsetKeptEachEndIsReadByTheClockWhereItIsRead() {
        // The offset here changes at midnight UTC: one hour east before it, none after.
        val session = SleepSession(at(today - 1, 22), at(today, 6))
        val night = SleepNights.of(listOf(session)) { if (it < at(today, 0)) HOUR.toInt() else 0 }.single()
        assertEquals(HOUR.toInt(), night.startUtcOffsetSeconds)
        assertEquals(0, night.utcOffsetSeconds)
    }

    @Test
    fun nightsComeOldestFirst() {
        val nights = SleepNights.of(listOf(plainNight(today, 7.0), plainNight(today - 2, 6.0), plainNight(today - 1, 8.0)), utc)
        assertEquals(listOf(today - 2, today - 1, today), nights.map { it.epochDay })
    }

    // ---- The score's parts --------------------------------------------------------------------

    @Test
    fun levelWithWhatIsUsualScoresSeventyFive() {
        assertEquals(75, ReadinessScore.against(50.0, List(7) { 50.0 }, floor = 2.0))
    }

    @Test
    fun eachDeviationIsWorthTwentyFive() {
        // Mean 50, standard deviation 2.
        val usual = listOf(48.0, 52.0, 48.0, 52.0, 48.0, 52.0, 50.0)
        assertEquals(100, ReadinessScore.against(52.0, usual, floor = 0.5))
        assertEquals(50, ReadinessScore.against(48.0, usual, floor = 0.5))
        assertEquals(25, ReadinessScore.against(46.0, usual, floor = 0.5))
    }

    @Test
    fun theScoreStaysBetweenNoneAndFull() {
        val usual = listOf(48.0, 52.0, 48.0, 52.0, 48.0, 52.0, 50.0)
        assertEquals(100, ReadinessScore.against(70.0, usual, floor = 0.5))
        assertEquals(0, ReadinessScore.against(30.0, usual, floor = 0.5))
    }

    @Test
    fun aWeekOfIdenticalNightsDoesNotMakeAnAlarmOfAnOrdinaryOne() {
        // With no spread at all, the floor is the deviation: one beat off is two thirds of one.
        assertEquals(58, ReadinessScore.against(49.0, List(7) { 50.0 }, floor = 1.5))
    }

    @Test
    fun sleepIsFullAtEightHoursAndNothingAtFour() {
        assertEquals(100, ReadinessScore.sleepScore(8 * HOUR))
        assertEquals(100, ReadinessScore.sleepScore(9 * HOUR))
        assertEquals(75, ReadinessScore.sleepScore(7 * HOUR))
        assertEquals(50, ReadinessScore.sleepScore(6 * HOUR))
        assertEquals(0, ReadinessScore.sleepScore(4 * HOUR))
        assertEquals(0, ReadinessScore.sleepScore(3 * HOUR))
    }

    // ---- The score ----------------------------------------------------------------------------

    @Test
    fun anOrdinaryDayIsSteady() {
        val readiness = assertNotNull(
            ReadinessScore.of(
                today,
                variability = overnight(10) { 50.0 } + HealthSample(at(today, 3), 50.0),
                resting = mornings(10) { 55.0 } + HealthSample(at(today, 8), 55.0),
                lastNight = nightOf(today, 7.0),
                offsetAt = utc,
            ),
        )
        assertEquals(75, readiness.score)
        assertEquals(ReadinessLevel.MODERATE, readiness.level)
        assertFalse(readiness.learning)
        assertEquals(listOf(ReadinessFactor.VARIABILITY, ReadinessFactor.RESTING, ReadinessFactor.SLEEP), readiness.parts.map { it.factor })
        val variability = assertNotNull(readiness.part(ReadinessFactor.VARIABILITY))
        assertEquals(50.0, variability.value)
        assertEquals(50.0, assertNotNull(variability.usual), 1e-6)
        assertEquals(55.0, assertNotNull(readiness.part(ReadinessFactor.RESTING)).usual)
        assertNull(assertNotNull(readiness.part(ReadinessFactor.SLEEP)).usual)
    }

    @Test
    fun aGoodNightOnAHeartBetterThanUsualIsHigh() {
        val readiness = assertNotNull(
            ReadinessScore.of(
                today,
                variability = overnight(10) { 50.0 } + HealthSample(at(today, 3), 58.0),
                resting = mornings(10) { 55.0 } + HealthSample(at(today, 8), 53.0),
                lastNight = nightOf(today, 8.5),
                offsetAt = utc,
            ),
        )
        assertEquals(100, readiness.part(ReadinessFactor.VARIABILITY)?.score)
        assertEquals(100, readiness.part(ReadinessFactor.RESTING)?.score)
        assertEquals(100, readiness.score)
        assertEquals(ReadinessLevel.HIGH, readiness.level)
    }

    @Test
    fun aShortNightOnAHeartWorseThanUsualIsLow() {
        val readiness = assertNotNull(
            ReadinessScore.of(
                today,
                variability = overnight(10) { 50.0 } + HealthSample(at(today, 3), 36.0),
                resting = mornings(10) { 55.0 } + HealthSample(at(today, 8), 62.0),
                lastNight = nightOf(today, 5.0),
                offsetAt = utc,
            ),
        )
        assertEquals(0, readiness.part(ReadinessFactor.VARIABILITY)?.score)
        assertEquals(0, readiness.part(ReadinessFactor.RESTING)?.score)
        assertEquals(25, readiness.part(ReadinessFactor.SLEEP)?.score)
        assertEquals(ReadinessLevel.LOW, readiness.level)
    }

    @Test
    fun aHigherRestingRateThanUsualCountsAgainst() {
        val readiness = assertNotNull(
            ReadinessScore.of(today, emptyList(), mornings(10) { 55.0 } + HealthSample(at(today, 8), 56.5), nightOf(today, 7.0), utc),
        )
        // One floor's worth (1.5 beats) above the usual.
        assertEquals(50, readiness.part(ReadinessFactor.RESTING)?.score)
    }

    @Test
    fun withTooFewNightsBeforeItTheHeartIsNotScoredYet() {
        val readiness = assertNotNull(
            ReadinessScore.of(
                today,
                variability = overnight(3) { 50.0 } + HealthSample(at(today, 3), 30.0),
                resting = mornings(3) { 55.0 } + HealthSample(at(today, 8), 70.0),
                lastNight = nightOf(today, 7.0),
                offsetAt = utc,
            ),
        )
        assertEquals(listOf(ReadinessFactor.SLEEP), readiness.parts.map { it.factor })
        assertEquals(75, readiness.score)
        assertTrue(readiness.learning)
    }

    @Test
    fun theScoreIsOverWhateverPartsThereAre() {
        val readiness = assertNotNull(ReadinessScore.of(today, overnight(10) { 50.0 } + HealthSample(at(today, 3), 50.0), emptyList(), null, utc))
        assertEquals(listOf(ReadinessFactor.VARIABILITY), readiness.parts.map { it.factor })
        assertEquals(75, readiness.score)
    }

    @Test
    fun withNothingFromLastNightThereIsNoScore() {
        // A resting rate, even today's, says nothing about last night.
        assertNull(ReadinessScore.of(today, overnight(10) { 50.0 }, mornings(10) { 55.0 } + HealthSample(at(today, 8), 55.0), null, utc))
        // Yesterday's night is not last night.
        assertNull(ReadinessScore.of(today, emptyList(), emptyList(), nightOf(today - 1, 8.0), utc))
    }

    @Test
    fun aReadingLateInTheEveningBelongsToTheNightAhead() {
        val baseline = overnight(10) { 50.0 }
        // Eleven at night yesterday is part of the night that ended this morning.
        assertNotNull(ReadinessScore.of(today, baseline + HealthSample(at(today - 1, 23), 50.0), emptyList(), null, utc))
        // Eleven tonight is tomorrow's.
        assertNull(ReadinessScore.of(today, baseline + HealthSample(at(today, 23), 50.0), emptyList(), null, utc))
    }

    @Test
    fun severalReadingsInANightAreAveraged() {
        val readiness = assertNotNull(
            ReadinessScore.of(today, overnight(10) { 50.0 } + listOf(HealthSample(at(today, 1), 44.0), HealthSample(at(today, 4), 56.0)), emptyList(), null, utc),
        )
        assertEquals(50.0, readiness.part(ReadinessFactor.VARIABILITY)?.value)
    }

    @Test
    fun yesterdaysRestingRateStandsUntilTodaysArrives() {
        val readiness = assertNotNull(ReadinessScore.of(today, emptyList(), mornings(11) { if (it == today - 1) 57.0 else 55.0 }, nightOf(today, 7.0), utc))
        val resting = assertNotNull(readiness.part(ReadinessFactor.RESTING))
        assertEquals(57.0, resting.value)
        // Held against the ten days before yesterday, not against itself.
        assertEquals(55.0, resting.usual)
    }

    @Test
    fun whatIsUsualIsTheLastFourWeeks() {
        // Thirty days ago and before, the resting rate was far lower: too long ago to count.
        val resting = mornings(40) { if (it < today - ReadinessScore.BASELINE_DAYS) 40.0 else 55.0 } + HealthSample(at(today, 8), 55.0)
        val readiness = assertNotNull(ReadinessScore.of(today, emptyList(), resting, nightOf(today, 7.0), utc))
        assertEquals(55.0, readiness.part(ReadinessFactor.RESTING)?.usual)
    }

    // ---- Today's board ------------------------------------------------------------------------

    @Test
    fun theWeeksStepsAreSevenDaysEndingToday() {
        val board = BodyBoards.build(at(today, 12), utc, steps = mapOf(today - 6 to 1_000L, today - 4 to 3_000L, today to 500L, today - 9 to 9_999L))
        val steps = assertNotNull(board.steps)
        assertEquals((today - 6..today).toList(), steps.days.map { it.epochDay })
        assertEquals(listOf(1_000L, 0L, 3_000L, 0L, 0L, 0L, 500L), steps.days.map { it.steps })
        assertEquals(500L, steps.today)
        // Over the days before today the band was worn on.
        assertEquals(2_000L, steps.average)
    }

    @Test
    fun withNoEarlierDayThereIsNoUsual() {
        assertNull(assertNotNull(BodyBoards.build(at(today, 12), utc, steps = mapOf(today to 500L)).steps).average)
    }

    @Test
    fun whatIsNotSharedIsNotOnTheBoard() {
        val board = BodyBoards.build(at(today, 12), utc)
        assertNull(board.steps)
        assertNull(board.sleep)
        assertNull(board.readiness)
        assertFalse(board.readinessShared)
    }

    @Test
    fun lastNightIsTheNightThatEndedToday() {
        val board = BodyBoards.build(at(today, 12), utc, sleep = listOf(plainNight(today - 2, 7.0), plainNight(today - 1, 6.0), plainNight(today, 8.0)))
        val sleep = assertNotNull(board.sleep)
        assertEquals(8 * HOUR, sleep.lastNight?.asleepSeconds)
        // The nights before it, on average.
        assertEquals(6 * HOUR + 30 * 60, sleep.averageSeconds)
        assertTrue(board.readinessShared)
        assertEquals(100, board.readiness?.score)
    }

    @Test
    fun aNightThatHasNotArrivedIsNoNightAndNoScore() {
        val board = BodyBoards.build(at(today, 12), utc, sleep = listOf(plainNight(today - 1, 6.0)), resting = emptyList(), variability = emptyList())
        val sleep = assertNotNull(board.sleep)
        assertNull(sleep.lastNight)
        assertEquals(6 * HOUR, sleep.averageSeconds)
        assertNull(board.readiness)
        assertTrue(board.readinessShared)
    }

    @Test
    fun todayIsTheLocalDay() {
        // Two in the morning UTC on the day after is still nine in the evening of today, five hours west.
        val board = BodyBoards.build(at(today + 1, 2), { -5 * HOUR.toInt() }, steps = mapOf(today to 700L, today + 1 to 1L))
        assertEquals(700L, board.steps?.today)
    }

    private companion object {
        const val DAY = 86_400L
        const val HOUR = 3_600L
    }
}
