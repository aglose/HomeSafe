package com.meticulouscreations.homesafe.packages.domain

import androidx.compose.runtime.Immutable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Instant

/** A day number from 1970-01-01 of a calendar date, and back: the app's dates are all kept this way. */
object ParcelDays {
    fun of(year: Int, month: Int, day: Int): Int {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val shifted = (month + 9) % 12
        val dayOfYear = (153 * shifted + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }

    /** "2026-10-31" as a day number; null for what isn't one. */
    fun parse(text: String?): Int? {
        val parts = text?.split('-')?.mapNotNull { it.toIntOrNull() } ?: return null
        return if (parts.size == 3) of(parts[0], parts[1], parts[2]) else null
    }

    /** A day number as year, month and day. */
    fun date(day: Int): Triple<Int, Int, Int> {
        val shifted = day + 719468
        val era = (if (shifted >= 0) shifted else shifted - 146096) / 146097
        val dayOfEra = shifted - era * 146097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val m = (5 * dayOfYear + 2) / 153
        val d = dayOfYear - (153 * m + 2) / 5 + 1
        val month = if (m < 10) m + 3 else m - 9
        return Triple(yearOfEra + era * 400 + if (month <= 2) 1 else 0, month, d)
    }

    fun format(day: Int): String {
        val (year, month, d) = date(day)
        return "$year-${month.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** 0 for Monday. */
    fun weekday(day: Int): Int = (day + 3).mod(7)
}

/**
 * The household's clock. What a moment reads as depends on that moment, not on today: a delivery
 * from before the clocks changed happened at the hour it happened.
 */
@Immutable
@ConsistentCopyVisibility
data class ParcelZone private constructor(private val timeZone: TimeZone?, private val fixedSeconds: Int) {
    /** Seconds east of UTC at a moment. */
    fun offsetAt(epochSeconds: Long): Int = timeZone?.offsetAt(Instant.fromEpochSeconds(epochSeconds))?.totalSeconds ?: fixedSeconds

    companion object {
        fun of(timeZone: TimeZone) = ParcelZone(timeZone, 0)

        fun fixed(seconds: Int) = ParcelZone(null, seconds)
    }
}

/** The Today page, sorted into what each part of it wants to say. */
@Immutable
data class PackagesBoard(
    /** Something someone should look at: a problem, a promised day gone by, a signature, a parcel that may be gone. */
    val attention: List<Parcel> = emptyList(),
    /** Delivered and still out there. */
    val onPorch: List<Parcel> = emptyList(),
    val today: List<Parcel> = emptyList(),
    val tomorrow: List<Parcel> = emptyList(),
    val later: List<Parcel> = emptyList(),
    /** Came in the last few days. */
    val arrived: List<Parcel> = emptyList(),
) {
    val isEmpty: Boolean get() = attention.isEmpty() && onPorch.isEmpty() && today.isEmpty() && tomorrow.isEmpty() && later.isEmpty() && arrived.isEmpty()

    /** Everything not here yet, including what is under [attention] for it. */
    val onTheWay: Int get() = today.size + tomorrow.size + later.size + attention.count { !it.delivered }

    companion object {
        private const val ARRIVED_DAYS = 5

        fun of(parcels: List<Parcel>, today: Int, nowEpochSeconds: Long): PackagesBoard {
            val shown = parcels.filter { !it.archived }
            val attention = shown.filter { it.needsAttention }
            val rest = shown - attention.toSet()
            val onPorch = rest.filter { it.delivered && it.porch == PorchState.ON_PORCH }
            val coming = rest.filter { !it.delivered }

            // A promised day gone by with nothing more said isn't the next thing to happen: it goes after what has a day to come.
            fun passed(parcel: Parcel): Boolean = (parcel.expectedToDay ?: parcel.expectedFromDay ?: today) < today
            fun day(parcel: Parcel): Int = when {
                parcel.stage == ParcelStage.OUT_FOR_DELIVERY -> today
                passed(parcel) -> Int.MAX_VALUE
                else -> parcel.expectedFromDay ?: (Int.MAX_VALUE - 1)
            }
            val byDay = compareBy<Parcel>({ !it.pinned }, { day(it) }, { it.windowEnd ?: "99" }, { -it.updatedAt })
            val isToday = { parcel: Parcel -> parcel.stage == ParcelStage.OUT_FOR_DELIVERY || (parcel.expectedFromDay != null && parcel.expectedFromDay <= today && (parcel.expectedToDay ?: parcel.expectedFromDay) >= today) }
            val todays = coming.filter(isToday)
            val tomorrows = (coming - todays.toSet()).filter { it.expectedFromDay != null && it.expectedFromDay <= today + 1 && (it.expectedToDay ?: it.expectedFromDay) >= today + 1 }
            val later = coming - todays.toSet() - tomorrows.toSet()
            val cutoff = nowEpochSeconds - ARRIVED_DAYS * 86_400L
            val arrived = rest.filter { it.delivered && it.porch != PorchState.ON_PORCH && (it.deliveredAt ?: 0) >= cutoff }
            return PackagesBoard(
                attention = attention.sortedByDescending { it.updatedAt },
                onPorch = onPorch.sortedByDescending { it.deliveredAt ?: 0 },
                today = todays.sortedWith(byDay),
                tomorrow = tomorrows.sortedWith(byDay),
                later = later.sortedWith(byDay),
                arrived = arrived.sortedByDescending { it.deliveredAt ?: 0 },
            )
        }
    }
}

/** How a filter narrows the All page. */
@Immutable
data class ParcelFilter(val query: String = "", val owner: String? = null, val carrier: Carrier? = null, val onlyActive: Boolean = false) {
    fun matches(parcel: Parcel): Boolean {
        if (owner != null && parcel.owner != owner) return false
        if (carrier != null && parcel.carrier != carrier) return false
        if (onlyActive && (parcel.delivered || parcel.archived)) return false
        val words = query.trim().lowercase()
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(parcel.nickname, parcel.title, parcel.shop, parcel.tracking, parcel.orderId, parcel.notes, parcel.carrier.label.ifEmpty { null }, parcel.carrierName).joinToString(" ").lowercase()
        return words.split(' ').all { it in haystack }
    }
}

/** One carrier's record at this house. */
@Immutable
data class CarrierRecord(
    val carrier: Carrier,
    val delivered: Int,
    /** Of those with a promised day, how many came by it; null when none had one. */
    val onTimePercent: Int?,
    /** Whole days from shipping to the door, the middle one; null when none can say. */
    val medianDays: Int?,
    /** The hour they tend to come, on the household's clock. */
    val usualHour: Int?,
)

/** When a parcel will probably turn up, by how its carrier has done here. */
@Immutable
data class ArrivalGuess(val fromHour: Int, val toHour: Int, val basedOn: Int)

/** What the household's deliveries add up to. */
@Immutable
data class PackagesInsights(
    val deliveredAll: Int = 0,
    val deliveredThisYear: Int = 0,
    val deliveredThisMonth: Int = 0,
    val deliveredLastMonth: Int = 0,
    val spentThisMonth: Double = 0.0,
    val spentThisYear: Double = 0.0,
    val carriers: List<CarrierRecord> = emptyList(),
    /** Seven rows from Monday, twenty-four columns: deliveries per hour of the week. */
    val heat: List<List<Int>> = emptyList(),
    val heatMax: Int = 0,
    val busiestWeekday: Int? = null,
    val busiestHour: Int? = null,
    val perPerson: List<Pair<String, Int>> = emptyList(),
    val topShops: List<Pair<String, Int>> = emptyList(),
    /** One per month, oldest first, for the last twelve: how many came. */
    val months: List<MonthCount> = emptyList(),
    val cameraConfirmed: Int = 0,
    val cameraSeen: Int = 0,
    val cameraNotSeen: Int = 0,
    val returned: Int = 0,
)

@Immutable
data class MonthCount(val year: Int, val month: Int, val count: Int)

object PackagesAnalysis {
    /** Below this many past deliveries a guess at the time of day would be noise. */
    private const val GUESS_MIN = 3

    private fun localHour(epochSeconds: Long, zone: ParcelZone): Int = (((epochSeconds + zone.offsetAt(epochSeconds)).mod(86_400L)) / 3600).toInt()

    private fun localDay(epochSeconds: Long, zone: ParcelZone): Int = ((epochSeconds + zone.offsetAt(epochSeconds)).floorDiv(86_400L)).toInt()

    private fun median(values: List<Int>): Int? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** The middle hour they come, and how far most fall either side of it. Wraps nothing: nobody delivers at midnight. */
    private fun usualHours(hours: List<Int>): Pair<Int, Int>? {
        if (hours.isEmpty()) return null
        val sorted = hours.sorted()
        val mid = sorted[sorted.size / 2]
        val lower = sorted[sorted.size / 4]
        val upper = sorted[(sorted.size * 3) / 4]
        return mid to max(mid - lower, upper - mid)
    }

    /** When a parcel on its way will probably come, or null when its carrier hasn't come here often enough to say. */
    fun arrivalGuess(parcel: Parcel, parcels: List<Parcel>, zone: ParcelZone): ArrivalGuess? {
        if (parcel.delivered) return null
        val past = parcels.filter { it.delivered && it.deliveredAt != null && it.carrier == parcel.carrier && it.verdict != CameraVerdict.CAMERA_ONLY }
            .map { localHour(it.visitAt ?: it.deliveredAt!!, zone) }
        if (past.size < GUESS_MIN) return null
        val (mid, spread) = usualHours(past) ?: return null
        return ArrivalGuess(max(0, mid - max(spread, 1)), min(23, mid + max(spread, 1)), past.size)
    }

    fun insights(parcels: List<Parcel>, today: Int, zone: ParcelZone): PackagesInsights {
        val (year, month, _) = ParcelDays.date(today)
        val delivered = parcels.filter { it.delivered && it.deliveredAt != null }
        fun dateOf(parcel: Parcel) = ParcelDays.date(localDay(parcel.deliveredAt!!, zone))
        fun inMonth(y: Int, m: Int) = delivered.filter { dateOf(it).let { d -> d.first == y && d.second == m } }
        val thisMonth = inMonth(year, month)
        val last = if (month == 1) inMonth(year - 1, 12) else inMonth(year, month - 1)
        val thisYear = delivered.filter { dateOf(it).first == year }
        val heat = List(7) { MutableList(24) { 0 } }
        delivered.forEach { parcel ->
            val at = parcel.visitAt ?: parcel.deliveredAt!!
            heat[ParcelDays.weekday(localDay(at, zone))][localHour(at, zone)]++
        }
        // One the camera made up has no carrier to rate.
        val carriers = delivered.filter { !it.fromCamera }.groupBy { it.carrier }.map { (carrier, group) ->
            val promised = group.filter { it.expectedToDay != null }
            val onTime = promised.count { localDay(it.deliveredAt!!, zone) <= it.expectedToDay!! }
            val hours = group.map { localHour(it.visitAt ?: it.deliveredAt!!, zone) }
            val usual = usualHours(hours)
            CarrierRecord(
                carrier = carrier,
                delivered = group.size,
                onTimePercent = if (promised.isEmpty()) null else (onTime * 100f / promised.size).roundToInt(),
                medianDays = median(group.filter { it.createdAt > 0 && it.createdAt < it.deliveredAt!! }.map { ((it.deliveredAt!! - it.createdAt) / 86_400L).toInt().coerceAtLeast(0) }),
                usualHour = usual?.first,
            )
        }.sortedByDescending { it.delivered }
        val months = List(12) { back ->
            val index = year * 12 + (month - 1) - (11 - back)
            val y = index.floorDiv(12)
            val m = index.mod(12) + 1
            MonthCount(y, m, inMonth(y, m).size)
        }
        val weekdayTotals = heat.map { it.sum() }
        val busiestWeekday = weekdayTotals.indices.maxByOrNull { weekdayTotals[it] }
        return PackagesInsights(
            deliveredAll = delivered.size,
            deliveredThisYear = thisYear.size,
            deliveredThisMonth = thisMonth.size,
            deliveredLastMonth = last.size,
            spentThisMonth = thisMonth.sumOf { it.total ?: 0.0 },
            spentThisYear = thisYear.sumOf { it.total ?: 0.0 },
            carriers = carriers,
            heat = heat,
            heatMax = heat.maxOf { it.max() },
            busiestWeekday = busiestWeekday.takeIf { delivered.isNotEmpty() },
            // Said together with the day ("most often on Friday, around 5 PM"), so it is that day's hour.
            busiestHour = busiestWeekday?.let { day -> heat[day].indices.maxByOrNull { heat[day][it] } }.takeIf { delivered.isNotEmpty() },
            perPerson = delivered.filter { it.owner.isNotBlank() }.groupingBy { it.owner }.eachCount().toList().sortedByDescending { it.second },
            topShops = delivered.mapNotNull { it.shop }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(6),
            months = months,
            cameraConfirmed = delivered.count { it.verdict == CameraVerdict.CONFIRMED },
            cameraSeen = delivered.count { it.verdict == CameraVerdict.SEEN },
            cameraNotSeen = delivered.count { it.verdict == CameraVerdict.NOT_SEEN },
            returned = parcels.count { it.returnState != ReturnState.NONE },
        )
    }

    /** Parcels that can still go back, soonest deadline first, and the ones under way or done. */
    fun returnsOf(parcels: List<Parcel>, today: Int): Pair<List<Parcel>, List<Parcel>> {
        val open = parcels.filter { it.delivered && !it.masked && it.returnState == ReturnState.NONE && it.returnByDay != null && it.returnByDay >= today }
            .sortedBy { it.returnByDay }
        val moving = parcels.filter { !it.masked && it.returnState != ReturnState.NONE }.sortedByDescending { it.updatedAt }
        return open to moving
    }
}
