package com.meticulouscreations.homesafe.atlas.ui

import com.meticulouscreations.homesafe.atlas.AtlasPaths
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalyzer
import com.meticulouscreations.homesafe.atlas.domain.AtlasDensity
import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.AtlasServerStatus
import com.meticulouscreations.homesafe.atlas.domain.AtlasSyncState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.Move
import com.meticulouscreations.homesafe.atlas.domain.Place
import com.meticulouscreations.homesafe.atlas.domain.PlaceKind
import com.meticulouscreations.homesafe.atlas.domain.Polyline
import com.meticulouscreations.homesafe.atlas.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.Visit
import com.meticulouscreations.homesafe.atlas.domain.metersBetween
import com.meticulouscreations.homesafe.atlas.domain.weekdayOf

/**
 * A made-up life for previews and tests: nobody lives at 40, -89, and none of these places exist.
 * Two and a half years of one person's days in a town called Fairview: a house, then another
 * across town; an office on weekdays, left for later on Wednesdays; a gym, a Saturday café, a
 * bowling alley that stopped being gone to; a drive to a lake and a flight to the desert.
 */
object AtlasFixtures {
    const val OFFSET = -360

    val firstDay = CivilDate.dayOf(2023, 1, 2)
    val lastDay = CivilDate.dayOf(2025, 6, 27)

    /** The day the household moved from the first house to the second. */
    val movedDay = CivilDate.dayOf(2024, 3, 1)
    val lakeTripDay = CivilDate.dayOf(2024, 7, 12)
    val desertTripDay = CivilDate.dayOf(2025, 2, 6)

    private fun place(key: String, latitude: Double, longitude: Double, name: String, type: String, kind: PlaceKind = PlaceKind.OTHER, city: String = "Fairview", region: String = "IL") =
        Place(key, latitude, longitude, kind, name, "$name, $city", type, city, region, "US")

    val firstHome = place("ChIJfirst", 40.000, -89.000, "12 Orchard Lane", "premise")
    val home = place("ChIJhome", 40.060, -89.080, "7 Mill Road", "premise", PlaceKind.HOME)
    val work = place("ChIJwork", 40.045, -89.030, "Northfield Works", "corporate_office", PlaceKind.WORK)
    val gym = place("ChIJgym", 40.020, -89.012, "Iron Yard Gym", "gym")
    val cafe = place("ChIJcafe", 40.031, -89.041, "Kettle and Crumb", "cafe")
    val bowling = place("ChIJbowl", 40.011, -89.060, "Fairview Lanes", "bowling_alley")
    val grocer = place("ChIJgrocer", 40.052, -89.071, "Greenway Market", "supermarket")
    val lake = place("ChIJlake", 42.900, -89.400, "Heron Lake Cabins", "lodging", city = "Lakemont", region = "WI")
    val lakeDiner = place("ChIJdiner", 42.910, -89.385, "The Dockside", "restaurant", city = "Lakemont", region = "WI")
    val desert = place("ChIJdesert", 36.170, -115.140, "Sunridge Hotel", "lodging", city = "Sunridge", region = "NV")
    val canyon = place("ChIJcanyon", 36.135, -115.430, "Red Hollow Canyon", "park", city = "Sunridge", region = "NV")
    val airport = place("ChIJairport", 40.110, -88.930, "Fairview Regional Airport", "airport")
    val farAirport = place("ChIJfar", 36.085, -115.150, "Sunridge International", "airport", city = "Sunridge", region = "NV")

    val places: Map<String, Place> = listOf(firstHome, home, work, gym, cafe, bowling, grocer, lake, lakeDiner, desert, canyon, airport, farAirport).associateBy { it.key }

    private class Builder {
        val visits = ArrayList<Visit>()
        val moves = ArrayList<Move>()
        val tracks = ArrayList<Track>()
        var at: Place = firstHome
        var since: Long = (firstDay.toLong() * SECONDS_PER_DAY) - OFFSET * 60L

        /** Local seconds of [day] at [minute] past midnight, as epoch seconds. */
        fun time(day: Int, minute: Int): Long = day.toLong() * SECONDS_PER_DAY + minute * 60L - OFFSET * 60L

        /** Leaves where it is at [leave], getting to [to] [minutes] later by [mode]. */
        fun go(to: Place, leave: Long, minutes: Int, mode: TravelMode = TravelMode.DRIVE) {
            if (leave <= since) return
            visits += Visit(since, leave, OFFSET, at.latitude, at.longitude, at.key, at.kind)
            val arrive = leave + minutes * 60L
            val straight = metersBetween(at.latitude, at.longitude, to.latitude, to.longitude)
            moves += Move(leave, arrive, OFFSET, mode, at.latitude, at.longitude, to.latitude, to.longitude, if (mode == TravelMode.FLY) straight else straight * 1.3)
            if (mode != TravelMode.FLY) tracks += track(at, to, leave, arrive)
            at = to
            since = arrive
        }

        // A road that bows a little to one side of the straight line, the same way each time.
        private fun track(from: Place, to: Place, leave: Long, arrive: Long): Track {
            val steps = 6
            val degrees = DoubleArray((steps + 1) * 2)
            val seconds = IntArray(steps + 1)
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val bow = 4 * t * (1 - t) * 0.12
                degrees[2 * i] = from.latitude + (to.latitude - from.latitude) * t + (to.longitude - from.longitude) * bow
                degrees[2 * i + 1] = from.longitude + (to.longitude - from.longitude) * t - (to.latitude - from.latitude) * bow
                seconds[i] = ((arrive - leave) * t).toInt()
            }
            return Track.decode(leave, OFFSET, Polyline.encode(degrees), Polyline.encodeNumbers(seconds))
        }

        fun finish(end: Long) {
            if (end > since) visits += Visit(since, end, OFFSET, at.latitude, at.longitude, at.key, at.kind)
        }
    }

    private val built: Builder by lazy {
        Builder().apply {
            var day = firstDay
            while (day <= lastDay) {
                val house = if (day < movedDay) firstHome else home
                // Something that differs from day to day without being random: the same every run.
                val wobble = (day * 7919) % 11
                when {
                    day == lakeTripDay -> {
                        go(lake, time(day, 9 * 60), 300)
                        go(lakeDiner, time(day + 1, 18 * 60), 10)
                        go(lake, time(day + 1, 20 * 60), 10)
                        go(lakeDiner, time(day + 3, 12 * 60), 12, TravelMode.WALK)
                        go(lake, time(day + 3, 13 * 60 + 30), 12, TravelMode.WALK)
                        go(house, time(day + 5, 10 * 60), 310)
                        day += 5
                    }

                    day == desertTripDay -> {
                        go(airport, time(day, 6 * 60), 35)
                        go(farAirport, time(day, 8 * 60), 245, TravelMode.FLY)
                        go(desert, time(day, 12 * 60 + 45), 25)
                        go(canyon, time(day + 1, 9 * 60), 40)
                        go(desert, time(day + 1, 15 * 60), 40)
                        go(farAirport, time(day + 3, 11 * 60), 25)
                        go(airport, time(day + 3, 13 * 60), 230, TravelMode.FLY)
                        go(house, time(day + 3, 17 * 60 + 30), 35)
                        day += 3
                    }

                    weekdayOf(day) < 5 -> {
                        // Wednesdays start late; the later the start, the slower the drive.
                        val leave = 7 * 60 + 40 + wobble + if (weekdayOf(day) == 2) 22 else 0
                        go(work, time(day, leave), 18 + (leave - 7 * 60 - 40))
                        val out = 17 * 60 + wobble
                        if (weekdayOf(day) == 1 || weekdayOf(day) == 3) {
                            go(gym, time(day, out), 14)
                            go(house, time(day, out + 14 + 65), 16)
                        } else {
                            go(house, time(day, out), 24 + wobble / 2)
                        }
                        // The move: the same things, from a different front door.
                        if (weekdayOf(day) == 4 && day < CivilDate.dayOf(2023, 11, 1) && (day / 7) % 2 == 0) {
                            go(bowling, time(day, 19 * 60 + 30), 12)
                            go(house, time(day, 21 * 60 + 45), 12)
                        }
                    }

                    weekdayOf(day) == 5 -> {
                        go(cafe, time(day, 9 * 60 + wobble), 4, TravelMode.DRIVE)
                        go(grocer, time(day, 10 * 60 + 10), 9)
                        go(house, time(day, 11 * 60), 8)
                    }

                    wobble < 4 -> {
                        go(cafe, time(day, 10 * 60 + 30), 18, TravelMode.CYCLE)
                        go(house, time(day, 11 * 60 + 40), 18, TravelMode.CYCLE)
                    }
                    // Otherwise a Sunday at home.
                }
                if (day >= movedDay && at == firstHome) at = home
                day++
            }
            finish(time(lastDay, 22 * 60))
        }
    }

    val history: AtlasHistory by lazy { AtlasHistory(built.visits, built.moves, places) }
    val tracks: List<Track> get() = built.tracks
    val paths: AtlasPaths by lazy { AtlasPaths(tracks, AtlasDensity.of(tracks)) }

    val server = AtlasServerStatus(
        driveConfigured = true,
        serviceAccount = "relay@example.iam.gserviceaccount.com",
        checkedAtEpochSeconds = lastDay * SECONDS_PER_DAY,
        fileName = "Timeline.json",
        fileReadAtEpochSeconds = lastDay * SECONDS_PER_DAY - 3600,
        namingConfigured = true,
        placesTotal = 13,
        placesNamed = 13,
        askedThisMonth = 13,
        monthlyCap = 4000,
    )

    val state: AtlasUiState by lazy {
        AtlasUiState(
            loaded = true,
            history = history,
            analysis = AtlasAnalyzer.analyze(history),
            paths = paths,
            sync = AtlasSyncState(server = server, checkedAtEpochSeconds = lastDay * SECONDS_PER_DAY),
            today = lastDay + 1,
            nowEpochSeconds = (lastDay + 1) * SECONDS_PER_DAY + 9 * 3600,
        )
    }
}
