package com.meticulouscreations.homesafe.weather.ski.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherKind
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_note_clear
import homesafe.shared.generated.resources.ski_note_cold
import homesafe.shared.generated.resources.ski_note_fresh
import homesafe.shared.generated.resources.ski_note_rain
import homesafe.shared.generated.resources.ski_note_snowing
import homesafe.shared.generated.resources.ski_note_spread
import homesafe.shared.generated.resources.ski_note_wind
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/** Everything known about one resort, each part arriving when its service answers. */
@Immutable
data class SkiEntry(
    val ref: SkiResortRef,
    /** OnTheSnow's listing. Read [latest], which puts the resort's own page over it. */
    val report: SkiReport? = null,
    val page: SkiResortPage? = null,
    /** What the resort's own site reports. */
    val news: SkiResortNews? = null,
    /** The resort's own ticket prices. */
    val prices: SkiResortPrices? = null,
    val forecast: SkiForecast? = null,
    val price: SkiTicketPrice? = null,
    val drive: SkiDrive? = null,
    /** The drive there on the trip's morning and back that afternoon, where the traffic can be known ahead. */
    val driveOut: SkiDrive? = null,
    val driveBack: SkiDrive? = null,
    val chains: List<ChainControl> = emptyList(),
    /** Part of the road couldn't be checked, so [chains] may be missing some or out of date. */
    val chainsUnavailable: Boolean = false,
    /** What the resort's own site says about parking. */
    val parking: SkiParking? = null,
    val alerts: List<WeatherAlert> = emptyList(),
    /** Which of the household's passes get in here. */
    val passes: Set<SkiPass> = emptySet(),
    val loading: Boolean = false,
    val error: UiText? = null,
) {
    /** How the resort is doing: its own word for it where it has one, over the listing. */
    val latest: SkiReport?
        get() {
            val listing = report ?: page?.report ?: news?.let { SkiReport(ref.slug, ref.regionSlug, ref.name) }
            return if (news != null) listing?.merged(news) else listing
        }
}

/** What a ticket comes to for the household. */
@Immutable
data class SkiCost(
    /** The whole party's day, or null where no price is known. */
    val total: Double? = null,
    val adult: Double? = null,
    val child: Double? = null,
    /** The pass that covers it, so that the day costs nothing more. */
    val coveredBy: SkiPass? = null,
    /** The price is the one the household set, not the listing's. */
    val own: Boolean = false,
    /** There are children going and no child's price: [total] leaves them out. */
    val childrenLeftOut: Boolean = false,
    /** Where the price came from, which is how far to trust it. */
    val source: Source = Source.NONE,
    /** The day the price is for, where it's a dated one; before the season, the first day on sale. */
    val onEpochDay: Int? = null,
    /** The same party at the window, where the resort gives that too. */
    val windowTotal: Double? = null,
    /** [total] is the least it could be: the resort gives where its prices start, not the day's. */
    val startsAt: Boolean = false,
) {
    enum class Source {
        /** The resort's own price for that date. */
        RESORT_DATED,

        /** The resort's own rate table. */
        RESORT_TABLE,

        /** A listing elsewhere, which can be a season old. */
        LISTING,
        NONE,
    }
}

/** What the whole trip comes to, a part at a time. A part that's null isn't known, and [total] leaves it out. */
@Immutable
data class SkiTripCost(
    val days: Int,
    /** Every ticket for every day. */
    val tickets: Double? = null,
    /** [tickets] is a ticket for all the [days] the resort sells as one, not day tickets added up. */
    val ticketsAreOne: Boolean = false,
    /** Days with no price, which [tickets] leaves out. */
    val daysNotPriced: Int = 0,
    val coveredBy: SkiPass? = null,
    /** [tickets] is the least they could be. */
    val startsAt: Boolean = false,
    /** There are children going and no child's price: [tickets] leaves them out. */
    val childrenLeftOut: Boolean = false,
    /** [tickets] is the price on the first day a shop has on sale, as it has none for the day itself. */
    val ticketsFromAnotherDay: Boolean = false,
    val nights: Int = 0,
    /** Null when no price for a night has been set. */
    val lodging: Double? = null,
    val parking: Double? = null,
    /** Gas or charging, there and back. */
    val driving: Double? = null,
) {
    val total: Double? get() = listOfNotNull(tickets, lodging, parking, driving).takeIf { it.isNotEmpty() }?.sum()

    /** Everything that goes in it is known, so [total] is the whole of it. */
    val complete: Boolean get() = tickets != null && daysNotPriced == 0 && !startsAt && !childrenLeftOut && !ticketsFromAnotherDay && (lodging != null || nights == 0) && parking != null && driving != null
}

/** A thing worth saying about a resort's day, and how it should read. */
@Immutable
data class SkiNote(val text: UiText, val tone: Tone) {
    enum class Tone { GOOD, NEUTRAL, WATCH }
}

/** A resort as it looks for one day of a trip. */
@Immutable
data class SkiDayView(
    val entry: SkiEntry,
    val epochDay: Int,
    /** It should be running that day, as far as its dates and state say. */
    val runs: Boolean,
    val day: SkiForecastDay?,
    /** Snow over the three days up to and including the trip's, whichever of them are past or to come. */
    val freshCm: Double,
    val cost: SkiCost,
    /** The whole trip's, from this day for as many days as the household is going. */
    val trip: SkiTripCost,
    val notes: List<SkiNote>,
    /** How it ranks: higher is a better day out. Only ever compared with another resort's that same day. */
    val score: Double,
)

/**
 * Compares resorts for a day of skiing, from the forecast, the reports, the road and the price.
 * Nothing here fetches or remembers anything.
 */
object SkiPlanner {
    private const val METERS_PER_MILE = 1_609.344

    /** Snow that makes a day worth talking about: about four inches. */
    private const val FRESH_WORTH_SAYING_CM = 10.0

    /** Where the snow stops adding to a day: past this it is deep enough, and the roads are the issue. */
    private const val FRESH_CAP_CM = 50.0

    /** Gusts at which lifts start to close, the high ones first. */
    private const val GUST_WATCH_KMH = 60.0
    private const val GUST_HOLD_KMH = 80.0

    /** Rain at the base worth warning of. */
    private const val RAIN_WATCH_MM = 2.0

    /** Cold enough at the summit to change what to wear, about -4 °F. */
    private const val COLD_C = -20.0

    /** A gap between the models this much wider than their middle guess isn't a forecast yet. */
    private const val SPREAD_WATCH_CM = 15.0

    /** A closed road on the way may only mean a detour, but it's worth about a foot of snow. */
    private const val ROAD_CLOSED_POINTS = 30.0

    /** Keeps a day from turning on the far end of the forecast: the first days are the ones to trust. */
    private const val FRESH_DAYS = 3

    fun view(entry: SkiEntry, plan: SkiPlan, epochDay: Int, todayEpochDay: Int, nowEpochSeconds: Long, units: WeatherUnits): SkiDayView {
        val forecast = entry.forecast
        val day = forecast?.day(epochDay)
        val report = entry.latest
        val runs = report?.runsOn(epochDay, todayEpochDay, nowEpochSeconds) ?: false
        // Day by day: a forecast kept from a while back may not reach them all, and the report's outlook can.
        val lastDay = epochDay + plan.tripDays.coerceAtLeast(1) - 1
        val fresh = ((epochDay - (FRESH_DAYS - 1))..lastDay).sumOf { each ->
            forecast?.day(each)?.snowCm ?: report?.outlook?.firstOrNull { it.epochDay == each }?.cm ?: 0.0
        }
        val cost = cost(plan, entry, epochDay)
        return SkiDayView(
            entry = entry,
            epochDay = epochDay,
            runs = runs,
            day = day,
            freshCm = fresh,
            cost = cost,
            trip = trip(plan, entry, epochDay),
            notes = notes(day, fresh, units),
            score = score(entry, day, fresh, cost, report),
        )
    }

    /** The resorts that run that day, the best first, then those that don't, the soonest to open first. */
    fun ranked(views: List<SkiDayView>): List<SkiDayView> =
        views.sortedWith(compareByDescending<SkiDayView> { it.runs }.thenByDescending { if (it.runs) it.score else -(openingOf(it) ?: Int.MAX_VALUE).toDouble() })

    private fun openingOf(view: SkiDayView): Int? = view.entry.latest?.openingEpochDay

    /**
     * Points in centimetres of snow, which is what a day is mostly about. A long drive, wet base,
     * or lift-closing wind takes away about what they'd take away from the day.
     */
    private fun score(entry: SkiEntry, day: SkiForecastDay?, freshCm: Double, cost: SkiCost, report: SkiReport?): Double {
        var score = min(freshCm, FRESH_CAP_CM)
        if (day != null) {
            score -= min((day.baseRainMm ?: 0.0) * 1.5, 20.0)
            if (day.gustMaxKmh >= GUST_HOLD_KMH) {
                score -= 15
            } else if (day.gustMaxKmh >= GUST_WATCH_KMH) {
                score -= 6
            }
        }
        // How much of the mountain is running, while that's something reported.
        val lifts = report?.liftsOpen
        val total = report?.liftsTotal
        if (lifts != null && total != null && total > 0 && lifts > 0) score += 10.0 * lifts / total
        entry.drive?.let { score -= 2.0 * it.seconds / 3600 }
        cost.adult?.let { score -= it / 60 }
        score -= entry.chains.count { it.level != ChainLevel.NOTICE }.coerceAtMost(3)
        if (entry.chains.any { it.level == ChainLevel.CLOSED }) score -= ROAD_CLOSED_POINTS
        return score
    }

    /** What stands out about the day, the most useful first; three at most, for a card to hold. */
    private fun notes(day: SkiForecastDay?, freshCm: Double, units: WeatherUnits): List<SkiNote> {
        if (day == null) return emptyList()
        return buildList {
            if (freshCm >= FRESH_WORTH_SAYING_CM) add(SkiNote(UiText.of(Res.string.ski_note_fresh, WeatherFormat.snow(freshCm, units)), SkiNote.Tone.GOOD))
            if ((day.baseRainMm ?: 0.0) >= RAIN_WATCH_MM) add(SkiNote(UiText.of(Res.string.ski_note_rain, WeatherFormat.precipitation(day.baseRainMm ?: 0.0, units)), SkiNote.Tone.WATCH))
            if (day.gustMaxKmh >= GUST_WATCH_KMH) add(SkiNote(UiText.of(Res.string.ski_note_wind, WeatherFormat.speed(day.gustMaxKmh, units)), SkiNote.Tone.WATCH))
            if (day.snowHighCm - day.snowLowCm >= SPREAD_WATCH_CM) {
                add(SkiNote(UiText.of(Res.string.ski_note_spread, WeatherFormat.snow(day.snowLowCm, units), WeatherFormat.snow(day.snowHighCm, units)), SkiNote.Tone.WATCH))
            }
            if (day.summitLowC <= COLD_C) add(SkiNote(UiText.of(Res.string.ski_note_cold, WeatherFormat.degrees(day.summitLowC, units)), SkiNote.Tone.NEUTRAL))
            if (day.snowCm >= 1.0 && freshCm < FRESH_WORTH_SAYING_CM) add(SkiNote(UiText.of(Res.string.ski_note_snowing, WeatherFormat.snow(day.snowCm, units)), SkiNote.Tone.NEUTRAL))
            if (isEmpty() && (day.kind == WeatherKind.CLEAR || day.kind == WeatherKind.MOSTLY_CLEAR)) add(SkiNote(UiText.of(Res.string.ski_note_clear), SkiNote.Tone.GOOD))
        }.take(3)
    }

    /**
     * The day's cost for the party. A pass that covers the resort makes it nothing; a price the
     * household set stands for everyone going; then the resort's own price for that date, then its
     * own rate table, and only then a listing elsewhere.
     */
    fun cost(plan: SkiPlan, entry: SkiEntry, epochDay: Int): SkiCost {
        val covered = plan.passes.firstOrNull { it in entry.passes }
        if (covered != null) return SkiCost(total = 0.0, coveredBy = covered)
        plan.ownPrices[entry.ref.slug]?.let { own ->
            val each = own.toDouble()
            return SkiCost(total = each * (plan.adults + plan.children), adult = each, child = each.takeIf { plan.children > 0 }, own = true)
        }
        entry.prices?.priceFor(epochDay)?.let { (day, price) -> party(plan, price, SkiCost.Source.RESORT_DATED, day)?.let { return it } }
        entry.prices?.tierOn(epochDay)?.let { tier ->
            party(plan, tier.price, SkiCost.Source.RESORT_TABLE, null)?.let { return it.copy(startsAt = tier.onlineIsLowest && tier.price.adultOnline != null) }
        }
        val listed = entry.price ?: return SkiCost()
        return party(plan, SkiDayPrice(adultWindow = listed.adultDay, childWindow = listed.childDay), SkiCost.Source.LISTING, null)?.copy(windowTotal = null) ?: SkiCost()
    }

    /**
     * The whole trip, from [epochDay]. Tickets come as one ticket for all the days where the resort
     * sells that, otherwise each day's own. Parking is what the household set, or what the resort
     * publishes. Driving is one trip there and back.
     */
    fun trip(plan: SkiPlan, entry: SkiEntry, epochDay: Int): SkiTripCost {
        val days = plan.tripDays.coerceIn(1, SkiPlan.MAX_TRIP_DAYS)
        val nights = days - 1
        val first = cost(plan, entry, epochDay)
        val pack = if (days > 1 && first.coveredBy == null && !first.own) entry.prices?.packs?.get(epochDay)?.get(days)?.let { party(plan, it, SkiCost.Source.RESORT_DATED, epochDay) } else null
        // Before a season, a day's cost is the first day on sale's, which it says. Several of those added up would be one day's price over and over.
        val daily = if (pack == null) {
            (0 until days).map { after -> cost(plan, entry, epochDay + after).takeIf { days == 1 || it.onEpochDay == null || it.onEpochDay == epochDay + after } ?: SkiCost() }
        } else {
            emptyList()
        }
        val parkingADay = plan.ownParking[entry.ref.slug] ?: entry.parking?.dayPrice
        return SkiTripCost(
            days = days,
            tickets = pack?.total ?: daily.mapNotNull { it.total }.takeIf { it.isNotEmpty() }?.sum(),
            ticketsAreOne = pack != null,
            daysNotPriced = daily.count { it.total == null },
            coveredBy = first.coveredBy,
            startsAt = daily.any { it.startsAt },
            childrenLeftOut = pack?.childrenLeftOut ?: daily.any { it.childrenLeftOut },
            ticketsFromAnotherDay = days == 1 && daily.any { it.onEpochDay != null && it.onEpochDay != epochDay },
            nights = nights,
            lodging = plan.lodgingPerNight.takeIf { it > 0 }?.let { (it * nights).toDouble() },
            parking = parkingADay?.let { (it * days).toDouble() },
            // A rough figure, so not to the cent.
            driving = entry.drive?.let { round(2 * it.meters / METERS_PER_MILE * plan.drivingCentsPerMile / 100) },
        )
    }

    private fun party(plan: SkiPlan, price: SkiDayPrice, source: SkiCost.Source, onEpochDay: Int?): SkiCost? {
        val adult = price.adult ?: return null
        val child = price.child
        fun total(a: Double?, c: Double?) = a?.let { it * plan.adults + (c ?: 0.0) * plan.children }
        return SkiCost(
            total = total(adult, child),
            adult = adult,
            child = child,
            childrenLeftOut = plan.children > 0 && child == null,
            source = source,
            onEpochDay = onEpochDay,
            // Only if it's the whole party's: a window total that left a child out would read as less than it is.
            windowTotal = total(price.adultWindow, price.childWindow).takeIf { price.adultOnline != null && (plan.children == 0 || price.childWindow != null) },
        )
    }

    /** A price as it reads on a ticket: whole dollars when it's whole, and always its currency's sign. */
    fun money(amount: Double, currency: String = "US$"): String {
        val sign = currency.removePrefix("US").ifBlank { "$" }
        val whole = amount.roundToInt()
        if (kotlin.math.abs(amount - whole) >= 0.005) return "$sign${WeatherFormat.decimal(amount, 2)}"
        val digits = whole.toString()
        return if (digits.length > 3) "$sign${digits.dropLast(3)},${digits.takeLast(3)}" else "$sign$digits"
    }

    /**
     * The days ahead to plan for, from today, each with how much of the snow to come the followed
     * resorts expect at the most: enough to see at a glance when the storm lands.
     */
    fun dayStrip(entries: List<SkiEntry>, todayEpochDay: Int, days: Int): List<SkiStripDay> = (0 until days).map { i ->
        val day = todayEpochDay + i
        SkiStripDay(day, entries.maxOfOrNull { it.forecast?.day(day)?.snowCm ?: it.latest?.outlook?.firstOrNull { o -> o.epochDay == day }?.cm ?: 0.0 } ?: 0.0)
    }

    /** The Saturday coming, or today if it is one: when most people mean to go. 1 January 1970 was a Thursday. */
    fun nextSaturday(todayEpochDay: Int): Int {
        val weekday = (todayEpochDay + 3).mod(7) // 0 for Monday.
        return todayEpochDay + (5 - weekday).mod(7)
    }
}

/** One day on the strip of days to choose from. */
@Immutable
data class SkiStripDay(val epochDay: Int, val mostSnowCm: Double)
