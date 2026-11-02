package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ski.domain.SkiCost
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNote
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPrices
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiSnowDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_note_clear
import homesafe.shared.generated.resources.ski_note_rain
import homesafe.shared.generated.resources.ski_note_spread
import homesafe.shared.generated.resources.ski_note_wind
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkiPlannerTest {
    private val today = 20_459 // A Tuesday.
    private val saturday = 20_463
    private val now = today * SkiForecast.DAY_SECONDS + 43_200
    private val units = WeatherUnits()

    private fun ref(slug: String) = SkiResortRef(slug, "california", slug)

    private fun day(epochDay: Int, snow: Double = 0.0, low: Double = snow, high: Double = snow, code: Int = 3, gust: Double = 20.0, rain: Double = 0.0, summitLow: Double = -5.0) = SkiForecastDay(
        epochSeconds = epochDay * SkiForecast.DAY_SECONDS + 28_800,
        weatherCode = code,
        snowCm = snow,
        snowLowCm = low,
        snowHighCm = high,
        summitHighC = 0.0,
        summitLowC = summitLow,
        baseRainMm = rain,
        gustMaxKmh = gust,
    )

    private fun forecast(vararg days: SkiForecastDay) = SkiForecast(0, -28_800, days = days.toList())

    private fun report(slug: String, state: SkiOpenState = SkiOpenState.OPEN, opens: Int? = null, closes: Int? = null, outlook: List<SkiSnowDay> = emptyList()) =
        SkiReport(slug, "california", slug, state = state, openingEpochDay = opens, closingEpochDay = closes, outlook = outlook, updatedAtEpochSeconds = now - 3_600)

    private fun entry(slug: String, days: List<SkiForecastDay> = emptyList(), driveHours: Double? = null, adult: Double? = null, state: SkiOpenState = SkiOpenState.OPEN, opens: Int? = null) = SkiEntry(
        ref(slug),
        report = report(slug, state, opens),
        forecast = forecast(*days.toTypedArray()),
        drive = driveHours?.let { SkiDrive((it * 3_600).toLong(), 0.0, SkiDriveSource.FREE_FLOW) },
        price = adult?.let { SkiTicketPrice(adultDay = it) },
    )

    private fun view(entry: SkiEntry, plan: SkiPlan = SkiPlan()) = SkiPlanner.view(entry, plan, saturday, today, now, units)

    // ---- Is it running ----------------------------------------------------------------------------

    @Test
    fun aClosedResortRunsFromTheDateItGave() {
        val report = report("a", SkiOpenState.CLOSED, opens = saturday)
        assertTrue(report.runsOn(saturday, today, now))
        assertFalse(report.runsOn(saturday - 1, today, now))
    }

    @Test
    fun aDateAlreadyPastSaysNothingAboutAClosedResort() {
        // It said it would open back in spring and it's shut: last season's date.
        assertFalse(report("a", SkiOpenState.CLOSED, opens = today - 200).runsOn(saturday, today, now))
    }

    @Test
    fun anOpenResortRunsUntilItCloses() {
        val report = report("a", SkiOpenState.OPEN, closes = today + 1)
        assertTrue(report.runsOn(today + 1, today, now))
        assertFalse(report.runsOn(today + 2, today, now))
    }

    @Test
    fun aClosingDateAlreadyPastDoesntCloseAnOpenResort() {
        assertTrue(report("a", SkiOpenState.OPEN, closes = today - 30).runsOn(saturday, today, now))
    }

    @Test
    fun aResortNotOperatingNeverRuns() {
        assertFalse(report("a", SkiOpenState.NOT_OPERATING, opens = saturday).runsOn(saturday, today, now))
    }

    @Test
    fun anOldReportIsntCurrent() {
        val report = report("a").copy(updatedAtEpochSeconds = 1_000)
        assertTrue(report.isCurrent(1_000 + 6 * 86_400))
        assertFalse(report.isCurrent(1_000 + 8 * 86_400))
        assertFalse(report("b").copy(updatedAtEpochSeconds = null).isCurrent(1_000), "one that has never said anything")
    }

    @Test
    fun anOpenFromAnOldReportDoesntMeanItsRunning() {
        val old = report("a", SkiOpenState.OPEN).copy(updatedAtEpochSeconds = now - 30 * 86_400)
        assertFalse(old.runsOn(saturday, today, now))
        assertFalse(old.runningNow(now))
        assertTrue(old.copy(openingEpochDay = saturday).runsOn(saturday, today, now), "a date it gave still ahead still counts")
    }

    // ---- Comparing --------------------------------------------------------------------------------

    @Test
    fun freshSnowIsTheThreeDaysUpToTheDay() {
        val view = view(entry("a", listOf(day(saturday - 3, 50.0), day(saturday - 2, 3.0), day(saturday - 1, 5.0), day(saturday, 2.0), day(saturday + 1, 40.0))))
        assertEquals(10.0, view.freshCm)
    }

    @Test
    fun withNoForecastTheFreshSnowComesFromTheReportsOutlook() {
        val entry = SkiEntry(ref("a"), report = report("a", outlook = listOf(SkiSnowDay(saturday, 7.0), SkiSnowDay(saturday + 1, 9.0))))
        assertEquals(7.0, view(entry).freshCm)
    }

    @Test
    fun aForecastThatStopsShortLeavesTheRestToTheOutlook() {
        val entry = SkiEntry(ref("a"), report = report("a", outlook = listOf(SkiSnowDay(saturday, 7.0))), forecast = forecast(day(saturday - 1, 3.0)))
        assertEquals(10.0, view(entry).freshCm)
    }

    @Test
    fun theSnowiestWinsWhenAllElseIsEven() {
        val ranked = SkiPlanner.ranked(listOf(view(entry("dry", listOf(day(saturday)))), view(entry("deep", listOf(day(saturday, 30.0))))))
        assertEquals(listOf("deep", "dry"), ranked.map { it.entry.ref.slug })
    }

    @Test
    fun aMuchLongerDriveCanOutweighALittleSnow() {
        val near = view(entry("near", listOf(day(saturday, 2.0)), driveHours = 2.0))
        val far = view(entry("far", listOf(day(saturday, 4.0)), driveHours = 8.0))
        assertEquals("near", SkiPlanner.ranked(listOf(far, near)).first().entry.ref.slug)
    }

    @Test
    fun windAndRainTakeAwayFromADay() {
        val calm = view(entry("calm", listOf(day(saturday, 10.0))))
        assertTrue(view(entry("windy", listOf(day(saturday, 10.0, gust = 95.0)))).score < calm.score)
        assertTrue(view(entry("wet", listOf(day(saturday, 10.0, rain = 8.0)))).score < calm.score)
    }

    @Test
    fun whatsOpenComesBeforeWhatsSnowier() {
        val shut = view(entry("shut", listOf(day(saturday, 60.0)), state = SkiOpenState.CLOSED, opens = saturday + 30))
        val open = view(entry("open", listOf(day(saturday))))
        assertEquals("open", SkiPlanner.ranked(listOf(shut, open)).first().entry.ref.slug)
    }

    @Test
    fun amongTheClosedTheFirstToOpenComesFirst() {
        val later = view(entry("later", state = SkiOpenState.CLOSED, opens = saturday + 30))
        val sooner = view(entry("sooner", state = SkiOpenState.CLOSED, opens = saturday + 5))
        assertEquals("sooner", SkiPlanner.ranked(listOf(later, sooner)).first().entry.ref.slug)
    }

    // ---- Notes --------------------------------------------------------------------------------------

    private fun tones(view: com.meticulouscreations.homesafe.weather.ski.domain.SkiDayView) = view.notes.map { (it.text as UiText.Resource).res to it.tone }

    @Test
    fun warnsOfRainAndLiftClosingWind() {
        val notes = tones(view(entry("a", listOf(day(saturday, gust = 70.0, rain = 5.0)))))
        assertTrue(Res.string.ski_note_rain to SkiNote.Tone.WATCH in notes)
        assertTrue(Res.string.ski_note_wind to SkiNote.Tone.WATCH in notes)
    }

    @Test
    fun saysWhenTheModelsDisagree() {
        assertTrue(tones(view(entry("a", listOf(day(saturday, 10.0, low = 2.0, high = 30.0))))).any { it.first == Res.string.ski_note_spread })
        assertFalse(tones(view(entry("a", listOf(day(saturday, 10.0, low = 9.0, high = 12.0))))).any { it.first == Res.string.ski_note_spread })
    }

    @Test
    fun aQuietClearDayGetsAKindWord() {
        assertEquals(listOf(Res.string.ski_note_clear to SkiNote.Tone.GOOD), tones(view(entry("a", listOf(day(saturday, code = 0))))))
    }

    @Test
    fun noForecastForTheDayMeansNothingToSay() {
        assertTrue(view(entry("a")).notes.isEmpty())
    }

    // ---- What it costs ------------------------------------------------------------------------------

    private val plan = SkiPlan(adults = 2, children = 1)

    @Test
    fun addsUpTheParty() {
        val cost = SkiPlanner.cost(plan, SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0, childDay = 100.0)), saturday)
        assertEquals(500.0, cost.total)
        assertFalse(cost.childrenLeftOut)
    }

    @Test
    fun aMissingChildPriceIsSaidRatherThanCountedAsFree() {
        val cost = SkiPlanner.cost(plan, SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0)), saturday)
        assertEquals(400.0, cost.total)
        assertTrue(cost.childrenLeftOut)
    }

    @Test
    fun noChildrenMeansNothingLeftOut() {
        assertFalse(SkiPlanner.cost(SkiPlan(adults = 2), SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0)), saturday).childrenLeftOut)
    }

    @Test
    fun aPassCoversIt() {
        val cost = SkiPlanner.cost(plan.copy(passes = setOf(SkiPass.IKON)), SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0), passes = setOf(SkiPass.IKON)), saturday)
        assertEquals(0.0, cost.total)
        assertEquals(SkiPass.IKON, cost.coveredBy)
    }

    @Test
    fun aPassForSomewhereElseDoesnt() {
        val cost = SkiPlanner.cost(plan.copy(passes = setOf(SkiPass.EPIC)), SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0, childDay = 100.0), passes = setOf(SkiPass.IKON)), saturday)
        assertNull(cost.coveredBy)
        assertEquals(500.0, cost.total)
    }

    @Test
    fun thePriceTheHouseholdSetBeatsTheListing() {
        val cost = SkiPlanner.cost(plan.copy(ownPrices = mapOf("a" to 120)), SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0)), saturday)
        assertEquals(360.0, cost.total)
        assertTrue(cost.own)
    }

    private val dated = SkiDayPrice(adultOnline = 205.0, adultWindow = 284.0, childOnline = 144.0, childWindow = 199.0)

    @Test
    fun theResortsPriceForTheDateBeatsEveryListing() {
        val entry = SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 237.0, childDay = 166.0), prices = SkiResortPrices("https://a.example", days = mapOf(saturday to dated)))
        val cost = SkiPlanner.cost(plan, entry, saturday)
        assertEquals(205.0 * 2 + 144.0, cost.total)
        assertEquals(284.0 * 2 + 199.0, cost.windowTotal)
        assertEquals(SkiCost.Source.RESORT_DATED, cost.source)
        assertEquals(saturday, cost.onEpochDay)
    }

    @Test
    fun beforeTheSeasonItsTheFirstDayOnSale() {
        val entry = SkiEntry(ref("a"), prices = SkiResortPrices("https://a.example", days = mapOf(saturday + 20 to dated, saturday + 40 to SkiDayPrice(adultOnline = 1.0))))
        val cost = SkiPlanner.cost(plan, entry, saturday)
        assertEquals(saturday + 20, cost.onEpochDay)
        assertEquals(205.0, cost.adult)
    }

    @Test
    fun aDayMissingInsideTheCalendarHasNoPriceOfItsOwn() {
        val tier = SkiPriceTier(SkiPriceTier.Kind.ANY_DAY, SkiDayPrice(adultWindow = 100.0))
        val entry = SkiEntry(ref("a"), prices = SkiResortPrices("https://a.example", days = mapOf(saturday - 1 to dated, saturday + 1 to dated), tiers = listOf(tier)))
        val cost = SkiPlanner.cost(plan, entry, saturday)
        assertEquals(SkiCost.Source.RESORT_TABLE, cost.source, "Sunday's price isn't Saturday's")
        assertEquals(100.0, cost.adult)
        assertNull(entry.prices?.priceFor(saturday))
    }

    @Test
    fun aRateThatsOnlyWherePricesStartIsSaidToBe() {
        val starting = SkiPriceTier(SkiPriceTier.Kind.ANY_DAY, SkiDayPrice(adultOnline = 89.0, adultWindow = 189.0), onlineIsLowest = true)
        val entry = SkiEntry(ref("a"), prices = SkiResortPrices("https://a.example", tiers = listOf(starting)))
        assertTrue(SkiPlanner.cost(plan, entry, saturday).startsAt)
        val windowOnly = entry.copy(prices = SkiResortPrices("https://a.example", tiers = listOf(starting.copy(price = SkiDayPrice(adultWindow = 189.0)))))
        assertFalse(SkiPlanner.cost(plan, windowOnly, saturday).startsAt, "a window price is the price")
    }

    @Test
    fun aWindowTotalIsTheWholePartysOrNothing() {
        val noChildWindow = SkiDayPrice(adultOnline = 100.0, adultWindow = 150.0, childOnline = 50.0)
        val entry = SkiEntry(ref("a"), prices = SkiResortPrices("https://a.example", days = mapOf(saturday to noChildWindow)))
        assertNull(SkiPlanner.cost(plan, entry, saturday).windowTotal)
        assertEquals(300.0, SkiPlanner.cost(SkiPlan(adults = 2), entry, saturday).windowTotal, "no children to leave out")
    }

    @Test
    fun aDatePastTheLastPriceFallsToTheRates() {
        val tier = SkiPriceTier(SkiPriceTier.Kind.ANY_DAY, SkiDayPrice(adultWindow = 100.0))
        val entry = SkiEntry(ref("a"), prices = SkiResortPrices("https://a.example", days = mapOf(saturday - 5 to dated), tiers = listOf(tier)))
        val cost = SkiPlanner.cost(plan, entry, saturday)
        assertEquals(SkiCost.Source.RESORT_TABLE, cost.source)
        assertEquals(100.0, cost.adult)
        assertNull(cost.windowTotal, "a price only ever sold at the window has no online figure to set it beside")
    }

    @Test
    fun theRatesGoByTheKindOfDay() {
        val prices = SkiResortPrices(
            "https://a.example",
            tiers = listOf(
                SkiPriceTier(SkiPriceTier.Kind.SUNDAY_TO_FRIDAY, SkiDayPrice(adultWindow = 189.0)),
                SkiPriceTier(SkiPriceTier.Kind.SATURDAY, SkiDayPrice(adultWindow = 249.0)),
            ),
        )
        assertEquals(249.0, prices.tierOn(saturday)?.price?.adult)
        assertEquals(189.0, prices.tierOn(saturday + 1)?.price?.adult, "the table gives Sunday with the cheaper days")
        assertEquals(189.0, prices.tierOn(today)?.price?.adult)
    }

    @Test
    fun peakIsTakenAsTheWeekend() {
        val prices = SkiResortPrices(
            "https://a.example",
            tiers = listOf(SkiPriceTier(SkiPriceTier.Kind.NON_PEAK, SkiDayPrice(adultWindow = 167.0)), SkiPriceTier(SkiPriceTier.Kind.PEAK, SkiDayPrice(adultWindow = 191.0))),
        )
        assertEquals(191.0, prices.tierOn(saturday)?.price?.adult)
        assertEquals(191.0, prices.tierOn(saturday + 1)?.price?.adult, "Sunday counts with the weekend here, as nothing says otherwise")
        assertEquals(167.0, prices.tierOn(today)?.price?.adult)
    }

    @Test
    fun aListingIsSaidToBeOne() {
        assertEquals(SkiCost.Source.LISTING, SkiPlanner.cost(plan, SkiEntry(ref("a"), price = SkiTicketPrice(adultDay = 200.0, childDay = 100.0)), saturday).source)
    }

    // ---- The resort's own word --------------------------------------------------------------------

    @Test
    fun aResortsOwnPageWithNoDateIsTakenAtItsWordWhileItsBeenReadLately() {
        val news = SkiResortNews("https://a.example", last24Cm = 5.0, fetchedAtEpochSeconds = 1_000_000)
        assertTrue(report("a").merged(news).isCurrent(1_000_000 + 86_400))
        assertFalse(report("a").merged(news).isCurrent(1_000_000 + 8 * 86_400), "a site down for a week leaves nothing to go by")
    }

    @Test
    fun anOldListingsCountsDontPassForTheResortsOwn() {
        val listing = report("a").copy(liftsOpen = 12, liftsTotal = 20, runsOpen = 80, runsTotal = 100, runsOpenPercent = 80)
        val merged = listing.merged(SkiResortNews("https://a.example", last24Cm = 5.0, updatedAtEpochSeconds = now))
        assertNull(merged.liftsOpen)
        assertNull(merged.liftsTotal)
        assertNull(merged.runsOpen)
        assertNull(merged.runsTotal)
        assertNull(merged.runsOpenPercent)
    }

    @Test
    fun theTripsHoursGoByTheMountainsZoneAcrossAClockChange() {
        val clocksChange = LocalDate(2027, 3, 14).toEpochDays().toInt()
        val midnightUtc = clocksChange * SkiForecast.DAY_SECONDS
        val pacific = SkiForecast(0, -28_800, timeZoneId = "America/Los_Angeles")
        assertEquals(midnightUtc + 14 * 3_600, pacific.epochSecondsAt(clocksChange, 7), "7 AM daylight time, though the forecast was fetched on standard")
        assertEquals(midnightUtc + 15 * 3_600, pacific.copy(timeZoneId = "").epochSecondsAt(clocksChange, 7), "no zone: the offset is all there is")
        assertEquals(midnightUtc + 15 * 3_600, pacific.copy(timeZoneId = "Nowhere/Much").epochSecondsAt(clocksChange, 7))
    }

    @Test
    fun aResortsOwnOldReportIsStillOld() {
        val news = SkiResortNews("https://a.example", last24Cm = 5.0, updatedAtEpochSeconds = 1_000)
        assertFalse(report("a").merged(news).isCurrent(1_000 + 30 * 86_400))
    }

    @Test
    fun liftsTurningMeanItsOpenWhateverTheListingSays() {
        val news = SkiResortNews("https://a.example", liftsOpen = 3, liftsTotal = 10)
        assertEquals(SkiOpenState.OPEN, report("a", SkiOpenState.CLOSED).merged(news).state)
    }

    @Test
    fun aResortThatSaysItsClosedIsClosed() {
        val news = SkiResortNews("https://a.example", running = false)
        assertEquals(SkiOpenState.CLOSED, report("a", SkiOpenState.OPEN).merged(news).state)
    }

    @Test
    fun theDatesStayTheListings() {
        val merged = report("a", SkiOpenState.CLOSED, opens = saturday).merged(SkiResortNews("https://a.example", last24Cm = 5.0))
        assertEquals(saturday, merged.openingEpochDay)
    }

    @Test
    fun anEntryWithOnlyTheResortsWordStillHasAReport() {
        assertEquals(5.0, SkiEntry(ref("a"), news = SkiResortNews("https://a.example", last24Cm = 5.0)).latest?.last24Cm)
    }

    @Test
    fun noPriceAnywhereIsNoFigure() {
        assertNull(SkiPlanner.cost(plan, SkiEntry(ref("a")), saturday).total)
    }

    @Test
    fun writesMoney() {
        assertEquals("$269", SkiPlanner.money(269.0))
        assertEquals("$1,399", SkiPlanner.money(1_399.0))
        assertEquals("$68.99", SkiPlanner.money(68.99))
        assertEquals("C$89", SkiPlanner.money(89.0, "C$"))
    }

    // ---- Days ---------------------------------------------------------------------------------------

    @Test
    fun theNextSaturdayIsTodayOnASaturday() {
        assertEquals(saturday, SkiPlanner.nextSaturday(today))
        assertEquals(saturday, SkiPlanner.nextSaturday(saturday))
        assertEquals(saturday + 7, SkiPlanner.nextSaturday(saturday + 1))
    }

    @Test
    fun theStripShowsTheMostAnyResortExpects() {
        val strip = SkiPlanner.dayStrip(
            listOf(
                entry("a", listOf(day(today, 2.0), day(today + 1, 9.0))),
                SkiEntry(ref("b"), report = report("b", outlook = listOf(SkiSnowDay(today, 5.0)))),
            ),
            today,
            3,
        )
        assertEquals(listOf(5.0, 9.0, 0.0), strip.map { it.mostSnowCm })
        assertEquals(today + 2, strip.last().epochDay)
    }

    @Test
    fun forecastDaysAreCutOnTheMountainsClock() {
        val forecast = forecast(day(today))
        assertEquals(today, forecast.epochDayOf(today * SkiForecast.DAY_SECONDS + 28_800))
        assertEquals(today - 1, forecast.epochDayOf(today * SkiForecast.DAY_SECONDS + 28_799))
        assertEquals(day(today), forecast.day(today))
    }
}
