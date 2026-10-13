package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One place and how it figures in the history. Days are counted from 1970-01-01. */
@Immutable
data class PlaceStat(
    val place: Place,
    val visits: Int,
    val seconds: Long,
    /** How many different days it was visited on. */
    val days: Int,
    val firstDay: Int,
    val lastDay: Int,
    /** The usual time of arriving, in minutes past midnight: the middle one of them all. */
    val typicalArrivalMinute: Int,
    /** The usual number of days between one visit and the next; 0 for a place visited once. */
    val typicalGapDays: Int,
)

/** Somewhere lived: where the nights were spent from [startDay] to [endDay], and the weekday place that went with it. */
@Immutable
data class Era(
    val home: Place,
    val startDay: Int,
    val endDay: Int,
    val work: Place? = null,
    val locality: Locality? = null,
    val places: Int = 0,
    val meters: Double = 0.0,
)

/** A stretch of days away from home. */
@Immutable
data class Trip(
    val startDay: Int,
    val endDay: Int,
    val farthestKm: Double,
    /** The town most of it was spent in, as far as is known. */
    val locality: Locality?,
    /** The place most of it was spent at, for a trip to somewhere with no town to its name. */
    val mainPlace: Place,
    val meters: Double,
    val flew: Boolean,
    /** The places stopped at, in order, each once. */
    val stops: List<Place>,
    val start: Long,
    val end: Long,
) {
    val nights: Int get() = endDay - startDay
}

@Immutable
data class ModeTotal(val meters: Double = 0.0, val seconds: Long = 0, val trips: Int = 0)

/** One calendar year of it. Shares are of the time that is accounted for and add up to one. */
@Immutable
data class YearStat(
    val year: Int,
    val modes: Map<TravelMode, ModeTotal>,
    val daysTracked: Int,
    val places: Int,
    val newPlaces: Int,
    val homeShare: Float,
    val workShare: Float,
    val otherShare: Float,
    val movingShare: Float,
    /** How far from its centre the year's time was spread, in kilometres (the radius of gyration). */
    val rangeKm: Double,
    val cities: Int,
    val regions: Int,
    val countries: Int,
) {
    val groundMeters: Double get() = modes.entries.sumOf { (mode, total) -> if (mode == TravelMode.FLY) 0.0 else total.meters }
    val activeMeters: Double get() = modes.entries.sumOf { (mode, total) -> if (mode.active) total.meters else 0.0 }
}

/** What an hour of the week is usually spent on, as shares of the time accounted for. */
@Immutable
data class RoutineSlot(val home: Float = 0f, val work: Float = 0f, val other: Float = 0f, val moving: Float = 0f) {
    val known: Boolean get() = home + work + other + moving > 0f
}

/**
 * The usual week: [slots] is 7 days of 24 hours, Monday's midnight first. [regularity] is how
 * much of the week is spent where that hour of the week is usually spent, from 0 to 1, over the
 * [weeks] it was worked out from.
 */
@Immutable
data class Routine(val slots: List<RoutineSlot>, val regularity: Float, val weeks: Int) {
    fun slot(weekday: Int, hour: Int): RoutineSlot = slots.getOrElse(weekday * 24 + hour) { RoutineSlot() }
}

@Immutable
data class WeekdayCommute(val weekday: Int, val departMinute: Int, val minutes: Int, val count: Int)

/** One direction of the trip between home and work, from the days it was made in one go. */
@Immutable
data class CommuteLeg(
    val departMinute: Int,
    val minutes: Int,
    val count: Int,
    val byWeekday: List<WeekdayCommute>,
    /** The quarter-hour to leave in that has the shortest trips, when one stands out; minutes past midnight, and how many minutes it saves. */
    val bestDepartMinute: Int? = null,
    val minutesSaved: Int = 0,
)

@Immutable
data class Commute(
    val toWork: CommuteLeg?,
    val toHome: CommuteLeg?,
    /** Hours spent on it in the last twelve months of the record. */
    val hoursPerYear: Int,
    /** The usual trip to work month by month, oldest first: months since year 0, and minutes. */
    val trend: List<Pair<Int, Int>>,
)

/** A country or a state, and how much of the record was spent in it. */
@Immutable
data class Region(val code: String, val firstDay: Int, val days: Int)

@Immutable
data class Records(
    val groundMeters: Double = 0.0,
    val airMeters: Double = 0.0,
    val longestDay: Int? = null,
    val longestDayMeters: Double = 0.0,
    val farthestDay: Int? = null,
    val farthestKm: Double = 0.0,
    val farthestPlace: Place? = null,
    val busiestDay: Int? = null,
    val busiestDayPlaces: Int = 0,
    val homeStreakStart: Int? = null,
    val homeStreakDays: Int = 0,
)

/** Something the record shows that its owner may not have noticed. Each is said by the screen, from these numbers. */
@Immutable
sealed interface Insight {
    /** How much of life is spent at home, as a whole percentage, and the hours a day that is. */
    data class HomeShare(val percent: Int, val hoursPerDay: Float) : Insight

    data class CommuteCost(val hoursPerYear: Int, val minutesEachWay: Int) : Insight

    /** The weekday that leaves for work later than the rest. */
    data class LateWeekday(val weekday: Int, val minutesLater: Int) : Insight

    /** Leaving in a particular quarter of an hour makes the trip to work shorter. */
    data class BetterDeparture(val departMinute: Int, val minutesSaved: Int) : Insight

    /** Somewhere that used to be gone to every [gapDays] or so and hasn't been for [daysSince]. */
    data class Drifted(val place: Place, val visits: Int, val gapDays: Int, val daysSince: Int) : Insight

    /** Somewhere gone to on the same day of the week, more often than not. */
    data class Habit(val place: Place, val weekday: Int, val arrivalMinute: Int, val visits: Int) : Insight

    data class NewPlaces(val thisYear: Int, val lastYear: Int, val year: Int) : Insight

    /** [returner]: life turns round a couple of places. Otherwise it ranges well beyond them. */
    data class Shape(val returner: Boolean, val topTwoShare: Int) : Insight

    data class Regularity(val percent: Int) : Insight

    data class Range(val km: Int, val lastYearKm: Int, val year: Int) : Insight

    /** Drives short enough to have walked. */
    data class ShortDrives(val count: Int, val km: Int, val year: Int) : Insight

    data class ActiveDistance(val km: Int, val lastYearKm: Int, val year: Int) : Insight

    data class DaysIn(val count: Int, val year: Int) : Insight
}

/** What each day held, for the calendar: one entry per day from [firstDay]. */
@Immutable
class DayStats(
    val firstDay: Int,
    val groundMeters: FloatArray,
    val airMeters: FloatArray,
    val places: IntArray,
    val newPlaces: IntArray,
    val farthestKm: FloatArray,
    val trackedSeconds: IntArray,
) {
    val size: Int get() = places.size

    fun index(day: Int): Int? = (day - firstDay).takeIf { it in 0 until size }

    companion object {
        val Empty = DayStats(0, FloatArray(0), FloatArray(0), IntArray(0), IntArray(0), FloatArray(0), IntArray(0))
    }
}

/** Everything worked out from an [AtlasHistory] that the screens show. */
@Immutable
data class AtlasAnalysis(
    val places: List<PlaceStat> = emptyList(),
    val eras: List<Era> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val years: List<YearStat> = emptyList(),
    val routine: Routine = Routine(emptyList(), 0f, 0),
    val commute: Commute? = null,
    val countries: List<Region> = emptyList(),
    val regions: List<Region> = emptyList(),
    val cities: Int = 0,
    val records: Records = Records(),
    val insights: List<Insight> = emptyList(),
    val days: DayStats = DayStats.Empty,
) {
    fun homeAt(day: Int): Era? = eras.lastOrNull { it.startDay <= day } ?: eras.firstOrNull()

    companion object {
        val Empty = AtlasAnalysis()
    }
}

/** What a visit is to the person, given where they lived and worked at the time. */
enum class VisitRole { HOME, WORK, OTHER }

/**
 * Works out [AtlasAnalysis] from a history. Pure, and a second or so for a decade of visits: run
 * it off the main thread.
 */
object AtlasAnalyzer {
    /** Two stays this close together are at the same address, whatever ids Google gave them. */
    private const val SAME_PLACE_METERS = 300.0

    /** Further than this from home is away. */
    private const val AWAY_METERS = 80_000.0
    private const val SITE_DEGREES = 0.002
    private const val NIGHT_END_SECOND = 6 * 3600
    private const val WORKDAY_START_SECOND = 9 * 3600
    private const val WORKDAY_END_SECOND = 17 * 3600
    private const val ROUTINE_DAYS = 182
    private const val SHORT_DRIVE_METERS = 1_600.0

    fun analyze(history: AtlasHistory): AtlasAnalysis {
        if (history.isEmpty) return AtlasAnalysis.Empty
        val eras = eras(history)
        val roles = history.visits.map { role(it, eraAt(eras, it.startDay)) }
        val days = dayStats(history, eras)
        val places = placeStats(history)
        val years = years(history, roles, days)
        val routine = routine(history, roles, (history.lastDay - ROUTINE_DAYS + 1).coerceAtLeast(history.firstDay), history.lastDay)
        val commute = commute(history, roles)
        val trips = trips(history, eras)
        val (countries, regions, cities) = coverage(history)
        val records = records(history, eras, days)
        val filled = eras.map { era ->
            val within = history.visits.filter { it.startDay in era.startDay..era.endDay }
            era.copy(
                locality = history.locality(era.home),
                places = within.mapTo(HashSet()) { it.placeKey }.size,
                meters = history.moves.sumOf { if (it.startDay in era.startDay..era.endDay && it.mode != TravelMode.FLY) it.meters else 0.0 },
            )
        }
        return AtlasAnalysis(
            places = places,
            eras = filled,
            trips = trips,
            years = years,
            routine = routine,
            commute = commute,
            countries = countries,
            regions = regions,
            cities = cities,
            records = records,
            insights = insights(history, places, years, routine, commute, roles, days, eras),
            days = days,
        )
    }

    private fun site(latitude: Double, longitude: Double): Long =
        (latitude / SITE_DEGREES).roundToLong() * 1_000_000L + (longitude / SITE_DEGREES).roundToLong()

    private fun eraAt(eras: List<Era>, day: Int): Era? = eras.lastOrNull { it.startDay <= day } ?: eras.firstOrNull()

    private fun near(place: Place?, visit: Visit): Boolean =
        place != null && metersBetween(place.latitude, place.longitude, visit.latitude, visit.longitude) <= SAME_PLACE_METERS

    internal fun role(visit: Visit, era: Era?): VisitRole = when {
        near(era?.home, visit) -> VisitRole.HOME

        near(era?.work, visit) -> VisitRole.WORK

        // What Google calls home is today's; a stay at it years ago was still a stay at home.
        visit.kind == PlaceKind.HOME && era == null -> VisitRole.HOME

        visit.kind == PlaceKind.WORK -> VisitRole.WORK

        else -> VisitRole.OTHER
    }

    /** The seconds of a stay that fall between two times of day, on the clock where it was. */
    internal fun secondsWithin(visit: Visit, fromSecond: Int, toSecond: Int, weekdaysOnly: Boolean = false): Long {
        val shift = visit.offsetMinutes * 60L
        val start = visit.start + shift
        val end = visit.end + shift
        var total = 0L
        var day = start.floorDiv(SECONDS_PER_DAY)
        // A gap in the record leaves a stay open for weeks; what it says of those nights isn't known.
        val last = minOf((end - 1).floorDiv(SECONDS_PER_DAY), day + 14)
        while (day <= last) {
            if (!weekdaysOnly || weekdayOf(day.toInt()) < 5) {
                val a = maxOf(start, day * SECONDS_PER_DAY + fromSecond)
                val b = minOf(end, day * SECONDS_PER_DAY + toSecond)
                if (b > a) total += b - a
            }
            day++
        }
        return total
    }

    /** Where most of a span's time between two times of day was spent, as the place whose stays there add up longest. */
    private class Tally {
        val seconds = HashMap<Long, Long>()
        val keys = HashMap<Long, HashMap<String, Long>>()
        val days = HashMap<Long, HashSet<Int>>()

        fun add(site: Long, visit: Visit, amount: Long) {
            if (amount <= 0) return
            seconds[site] = (seconds[site] ?: 0) + amount
            keys.getOrPut(site) { HashMap() }.let { it[visit.placeKey] = (it[visit.placeKey] ?: 0) + amount }
            days.getOrPut(site) { HashSet() }.add(visit.startDay)
        }

        fun top(): Long? = seconds.maxByOrNull { it.value }?.key

        fun place(site: Long, history: AtlasHistory): Place? = keys[site]?.maxByOrNull { it.value }?.key?.let(history::place)
    }

    /**
     * Where home has been. Home is where the nights are: for each month, the place most of the
     * hours between midnight and six were spent, and a run of months at one place is an era. A
     * month spent somewhere else (a long trip) doesn't break one.
     */
    internal fun eras(history: AtlasHistory): List<Era> {
        val nights = HashMap<Int, Tally>()
        history.visits.forEach { visit ->
            nights.getOrPut(CivilDate.of(visit.startDay).monthIndex) { Tally() }.add(site(visit.latitude, visit.longitude), visit, secondsWithin(visit, 0, NIGHT_END_SECOND))
        }
        // Each month's home, where a few nights' worth says so.
        val monthly = nights.entries.sortedBy { it.key }.mapNotNull { (month, tally) ->
            val top = tally.top() ?: return@mapNotNull null
            val place = tally.place(top, history) ?: return@mapNotNull null
            if ((tally.seconds[top] ?: 0) < 4 * NIGHT_END_SECOND) null else month to place
        }
        if (monthly.isEmpty()) {
            // No nights on record at all: what Google calls home, or failing that wherever most time went.
            val fallback = history.places.values.firstOrNull { it.kind == PlaceKind.HOME }
                ?: history.visits.groupBy { it.placeKey }.maxByOrNull { (_, stays) -> stays.sumOf { it.seconds } }?.key?.let(history::place)
                ?: return emptyList()
            return listOf(withWork(history, Era(fallback, history.firstDay, history.lastDay)))
        }
        // Runs of months at one place, then those of a single month folded into the one before.
        val runs = ArrayList<Triple<Place, Int, Int>>()
        monthly.forEach { (month, place) ->
            val last = runs.lastOrNull()
            if (last != null && metersBetween(last.first.latitude, last.first.longitude, place.latitude, place.longitude) <= SAME_PLACE_METERS) {
                runs[runs.lastIndex] = Triple(last.first, last.second, month)
            } else {
                runs += Triple(place, month, month)
            }
        }
        val kept = ArrayList<Triple<Place, Int, Int>>()
        runs.forEach { run ->
            val last = kept.lastOrNull()
            when {
                run.third - run.second >= 1 || runs.size == 1 -> {
                    // Back where it was before a month away: one era, not two.
                    if (last != null && metersBetween(last.first.latitude, last.first.longitude, run.first.latitude, run.first.longitude) <= SAME_PLACE_METERS) {
                        kept[kept.lastIndex] = Triple(last.first, last.second, run.third)
                    } else {
                        kept += run
                    }
                }

                last != null -> kept[kept.lastIndex] = Triple(last.first, last.second, run.third)
            }
        }
        if (kept.isEmpty()) kept += runs.maxBy { it.third - it.second }
        return kept.mapIndexed { i, (place, fromMonth, _) ->
            val start = if (i == 0) history.firstDay else CivilDate.dayOf(fromMonth / 12, fromMonth % 12 + 1, 1)
            val end = if (i == kept.lastIndex) history.lastDay else CivilDate.dayOf(kept[i + 1].second / 12, kept[i + 1].second % 12 + 1, 1) - 1
            withWork(history, Era(place.copy(kind = PlaceKind.HOME), start, end))
        }
    }

    /** The era with the place its weekdays were spent at, when there is one: a fifth of working hours on twenty days or more. */
    private fun withWork(history: AtlasHistory, era: Era): Era {
        val tally = Tally()
        var total = 0L
        history.visits.forEach { visit ->
            if (visit.startDay !in era.startDay..era.endDay) return@forEach
            val amount = secondsWithin(visit, WORKDAY_START_SECOND, WORKDAY_END_SECOND, weekdaysOnly = true)
            total += amount
            if (!near(era.home, visit)) tally.add(site(visit.latitude, visit.longitude), visit, amount)
        }
        val top = tally.top()
        val found = top?.takeIf { (tally.seconds[it] ?: 0) >= total / 5 && (tally.days[it]?.size ?: 0) >= 20 }?.let { tally.place(it, history) }
        val named = history.visits.firstOrNull { it.kind == PlaceKind.WORK && it.startDay in era.startDay..era.endDay }?.let { history.place(it.placeKey) }
        return era.copy(work = (found ?: named)?.copy(kind = PlaceKind.WORK))
    }

    private fun dayStats(history: AtlasHistory, eras: List<Era>): DayStats {
        val count = history.dayCount
        val ground = FloatArray(count)
        val air = FloatArray(count)
        val places = IntArray(count)
        val fresh = IntArray(count)
        val farthest = FloatArray(count)
        val tracked = IntArray(count)
        history.moves.forEach { move ->
            val at = move.startDay - history.firstDay
            if (at in 0 until count) {
                if (move.mode == TravelMode.FLY) air[at] += move.meters.toFloat() else ground[at] += move.meters.toFloat()
                tracked[at] += move.seconds.coerceIn(0, SECONDS_PER_DAY).toInt()
            }
        }
        val seen = HashSet<String>()
        history.visits.forEach { visit ->
            if (seen.add(visit.placeKey)) fresh[visit.startDay - history.firstDay]++
        }
        for (i in 0 until count) {
            val day = history.firstDay + i
            val visits = history.visitsOn(day)
            if (visits.isEmpty()) continue
            places[i] = visits.mapTo(HashSet()) { it.placeKey }.size
            val home = eraAt(eras, day)?.home
            visits.forEach { visit ->
                if (home != null) farthest[i] = maxOf(farthest[i], (metersBetween(home.latitude, home.longitude, visit.latitude, visit.longitude) / 1000).toFloat())
                val from = maxOf(visit.start + visit.offsetMinutes * 60L, day * SECONDS_PER_DAY)
                val until = minOf(visit.end + visit.offsetMinutes * 60L, (day + 1) * SECONDS_PER_DAY)
                if (until > from) tracked[i] += (until - from).toInt()
            }
        }
        return DayStats(history.firstDay, ground, air, places, fresh, farthest, tracked)
    }

    private fun median(values: List<Int>): Int = if (values.isEmpty()) 0 else values.sorted()[values.size / 2]

    internal fun placeStats(history: AtlasHistory): List<PlaceStat> =
        history.visits.groupBy { it.placeKey }.map { (key, stays) ->
            val days = stays.map { it.startDay }.distinct()
            PlaceStat(
                place = history.place(key),
                visits = stays.size,
                seconds = stays.sumOf { it.seconds },
                days = days.size,
                firstDay = days.first(),
                lastDay = stays.last().endDay,
                typicalArrivalMinute = median(stays.map { localMinute(it.start, it.offsetMinutes) }),
                typicalGapDays = median(days.zipWithNext { a, b -> b - a }),
            )
        }.sortedByDescending { it.seconds }

    private fun years(history: AtlasHistory, roles: List<VisitRole>, days: DayStats): List<YearStat> {
        val firstSeen = HashMap<String, Int>()
        history.visits.forEach { visit -> firstSeen.getOrPut(visit.placeKey) { CivilDate.of(visit.startDay).year } }
        val visitsByYear = history.visits.indices.groupBy { CivilDate.of(history.visits[it].startDay).year }
        val movesByYear = history.moves.groupBy { CivilDate.of(it.startDay).year }
        // Time is counted in the year it fell in: the night of New Year's Eve is part of both.
        // A place, a visit and a trip's distance belong to the year they began in.
        val time = HashMap<Int, LongArray>()
        history.visits.forEachIndexed { i, visit ->
            secondsByYear(visit.start, visit.end, visit.offsetMinutes).forEach { (year, seconds) -> time.getOrPut(year) { LongArray(4) }[roles[i].ordinal] += seconds }
        }
        history.moves.forEach { move ->
            secondsByYear(move.start, move.end, move.offsetMinutes).forEach { (year, seconds) -> time.getOrPut(year) { LongArray(4) }[MOVING] += seconds }
        }
        return (visitsByYear.keys + movesByYear.keys + time.keys).sorted().map { year ->
            val indexes = visitsByYear[year].orEmpty()
            val moves = movesByYear[year].orEmpty()
            val seconds = time[year] ?: LongArray(4)
            val moving = seconds[MOVING]
            val total = seconds.sum().coerceAtLeast(1).toFloat()
            val stays = indexes.map { history.visits[it] }
            val keys = stays.mapTo(HashSet()) { it.placeKey }
            val localities = keys.mapNotNull { history.locality(history.place(it)) }
            YearStat(
                year = year,
                modes = moves.groupBy { it.mode }.mapValues { (_, of) -> ModeTotal(of.sumOf { it.meters }, of.sumOf { it.seconds }, of.size) },
                // A Sunday spent wholly at home starts nothing and is a day on record all the same.
                daysTracked = (CivilDate.dayOf(year, 1, 1) until CivilDate.dayOf(year + 1, 1, 1)).count { day -> days.index(day)?.let { days.trackedSeconds[it] > 0 } == true },
                places = keys.size,
                newPlaces = keys.count { firstSeen[it] == year },
                homeShare = seconds[VisitRole.HOME.ordinal] / total,
                workShare = seconds[VisitRole.WORK.ordinal] / total,
                otherShare = seconds[VisitRole.OTHER.ordinal] / total,
                movingShare = moving / total,
                rangeKm = rangeKm(stays),
                cities = localities.mapNotNullTo(HashSet()) { it.city?.let { city -> "$city|${it.region}|${it.country}" } }.size,
                regions = localities.mapNotNullTo(HashSet()) { it.region?.let { region -> "${it.country}-$region" } }.size,
                countries = localities.mapNotNullTo(HashSet()) { it.country }.size,
            )
        }
    }

    private const val MOVING = 3

    /**
     * A span's seconds, local year by local year: one entry unless it runs over the turn of a
     * year, where the clock was [offsetMinutes] ahead of UTC.
     */
    internal fun secondsByYear(start: Long, end: Long, offsetMinutes: Int): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>(1)
        var at = start
        while (at < end) {
            val year = CivilDate.of(localDay(at, offsetMinutes)).year
            val next = minOf(end, CivilDate.dayOf(year + 1, 1, 1) * SECONDS_PER_DAY - offsetMinutes * 60L)
            out += year to next - at
            at = next
        }
        return out
    }

    /**
     * The radius of gyration: how far, on average, time was spent from the centre of where it was
     * all spent, in kilometres. A year at home and the office five kilometres apart is about two.
     */
    internal fun rangeKm(stays: List<Visit>): Double {
        val total = stays.sumOf { it.seconds }.toDouble()
        if (total <= 0) return 0.0
        val latitude = stays.sumOf { it.latitude * it.seconds } / total
        val longitude = stays.sumOf { it.longitude * it.seconds } / total
        val squared = stays.sumOf { stay ->
            val km = metersBetween(latitude, longitude, stay.latitude, stay.longitude) / 1000
            km * km * stay.seconds
        }
        return sqrt(squared / total)
    }

    /** The usual week over a span of days, and how regular it is. */
    fun routine(history: AtlasHistory, roles: List<VisitRole>, fromDay: Int, toDay: Int): Routine {
        val slots = Array(168) { FloatArray(4) }
        // For each hour of the week, the time spent at each place: where most of it goes is the usual place.
        val sites = Array(168) { HashMap<Long, Float>() }
        val from = fromDay * SECONDS_PER_DAY
        val until = (toDay + 1) * SECONDS_PER_DAY

        fun spread(startLocal: Long, endLocal: Long, add: (slot: Int, seconds: Float) -> Unit) {
            var at = maxOf(startLocal, from)
            val end = minOf(endLocal, until)
            while (at < end) {
                val hourEnd = (at.floorDiv(3600) + 1) * 3600
                val next = minOf(hourEnd, end)
                val day = at.floorDiv(SECONDS_PER_DAY).toInt()
                add(weekdayOf(day) * 24 + (at.mod(SECONDS_PER_DAY) / 3600).toInt(), (next - at).toFloat())
                at = next
            }
        }
        history.visits.forEachIndexed { i, visit ->
            if (visit.endDay < fromDay || visit.startDay > toDay) return@forEachIndexed
            val shift = visit.offsetMinutes * 60L
            val role = roles[i]
            val where = if (role == VisitRole.OTHER) site(visit.latitude, visit.longitude) else -1L - role.ordinal
            spread(visit.start + shift, visit.end + shift) { slot, seconds ->
                slots[slot][role.ordinal] += seconds
                sites[slot][where] = (sites[slot][where] ?: 0f) + seconds
            }
        }
        history.moves.forEach { move ->
            if (move.startDay < fromDay - 1 || move.startDay > toDay) return@forEach
            val shift = move.offsetMinutes * 60L
            spread(move.start + shift, move.end + shift) { slot, seconds -> slots[slot][3] += seconds }
        }
        var usual = 0f
        var all = 0f
        val out = slots.mapIndexed { i, seconds ->
            val total = seconds.sum()
            usual += maxOf(sites[i].values.maxOrNull() ?: 0f, seconds[3])
            all += total
            if (total <= 0f) RoutineSlot() else RoutineSlot(seconds[0] / total, seconds[1] / total, seconds[2] / total, seconds[3] / total)
        }
        return Routine(out, if (all > 0f) usual / all else 0f, ((toDay - fromDay + 1) / 7).coerceAtLeast(0))
    }

    private data class Ride(val day: Int, val departMinute: Int, val minutes: Int)

    private fun leg(rides: List<Ride>, lastDay: Int): CommuteLeg? {
        // The last year when it has enough to go on: a commute changes with a job or a house.
        val recent = rides.filter { it.day > lastDay - 365 }.takeIf { it.size >= 10 } ?: rides
        if (recent.size < 5) return null
        val usual = median(recent.map { it.minutes })
        val byWeekday = recent.groupBy { weekdayOf(it.day) }.filter { it.value.size >= 3 }.map { (weekday, of) ->
            WeekdayCommute(weekday, median(of.map { it.departMinute }), median(of.map { it.minutes }), of.size)
        }.sortedBy { it.weekday }
        val best = recent.groupBy { it.departMinute / 15 }.filter { it.value.size >= 5 }
            .map { (quarter, of) -> quarter * 15 to median(of.map { it.minutes }) }.minByOrNull { it.second }
        val saved = best?.let { usual - it.second } ?: 0
        return CommuteLeg(
            departMinute = median(recent.map { it.departMinute }),
            minutes = usual,
            count = recent.size,
            byWeekday = byWeekday,
            bestDepartMinute = best?.first?.takeIf { saved >= 4 },
            minutesSaved = saved.coerceAtLeast(0),
        )
    }

    /** The trip between home and work, from the weekdays it was made in one go (a stop on the way makes it two trips, and neither is the commute). */
    internal fun commute(history: AtlasHistory, roles: List<VisitRole>): Commute? {
        val out = ArrayList<Ride>()
        val back = ArrayList<Ride>()
        for (i in 0 until history.visits.size - 1) {
            val a = history.visits[i]
            val b = history.visits[i + 1]
            val minutes = ((b.start - a.end) / 60).toInt()
            if (minutes !in 3..180 || weekdayOf(a.endDay) >= 5) continue
            val ride = Ride(localDay(a.end, a.offsetMinutes), localMinute(a.end, a.offsetMinutes), minutes)
            if (roles[i] == VisitRole.HOME && roles[i + 1] == VisitRole.WORK) out += ride
            if (roles[i] == VisitRole.WORK && roles[i + 1] == VisitRole.HOME) back += ride
        }
        val toWork = leg(out, history.lastDay)
        val toHome = leg(back, history.lastDay)
        if (toWork == null && toHome == null) return null
        val year = (out + back).filter { it.day > history.lastDay - 365 }.sumOf { it.minutes }
        val trend = out.groupBy { CivilDate.of(it.day).monthIndex }.filter { it.value.size >= 3 }.map { (month, of) -> month to median(of.map { it.minutes }) }.sortedBy { it.first }
        return Commute(toWork, toHome, (year / 60.0).roundToInt(), trend.takeLast(48))
    }

    internal fun trips(history: AtlasHistory, eras: List<Era>): List<Trip> {
        val trips = ArrayList<Trip>()
        var run = ArrayList<Visit>()
        var farthest = 0.0

        fun close() {
            val stays = run
            run = ArrayList()
            val far = farthest
            farthest = 0.0
            if (stays.isEmpty()) return
            val first = stays.first()
            val last = stays.last()
            val nights = last.endDay - first.startDay
            if (nights < 1 && far < 150_000) return
            // The way there and back counts: from leaving home to getting back to it.
            val before = history.visits.getOrNull(history.visits.indexOf(first) - 1)?.end ?: first.start
            val after = history.visits.getOrNull(history.visits.indexOf(last) + 1)?.start ?: last.end
            val moves = history.moves.filter { it.start >= before - 60 && it.end <= after + 60 }
            val byPlace = stays.groupBy { it.placeKey }
            val mainPlace = history.place(byPlace.maxBy { (_, of) -> of.sumOf { it.seconds } }.key)
            val locality = stays.mapNotNull { stay -> history.locality(history.place(stay.placeKey))?.let { it to stay.seconds } }
                .groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key
            trips += Trip(
                startDay = first.startDay,
                endDay = last.endDay,
                farthestKm = far / 1000,
                locality = locality,
                mainPlace = mainPlace,
                meters = moves.sumOf { it.meters },
                flew = moves.any { it.mode == TravelMode.FLY },
                stops = stays.map { it.placeKey }.distinct().take(40).map(history::place),
                start = before,
                end = after,
            )
        }
        history.visits.forEach { visit ->
            val home = eraAt(eras, visit.startDay)?.home
            val meters = home?.let { metersBetween(it.latitude, it.longitude, visit.latitude, visit.longitude) } ?: 0.0
            if (meters > AWAY_METERS) {
                run += visit
                farthest = maxOf(farthest, meters)
            } else {
                close()
            }
        }
        close()
        return trips.asReversed().toList()
    }

    private fun coverage(history: AtlasHistory): Triple<List<Region>, List<Region>, Int> {
        val countries = HashMap<String, HashSet<Int>>()
        val regions = HashMap<String, HashSet<Int>>()
        val cities = HashSet<String>()
        val localities = HashMap<String, Locality?>()
        history.visits.forEach { visit ->
            val locality = localities.getOrPut(visit.placeKey) { history.locality(history.place(visit.placeKey)) } ?: return@forEach
            // Every day of the stay, not only the one it began on (a stay left open by a gap in the record aside).
            val days = visit.startDay..minOf(visit.endDay, visit.startDay + 60)
            locality.country?.let { country ->
                countries.getOrPut(country) { HashSet() }.addAll(days)
                locality.region?.let { regions.getOrPut("$country-$it") { HashSet() }.addAll(days) }
            }
            locality.city?.let { cities += "$it|${locality.region}|${locality.country}" }
        }

        fun ranked(of: Map<String, Set<Int>>) = of.map { (code, days) -> Region(code, days.min(), days.size) }.sortedByDescending { it.days }
        return Triple(ranked(countries), ranked(regions), cities.size)
    }

    private fun records(history: AtlasHistory, eras: List<Era>, days: DayStats): Records {
        var longest = -1
        var busiest = -1
        var farthest = -1
        var streak = 0
        var bestStreak = 0
        var bestStreakStart: Int? = null
        for (i in 0 until days.size) {
            if (longest < 0 || days.groundMeters[i] > days.groundMeters[longest]) longest = i
            if (busiest < 0 || days.places[i] > days.places[busiest]) busiest = i
            if (farthest < 0 || days.farthestKm[i] > days.farthestKm[farthest]) farthest = i
            // A day at home: nowhere else, hardly moved, and most of the day accounted for.
            val home = days.places[i] == 1 && days.farthestKm[i] < 0.5f && days.groundMeters[i] < 500f && days.trackedSeconds[i] >= 18 * 3600
            streak = if (home) streak + 1 else 0
            if (streak > bestStreak) {
                bestStreak = streak
                bestStreakStart = days.firstDay + i - streak + 1
            }
        }
        val farthestDay = farthest.takeIf { it >= 0 && days.farthestKm[it] > 0f }?.let { days.firstDay + it }
        val farthestPlace = farthestDay?.let { day ->
            val home = eraAt(eras, day)?.home ?: return@let null
            history.visitsOn(day).maxByOrNull { metersBetween(home.latitude, home.longitude, it.latitude, it.longitude) }?.let { history.place(it.placeKey) }
        }
        return Records(
            groundMeters = days.groundMeters.sumOf { it.toDouble() },
            airMeters = days.airMeters.sumOf { it.toDouble() },
            longestDay = longest.takeIf { it >= 0 && days.groundMeters[it] > 0f }?.let { days.firstDay + it },
            longestDayMeters = if (longest >= 0) days.groundMeters[longest].toDouble() else 0.0,
            farthestDay = farthestDay,
            farthestKm = if (farthest >= 0) days.farthestKm[farthest].toDouble() else 0.0,
            farthestPlace = farthestPlace,
            busiestDay = busiest.takeIf { it >= 0 && days.places[it] > 1 }?.let { days.firstDay + it },
            busiestDayPlaces = if (busiest >= 0) days.places[busiest] else 0,
            homeStreakStart = bestStreakStart.takeIf { bestStreak >= 2 },
            homeStreakDays = bestStreak,
        )
    }

    private fun insights(
        history: AtlasHistory,
        places: List<PlaceStat>,
        years: List<YearStat>,
        routine: Routine,
        commute: Commute?,
        roles: List<VisitRole>,
        days: DayStats,
        eras: List<Era>,
    ): List<Insight> {
        val out = ArrayList<Insight>()
        val lastDay = history.lastDay
        val thisYear = years.lastOrNull()
        val lastYear = years.getOrNull(years.size - 2)
        // The year so far against the same stretch of the one before would be fairer, but a year
        // that has barely started says little either way: compare whole years until it is half gone.
        val current = thisYear?.takeIf { it.daysTracked >= 150 } ?: lastYear
        val before = current?.let { years.getOrNull(years.indexOf(it) - 1) }

        val recentHome = history.visits.indices.filter { history.visits[it].startDay > lastDay - 365 }
        val recentSeconds = recentHome.sumOf { history.visits[it].seconds } + history.moves.sumOf { if (it.startDay > lastDay - 365) it.seconds else 0L }
        if (recentSeconds > 30 * SECONDS_PER_DAY) {
            val home = recentHome.sumOf { if (roles[it] == VisitRole.HOME) history.visits[it].seconds else 0L }.toDouble() / recentSeconds
            out += Insight.HomeShare((home * 100).roundToInt(), (home * 24).toFloat())
        }
        if (routine.weeks >= 8) out += Insight.Regularity((routine.regularity * 100).roundToInt())

        commute?.let { found ->
            val leg = found.toWork ?: found.toHome
            if (leg != null && found.hoursPerYear > 0) out += Insight.CommuteCost(found.hoursPerYear, leg.minutes)
            found.toWork?.let { toWork ->
                toWork.byWeekday.maxByOrNull { it.departMinute }?.let { late ->
                    val later = late.departMinute - toWork.departMinute
                    if (later >= 8 && toWork.byWeekday.size >= 3) out += Insight.LateWeekday(late.weekday, later)
                }
                toWork.bestDepartMinute?.let { out += Insight.BetterDeparture(it, toWork.minutesSaved) }
            }
        }

        // Somewhere that had a rhythm to it and has gone quiet for far longer than that rhythm.
        // A house moved out of and a job left are not places drifted away from.
        val lived = eras.flatMap { listOfNotNull(it.home, it.work) }
        places.asSequence()
            .filter { it.visits >= 8 && it.typicalGapDays in 1..45 && it.place.kind == PlaceKind.OTHER && it.place.name != null }
            .filter { stat -> lived.none { metersBetween(it.latitude, it.longitude, stat.place.latitude, stat.place.longitude) <= SAME_PLACE_METERS } }
            .filter { lastDay - it.lastDay > maxOf(4 * it.typicalGapDays, 120) }
            .sortedByDescending { it.visits }
            .take(3)
            .forEach { out += Insight.Drifted(it.place, it.visits, it.typicalGapDays, lastDay - it.lastDay) }

        // A standing date: most of a place's recent visits on one day of the week.
        val recentByPlace = history.visits.filter { it.startDay > lastDay - 365 }.groupBy { it.placeKey }
        recentByPlace.asSequence()
            .mapNotNull { (key, stays) ->
                val place = history.place(key)
                if (stays.size < 10 || place.name == null) return@mapNotNull null
                val role = roles[history.visits.indexOf(stays.first())]
                if (role != VisitRole.OTHER) return@mapNotNull null
                val (weekday, on) = stays.groupBy { weekdayOf(it.startDay) }.maxBy { it.value.size }
                if (on.size * 10 < stays.size * 6) null else Insight.Habit(place, weekday, median(on.map { localMinute(it.start, it.offsetMinutes) }), on.size)
            }
            .sortedByDescending { it.visits }
            .take(2)
            .forEach { out += it }

        if (current != null && before != null) {
            out += Insight.NewPlaces(current.newPlaces, before.newPlaces, current.year)
            if (current.rangeKm >= 1 || before.rangeKm >= 1) out += Insight.Range(current.rangeKm.roundToInt(), before.rangeKm.roundToInt(), current.year)
            val active = (current.activeMeters / 1000).roundToInt()
            if (active > 0 || before.activeMeters > 0) out += Insight.ActiveDistance(active, (before.activeMeters / 1000).roundToInt(), current.year)
        }
        current?.let { year ->
            val shortDrives = history.moves.filter { it.mode == TravelMode.DRIVE && it.meters in 200.0..SHORT_DRIVE_METERS && CivilDate.of(it.startDay).year == year.year }
            if (shortDrives.size >= 10) out += Insight.ShortDrives(shortDrives.size, (shortDrives.sumOf { it.meters } / 1000).roundToInt(), year.year)
            val first = CivilDate.dayOf(year.year, 1, 1)
            var daysIn = 0
            for (day in first until CivilDate.dayOf(year.year + 1, 1, 1)) {
                val i = days.index(day) ?: continue
                if (days.places[i] == 1 && days.farthestKm[i] < 0.5f && days.groundMeters[i] < 500f && days.trackedSeconds[i] >= 18 * 3600) daysIn++
            }
            if (daysIn >= 5) out += Insight.DaysIn(daysIn, year.year)
        }

        // Whether life at home turns round two places or ranges beyond them: how much of the
        // spread of the last year's visits the two most visited account for. Trips away are left
        // out, or one flight would make an explorer of anyone.
        val recent = history.visits.filter { visit ->
            visit.startDay > lastDay - 365 && eraAt(eras, visit.startDay)?.home?.let { metersBetween(it.latitude, it.longitude, visit.latitude, visit.longitude) <= AWAY_METERS } == true
        }
        if (recent.size >= 50) {
            val top = recent.groupBy { it.placeKey }.entries.sortedByDescending { it.value.size }.take(2).flatMap { it.value }
            val whole = visitRangeKm(recent)
            if (whole > 0.5) {
                val share = (visitRangeKm(top) / whole).coerceIn(0.0, 1.0)
                out += Insight.Shape(returner = share >= 0.5, topTwoShare = (share * 100).roundToInt())
            }
        }
        return out
    }

    /** The radius of gyration with every visit counting the same, however long it lasted. */
    private fun visitRangeKm(stays: List<Visit>): Double {
        if (stays.isEmpty()) return 0.0
        val latitude = stays.sumOf { it.latitude } / stays.size
        val longitude = stays.sumOf { it.longitude } / stays.size
        // Flat is near enough over the distances a year of visits covers.
        val kmPerDegree = 111.32
        val squared = stays.sumOf { stay ->
            val north = (stay.latitude - latitude) * kmPerDegree
            val east = (stay.longitude - longitude) * kmPerDegree * cos(latitude * PI / 180)
            north * north + east * east
        }
        return sqrt(squared / stays.size)
    }

    /** How evenly time is spread over places, from 0 (all in one) to 1 (the same in each). */
    fun evenness(seconds: Collection<Long>): Double {
        val total = seconds.sum().toDouble()
        if (total <= 0 || seconds.size < 2) return 0.0
        return -seconds.sumOf { if (it <= 0) 0.0 else (it / total) * ln(it / total) } / ln(seconds.size.toDouble())
    }

    /**
     * When a place is gone to: for each hour of the week (Monday's midnight first, 168 of them),
     * the time spent there in it, against the hour most is: 1 for that one, 0 for an hour never there.
     */
    fun rhythm(history: AtlasHistory, placeKey: String): List<Float> {
        val seconds = FloatArray(168)
        history.visits.forEach { visit ->
            if (visit.placeKey != placeKey) return@forEach
            val shift = visit.offsetMinutes * 60L
            var at = visit.start + shift
            val end = minOf(visit.end + shift, at + 14 * SECONDS_PER_DAY)
            while (at < end) {
                val next = minOf((at.floorDiv(3600) + 1) * 3600, end)
                seconds[weekdayOf(at.floorDiv(SECONDS_PER_DAY).toInt()) * 24 + (at.mod(SECONDS_PER_DAY) / 3600).toInt()] += (next - at).toFloat()
                at = next
            }
        }
        val most = seconds.max()
        return seconds.map { if (most > 0f) it / most else 0f }
    }
}
