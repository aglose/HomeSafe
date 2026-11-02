package com.meticulouscreations.homesafe.weather.ski.data

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.data.WEATHER_CLIENT
import com.meticulouscreations.homesafe.weather.data.WeatherServiceException
import com.meticulouscreations.homesafe.weather.data.weatherJson
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDayPrice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiParking
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_no_ticket_price
import homesafe.shared.generated.resources.ski_parking_note_heavenly
import homesafe.shared.generated.resources.ski_parking_note_kirkwood
import homesafe.shared.generated.resources.ski_parking_note_northstar
import homesafe.shared.generated.resources.ski_parking_note_palisades
import homesafe.shared.generated.resources.ski_parking_note_sierra
import homesafe.shared.generated.resources.ski_parking_note_sugar_bowl
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/** What each resort's own site says about parking. Only what it says: prices change, so most are left for the household to set. */
internal object ResortParking {
    val ALL: Map<String, SkiParking> = mapOf(
        "palisades-tahoe" to SkiParking("https://www.palisadestahoe.com/mountain-information/parking-and-road-conditions/parking-program", Res.string.ski_parking_note_palisades, reservationNeeded = true, dayPrice = 0),
        "northstar-california" to SkiParking("https://www.northstarcalifornia.com/explore-the-resort/about-the-resort/getting-here.aspx", Res.string.ski_parking_note_northstar, reservationNeeded = true, dayPrice = 0),
        "sugar-bowl-resort" to SkiParking("https://www.sugarbowl.com/parking-guide", Res.string.ski_parking_note_sugar_bowl, dayPrice = 0),
        "heavenly-mountain-resort" to SkiParking("https://www.skiheavenly.com/explore-the-resort/about-the-resort/getting-here-and-parking.aspx", Res.string.ski_parking_note_heavenly, reservationNeeded = true, dayPrice = 0),
        "kirkwood" to SkiParking("https://www.kirkwood.com/explore-the-resort/about-the-resort/getting-here.aspx", Res.string.ski_parking_note_kirkwood, reservationNeeded = true, dayPrice = 0),
        "sierra-at-tahoe" to SkiParking("https://sierraattahoe.com/parking/", Res.string.ski_parking_note_sierra, reservationNeeded = true, dayPrice = 25),
    )
}

/**
 * How to read one resort's own website, by the slug it has on OnTheSnow's lists. Anything not
 * here shows OnTheSnow's listing, saying so.
 */
internal sealed interface ResortSite {
    /** Where to send someone for its tickets. */
    val ticketsUrl: String?

    /** A Vail Resorts site: a snow report and a shop with a dated price API, on the same paths on all of them. */
    class Vail(val host: String, val zone: String, private val reportPage: String = "snow-and-weather-report.aspx", private val terrainPage: String = "terrain-and-lift-status.aspx") : ResortSite {
        val reportUrl get() = "https://$host/the-mountain/mountain-conditions/$reportPage"
        val terrainUrl get() = "https://$host/the-mountain/mountain-conditions/$terrainPage"
        override val ticketsUrl get() = "https://$host/plan-your-trip/lift-access/tickets.aspx"
    }

    /** A mountain drawing its report from the feed Alterra's sites use. [feedSlug] names it there, [pageUrl] is the page a person reads. */
    class Feed(
        val feedSlug: String,
        val pageUrl: String,
        override val ticketsUrl: String?,
        val shop: Shop? = null,
        val inntopia: Inntopia? = null,
        /** Which mountain, where a config covers several. */
        val resortId: Int? = null,
    ) : ResortSite

    /** A POWDR mountain: a small JSON API behind its site, and a shop on the shared platform. */
    class Powdr(val apiHost: String, val pageUrl: String, override val ticketsUrl: String?, val pageDataUrl: String? = null, val shop: Shop? = null) : ResortSite

    /** A plain page or two, read by [report] and, where it publishes a rate table, [prices]. */
    class Pages(
        val reportUrl: String?,
        override val ticketsUrl: String?,
        val report: ((html: String, zone: TimeZone, now: Long) -> SkiResortNews?)? = null,
        val prices: ((html: String) -> List<SkiPriceTier>)? = null,
        val zone: String = PACIFIC,
        val entabeni: Entabeni? = null,
    ) : ResortSite

    /** A ticket calendar on the shop platform: its address, its category, and which product in it. */
    class Shop(val url: String, val categorySlug: String, val productPattern: String)

    /** A ticket calendar on Inntopia, which answers a plain request. [childrenFree]: the resort says children ski free and has no price for them. */
    class Inntopia(val host: String, val salesId: String, val childrenFree: Boolean = false)

    /** A shop on Entabeni: its GraphQL address and the product. It gives only the adult's price this way. */
    class Entabeni(val graphqlUrl: String, val productSlug: String)

    companion object {
        const val PACIFIC = "America/Los_Angeles"
        private const val MOUNTAIN = "America/Denver"
        private const val EASTERN = "America/New_York"

        /** Vail's newer mountains put their pages under these names. */
        private const val WEATHER_REPORT = "weather-report.aspx"
        private const val LIFT_AND_TERRAIN = "lift-and-terrain-status.aspx"
        private const val BIG_BEAR_REPORT = "https://www.bigbearmountainresort.com/mountain-information"
        private const val BIG_BEAR_TICKETS = "https://www.bigbearmountainresort.com/ski-and-snowboard/lift-tickets"

        private fun feed(slug: String, page: String, tickets: String?, shop: Shop? = null) = Feed(slug, page, tickets, shop)

        /** Each confirmed against the real site. A new one is a line here, once its page has been read. */
        val ALL: Map<String, ResortSite> = mapOf(
            // Vail Resorts.
            "heavenly-mountain-resort" to Vail("www.skiheavenly.com", PACIFIC),
            "northstar-california" to Vail("www.northstarcalifornia.com", PACIFIC),
            "kirkwood" to Vail("www.kirkwood.com", PACIFIC),
            "vail" to Vail("www.vail.com", MOUNTAIN),
            "beaver-creek" to Vail("www.beavercreek.com", MOUNTAIN),
            "breckenridge" to Vail("www.breckenridge.com", MOUNTAIN),
            "keystone" to Vail("www.keystoneresort.com", MOUNTAIN),
            "park-city-mountain-resort" to Vail("www.parkcitymountain.com", MOUNTAIN),
            "whistler-blackcomb" to Vail("www.whistlerblackcomb.com", PACIFIC),
            "stowe-mountain-resort" to Vail("www.stowe.com", EASTERN),
            "okemo-mountain-resort" to Vail("www.okemo.com", EASTERN, terrainPage = LIFT_AND_TERRAIN),
            "hunter-mountain" to Vail("www.huntermtn.com", EASTERN),
            "crested-butte-mountain-resort" to Vail("www.skicb.com", MOUNTAIN, reportPage = WEATHER_REPORT, terrainPage = LIFT_AND_TERRAIN),
            "stevens-pass-resort" to Vail("www.stevenspass.com", PACIFIC, reportPage = WEATHER_REPORT, terrainPage = LIFT_AND_TERRAIN),
            "mount-snow" to Vail("www.mountsnow.com", EASTERN, reportPage = WEATHER_REPORT, terrainPage = LIFT_AND_TERRAIN),
            // The feed.
            "palisades-tahoe" to feed(
                "palisades-tahoe",
                "https://www.palisadestahoe.com/mountain-information/mountain-report",
                "https://shop.palisadestahoe.com/l/winter-lift-tickets/day-lift-tickets/c/daylifttickets",
                Shop("https://shop.palisadestahoe.com/l/winter-lift-tickets/day-lift-tickets/c/daylifttickets", "day-lift-tickets", "day lift|daylift"),
            ),
            "mammoth-mountain-ski-area" to Feed("mammoth", "https://www.mammothmountain.com/on-the-mountain/mountain-report", "https://www.mammothmountain.com/plan-your-trip/tickets", inntopia = Inntopia("book.mammothmountain.com", "17793040")),
            "june-mountain" to Feed("june-mountain", "https://www.junemountain.com/mountain-information/mountain-report", "https://www.junemountain.com/plan-your-trip/lift-tickets", inntopia = Inntopia("book.junemountain.com", "11220783", childrenFree = true)),
            "steamboat" to feed("steamboat", "https://www.steamboat.com/the-mountain/mountain-report", null),
            "winter-park-resort" to feed("winter-park", "https://www.winterparkresort.com/the-mountain/mountain-report", "https://shop.winterparkresort.com/l/winter-lift-tickets/day-lift-tickets/c/daylifttickets", Shop("https://shop.winterparkresort.com/l/winter-lift-tickets/day-lift-tickets/c/daylifttickets", "day-lift-tickets", "day lift|daylift")),
            "deer-valley-resort" to feed("deer-valley", "https://www.deervalley.com/explore-the-mountain/mountain-report", "https://www.deervalley.com/plan-your-trip/tickets-and-passes/lift-tickets"),
            "stratton-mountain" to feed("stratton", "https://www.stratton.com/the-mountain/mountain-report", null),
            "sugarbush" to feed("sugarbush", "https://www.sugarbush.com/mountain/conditions", "https://www.sugarbush.com/plan-your-trip/lift-tickets"),
            "solitude-mountain-resort" to feed("solitude", "https://www.solitudemountain.com/mountain-and-village/conditions-and-maps", "https://www.solitudemountain.com/plan-your-trip/snowboarding-ski-lift-tickets-passes"),
            "crystal-mountain-wa" to feed("crystal-mountain", "https://www.crystalmountainresort.com/the-mountain/mountain-report-and-webcams/snow-and-grooming-report", null),
            "arapahoe-basin-ski-area" to feed("arapahoe-basin", "https://www.arapahoebasin.com/snow-report/", "https://www.arapahoebasin.com/tickets-and-passes/"),
            "blue-mountain" to feed("blue-mountain", "https://www.bluemountain.ca/mountain/mountain-report", "https://www.bluemountain.ca/plan-your-trip/day-tickets/winter-lift-tickets"),
            "snow-valley" to Feed("big-bear-mountain", BIG_BEAR_REPORT, BIG_BEAR_TICKETS, resortId = 173),
            "bear-mountain" to Feed("big-bear-mountain", BIG_BEAR_REPORT, BIG_BEAR_TICKETS, resortId = 57),
            "snow-summit" to Feed("big-bear-mountain", BIG_BEAR_REPORT, BIG_BEAR_TICKETS, resortId = 58),
            // POWDR.
            "boreal" to Powdr(
                "api.rideboreal.com",
                "https://www.rideboreal.com/explore/status-conditions/lift-trail-status/",
                "https://book.rideboreal.com/s/lift-tickets/c/go-time-tickets",
                "https://www.rideboreal.com/page-data/day-access/tickets/page-data.json",
                Shop("https://book.rideboreal.com/s/lift-tickets/c/go-time-tickets", "lift-tickets", "go.?time"),
            ),
            "soda-springs" to Powdr("api.skisodasprings.com", "https://www.skisodasprings.com/the-mountain/conditions-weather/recent-snowfall/", "https://www.skisodasprings.com/plan-your-trip/tickets-passes/tickets-passes-soda-springs/"),
            // Their own pages.
            "sugar-bowl-resort" to Pages(
                "https://www.sugarbowl.com/conditions",
                "https://www.sugarbowl.com/tickets",
                report = { html, zone, _ -> ResortPages.sugarBowlReport(html, "https://www.sugarbowl.com/conditions", zone) },
                prices = ResortPages::sugarBowlPrices,
            ),
            "sierra-at-tahoe" to Pages(
                "https://sierraattahoe.com/weather-snow-report/",
                "https://sierraattahoe.com/lift-tickets/",
                report = { html, zone, _ -> ResortPages.sierraReport(html, "https://sierraattahoe.com/weather-snow-report/", zone) },
                prices = ResortPages::sierraPrices,
            ),
            "mt-rose-ski-tahoe" to Pages(
                "https://skirose.com/feed/snow-report-xml",
                "https://skirose.com/lift-tickets/",
                report = { xml, zone, _ -> ResortPages.roseReport(xml, "https://skirose.com/snow-report/", zone) },
            ),
            "homewood-mountain-resort" to Pages(
                "https://skihomewood.com/snowreport/",
                "https://skihomewood.com/tickets/",
                report = { html, _, _ -> ResortPages.homewoodReport(html, "https://skihomewood.com/snowreport/") },
            ),
            "donner-ski-ranch" to Pages(
                "https://www.donnerskiranch.com/liftstatus",
                "https://www.donnerskiranch.com/pricing",
                report = { html, zone, _ -> ResortPages.donnerRanchReport(html, "https://www.donnerskiranch.com/liftstatus", zone) },
                prices = ResortPages::donnerRanchPrices,
            ),
            "tahoe-donner" to Pages(
                "https://www.tahoedonner.com/amenities/amenities/downhill-ski/trails-conditions/snow-weather/",
                "https://www.tahoedonner.com/amenities/amenities/downhill-ski/tickets-passes-rental/day-tickets/",
                report = { html, zone, now -> ResortPages.tahoeDonnerReport(html, "https://www.tahoedonner.com/amenities/amenities/downhill-ski/trails-conditions/snow-weather/", zone, now) },
            ),
            "diamond-peak" to Pages(
                "https://www.diamondpeak.com/the-mountain/mountain-report/",
                "https://www.diamondpeak.com/tickets-passes-rentals/lift-tickets/",
                report = { html, _, _ -> ResortPages.diamondPeakReport(html, "https://www.diamondpeak.com/the-mountain/mountain-report/") },
            ),
            "dodge-ridge" to Pages(
                "https://dodgeridge.com/snow-report/",
                "https://dodgeridge.com/lift-tickets/",
                report = { html, _, _ -> ResortPages.dodgeRidgeReport(html, "https://dodgeridge.com/snow-report/") },
            ),
            // Its report isn't one to read, but its shop's prices are.
            "mount-shasta-board-ski-park" to Pages(
                null,
                "https://estore.skipark.com/",
                entabeni = Entabeni("https://skipark-estore.entabeni.systems/graphql", "day-tickets"),
            ),
            "ski-china-peak" to Pages(
                "https://www.skichinapeak.com/conditions",
                "https://www.skichinapeak.com/lift-tickets",
                report = { html, _, _ -> ResortPages.chinaPeakReport(html, "https://www.skichinapeak.com/conditions") },
                prices = ResortPages::chinaPeakPrices,
            ),
        )
    }
}

/** What the feed's public config for a mountain says to ask for: the token its own page uses comes from here each time, so none is kept. */
@Serializable
internal class FeedConfigDto(val bearerToken: String? = null, val resortIds: List<Int> = emptyList())

/** Fetches and reads a resort's own site, over plain requests where it allows that. */
@Inject
class ResortSiteApi(@Named(WEATHER_CLIENT) private val httpClient: HttpClient, private val clock: Clock) {
    private suspend fun page(url: String): String? {
        val response = httpClient.get(url)
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    /** The resort's report, or null if it has none the app can read, or it said nothing just now. */
    suspend fun news(slug: String): SkiResortNews? {
        val site = ResortSite.ALL[slug] ?: return null
        return when (site) {
            is ResortSite.Vail -> {
                val report = page(site.reportUrl) ?: return null
                val terrain = runCatching { page(site.terrainUrl) }.getOrNull()
                withContext(Dispatchers.Default) { ResortPages.vailReport(report, terrain, site.reportUrl, TimeZone.of(site.zone)) }
            }

            is ResortSite.Feed -> {
                val config = page("https://v4.mtnfeed.com/resorts/${site.feedSlug}.json")?.let { weatherJson.decodeFromString<FeedConfigDto>(it) } ?: return null
                val token = config.bearerToken ?: return null
                val id = site.resortId?.takeIf { it in config.resortIds } ?: config.resortIds.firstOrNull() ?: return null
                val feed = page("https://mtnpowder.com/feed/v3.json?bearer_token=${token.encodeURLParameter()}&resortId%5B%5D=$id") ?: return null
                withContext(Dispatchers.Default) { ResortPages.feedReport(feed, site.pageUrl) }
            }

            is ResortSite.Powdr -> {
                val reports = httpClient.get("https://${site.apiHost}/api/v1/dor/drupal/snow-reports") {
                    parameter("sort", "date")
                    parameter("direction", "desc")
                }.takeIf { it.status.isSuccess() }?.bodyAsText() ?: return null
                val status = runCatching { page("https://${site.apiHost}/api/v1/dor/lift-trail-report?alphabetical=true") }.getOrNull()
                withContext(Dispatchers.Default) { ResortPages.powdrReport(reports, status, site.pageUrl) }
            }

            is ResortSite.Pages -> {
                val read = site.report ?: return null
                val text = page(site.reportUrl ?: return null) ?: return null
                val now = clock.now().epochSeconds
                withContext(Dispatchers.Default) { read(text, TimeZone.of(site.zone), now) }
            }
        }
    }

    /** A ticket calendar that answers a plain request, with no browser. Null if there's none, and it throws if it won't answer. */
    internal suspend fun calendar(slug: String, epochDays: List<Int>, tripDays: Int): ShopPrices? = when (val site = ResortSite.ALL[slug]) {
        is ResortSite.Feed -> site.inntopia?.let { inntopia(it, epochDays, tripDays) }
        is ResortSite.Pages -> site.entabeni?.let { entabeni(it, epochDays) }
        else -> null
    }

    private suspend fun inntopia(shop: ResortSite.Inntopia, epochDays: List<Int>, tripDays: Int): ShopPrices {
        val months = epochDays.map { LocalDate.fromEpochDays(it) }.map { it.year to it.month.number }.distinct().take(MAX_CALENDAR_MONTHS)

        suspend fun rows(year: Int, month: Int, days: Int, age: Int): String {
            val response = httpClient.post("https://${shop.host}/Ecomm/JSON/ActivityCalendar/") {
                setBody(
                    FormDataContent(
                        parameters {
                            append("salesId", shop.salesId)
                            append("productCategoryId", INNTOPIA_LIFT_TICKETS)
                            append("startMonth", month.toString())
                            append("startYear", year.toString())
                            append("numberMonths", "1")
                            append("days", days.toString())
                            append("ageCategory", age.toString())
                        },
                    ),
                )
            }
            if (!response.status.isSuccess()) throw WeatherServiceException(UiText.of(Res.string.ski_no_ticket_price), response.status.value)
            return response.bodyAsText()
        }

        suspend fun prices(days: Int): Map<Int, SkiDayPrice> = months.map { (year, month) ->
            val adult = rows(year, month, days, INNTOPIA_ADULT)
            val child = if (shop.childrenFree) null else rows(year, month, days, INNTOPIA_CHILD)
            withContext(Dispatchers.Default) { ResortPages.inntopiaPrices(adult, child, days, shop.childrenFree) }
        }.fold(emptyMap()) { all, month -> all + month }

        val dayTickets = prices(1)
        val packs = if (tripDays > 1) prices(tripDays).mapValues { (_, price) -> mapOf(tripDays to price) } else emptyMap()
        return ShopPrices(dayTickets, packs)
    }

    /** Its calendar comes back a couple of weeks round the date asked about. */
    private suspend fun entabeni(shop: ResortSite.Entabeni, epochDays: List<Int>): ShopPrices {
        val found = HashMap<Int, SkiDayPrice>()
        val asked = HashSet<Int>()
        for (day in epochDays.sorted()) {
            if (day in asked) continue
            if (asked.size >= MAX_CALENDAR_ASKS * ENTABENI_DAYS) break
            val date = LocalDate.fromEpochDays(day)
            val query = buildJsonObject { put("query", "{ ecommerce { calendarPrices(slug:\"${shop.productSlug}\",forDate:\"$date\") { displayPrice date soldOut } } }") }
            val response = httpClient.post(shop.graphqlUrl) {
                contentType(ContentType.Application.Json)
                setBody(query.toString())
            }
            if (!response.status.isSuccess()) throw WeatherServiceException(UiText.of(Res.string.ski_no_ticket_price), response.status.value)
            val text = response.bodyAsText()
            found += withContext(Dispatchers.Default) { ResortPages.entabeniPrices(text) }
            asked += (day until day + ENTABENI_DAYS)
        }
        return ShopPrices(found)
    }

    /** The resort's published rate table, where it has one on a plain page. */
    suspend fun priceTable(slug: String): List<SkiPriceTier> {
        val site = ResortSite.ALL[slug] ?: return emptyList()
        return when (site) {
            is ResortSite.Pages -> {
                val read = site.prices ?: return emptyList()
                val text = page(site.ticketsUrl ?: return emptyList()) ?: return emptyList()
                withContext(Dispatchers.Default) { read(text) }
            }

            is ResortSite.Powdr -> {
                val text = page(site.pageDataUrl ?: return emptyList()) ?: return emptyList()
                withContext(Dispatchers.Default) { ResortPages.powdrPrices(text) }
            }

            else -> emptyList()
        }
    }

    private companion object {
        const val MAX_CALENDAR_MONTHS = 3
        const val INNTOPIA_LIFT_TICKETS = "117"
        const val INNTOPIA_ADULT = 8
        const val INNTOPIA_CHILD = 3
        const val MAX_CALENDAR_ASKS = 4
        const val ENTABENI_DAYS = 7
    }
}
