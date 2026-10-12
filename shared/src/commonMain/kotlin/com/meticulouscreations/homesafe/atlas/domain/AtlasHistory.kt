package com.meticulouscreations.homesafe.atlas.domain

import androidx.compose.runtime.Immutable
import kotlin.math.floor

/** A year, a month (1–12) and a day of the month. */
@Immutable
data class CivilDate(val year: Int, val month: Int, val dayOfMonth: Int) {
    /** Months since year 0, for telling months apart and in order. */
    val monthIndex: Int get() = year * 12 + month - 1

    companion object {
        /** The date of a day counted from 1970-01-01, by the proleptic Gregorian calendar. */
        fun of(day: Int): CivilDate {
            val z = day + 719_468
            val era = z.floorDiv(146_097)
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val shifted = (5 * dayOfYear + 2) / 153
            val month = if (shifted < 10) shifted + 3 else shifted - 9
            return CivilDate(yearOfEra + era * 400 + if (month <= 2) 1 else 0, month, dayOfYear - (153 * shifted + 2) / 5 + 1)
        }

        /** The day counted from 1970-01-01 of a date. */
        fun dayOf(year: Int, month: Int, dayOfMonth: Int): Int {
            val y = if (month <= 2) year - 1 else year
            val era = y.floorDiv(400)
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + dayOfMonth - 1
            return era * 146_097 + yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear - 719_468
        }

        fun daysIn(year: Int, month: Int): Int = dayOf(if (month == 12) year + 1 else year, if (month == 12) 1 else month + 1, 1) - dayOf(year, month, 1)
    }
}

/** The town a place is in, as far as is known: any of the three may be missing. */
@Immutable
data class Locality(val city: String?, val region: String?, val country: String?)

/**
 * Everything that is known of where someone has been: every [visits] and every [moves], each in
 * the order it happened, and what the places are called. The paths between are not here: there
 * are millions of points to them, and they are read when a map or a day asks (`AtlasRepository`).
 */
@Immutable
class AtlasHistory(val visits: List<Visit>, val moves: List<Move>, named: Map<String, Place> = emptyMap()) {

    val isEmpty: Boolean get() = visits.isEmpty() && moves.isEmpty()

    /** The first and last day anything is known of, counted from 1970-01-01; both 0 when nothing is. */
    val firstDay: Int = listOfNotNull(visits.firstOrNull()?.startDay, moves.firstOrNull()?.startDay).minOrNull() ?: 0
    val lastDay: Int = maxOf(visits.maxOfOrNull { it.endDay } ?: firstDay, moves.maxOfOrNull { it.startDay } ?: firstDay)
    val dayCount: Int get() = if (isEmpty) 0 else lastDay - firstDay + 1

    /** Every place visited, by its key: Google's for those it has named, and a stand-in at the spot for the rest. */
    val places: Map<String, Place> = buildMap {
        visits.forEach { visit ->
            val known = get(visit.placeKey)
            if (known == null) {
                put(visit.placeKey, (named[visit.placeKey] ?: Place(visit.placeKey, visit.latitude, visit.longitude)).let { if (visit.kind != PlaceKind.OTHER) it.copy(kind = visit.kind) else it })
            } else if (known.kind == PlaceKind.OTHER && visit.kind != PlaceKind.OTHER) {
                put(visit.placeKey, known.copy(kind = visit.kind))
            }
        }
    }

    // For each day, the first and last of the visits (and moves) that touch it. They are in time
    // order and don't overlap, so those that touch one day are next to each other.
    private val visitSpans: IntArray by lazy { spans(visits.size) { i -> visits[i].startDay to visits[i].endDay } }
    private val moveSpans: IntArray by lazy { spans(moves.size) { i -> moves[i].startDay to localDay(maxOf(moves[i].start, moves[i].end - 1), moves[i].offsetMinutes) } }

    private fun spans(count: Int, days: (Int) -> Pair<Int, Int>): IntArray {
        val out = IntArray(dayCount * 2) { -1 }
        for (i in 0 until count) {
            val (from, to) = days(i)
            // A stay with a gap in the record after it can run for months; a day is at most a day.
            for (day in from.coerceAtLeast(firstDay)..to.coerceAtMost(lastDay)) {
                val at = (day - firstDay) * 2
                if (out[at] < 0) out[at] = i
                out[at + 1] = i
            }
        }
        return out
    }

    fun visitsOn(day: Int): List<Visit> = slice(visits, visitSpans, day)

    fun movesOn(day: Int): List<Move> = slice(moves, moveSpans, day)

    private fun <T> slice(all: List<T>, spans: IntArray, day: Int): List<T> {
        if (day < firstDay || day > lastDay || isEmpty) return emptyList()
        val at = (day - firstDay) * 2
        return if (spans[at] < 0) emptyList() else all.subList(spans[at], spans[at + 1] + 1)
    }

    fun place(key: String): Place = places[key] ?: Place(key, 0.0, 0.0)

    // Places that say which town they are in, by quarter-degree square, for those that don't.
    private val towns: Map<Long, List<Place>> by lazy { places.values.filter { it.city != null || it.country != null }.groupBy { square(it.latitude, it.longitude) } }

    private fun square(latitude: Double, longitude: Double, north: Int = 0, east: Int = 0): Long =
        (floor(latitude * 4).toLong() + north) * 100_000L + floor(longitude * 4).toLong() + east

    /**
     * The town a place is in: its own when Google gave one, otherwise that of the nearest place
     * within [NEIGHBOUR_METERS] that has one. A stay Google matched to nothing is, more often than
     * not, down the road from somewhere it did.
     */
    fun locality(place: Place): Locality? {
        if (place.city != null || place.country != null) return Locality(place.city, place.region, place.country)
        var best: Place? = null
        var bestMeters = NEIGHBOUR_METERS
        for (north in -1..1) {
            for (east in -1..1) {
                towns[square(place.latitude, place.longitude, north, east)]?.forEach { other ->
                    val meters = metersBetween(place.latitude, place.longitude, other.latitude, other.longitude)
                    if (meters < bestMeters) {
                        bestMeters = meters
                        best = other
                    }
                }
            }
        }
        return best?.let { Locality(it.city, it.region, it.country) }
    }

    /** Where the person was at a moment: the visit that covers it, or null while on the move or unknown. */
    fun visitAt(epochSeconds: Long): Visit? {
        var low = 0
        var high = visits.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val visit = visits[mid]
            when {
                visit.end <= epochSeconds -> low = mid + 1
                visit.start > epochSeconds -> high = mid - 1
                else -> return visit
            }
        }
        return null
    }

    companion object {
        val Empty = AtlasHistory(emptyList(), emptyList())

        const val NEIGHBOUR_METERS = 25_000.0
    }
}
