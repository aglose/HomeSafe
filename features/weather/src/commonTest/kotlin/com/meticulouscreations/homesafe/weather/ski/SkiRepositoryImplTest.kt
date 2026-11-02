package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.data.SkiDao
import com.meticulouscreations.homesafe.data.SkiKeptEntity
import com.meticulouscreations.homesafe.vault.HouseholdKeys
import com.meticulouscreations.homesafe.vault.InMemoryHouseholdKeys
import com.meticulouscreations.homesafe.weather.data.MutableClock
import com.meticulouscreations.homesafe.weather.data.NwsApi
import com.meticulouscreations.homesafe.weather.data.weatherJson
import com.meticulouscreations.homesafe.weather.ski.data.CaltransChainApi
import com.meticulouscreations.homesafe.weather.ski.data.OnTheSnowApi
import com.meticulouscreations.homesafe.weather.ski.data.ResortSiteApi
import com.meticulouscreations.homesafe.weather.ski.data.SkiBrowser
import com.meticulouscreations.homesafe.weather.ski.data.SkiForecastApi
import com.meticulouscreations.homesafe.weather.ski.data.SkiRepositoryImpl
import com.meticulouscreations.homesafe.weather.ski.data.SkiRoutesApi
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkiRepositoryImplTest {
    private class InMemorySkiDao : SkiDao {
        val rows = MutableStateFlow(emptyMap<String, SkiKeptEntity>())

        override suspend fun kept(key: String) = rows.value[key]

        override fun observe(key: String): Flow<SkiKeptEntity?> = rows.map { it[key] }

        override suspend fun keep(entity: SkiKeptEntity) {
            rows.value = rows.value + (entity.key to entity)
        }
    }

    private class FakeBrowser : SkiBrowser {
        override var isSupported = true
        var answer: String? = null
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun read(url: String, script: String, timeoutMillis: Long): String? {
            asked += url to script
            return answer
        }
    }

    private val home = SkiPoint(37.80444, -122.27111)
    private val mountain = SkiPoint(39.2, -120.23)
    private val listPage = "<script>self.__next_f.push([1,\"1:{\\\"title\\\":\\\"Alta\\\",\\\"slug\\\":\\\"alta\\\",\\\"region\\\":\\\"utah\\\",\\\"snow\\\":{},\\\"lifts\\\":{},\\\"status\\\":{}}\\n\"])</script>"

    private inner class Web(keys: HouseholdKeys = InMemoryHouseholdKeys(), private val answer: suspend (HttpRequestData) -> Answer) {
        val asked = mutableListOf<HttpRequestData>()
        val dao = InMemorySkiDao()
        val clock = MutableClock(1_000_000)
        val client = HttpClient(
            MockEngine { request ->
                asked += request
                val reply = answer(request)
                when {
                    reply.status != HttpStatusCode.OK -> respondError(reply.status)
                    reply.body == null -> respondOk()
                    else -> respond(reply.body, headers = headersOf("Content-Type", "text/plain"))
                }
            },
        )
        val browser = FakeBrowser()
        val repository = SkiRepositoryImpl(OnTheSnowApi(client), SkiForecastApi(client), SkiRoutesApi(client), CaltransChainApi(client), ResortSiteApi(client, clock), browser, NwsApi(client), keys, dao, clock)
    }

    private class Answer(val body: String? = null, val status: HttpStatusCode = HttpStatusCode.OK)

    private val osrm = """{"code":"Ok","routes":[{"duration":13062.3,"distance":297448,"geometry":"_p~iF~ps|U_ulLnnqC"}]}"""
    private val google = """{"routes":[{"duration":"14400s","distanceMeters":300000,"polyline":{"encodedPolyline":"_p~iF~ps|U"}}]}"""

    @Test
    fun theSameRegionIsntAskedForTwiceWhileItsYoung() = runTest {
        val web = Web { Answer(listPage) }
        assertEquals("alta", web.repository.reports("utah").getOrThrow().single().slug)
        web.repository.reports("utah")
        assertEquals(1, web.asked.size)
        web.clock.seconds += 1_800
        web.repository.reports("utah")
        assertEquals(2, web.asked.size)
    }

    @Test
    fun aPullAsksRegardless() = runTest {
        val web = Web { Answer(listPage) }
        web.repository.reports("utah")
        web.repository.reports("utah", force = true)
        assertEquals(2, web.asked.size)
    }

    @Test
    fun aServiceThatStopsAnsweringLeavesTheLastAnswer() = runTest {
        var up = true
        val web = Web { if (up) Answer(listPage) else Answer(status = HttpStatusCode.BadGateway) }
        web.repository.reports("utah")
        up = false
        web.clock.seconds += 1_800
        assertEquals("alta", web.repository.reports("utah").getOrThrow().single().slug)
    }

    @Test
    fun aServiceThatNeverAnsweredIsAFailure() = runTest {
        val web = Web { Answer(status = HttpStatusCode.Forbidden) }
        assertTrue(web.repository.reports("utah").isFailure)
    }

    @Test
    fun aPageThatChangedShapeIsAFailureNotAnEmptyRegion() = runTest {
        val web = Web { Answer("<html>we moved things</html>") }
        assertTrue(web.repository.reports("utah").isFailure)
    }

    @Test
    fun thePlanIsKept() = runTest {
        val web = Web { Answer() }
        assertEquals(SkiPlan(), web.repository.plan().first())
        web.repository.updatePlan { it.copy(adults = 3) }
        assertEquals(3, web.repository.plan().first().adults)
        assertEquals(3, weatherJson.decodeFromString(SkiPlan.serializer(), web.dao.rows.value.getValue("plan").json).adults)
    }

    @Test
    fun aPassesListIsItsResorts() = runTest {
        val web = Web { Answer(listPage) }
        assertEquals(setOf("alta"), web.repository.passResorts(SkiPass.IKON).getOrThrow())
        assertTrue(web.asked.single().url.encodedPath.contains("ikon-pass"))
    }

    // ---- The road ---------------------------------------------------------------------------------

    @Test
    fun withoutAKeyTheDriveIsOnAnEmptyRoad() = runTest {
        val web = Web { Answer(osrm) }
        val drive = web.repository.drive(home, mountain).getOrThrow()
        assertEquals(SkiDriveSource.FREE_FLOW, drive.source)
        assertEquals(13_062, drive.seconds)
        assertEquals(297_448.0, drive.meters)
        assertEquals(1, web.asked.size)
        web.repository.drive(home, mountain)
        assertEquals(1, web.asked.size, "an empty road is kept")
    }

    @Test
    fun theHouseItselfIsNeverTold() = runTest {
        val web = Web { Answer(osrm) }
        web.repository.drive(home, mountain)
        val url = web.asked.single().url.toString()
        assertTrue("-122.27,37.80" in url, url)
        assertFalse("37.804" in url || "122.271" in url, url)
    }

    @Test
    fun withAKeyTheDriveKnowsTheTraffic() = runTest {
        val web = Web(InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "secret")) { Answer(google) }
        val drive = web.repository.drive(home, mountain, departEpochSeconds = 2_000_000).getOrThrow()
        assertEquals(SkiDriveSource.TRAFFIC, drive.source)
        assertEquals(14_400, drive.seconds)
        assertEquals(2_000_000, drive.departEpochSeconds)
        val request = web.asked.single()
        assertEquals("secret", request.headers["X-Goog-Api-Key"])
        val body = (request.body as io.ktor.http.content.TextContent).text
        assertTrue("departureTime" in body, body)
        assertFalse("37.804" in body || "122.271" in body, body)
    }

    @Test
    fun aKeyNotAllowedRoutesFallsBackAndIsntAskedAgain() = runTest {
        val web = Web(InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "secret")) { request ->
            if ("googleapis" in request.url.host) Answer(status = HttpStatusCode.Forbidden) else Answer(osrm)
        }
        assertEquals(SkiDriveSource.FREE_FLOW, web.repository.drive(home, mountain).getOrThrow().source)
        val googleAsks = web.asked.count { "googleapis" in it.url.host }
        assertEquals(1, googleAsks)
        web.repository.drive(home, SkiPoint(38.0, -120.0))
        assertEquals(1, web.asked.count { "googleapis" in it.url.host })
    }

    @Test
    fun aPassingFailureFromGoogleIsOnlyThatDrives() = runTest {
        var down = true
        val web = Web(InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "secret")) { request ->
            when {
                "googleapis" !in request.url.host -> Answer(osrm)
                down -> Answer(status = HttpStatusCode.ServiceUnavailable)
                else -> Answer(google)
            }
        }
        assertEquals(SkiDriveSource.FREE_FLOW, web.repository.drive(home, mountain).getOrThrow().source)
        down = false
        assertEquals(SkiDriveSource.TRAFFIC, web.repository.drive(home, mountain).getOrThrow().source)
    }

    @Test
    fun aRoadGoogleHasNoRouteForLeavesItAskedForTheNext() = runTest {
        val web = Web(InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "secret")) { request ->
            if ("googleapis" in request.url.host) Answer("{}") else Answer(osrm)
        }
        assertEquals(SkiDriveSource.FREE_FLOW, web.repository.drive(home, mountain).getOrThrow().source)
        web.repository.drive(home, SkiPoint(38.0, -120.0))
        assertEquals(2, web.asked.count { "googleapis" in it.url.host })
    }

    @Test
    fun oldTrafficDoesntStandInWhenGoogleStopsAnswering() = runTest {
        var refused = false
        val web = Web(InMemoryHouseholdKeys(HouseholdKeys.GOOGLE_MAPS to "secret")) { request ->
            when {
                "googleapis" !in request.url.host -> Answer(osrm)
                refused -> Answer(status = HttpStatusCode.Forbidden)
                else -> Answer(google)
            }
        }
        assertEquals(SkiDriveSource.TRAFFIC, web.repository.drive(home, mountain).getOrThrow().source)
        refused = true
        web.clock.seconds += 3_600
        assertEquals(SkiDriveSource.FREE_FLOW, web.repository.drive(home, mountain).getOrThrow().source)
        web.clock.seconds += 3_600
        web.repository.drive(home, mountain)
        assertEquals(2, web.asked.count { "googleapis" in it.url.host }, "a refusal is still noticed, and not asked about again")
    }

    // ---- The resort's own site -----------------------------------------------------------------------

    private val vailPage = """<script>FR.snowReportData = {"OverallSnowConditions":"Early Season","TwentyFourHourSnowfall":{"Inches":"8","Centimeters":"20"},"BaseDepth":{"Inches":"0","Centimeters":"0"},"CurrentSeason":{"Inches":"8","Centimeters":"20"},"LastUpdatedText":"Updated November 01, 2026 at 10:20 AM PST"};</script>"""

    @Test
    fun aResortsReportIsReadFromItsOwnSiteAndKeptAWhile() = runTest {
        val web = Web { request -> if ("snow-and-weather" in request.url.encodedPath) Answer(vailPage) else Answer(status = HttpStatusCode.NotFound) }
        val news = web.repository.resortNews("northstar-california")
        assertEquals(8 * 2.54, news?.last24Cm)
        assertEquals("www.northstarcalifornia.com", web.asked.first().url.host)
        val asked = web.asked.size
        web.repository.resortNews("northstar-california")
        assertEquals(asked, web.asked.size)
    }

    @Test
    fun aResortWithNoSiteHereIsntAsked() = runTest {
        val web = Web { Answer("") }
        assertEquals(null, web.repository.resortNews("somewhere-unknown"))
        assertTrue(web.asked.isEmpty())
        assertFalse(web.repository.hasResortSite("somewhere-unknown"))
    }

    @Test
    fun aSiteThatStopsAnsweringLeavesItsLastReport() = runTest {
        var up = true
        val web = Web { if (up) Answer(vailPage) else Answer(status = HttpStatusCode.BadGateway) }
        web.repository.resortNews("northstar-california")
        up = false
        web.clock.seconds += 3_600
        assertEquals(8 * 2.54, web.repository.resortNews("northstar-california")?.last24Cm)
    }

    @Test
    fun whatARecentReadingWasIsKeptWithIt() = runTest {
        var up = true
        val web = Web { if (up) Answer(vailPage) else Answer(status = HttpStatusCode.BadGateway) }
        val readAt = web.clock.seconds
        assertEquals(readAt, web.repository.resortNews("northstar-california")?.fetchedAtEpochSeconds)
        up = false
        web.clock.seconds += 8 * 86_400
        assertEquals(readAt, web.repository.resortNews("northstar-california")?.fetchedAtEpochSeconds, "the last reading doesn't pass for a new one")
    }

    @Test
    fun aVailShopIsAskedThroughTheBrowserForTheDaysWanted() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        val day = 20_813 // 26 December 2026.
        val prices = web.repository.resortPrices("northstar-california", listOf(day))
        assertEquals(205.0, prices?.days?.get(day)?.adultOnline)
        assertEquals(199.0, prices?.days?.get(day)?.childWindow)
        assertEquals("https://www.northstarcalifornia.com/plan-your-trip/lift-access/tickets.aspx", web.browser.asked.single().first)
        assertTrue("2026-12-26" in web.browser.asked.single().second)
    }

    @Test
    fun aDayAlreadyInHandDoesntOpenTheBrowserAgain() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        val day = 20_813
        web.repository.resortPrices("northstar-california", listOf(day))
        web.repository.resortPrices("northstar-california", listOf(day))
        assertEquals(1, web.browser.asked.size)
    }

    @Test
    fun aDayNotOnSaleIsntAskedAboutAgain() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        // Before the season the shop only has its first day on sale.
        web.browser.answer = """{"2026-11-20":[131,164,92,115],"checked":["2026-11-02","2026-11-03","2026-11-20"]}"""
        web.repository.resortPrices("northstar-california", listOf(20_759, 20_760))
        web.repository.resortPrices("northstar-california", listOf(20_759, 20_760))
        assertEquals(1, web.browser.asked.size)
    }

    @Test
    fun aBrowserThatGotNowhereTriesAgain() = runTest {
        val web = Web { request -> if ("sugarbowl" in request.url.host) Answer(status = HttpStatusCode.NotFound) else Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = "{}"
        assertEquals(null, web.repository.resortPrices("northstar-california", listOf(20_813)))
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        assertEquals(205.0, web.repository.resortPrices("northstar-california", listOf(20_813))?.days?.get(20_813)?.adultOnline)
    }

    @Test
    fun aDayThatFailedInABatchIsAskedAboutAgain() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        web.repository.resortPrices("northstar-california", listOf(20_813, 20_814))
        web.browser.answer = """{"2026-12-27":[200,280,140,190],"checked":["2026-12-27"]}"""
        val prices = web.repository.resortPrices("northstar-california", listOf(20_813, 20_814))
        assertEquals(200.0, prices?.days?.get(20_814)?.adultOnline)
        assertEquals(2, web.browser.asked.size)
    }

    @Test
    fun aNewDayAddsToTheOnesAlreadyInHand() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        web.repository.resortPrices("northstar-california", listOf(20_813))
        web.browser.answer = """{"2026-12-27":[200,280,140,190],"checked":["2026-12-27"]}"""
        val prices = web.repository.resortPrices("northstar-california", listOf(20_814))
        assertEquals(setOf(20_813, 20_814), prices?.days?.keys)
        assertEquals(2, web.browser.asked.size)
    }

    @Test
    fun aShopThatRefusesLeavesWhatWasKept() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.answer = """{"2026-12-26":[205,284,144,199],"checked":["2026-12-26"]}"""
        web.repository.resortPrices("northstar-california", listOf(20_813))
        web.browser.answer = null
        web.clock.seconds += 2 * 86_400
        assertEquals(205.0, web.repository.resortPrices("northstar-california", listOf(20_813))?.days?.get(20_813)?.adultOnline)
    }

    @Test
    fun withNoBrowserAShopGivesNothingAndIsntAttempted() = runTest {
        val web = Web { Answer(status = HttpStatusCode.NotFound) }
        web.browser.isSupported = false
        assertEquals(null, web.repository.resortPrices("northstar-california", listOf(20_813)))
        assertTrue(web.browser.asked.isEmpty())
    }

    @Test
    fun aBrowserThatCouldntStartOrWasRefusedLeavesTheNextPriceAndLearnsNothing() = runTest {
        val web = Web { Answer("""{"title":"Lift Ticket","price":"$89","priceDescription":"(starting at)"}""") }
        web.browser.answer = null

        val prices = web.repository.resortPrices("boreal", listOf(20_813))

        assertEquals(1, web.browser.asked.size)
        assertEquals(89.0, prices?.tiers?.single()?.price?.adultOnline)
        assertTrue(prices?.days.orEmpty().isEmpty())
        assertTrue(prices?.daysAsked.orEmpty().isEmpty())
    }

    @Test
    fun aRateTableDoesntStandInForACalendarThatDidntAnswer() = runTest {
        val web = Web { Answer("""{"title":"Lift Ticket","price":"$89","priceDescription":"(starting at)"}""") }
        web.browser.answer = null
        web.repository.resortPrices("boreal", listOf(20_813))
        web.browser.answer = """{"2026-12-26":[99,129,79,99]}"""
        assertEquals(99.0, web.repository.resortPrices("boreal", listOf(20_813))?.days?.get(20_813)?.adultOnline)
        assertEquals(2, web.browser.asked.size)
    }

    @Test
    fun aCalendarWithNoDatesOnSaleIsAnAnswer() = runTest {
        val web = Web { Answer("""{"title":"Lift Ticket","price":"$89","priceDescription":"(starting at)"}""") }
        web.browser.answer = "{}"
        web.repository.resortPrices("boreal", listOf(20_813))
        web.repository.resortPrices("boreal", listOf(20_813))
        assertEquals(1, web.browser.asked.size, "it's a whole page, and it'll say the same for a while")
    }

    @Test
    fun aPlainRateTableNeedsNoBrowser() = runTest {
        val web = Web { Answer("Sat+Holiday Window Rates Adult (23-64) \$89 \$189 \$114 \$249 Child (6-12) \$49 \$109 \$66 \$139 Super Senior") }
        val prices = web.repository.resortPrices("sugar-bowl-resort", listOf(20_813))
        assertEquals(2, prices?.tiers?.size)
        assertTrue(web.browser.asked.isEmpty())
    }

    // ---- Chain controls ---------------------------------------------------------------------------

    private fun chainFile(status: String, latitude: Double, longitude: Double) = """
        {"data":[{"cc":{"index":"x","location":{"locationName":"Summit","route":"I-80","direction":"East","latitude":"$latitude","longitude":"$longitude"},"inService":"true","statusData":{"status":"$status","statusDescription":"Chains."}}}]}
    """.trimIndent()

    /** "_p~iF~ps|U_ulLnnqC": from 38.5, -120.2 to 40.7, -120.95, in Caltrans's District 3. */
    private val road = "_p~iF~ps|U_ulLnnqC"

    @Test
    fun onlyThoseOnTheRoadCount() = runTest {
        val web = Web { request ->
            when {
                "/d3/" in request.url.encodedPath -> Answer(chainFile("R-2", 39.6, -120.575))
                else -> Answer(chainFile("R-2", 40.0, -119.0))
            }
        }
        val report = web.repository.chainControls(road)
        assertTrue(report.complete)
        assertEquals("I-80", report.controls.single().route)
        assertFalse(web.asked.any { "/d11/" in it.url.encodedPath }, "San Diego's file has nothing to do with this road")
    }

    @Test
    fun twoRoadsThroughTheSameDistrictReadItsFileOnce() = runTest {
        val web = Web { Answer(chainFile("R-1", 39.6, -120.575)) }
        web.repository.chainControls(road)
        val asked = web.asked.count { "/d3/" in it.url.encodedPath }
        // A little shorter: its own box, and the same district.
        web.repository.chainControls("_p~iF~ps|U_ulLnnqC_ibE_seK")
        assertEquals(1, asked)
        assertEquals(1, web.asked.count { "/d3/" in it.url.encodedPath })
    }

    @Test
    fun noRoadNoChains() = runTest {
        val web = Web { Answer("{}") }
        assertTrue(web.repository.chainControls("").controls.isEmpty())
        assertTrue(web.asked.isEmpty())
    }

    @Test
    fun checkpointsAreKeptForAWhile() = runTest {
        val web = Web { Answer(chainFile("R-1", 39.6, -120.575)) }
        web.repository.chainControls(road)
        val asked = web.asked.size
        web.repository.chainControls(road)
        assertEquals(asked, web.asked.size)
        web.clock.seconds += 660
        web.repository.chainControls(road)
        assertTrue(web.asked.size > asked)
    }

    @Test
    fun aFileThatCantBeReadIsNoAllClear() = runTest {
        val web = Web { Answer(status = HttpStatusCode.BadGateway) }
        val report = web.repository.chainControls(road)
        assertTrue(report.controls.isEmpty())
        assertFalse(report.complete)
    }

    @Test
    fun aFailureKeepsWhatWasInForceAndTriesAgainSoon() = runTest {
        var up = true
        val web = Web { if (up) Answer(chainFile("R-2", 39.6, -120.575)) else Answer(status = HttpStatusCode.BadGateway) }
        web.repository.chainControls(road)
        up = false
        web.clock.seconds += 660
        val failing = web.repository.chainControls(road)
        assertEquals("I-80", failing.controls.first().route)
        assertFalse(failing.complete)

        val asked = web.asked.size
        web.repository.chainControls(road)
        assertEquals(asked, web.asked.size, "every resort on the road doesn't wait on the same dead file")

        up = true
        web.clock.seconds += 61
        assertTrue(web.repository.chainControls(road).complete)
    }
}
