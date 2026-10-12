package com.meticulouscreations.homesafe.atlas.domain

import com.meticulouscreations.homesafe.atlas.ui.AtlasFixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolylineTest {
    @Test
    fun readsGooglesOwnExample() {
        val degrees = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertContentEquals(doubleArrayOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453), degrees)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(degrees))
    }

    @Test
    fun numbersComeBackAsTheyWere() {
        val seconds = intArrayOf(0, 16, 47, 15, 86_400, 86_401)
        assertContentEquals(seconds, Polyline.decodeNumbers(Polyline.encodeNumbers(seconds)))
        // What the relay writes for the same numbers (test_relay.py).
        assertEquals("?_@}@~@", Polyline.encodeNumbers(intArrayOf(0, 16, 47, 15)))
    }

    @Test
    fun aStringCutShortGivesWhatWasWhole() {
        assertContentEquals(doubleArrayOf(38.5, -120.2), Polyline.decode("_p~iF~ps|U_ulL"))
        assertContentEquals(doubleArrayOf(), Polyline.decode(""))
        assertContentEquals(doubleArrayOf(), Polyline.decode("_p~i"))
    }

    @Test
    fun aTrackKeepsItsPointsToWithinAMetre() {
        val track = Track.decode(1000, -360, Polyline.encode(doubleArrayOf(40.0, -89.0, 40.05, -89.02)), Polyline.encodeNumbers(intArrayOf(0, 1800)))
        assertEquals(2, track.size)
        assertEquals(2800, track.end)
        assertTrue(abs(com.meticulouscreations.homesafe.weather.domain.WebMercator.longitude(track.x(1)) - -89.02) < 1e-5)
        assertTrue(abs(com.meticulouscreations.homesafe.weather.domain.WebMercator.latitude(track.y(1)) - 40.05) < 1e-5)
        assertTrue(track.minX < track.maxX && track.minY < track.maxY)
    }
}

class AtlasHistoryTest {
    private val history = AtlasFixtures.history

    @Test
    fun civilDatesGoThereAndBack() {
        assertEquals(CivilDate(1970, 1, 1), CivilDate.of(0))
        assertEquals(CivilDate(2024, 2, 29), CivilDate.of(CivilDate.dayOf(2024, 2, 29)))
        assertEquals(CivilDate(1969, 12, 31), CivilDate.of(-1))
        assertEquals(19_723, CivilDate.dayOf(2024, 1, 1))
        assertEquals(29, CivilDate.daysIn(2024, 2))
        assertEquals(31, CivilDate.daysIn(2023, 12))
        // 2024-01-01 was a Monday.
        assertEquals(0, weekdayOf(CivilDate.dayOf(2024, 1, 1)))
        assertEquals(6, weekdayOf(CivilDate.dayOf(2024, 1, 7)))
    }

    @Test
    fun aDayIsTheDayItWasThere() {
        // 03:00 UTC is still the evening before, six hours behind.
        assertEquals(CivilDate.dayOf(2024, 1, 1), localDay(CivilDate.dayOf(2024, 1, 2) * SECONDS_PER_DAY + 3 * 3600, -360))
        assertEquals(21 * 60, localMinute(CivilDate.dayOf(2024, 1, 2) * SECONDS_PER_DAY + 3 * 3600, -360))
    }

    @Test
    fun aWorkdayHoldsItsVisitsAndMoves() {
        val monday = CivilDate.dayOf(2024, 5, 6)
        assertEquals(listOf("ChIJhome", "ChIJwork", "ChIJhome"), history.visitsOn(monday).map { it.placeKey })
        assertEquals(2, history.movesOn(monday).size)
        assertTrue(history.visitsOn(history.firstDay - 1).isEmpty())
        assertTrue(history.visitsOn(history.lastDay + 1).isEmpty())
    }

    @Test
    fun aStayWithNoPlaceIdStandsInAtItsSpot() {
        val stay = Visit(0, 3600, 0, 40.0312, -89.0408, Place.keyAt(40.0312, -89.0408), PlaceKind.OTHER)
        val both = AtlasHistory(listOf(stay), emptyList(), AtlasFixtures.places)
        assertEquals("@40031,-89041", stay.placeKey)
        assertEquals(40.0312, both.place(stay.placeKey).latitude)
        assertNull(both.place(stay.placeKey).name)
    }

    @Test
    fun aPlaceWithNoTownTakesItsNeighbours() {
        val nameless = Visit(0, 3600, 0, 40.031, -89.045, "@a", PlaceKind.OTHER)
        val far = Visit(4000, 8000, 0, 45.0, -100.0, "@b", PlaceKind.OTHER)
        val cafe = Visit(9000, 9500, 0, AtlasFixtures.cafe.latitude, AtlasFixtures.cafe.longitude, AtlasFixtures.cafe.key, PlaceKind.OTHER)
        val history = AtlasHistory(listOf(nameless, far, cafe), emptyList(), AtlasFixtures.places)
        assertEquals(Locality("Fairview", "IL", "US"), history.locality(history.place("@a")))
        assertNull(history.locality(history.place("@b")))
    }

    @Test
    fun whereSomeoneWasAtAMoment() {
        val monday = CivilDate.dayOf(2024, 5, 6)
        val noon = monday * SECONDS_PER_DAY + 12 * 3600 + 6 * 3600
        assertEquals("ChIJwork", history.visitAt(noon)?.placeKey)
        // On the way in.
        assertNull(history.visitAt(history.movesOn(monday).first().start + 60))
    }
}

class AtlasAnalysisTest {
    private val history = AtlasFixtures.history
    private val analysis = AtlasFixtures.state.analysis

    @Test
    fun anEmptyHistoryHasNothingToSay() {
        assertEquals(AtlasAnalysis.Empty, AtlasAnalyzer.analyze(AtlasHistory.Empty))
    }

    @Test
    fun homeIsWhereTheNightsWereAndMovesWhenTheyDo() {
        assertEquals(listOf("ChIJfirst", "ChIJhome"), analysis.eras.map { it.home.key })
        val (first, second) = analysis.eras
        assertEquals(history.firstDay, first.startDay)
        assertEquals(AtlasFixtures.movedDay - 1, first.endDay)
        assertEquals(history.lastDay, second.endDay)
        assertEquals("ChIJwork", first.work?.key)
        assertEquals("ChIJwork", second.work?.key)
        assertEquals(Locality("Fairview", "IL", "US"), second.locality)
        assertEquals("ChIJfirst", analysis.homeAt(history.firstDay + 30)?.home?.key)
        assertEquals("ChIJhome", analysis.homeAt(history.lastDay)?.home?.key)
    }

    @Test
    fun aTripAwayDoesNotMoveHome() {
        // Five nights at the lake are most of no month's nights.
        assertEquals(2, analysis.eras.size)
    }

    @Test
    fun theCommuteIsTheDirectTrips() {
        val commute = assertNotNull(analysis.commute)
        val toWork = assertNotNull(commute.toWork)
        assertTrue(toWork.minutes in 19..28, "minutes ${toWork.minutes}")
        assertTrue(toWork.departMinute in 7 * 60 + 40..8 * 60 + 15, "departs ${toWork.departMinute}")
        assertEquals(5, toWork.byWeekday.size)
        // Wednesdays leave latest, and the early quarter-hour is the quick one.
        assertEquals(2, toWork.byWeekday.maxBy { it.departMinute }.weekday)
        assertEquals(7 * 60 + 30, toWork.bestDepartMinute)
        // Tuesdays and Thursdays go by the gym, so only three evenings a week count.
        assertEquals(setOf(0, 2, 4), assertNotNull(commute.toHome).byWeekday.map { it.weekday }.toSet())
        assertTrue(commute.hoursPerYear in 100..260, "hours ${commute.hoursPerYear}")
        assertTrue(commute.trend.size >= 12)
    }

    @Test
    fun tripsAreTheRunsOfDaysFarFromHome() {
        assertEquals(2, analysis.trips.size)
        val (desert, lake) = analysis.trips
        assertEquals(AtlasFixtures.desertTripDay, desert.startDay)
        assertEquals(3, desert.nights)
        assertTrue(desert.flew)
        assertEquals(Locality("Sunridge", "NV", "US"), desert.locality)
        assertTrue(desert.farthestKm > 2000)
        assertEquals(listOf("ChIJfar", "ChIJdesert", "ChIJcanyon"), desert.stops.map { it.key })
        assertEquals(AtlasFixtures.lakeTripDay, lake.startDay)
        assertEquals(5, lake.nights)
        assertFalse(lake.flew)
        assertEquals("ChIJlake", lake.mainPlace.key)
        // There and back, with the walks to the diner.
        assertTrue(lake.meters > 600_000, "meters ${lake.meters}")
    }

    @Test
    fun placesAreRankedByTimeSpent() {
        assertEquals(listOf("ChIJhome", "ChIJfirst", "ChIJwork"), analysis.places.take(3).map { it.place.key })
        val cafe = analysis.places.first { it.place.key == "ChIJcafe" }
        assertTrue(cafe.typicalArrivalMinute in 9 * 60..9 * 60 + 20, "arrives ${cafe.typicalArrivalMinute}")
        assertTrue(cafe.typicalGapDays in 1..7)
        assertEquals(history.visits.count { it.placeKey == "ChIJcafe" }, cafe.visits)
        val bowling = analysis.places.first { it.place.key == "ChIJbowl" }
        assertEquals(14, bowling.typicalGapDays)
        assertTrue(bowling.lastDay < CivilDate.dayOf(2023, 11, 1))
    }

    @Test
    fun theUsualWeekKnowsNightsFromWorkdays() {
        val routine = analysis.routine
        assertEquals(26, routine.weeks)
        assertTrue(routine.slot(0, 3).home > 0.95f)
        assertTrue(routine.slot(1, 11).work > 0.9f)
        assertTrue(routine.slot(6, 14).home > 0.95f)
        // Tuesday evenings are the gym's.
        assertTrue(routine.slot(1, 17).other > 0.5f)
        assertTrue(routine.regularity > 0.9f, "regularity ${routine.regularity}")
    }

    @Test
    fun eachYearAddsUp() {
        assertEquals(listOf(2023, 2024, 2025), analysis.years.map { it.year })
        val year = analysis.years[1]
        assertEquals(1f, year.homeShare + year.workShare + year.otherShare + year.movingShare, 0.001f)
        assertTrue(year.homeShare > 0.6f)
        assertTrue(year.workShare in 0.15f..0.3f)
        assertEquals(366, year.daysTracked)
        assertEquals(2, year.regions)
        assertEquals(1, year.countries)
        assertTrue(year.modes.getValue(TravelMode.DRIVE).meters > 1_000_000)
        assertNotNull(analysis.years[2].modes[TravelMode.FLY])
        assertTrue(year.activeMeters > 0)
        // The lake counts as new the year it was first gone to, and not again.
        assertTrue(year.newPlaces >= 3)
        assertEquals(0, analysis.years[2].newPlaces - 4)
    }

    @Test
    fun timeIsCountedInTheYearItFellIn() {
        // Ten at night on New Year's Eve to eight the next morning, six hours behind UTC.
        val start = CivilDate.dayOf(2023, 12, 31) * SECONDS_PER_DAY + 22 * 3600 + 6 * 3600
        assertEquals(listOf(2023 to 2 * 3600L, 2024 to 8 * 3600L), AtlasAnalyzer.secondsByYear(start, start + 10 * 3600, -360))
        assertEquals(listOf(2023 to 3600L), AtlasAnalyzer.secondsByYear(start, start + 3600, -360))
        assertEquals(emptyList(), AtlasAnalyzer.secondsByYear(start, start, -360))

        // A night at home over the turn of the year, then a morning out: the new year's home hours are the new year's.
        val home = Visit(start, start + 10 * 3600, -360, 40.0, -89.0, "home", PlaceKind.HOME)
        val out = Visit(start + 10 * 3600, start + 12 * 3600, -360, 40.3, -89.0, "out", PlaceKind.OTHER)
        val years = AtlasAnalyzer.analyze(AtlasHistory(listOf(home, out), emptyList())).years
        assertEquals(listOf(2023, 2024), years.map { it.year })
        assertEquals(1f, years[0].homeShare)
        assertEquals(0.8f, years[1].homeShare, 0.001f)
        assertEquals(0.2f, years[1].otherShare, 0.001f)
        // The stay itself, and the place, belong to the year they began in.
        assertEquals(listOf(1, 1), years.map { it.places })
    }

    @Test
    fun whereItAllWas() {
        assertEquals(listOf("US"), analysis.countries.map { it.code })
        assertEquals(listOf("US-IL", "US-WI", "US-NV"), analysis.regions.map { it.code })
        assertEquals(3, analysis.cities)
        assertEquals(AtlasFixtures.lakeTripDay, analysis.regions[1].firstDay)
    }

    @Test
    fun theRecords() {
        val records = analysis.records
        assertEquals("ChIJcanyon", records.farthestPlace?.key)
        assertTrue(records.farthestKm > 2000)
        assertEquals(AtlasFixtures.lakeTripDay, records.longestDay)
        assertTrue(records.airMeters > 4_000_000)
        assertTrue(records.groundMeters > records.longestDayMeters)
        assertTrue(records.busiestDayPlaces >= 3)
    }

    @Test
    fun theDaysForTheCalendar() {
        val days = analysis.days
        assertEquals(history.dayCount, days.size)
        val trip = assertNotNull(days.index(AtlasFixtures.desertTripDay))
        assertTrue(days.airMeters[trip] > 2_000_000f)
        assertTrue(days.farthestKm[trip] > 2000f)
        assertTrue(days.newPlaces[trip] >= 3)
        val sunday = assertNotNull(days.index(CivilDate.dayOf(2024, 5, 5)))
        assertTrue(days.places[sunday] in 1..2)
        assertNull(days.index(history.lastDay + 1))
    }

    @Test
    fun whatTheRecordShows() {
        val insights = analysis.insights
        val home = insights.filterIsInstance<Insight.HomeShare>().single()
        assertTrue(home.percent in 60..80, "home ${home.percent}")
        assertEquals(Insight.LateWeekday(2, insights.filterIsInstance<Insight.LateWeekday>().single().minutesLater), insights.filterIsInstance<Insight.LateWeekday>().single())
        val better = insights.filterIsInstance<Insight.BetterDeparture>().single()
        assertEquals(7 * 60 + 30, better.departMinute)
        assertTrue(better.minutesSaved >= 4)
        val drifted = insights.filterIsInstance<Insight.Drifted>().single()
        assertEquals("ChIJbowl", drifted.place.key)
        assertEquals(14, drifted.gapDays)
        assertTrue(drifted.daysSince > 500)
        val habits = insights.filterIsInstance<Insight.Habit>()
        assertTrue(habits.any { it.place.key == "ChIJgrocer" && it.weekday == 5 })
        assertTrue(insights.filterIsInstance<Insight.Regularity>().single().percent > 85)
        assertTrue(insights.filterIsInstance<Insight.Shape>().single().returner)
        assertTrue(insights.any { it is Insight.CommuteCost })
        // The drive home from the market, each Saturday of the year so far.
        assertTrue(insights.filterIsInstance<Insight.ShortDrives>().single().count in 20..30)
    }

    @Test
    fun aPlacesRhythmPeaksWhenItIsGoneTo() {
        val rhythm = AtlasAnalyzer.rhythm(history, "ChIJgym")
        assertEquals(168, rhythm.size)
        assertEquals(1f, rhythm.max())
        // Tuesday and Thursday, five to six.
        assertTrue(rhythm[1 * 24 + 17] > 0.8f)
        assertTrue(rhythm[3 * 24 + 17] > 0.8f)
        assertEquals(0f, rhythm[0 * 24 + 17])
    }

    @Test
    fun rangeIsHowFarTimeSpreadsFromItsCentre() {
        val home = Visit(0, 3600, 0, 40.0, -89.0, "a", PlaceKind.HOME)
        assertEquals(0.0, AtlasAnalyzer.rangeKm(listOf(home)))
        // Equal time at two places ten kilometres apart: five from the middle.
        val away = Visit(4000, 7600, 0, 40.0899, -89.0, "b", PlaceKind.OTHER)
        assertEquals(5.0, AtlasAnalyzer.rangeKm(listOf(home, away)), 0.05)
    }

    @Test
    fun evennessRunsFromOnePlaceToAllAlike() {
        assertEquals(0.0, AtlasAnalyzer.evenness(listOf(10L)))
        assertEquals(1.0, AtlasAnalyzer.evenness(listOf(5L, 5L, 5L)), 1e-9)
        assertTrue(AtlasAnalyzer.evenness(listOf(98L, 1L, 1L)) < 0.2)
    }
}

class AtlasDensityTest {
    private val density = AtlasFixtures.paths.density

    @Test
    fun noPathsNoDensity() {
        assertTrue(AtlasDensity.of(emptyList()).levels.isEmpty())
        assertNull(AtlasDensity.Empty.levelFor(8.0, 256f, 4f))
    }

    @Test
    fun aRoadDrivenOftenCountsOnceATrip() {
        assertEquals(2023, density.baseYear)
        assertEquals(AtlasDensity.LEVELS.toList(), density.levels.map { it.level })
        // The coarsest squares are tens of kilometres across: the whole town is one of them.
        val coarse = density.levels.first()
        assertTrue(coarse.weight.max() >= AtlasFixtures.tracks.size * 0.9f, "most ${coarse.weight.max()} of ${AtlasFixtures.tracks.size}")
        assertTrue(coarse.size < 200)
        // The finest follow the roads: many squares, none crossed more often than there were trips.
        val fine = density.levels.last()
        assertTrue(fine.size > coarse.size * 20)
        assertTrue(fine.weight.max() <= AtlasFixtures.tracks.size)
    }

    @Test
    fun squaresSayWhichYearsTheyWereCrossedIn() {
        val fine = density.levels.last()
        assertEquals(1, density.yearBit(2023))
        assertEquals(0b111, density.yearsThrough(2025))
        assertEquals(0b1, density.yearsThrough(2023))
        assertEquals(0, density.yearsThrough(2022))
        // The lake was 2024 only; the first house's street 2023 and the start of 2024.
        assertTrue(fine.years.any { it == density.yearBit(2024) })
        assertTrue(fine.years.any { it == 0b111 })
    }

    @Test
    fun onlyTheSquaresInViewAreVisited() {
        val fine = density.levels.last()
        val x = com.meticulouscreations.homesafe.weather.domain.WebMercator.x(-89.03)
        val y = com.meticulouscreations.homesafe.weather.domain.WebMercator.y(40.03)
        var near = 0
        fine.forEachIn(x - 0.0005, y - 0.0005, x + 0.0005, y + 0.0005) { near++ }
        var all = 0
        fine.forEachIn(0.0, 0.0, 1.0, 1.0) { all++ }
        assertEquals(fine.size, all)
        assertTrue(near in 1 until all / 4, "near $near of $all")
        var nowhere = 0
        fine.forEachIn(0.9, 0.9, 0.95, 0.95) { nowhere++ }
        assertEquals(0, nowhere)
    }

    @Test
    fun aFlightIsNotARoad() {
        // Two points an ocean apart are two squares, not a line of them.
        val hop = Track.decode(0, 0, Polyline.encode(doubleArrayOf(40.0, -89.0, 36.0, -115.0)), Polyline.encodeNumbers(intArrayOf(0, 14_000)))
        assertEquals(2, AtlasDensity.of(listOf(hop)).levels.last().size)
    }

    @Test
    fun theLevelIsTheCoarsestWhoseSquaresAreSmallEnough() {
        // Tiles 256 wide and squares of at most 4: a square is a tile's 64th, six levels on.
        assertEquals(9, density.levelFor(2.0, 256f, 4f)?.level)
        assertEquals(9, density.levelFor(3.0, 256f, 4f)?.level)
        assertEquals(12, density.levelFor(5.5, 256f, 4f)?.level)
        assertEquals(19, density.levelFor(13.0, 256f, 4f)?.level)
        // Closer in than the finest squares serve: the paths themselves are drawn.
        assertNull(density.levelFor(13.2, 256f, 4f))
        // Larger tiles need finer squares for the same zoom.
        assertEquals(13, density.levelFor(5.5, 512f, 4f)?.level)
    }
}
