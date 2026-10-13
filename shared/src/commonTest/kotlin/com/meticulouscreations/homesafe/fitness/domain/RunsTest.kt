package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs out of a health store's records: their pace and names, the weeks added up, the miles one by one, and the heart in zones. All numbers made up. */
class RunsTest {

    private val utc: (Long) -> Int = { 0 }

    /** Monday 19 January 1970, counted in days, and a run's start that morning. */
    private val monday = 18L
    private val start = monday * DAY + 7 * HOUR

    private fun record(id: String = "a", startAt: Long = start, seconds: Long = 1_800, miles: Double? = 3.0, active: Long? = null) =
        RunRecord(id, startAt, startAt + seconds, utcOffsetSeconds = 0, meters = miles?.let { it * METERS_PER_MILE }, activeSeconds = active)

    private fun run(seconds: Long = 1_800, miles: Double = 3.0) = Runs.of(record(seconds = seconds, miles = miles), utc)

    /** Stretches of [seconds] each covering [miles], one after another from the start of the run. */
    private fun spans(count: Int, seconds: Long, miles: Double, from: Long = start): List<DistanceSpan> =
        (0 until count).map { DistanceSpan(from + it * seconds, from + (it + 1) * seconds, miles * METERS_PER_MILE) }

    // ---- A run --------------------------------------------------------------------------------

    @Test
    fun paceIsMovingTimeOverMiles() {
        val run = Runs.of(RunRecord("a", start, start + 1_800, meters = 5_000.0, activeSeconds = 1_700, steps = 4_760), utc)
        assertEquals(1_700, run.seconds)
        // 5 km is 3.107 miles.
        assertEquals(547, run.paceSeconds)
        assertEquals(168, run.cadence)
        assertEquals(monday, run.epochDay)
    }

    @Test
    fun withNoMovingTimeTheWholeOfItCounts() {
        assertEquals(1_800, Runs.of(record(active = null), utc).seconds)
        // A moving time longer than the run is the tracker's mistake.
        assertEquals(1_800, Runs.of(record(active = 4_000), utc).seconds)
        assertEquals(1_800, Runs.of(record(active = 0), utc).seconds)
    }

    @Test
    fun aRunWithNoDistanceHasNoPace() {
        val run = Runs.of(record(miles = null), utc)
        assertEquals(0.0, run.meters)
        assertNull(run.paceSeconds)
        // Nor has one too short for a pace to mean anything.
        assertNull(Runs.of(record(miles = 0.05), utc).paceSeconds)
    }

    @Test
    fun whatNoHeartOrBodyCouldGiveIsLeftOut() {
        val run = Runs.of(RunRecord("a", start, start + 600, averageBpm = 300, peakBpm = 12, calories = 0.0, steps = 0, climbMeters = 0.0), utc)
        assertNull(run.averageBpm)
        assertNull(run.peakBpm)
        assertNull(run.calories)
        assertNull(run.steps)
        assertNull(run.cadence)
        assertNull(run.climbMeters)
    }

    @Test
    fun aRunKeepsTheOffsetItWasRecordedIn() {
        // Recorded five hours west: half past one in the morning UTC is half past eight the evening before.
        val recorded = Runs.of(RunRecord("a", monday * DAY + 90 * 60, monday * DAY + 2 * HOUR, utcOffsetSeconds = -5 * 3_600), utc)
        assertEquals(monday - 1, recorded.epochDay)
        assertEquals(RunTime.EVENING, recorded.time)
        // With none kept, the offset is the one in force where it is being read.
        val unmarked = Runs.of(RunRecord("a", monday * DAY + 90 * 60, monday * DAY + 2 * HOUR), utc)
        assertEquals(monday, unmarked.epochDay)
        assertEquals(RunTime.NIGHT, unmarked.time)
    }

    @Test
    fun aRunIsNamedForThePartOfTheDayItBeganIn() {
        fun at(hour: Int) = RunTime.of(monday * DAY + hour * HOUR, 0)
        assertEquals(RunTime.NIGHT, at(3))
        assertEquals(RunTime.MORNING, at(4))
        assertEquals(RunTime.MORNING, at(10))
        assertEquals(RunTime.MIDDAY, at(11))
        assertEquals(RunTime.MIDDAY, at(13))
        assertEquals(RunTime.AFTERNOON, at(14))
        assertEquals(RunTime.EVENING, at(17))
        assertEquals(RunTime.EVENING, at(20))
        assertEquals(RunTime.NIGHT, at(21))
    }

    // ---- Weeks and the board ------------------------------------------------------------------

    @Test
    fun aWeekBeginsOnMonday() {
        // 1 January 1970 was a Thursday.
        assertEquals(-3L, Runs.mondayOf(0))
        assertEquals(4L, Runs.mondayOf(4))
        assertEquals(4L, Runs.mondayOf(10))
        assertEquals(11L, Runs.mondayOf(11))
    }

    @Test
    fun theWeeksAreAddedUpOldestFirstEndingWithThisOne() {
        val runs = listOf(
            Runs.of(record("a", startAt = monday * DAY + 7 * HOUR, seconds = 1_800, miles = 3.0), utc),
            Runs.of(record("b", startAt = (monday + 2) * DAY + 7 * HOUR, seconds = 1_200, miles = 2.0), utc),
            // The Sunday two weeks back's week: the week of the 5th.
            Runs.of(record("c", startAt = (monday - 8) * DAY + 7 * HOUR, seconds = 2_400, miles = 4.0), utc),
        )
        val weeks = Runs.weeks(runs, todayEpochDay = monday + 3, count = 3)
        assertEquals(listOf(4L, 11L, 18L), weeks.map { it.startEpochDay })
        assertEquals(listOf(1, 0, 2), weeks.map { it.runs })
        assertEquals(listOf(2_400L, 0L, 3_000L), weeks.map { it.seconds })
        assertEquals(5.0, weeks.last().miles, 1e-9)
        assertEquals(0.0, weeks[1].meters)
    }

    @Test
    fun theBoardHasTheLatestFirstAndNamesTheLongestAndTheQuickest() {
        val board = Runs.board(
            listOf(
                record("long", startAt = start - 6 * DAY, seconds = 3_600, miles = 6.0),
                record("quick", startAt = start - 2 * DAY, seconds = 1_500, miles = 3.0),
                record("latest", startAt = start, seconds = 1_800, miles = 3.0),
                // Quicker still, but under a mile: no pace to hold against the others.
                record("dash", startAt = start - 4 * DAY, seconds = 200, miles = 0.5),
            ),
            todayEpochDay = monday,
            offsetAt = utc,
        )
        assertEquals(listOf("latest", "quick", "dash", "long"), board.runs.map { it.id })
        assertEquals("long", board.longestId)
        assertEquals("quick", board.fastestId)
        assertEquals(Runs.WEEKS, board.weeks.size)
        assertEquals(1, board.thisWeek?.runs)
        assertEquals("quick", board.run("quick")?.id)
        assertNull(board.run("nobody"))
    }

    @Test
    fun withOneRunThereIsNothingToCallTheLongest() {
        val board = Runs.board(listOf(record()), monday, utc)
        assertNull(board.longestId)
        assertNull(board.fastestId)
    }

    @Test
    fun aRecordThatEndsBeforeItBeginsIsNoRun() {
        assertEquals(emptyList(), Runs.board(listOf(RunRecord("a", start, start - 5)), monday, utc).runs)
    }

    // ---- Splits -------------------------------------------------------------------------------

    @Test
    fun anEvenRunSplitsEvenly() {
        // Two miles in minutes of a tenth of a mile each.
        val splits = Runs.splits(run(seconds = 1_200, miles = 2.0), RunTrack(distance = spans(20, 60, 0.1)))
        assertEquals(listOf(1, 2), splits.map { it.number })
        assertEquals(listOf(600L, 600L), splits.map { it.seconds })
        assertEquals(listOf(600, 600), splits.map { it.paceSeconds })
        assertTrue(splits.all { it.whole })
    }

    @Test
    fun aMileMarkInsideAStretchIsTakenThatFarThroughIt() {
        // Five stretches of three minutes and three tenths of a mile: the mile falls a third of the way through the fourth.
        val splits = Runs.splits(run(seconds = 900, miles = 1.5), RunTrack(distance = spans(5, 180, 0.3)))
        assertEquals(2, splits.size)
        assertEquals(600L, splits[0].seconds)
        assertTrue(splits[0].whole)
        // The half mile it ended on.
        assertEquals(0.5, splits[1].miles, 1e-6)
        assertEquals(300L, splits[1].seconds)
        assertEquals(600, splits[1].paceSeconds)
        assertFalse(splits[1].whole)
    }

    @Test
    fun aQuickerMileShowsInItsSplit() {
        // A mile at ten minutes, then one at eight.
        val distance = spans(10, 60, 0.1) + spans(8, 60, 0.125, from = start + 600)
        val splits = Runs.splits(run(seconds = 1_080, miles = 2.0), RunTrack(distance = distance))
        assertEquals(listOf(600L, 480L), splits.map { it.seconds })
    }

    @Test
    fun theFirstMileIsTimedFromTheStartOfTheRun() {
        // The distance begins half a minute after the run did.
        val splits = Runs.splits(run(seconds = 1_230, miles = 2.0), RunTrack(distance = spans(20, 60, 0.1, from = start + 30)))
        assertEquals(listOf(630L, 600L), splits.map { it.seconds })
    }

    @Test
    fun aSplitsHeartIsTheMeanOfTheReadingsInIt() {
        val heart = listOf(HealthSample(start + 100, 140.0), HealthSample(start + 500, 150.0), HealthSample(start + 700, 160.0), HealthSample(start + 800, 0.0))
        val splits = Runs.splits(run(seconds = 900, miles = 1.5), RunTrack(heart = heart, distance = spans(5, 180, 0.3)))
        assertEquals(145, splits[0].averageBpm)
        // The reading of nothing is no reading.
        assertEquals(160, splits[1].averageBpm)
    }

    @Test
    fun aSplitWithNoReadingsHasNoHeart() {
        assertNull(Runs.splits(run(seconds = 1_200, miles = 2.0), RunTrack(distance = spans(20, 60, 0.1))).first().averageBpm)
    }

    @Test
    fun distanceTooCoarseToSayWhereAMileBeganGivesNoSplits() {
        // The whole three miles as one stretch.
        assertEquals(emptyList(), Runs.splits(run(), RunTrack(distance = listOf(DistanceSpan(start, start + 1_800, 3 * METERS_PER_MILE)))))
        assertEquals(emptyList(), Runs.splits(run(), RunTrack()))
    }

    @Test
    fun aRunUnderAMileHasNoSplits() {
        assertEquals(emptyList(), Runs.splits(run(seconds = 480, miles = 0.8), RunTrack(distance = spans(8, 60, 0.1))))
    }

    // ---- The heart ----------------------------------------------------------------------------

    /** Zones under a maximum of 190: 95, 114, 133, 152, 171. */
    private val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190)))

    @Test
    fun eachReadingStandsUntilTheNext() {
        val heart = listOf(
            HealthSample(start, 100.0),
            HealthSample(start + 60, 120.0),
            HealthSample(start + 120, 90.0),
            HealthSample(start + 180, 160.0),
            // The band lost the wrist for a quarter of an hour: nobody knows what the heart did in it.
            HealthSample(start + 1_000, 175.0),
        )
        val summary = assertNotNull(Runs.heartSummary(heart, bounds, endEpochSeconds = start + 1_030))
        assertEquals(60_000L, summary.belowMillis)
        assertEquals(listOf(60_000L, 60_000L, 0L, 0L, 30_000L), summary.zoneMillis)
        assertEquals(210_000L, summary.totalMillis)
        assertEquals(175, summary.peakBpm)
        // (100 + 120 + 90) for a minute each and 175 for half of one.
        assertEquals(114, summary.averageBpm)
    }

    @Test
    fun readingsOutOfOrderAreTakenInOrder() {
        val heart = listOf(HealthSample(start + 60, 120.0), HealthSample(start, 100.0))
        val summary = assertNotNull(Runs.heartSummary(heart, bounds, endEpochSeconds = start + 120))
        assertEquals(listOf(60_000L, 60_000L, 0L, 0L, 0L), summary.zoneMillis)
    }

    @Test
    fun withNoReadingsThereIsNoSummary() {
        assertNull(Runs.heartSummary(emptyList(), bounds, start + 600))
        assertNull(Runs.heartSummary(listOf(HealthSample(start, 0.0), HealthSample(start + 5, 400.0)), bounds, start + 600))
    }

    @Test
    fun aLongGapBetweenReadingsBreaksTheLine() {
        val heart = listOf(
            HealthSample(start, 100.0),
            HealthSample(start + 60, 120.0),
            // Exactly as long as a reading stands for: still the same stretch.
            HealthSample(start + 60 + Runs.HEART_GAP_SECONDS, 130.0),
            // A second longer than that: the band was away.
            HealthSample(start + 61 + 2 * Runs.HEART_GAP_SECONDS, 150.0),
        )
        assertEquals(listOf(listOf(100.0, 120.0, 130.0), listOf(150.0)), Runs.heartStretches(heart).map { stretch -> stretch.map { it.value } })
        assertEquals(emptyList(), Runs.heartStretches(emptyList()))
    }

    @Test
    fun openingARunPutsItsTrackTogether() {
        val run = run(seconds = 1_200, miles = 2.0)
        val track = RunTrack(
            heart = listOf(HealthSample(start + 60, 150.0), HealthSample(start, 140.0), HealthSample(start + 90, 0.0)),
            distance = spans(20, 60, 0.1),
            route = listOf(RoutePoint(45.0, -93.0), RoutePoint(45.001, -93.001)),
        )
        val zoned = Runs.detail(run, track, bounds)
        assertEquals(run.id, zoned.runId)
        assertEquals(2, zoned.splits.size)
        // In order, and without the reading of nothing.
        assertEquals(listOf(140.0, 150.0), zoned.heart.map { it.value })
        assertNotNull(zoned.summary)
        assertEquals(track.route, zoned.route)
        // With no zones set there is a heart rate, and no time in zones.
        assertNull(Runs.detail(run, track, bounds = null).summary)
    }

    private companion object {
        const val DAY = 86_400L
        const val HOUR = 3_600L
    }
}
