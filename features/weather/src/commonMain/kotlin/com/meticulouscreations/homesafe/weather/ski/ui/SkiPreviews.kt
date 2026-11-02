package com.meticulouscreations.homesafe.weather.ski.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.weather.ski.SkiRegionLoad
import com.meticulouscreations.homesafe.weather.ski.SkiUiState
import com.meticulouscreations.homesafe.weather.ski.domain.ChainControl
import com.meticulouscreations.homesafe.weather.ski.domain.ChainLevel
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecastHour
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiParking
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPage
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPrices
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiSnowDay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_parking_note_palisades

/**
 * A made-up weekend for previews and tests: a storm coming in midweek, a good Saturday after it,
 * three mountains running and one yet to open. The names are the resorts' (they're public), the
 * numbers aren't anyone's.
 */
object SkiFixtures {
    /** Tuesday 6 January 2026, noon in California. */
    const val NOW = 1_767_729_600L
    const val TODAY = 20_459
    private const val OFFSET = -28_800

    /** What falls on each day from today on, and how far the models spread around it. */
    private class DayPlan(val snow: Double, val spread: Double, val code: Int, val gust: Double = 30.0, val rain: Double = 0.0)

    private val week = listOf(
        DayPlan(0.0, 0.0, 1),
        DayPlan(3.0, 2.0, 71),
        DayPlan(22.0, 9.0, 75, gust = 74.0),
        DayPlan(31.0, 16.0, 75, gust = 92.0),
        DayPlan(6.0, 4.0, 73),
        DayPlan(1.0, 1.0, 2),
        DayPlan(0.0, 0.0, 0),
        DayPlan(0.0, 0.0, 1),
        DayPlan(4.0, 8.0, 61, rain = 6.0),
        DayPlan(0.0, 3.0, 3),
    )

    private fun forecast(scale: Double = 1.0): SkiForecast = SkiForecast(
        fetchedAtEpochSeconds = NOW - 600,
        utcOffsetSeconds = OFFSET,
        timeZoneId = "America/Los_Angeles",
        days = week.mapIndexed { i, plan ->
            val snow = plan.snow * scale
            SkiForecastDay(
                epochSeconds = (TODAY + i) * SkiForecast.DAY_SECONDS - OFFSET,
                weatherCode = plan.code,
                snowCm = snow,
                snowLowCm = (snow - plan.spread).coerceAtLeast(0.0),
                snowHighCm = snow + plan.spread,
                summitHighC = -3.0 - i % 3,
                summitLowC = -9.0 - i % 4,
                baseHighC = 2.0 - i % 3,
                baseLowC = -5.0 - i % 3,
                baseRainMm = plan.rain,
                precipitationProbability = if (plan.snow > 0) 80 else 10,
                windMaxKmh = plan.gust * 0.6,
                gustMaxKmh = plan.gust,
            )
        },
        hours = (0..23).map { hour ->
            SkiForecastHour(
                epochSeconds = (TODAY + 4) * SkiForecast.DAY_SECONDS - OFFSET + hour * 3_600L,
                temperatureC = -7.0 + (hour - 6) * 0.3,
                snowCm = if (hour < 10) 0.6 else 0.0,
                weatherCode = if (hour < 10) 73 else 2,
                windKmh = 18.0,
                gustKmh = 34.0,
                visibilityM = if (hour < 10) 600.0 else 20_000.0,
                freezingLevelM = 1_950.0,
            )
        },
    )

    private fun report(ref: SkiResortRef, last72: Double, base: Double, lifts: Int, total: Int, state: SkiOpenState = SkiOpenState.OPEN, opening: Int? = null) = SkiReport(
        slug = ref.slug,
        regionSlug = ref.regionSlug,
        name = ref.name,
        baseDepthCm = base,
        middleDepthCm = base + 30,
        summitDepthCm = base + 60,
        last24Cm = last72 / 3,
        last48Cm = last72 / 2,
        last72Cm = last72,
        liftsOpen = lifts,
        liftsTotal = total,
        runsOpen = lifts * 7,
        runsTotal = total * 7,
        state = state,
        openingEpochDay = opening,
        updatedAtEpochSeconds = NOW - 5_400,
        outlook = week.mapIndexed { i, plan -> SkiSnowDay(TODAY + i, plan.snow) },
    )

    private val palisades = SkiResortRef("palisades-tahoe", "california", "Palisades Tahoe")
    private val northstar = SkiResortRef("northstar-california", "california", "Northstar California")
    private val kirkwood = SkiResortRef("kirkwood", "california", "Kirkwood")
    private val sierra = SkiResortRef("sierra-at-tahoe", "california", "Sierra-at-Tahoe")

    private val facts = SkiResortFacts(
        latitude = 39.1967,
        longitude = -120.2322,
        baseElevationM = 1_890.0,
        summitElevationM = 2_758.0,
        verticalM = 869.0,
        city = "Olympic Valley",
        acres = 6_000,
        runsBeginner = 16,
        runsIntermediate = 111,
        runsAdvanced = 93,
        runsExpert = 66,
        websiteUrl = "https://example.com/",
        ticketsUrl = "https://example.com/tickets",
    )

    private fun drive(hours: Double, source: SkiDriveSource = SkiDriveSource.FREE_FLOW) = SkiDrive((hours * 3_600).toLong(), hours * 90_000, source)

    val entries: Map<String, SkiEntry> = listOf(
        SkiEntry(
            palisades,
            report = report(palisades, 12.0, 120.0, 26, 39),
            page = SkiResortPage(facts, report(palisades, 12.0, 120.0, 26, 39)),
            news = SkiResortNews(
                pageUrl = "https://www.palisadestahoe.com/mountain-information/mountain-report",
                last24Cm = 20.32,
                last72Cm = 30.48,
                last7DaysCm = 40.64,
                seasonCm = 152.4,
                baseDepthCm = 91.44,
                summitDepthCm = 165.1,
                conditions = "Packed powder",
                liftsOpen = 26,
                liftsTotal = 39,
                runsOpen = 190,
                runsTotal = 296,
                running = true,
                updatedAtEpochSeconds = NOW - 5_400,
            ),
            prices = SkiResortPrices(
                pageUrl = "https://shop.palisadestahoe.com/l/winter-lift-tickets/day-lift-tickets/c/daylifttickets",
                days = (0..9).associate { (TODAY + it) to SkiDayPrice(adultOnline = 175.0, adultWindow = 269.0, childOnline = 122.0, childWindow = 188.0) },
                fetchedAtEpochSeconds = NOW - 3_600,
            ),
            forecast = forecast(),
            price = SkiTicketPrice(adultDay = 269.0, childDay = 188.0, adultSeason = 1_399.0, purchaseUrl = "https://example.com/tickets"),
            drive = drive(3.6),
            driveOut = drive(4.1, SkiDriveSource.TRAFFIC),
            driveBack = drive(5.2, SkiDriveSource.TRAFFIC),
            chains = listOf(ChainControl(ChainLevel.R2, "I-80", "Donner Summit", "East", 39.33, -120.34, "Chains are required on all vehicles except 4WD with snow tires.")),
        ),
        SkiEntry(northstar, report = report(northstar, 9.0, 95.0, 14, 20), forecast = forecast(0.7), price = SkiTicketPrice(adultDay = 237.0, childDay = 166.0), drive = drive(3.7), passes = setOf(SkiPass.EPIC)),
        SkiEntry(
            sierra,
            report = report(sierra, 4.0, 70.0, 8, 14),
            forecast = forecast(0.55),
            prices = SkiResortPrices(
                pageUrl = "https://sierraattahoe.com/lift-tickets/",
                tiers = listOf(
                    SkiPriceTier(SkiPriceTier.Kind.NON_PEAK, SkiDayPrice(adultWindow = 167.0, childWindow = 70.0)),
                    SkiPriceTier(SkiPriceTier.Kind.PEAK, SkiDayPrice(adultWindow = 191.0, childWindow = 79.0)),
                ),
                fetchedAtEpochSeconds = NOW - 3_600,
            ),
            drive = drive(3.2),
        ),
        SkiEntry(kirkwood, report = report(kirkwood, 0.0, 0.0, 0, 15, SkiOpenState.CLOSED, TODAY + 20), forecast = forecast(0.9), drive = drive(3.4)),
    ).associateBy { it.ref.slug }

    fun state(plan: SkiPlan = SkiPlan(resorts = listOf(palisades, northstar, sierra, kirkwood), passes = setOf(SkiPass.EPIC), children = 1)) = SkiUiState(
        plan = plan,
        entries = entries,
        regions = mapOf(
            "northern-california" to SkiRegionLoad(
                reports = listOf(
                    report(palisades, 12.0, 120.0, 26, 39),
                    report(northstar, 9.0, 95.0, 14, 20),
                    report(sierra, 4.0, 70.0, 8, 14),
                    report(kirkwood, 0.0, 0.0, 0, 15, SkiOpenState.CLOSED, TODAY + 20),
                ),
                prices = mapOf("palisades-tahoe" to SkiTicketPrice(adultDay = 269.0), "northstar-california" to SkiTicketPrice(adultDay = 237.0)),
            ),
        ),
        nowEpochSeconds = NOW,
        todayEpochDay = TODAY,
        tripEpochDay = SkiPlanner.nextSaturday(TODAY),
    )
}

@Composable
private fun SkiBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF47658A), Color(0xFF1B2638))))) { content() }
}

private val PagePadding = PaddingValues(top = 16.dp, bottom = 24.dp)

// Each drawn on a plain gradient: the sky behind them is the one the other weather previews draw.
@Preview
@Preview(name = "Ski tab, in Spanish", locale = "es")
@Composable
private fun SkiBoardPreview() {
    SkiBackdrop { SkiScreen(SkiFixtures.state(), SkiActions(), PagePadding, {}, {}, {}, {}) }
}

// Beside the sidebar: the cards keep their reading width, and every day fits in the strip.
@Preview(name = "Ski tab, wide", widthDp = 1000, heightDp = 800)
@Composable
private fun SkiBoardWidePreview() {
    SkiBackdrop { SkiScreen(SkiFixtures.state(), SkiActions(), PagePadding, {}, {}, {}, {}) }
}

// Out of season: nothing the household follows is running yet.
@Preview
@Composable
private fun SkiBoardClosedPreview() {
    val state = SkiFixtures.state()
    val closed = state.entries.mapValues { (_, entry) -> entry.copy(report = entry.report?.copy(state = SkiOpenState.CLOSED, openingEpochDay = SkiFixtures.TODAY + 40)) }
    SkiBackdrop { SkiScreen(state.copy(entries = closed), SkiActions(), PagePadding, {}, {}, {}, {}) }
}

// A resort read from its own site, and (below) one that the app only has a listing for.
@Preview(heightDp = 2300)
@Composable
private fun SkiResortPagePreview() {
    val state = SkiFixtures.state()
    SkiResortScreen(state.entries.getValue("palisades-tahoe").ref, state, SkiActions(), null, PagePadding)
}

@Preview(heightDp = 1500)
@Composable
private fun SkiResortListingPreview() {
    val state = SkiFixtures.state()
    SkiResortScreen(state.entries.getValue("northstar-california").ref, state.copy(plan = state.plan.copy(passes = emptySet())), SkiActions(), null, PagePadding)
}

// A long weekend: days added up, a night's lodging, parking the household set, and the drive.
@Preview(name = "Ski tab, a three-day trip", heightDp = 900)
@Composable
private fun SkiBoardTripPreview() {
    val state = SkiFixtures.state()
    SkiBackdrop { SkiScreen(state.copy(plan = state.plan.copy(tripDays = 3, passes = emptySet())), SkiActions(), PagePadding, {}, {}, {}, {}) }
}

@Preview(name = "Ski resort, a three-day trip", heightDp = 2600)
@Composable
private fun SkiResortTripPreview() {
    val state = SkiFixtures.state()
    val plan = state.plan.copy(tripDays = 3, lodgingPerNight = 320, ownParking = mapOf("palisades-tahoe" to 40), passes = emptySet())
    val entry = state.entries.getValue("palisades-tahoe").copy(parking = SkiParking("https://example.com/parking", Res.string.ski_parking_note_palisades, reservationNeeded = true))
    SkiResortScreen(entry.ref, state.copy(plan = plan, entries = state.entries + (entry.ref.slug to entry)), SkiActions(), null, PagePadding)
}

// A closed pass on the way, and a road service's own words on another stretch; and one that couldn't be read.
@Preview(name = "Ski resort, roads", heightDp = 2300)
@Composable
private fun SkiResortRoadsPreview() {
    val state = SkiFixtures.state()
    val entry = state.entries.getValue("palisades-tahoe").copy(
        chains = listOf(
            ChainControl(ChainLevel.CLOSED, "SR-89", "Monitor Pass", latitude = 38.66, longitude = -119.72, description = "Closed to traffic."),
            ChainControl(ChainLevel.NOTICE, "SR-431 (Mt. Rose Hwy)", "From Diamond Peak to Incline Lake", latitude = 0.0, longitude = 0.0, description = "Snow Covered"),
        ),
        chainsUnavailable = true,
    )
    SkiResortScreen(entry.ref, state.copy(entries = state.entries + (entry.ref.slug to entry)), SkiActions(), null, PagePadding)
}

// A site that has stopped being readable: on the tab, and by name in the setup.
@Preview(name = "Ski tab, a site not readable", heightDp = 1400)
@Composable
private fun SkiBoardSiteTroublePreview() {
    SkiBackdrop { SkiScreen(SkiFixtures.state().copy(siteTrouble = mapOf("kirkwood" to SkiFixtures.NOW - 5 * 86_400)), SkiActions(), PagePadding, {}, {}, {}, {}) }
}

@Preview(name = "Ski setup, a site not readable", heightDp = 1100)
@Composable
private fun SkiSetupSiteTroublePreview() {
    SkiSetupScreen(SkiFixtures.state().plan, SkiActions(), PagePadding, siteTrouble = listOf("Kirkwood" to SkiFixtures.NOW - 5 * 86_400))
}

@Preview
@Composable
private fun SkiRegionPagePreview() {
    val state = SkiFixtures.state()
    SkiRegionScreen("northern-california", state.regions["northern-california"], state, SkiActions(), PagePadding, {})
}

@Preview
@Composable
private fun SkiFindPagePreview() {
    SkiFindScreen(SkiFixtures.state().plan, PagePadding, {})
}

// A Mac with no home set: it can't tell where it is, and goes by a city from the weather.
@Preview(name = "Ski setup, no location", widthDp = 1000, heightDp = 800)
@Composable
private fun SkiSetupNoLocationPreview() {
    SkiSetupScreen(SkiFixtures.state().plan, SkiActions(), PagePadding, canLocate = false, startCity = "San Francisco")
}

@Preview
@Composable
private fun SkiSetupPagePreview() {
    SkiSetupScreen(SkiFixtures.state().plan, SkiActions(), PagePadding)
}
