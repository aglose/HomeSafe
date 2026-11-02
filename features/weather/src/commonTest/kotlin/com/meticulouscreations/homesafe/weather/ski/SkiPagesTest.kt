package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.weather.data.weatherJson
import com.meticulouscreations.homesafe.weather.ski.data.ChainLocationDto
import com.meticulouscreations.homesafe.weather.ski.data.ChainPointDto
import com.meticulouscreations.homesafe.weather.ski.data.ChainStatusDto
import com.meticulouscreations.homesafe.weather.ski.data.GeoBox
import com.meticulouscreations.homesafe.weather.ski.data.MountainDto
import com.meticulouscreations.homesafe.weather.ski.data.OnTheSnowPages
import com.meticulouscreations.homesafe.weather.ski.data.boxAround
import com.meticulouscreations.homesafe.weather.ski.data.decodePolyline
import com.meticulouscreations.homesafe.weather.ski.data.passesNear
import com.meticulouscreations.homesafe.weather.ski.domain.ChainLevel
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Ski tab's readers against the shapes the services really send, cut down from what they sent on 1 November 2026. */
class SkiPagesTest {
    /** Rows as a Next.js page streams them: split across pushes at any point, with references between them. */
    private fun page(vararg rows: String): String {
        val stream = rows.joinToString("\n") + "\n"
        val middle = stream.length / 2
        fun push(text: String) = "<script>self.__next_f.push([1,\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"])</script>"
        return "<html><script>self.__next_f.push([0])</script>" + push(stream.substring(0, middle)) + push(stream.substring(middle)) + "</html>"
    }

    private val regionPage = page(
        """1:{"uuid":"419","title":"Wolf Creek","slug":"wolf-creek","region":"colorado","snow":"$2","lifts":"$3","runs":"$4","status":"$5","updatedAt":"2026-11-01T13:11:00+00:00","forecast":"$6"}""",
        """2:{"base":43.18,"middle":43.18,"summit":null,"last24":0,"last48":12.7,"last72":12.7}""",
        """3:{"open":3,"total":11,"details":null}""",
        """4:{"open":43,"openPercent":30,"total":144,"details":null}""",
        """5:{"openFlag":3,"openingDate":"2026-10-31","closingDate":"2027-04-04"}""",
        """6:["$7","$8"]""",
        """7:{"date":"2026-11-01","snow":21.2265}""",
        """8:{"date":"2026-11-02","snow":0}""",
        // The same resort again, in a banner with no forecast: the fuller one wins.
        """a:{"title":"Wolf Creek","slug":"wolf-creek","region":"colorado","snow":"$2","lifts":"$3","runs":"$4","status":"$5"}""",
        """b:{"title":"Alta","slug":"alta","region":"utah","snow":{"base":null,"last24":0},"lifts":{"open":null,"total":6},"status":{"openFlag":2,"openingDate":"2026-11-20"}}""",
        // Not a resort, for all that it mentions a slug.
        """c:{"slug":"colorado","title":"Colorado","lifts":0}""",
    )

    @Test
    fun readsARegionsResorts() {
        val reports = OnTheSnowPages.reports(regionPage)
        assertEquals(listOf("alta", "wolf-creek"), reports.map { it.slug })
        val wolf = reports.last()
        assertEquals("colorado", wolf.regionSlug)
        assertEquals(43.18, wolf.baseDepthCm)
        assertNull(wolf.summitDepthCm)
        assertEquals(12.7, wolf.last48Cm)
        assertEquals(3, wolf.liftsOpen)
        assertEquals(144, wolf.runsTotal)
        assertEquals(SkiOpenState.LIMITED, wolf.state)
        assertEquals(20_757, wolf.openingEpochDay, "31 October 2026")
        assertEquals(2, wolf.outlook.size)
        assertEquals(21.2265, wolf.outlook.first().cm)
        assertNotNull(wolf.updatedAtEpochSeconds)
    }

    @Test
    fun whatAResortHasntSaidStaysUnsaid() {
        val alta = OnTheSnowPages.reports(regionPage).first()
        assertNull(alta.baseDepthCm)
        assertNull(alta.liftsOpen)
        assertEquals(SkiOpenState.CLOSED, alta.state)
    }

    @Test
    fun aPageThatChangedShapeReadsAsEmpty() {
        assertTrue(OnTheSnowPages.reports("<html>nothing like it</html>").isEmpty())
    }

    @Test
    fun readsListedPrices() {
        val prices = OnTheSnowPages.prices(
            page(
                """1:{"uuid":"1","slug":"mammoth","title":"Mammoth"}""",
                """2:{"resort":"$1","currency":"US$","seasonPassAdult":"1,678.00","seasonPassChild":"379.00","dailyPassAdult":"189.00","dailyPassChild":"0.00","purchaseUrl":"https://example.com/tickets?utm_source=OnTheSnow&utm_medium=RMP"}""",
                """3:{"uuid":"2","slug":"bear","title":"Bear"}""",
                """4:{"resort":"$3","currency":"US$","seasonPassAdult":"739.00","dailyPassAdult":null,"dailyPassChild":null,"purchaseUrl":null}""",
            ),
        )
        val mammoth = prices.getValue("mammoth")
        assertEquals(1_678.0, mammoth.adultSeason)
        assertEquals(189.0, mammoth.adultDay)
        assertNull(mammoth.childDay, "a listed 0.00 is no price, not a free ticket")
        assertEquals("https://example.com/tickets", mammoth.purchaseUrl)
        assertNull(prices.getValue("bear").adultDay)
    }

    @Test
    fun readsAResortsOwnPage() {
        val html = page(
            // A resort nearby, in the same shape: this page isn't about it.
            """0:{"uuid":"451","title":"Tahoe Donner","slug":"tahoe-donner","latitude":39.35,"longitude":-120.25,"region":"$2","website":"https://donner.example/","snow":"$3","lifts":"$4","runs":"$5","status":"$6","webcams":[]}""",
            """1:{"uuid":"419","title":"Palisades Tahoe","slug":"palisades-tahoe","latitude":39.19,"longitude":-120.23,"physicalCity":"Olympic Valley","region":"$2","website":"https://www.palisadestahoe.com/?utm_source=onthesnow","liftsUrl":"http:///www.example.com/tickets/","phone":"1-800-403-0206","snow":"$3","lifts":"$4","runs":"$5","status":"$6","updatedAt":"2026-11-01T20:02:25+00:00","depths":"$7","terrain":"$8","recent":"$9","forecast":[],"webcams":"$10"}""",
            """2:{"uuid":"311","slug":"california","title":"California"}""",
            """3:{"base":0,"middle":0,"summit":null,"last24":0,"last48":0,"last72":0}""",
            """4:{"open":0,"total":39,"details":null}""",
            """5:{"open":0,"openPercent":0,"total":296,"details":null}""",
            """6:{"openFlag":2,"openingDate":"2026-11-25","closingDate":"2027-05-23"}""",
            """7:{"base":12.5,"middle":0,"summit":null}""",
            """8:{"acres":{"open":0,"total":6000},"runsExpert":{"total":66}}""",
            """9:[{"date":"2026-05-20","snow":3.5}]""",
            """10:[{"uuid":"1","title":"Far East Cam","date":"2026-11-01T20:33:01+00:00","image":"https://cams.example.com/xl.jpg","thumbnail":"https://cams.example.com/la.jpg"},{"title":"Video only","image":null}]""",
            """b:{"runs":"$5","lifts":"$4","elevation":{"summit":2758.44,"verticalDrop":868.68,"base":1889.76},"terrain":"$8"}""",
        )
        val resort = assertNotNull(OnTheSnowPages.resortPage(html, "palisades-tahoe", "california", 99L))
        assertNull(OnTheSnowPages.resortPage(html, "somewhere-else", "california", 99L))
        assertEquals("Palisades Tahoe", resort.report.name)
        assertEquals(39.19, resort.facts.latitude)
        assertEquals(2_758.44, resort.facts.summitElevationM)
        assertEquals(1_889.76, resort.facts.baseElevationM)
        assertEquals(6_000, resort.facts.acres)
        assertEquals(66, resort.facts.runsExpert)
        assertEquals("https://www.palisadestahoe.com/", resort.facts.websiteUrl)
        assertEquals("http://www.example.com/tickets/", resort.facts.ticketsUrl)
        assertEquals("california", resort.report.regionSlug)
        assertEquals(12.5, resort.report.baseDepthCm, "the page's depths are newer than the summary")
        assertEquals(listOf("Far East Cam"), resort.webcams.map { it.title })
        assertEquals(3.5, resort.recentSnow.single().cm)
    }

    @Test
    fun cleansUrlsTypedByHand() {
        assertEquals("http://www.donner.example/tickets/", OnTheSnowPages.cleanUrl("http:///www.donner.example/tickets/"))
        assertNull(OnTheSnowPages.cleanUrl("javascript:alert(1)"))
        assertNull(OnTheSnowPages.cleanUrl("not a url"))
    }

    // ---- Open-Meteo ------------------------------------------------------------------------------

    @Test
    fun theForecastTakesItsRangeFromTheModels() {
        val summit = weatherJson.decodeFromString<MountainDto>(
            """
            {"utc_offset_seconds":-28800,"timezone":"America/Los_Angeles",
             "daily":{"time":[1000,2000,3000],
               "weather_code_best_match":[71,3,null],
               "temperature_2m_max_best_match":[-2.0,1.0,null],
               "temperature_2m_min_best_match":[-8.0,-4.0,null],
               "snowfall_sum_best_match":[10.0,0.0,null],
               "snowfall_sum_ecmwf_ifs025":[4.0,0.0,1.0],
               "snowfall_sum_gfs_seamless":[18.0,null,2.0],
               "wind_gusts_10m_max_best_match":[70.0,20.0,null]},
             "hourly":{"time":[1000,4600,8200],
               "temperature_2m_best_match":[-3.0,-4.0,null],
               "snowfall_best_match":[0.0,1.5,2.5],
               "freezing_level_height_best_match":[1900.0,null,1800.0]}}
            """.trimIndent(),
        )
        val base = weatherJson.decodeFromString<MountainDto>(
            """{"daily":{"time":[2000],"temperature_2m_max":[8.0],"temperature_2m_min":[1.0],"rain_sum":[3.0],"showers_sum":[1.5],"snowfall_sum":[0.0]}}""",
        )
        val forecast = assertNotNull(summit.toForecast(base, 42L))
        assertEquals(2, forecast.days.size, "a day beyond the model's reach is left out")
        val first = forecast.days[0]
        assertEquals(10.0, first.snowCm)
        assertEquals(4.0, first.snowLowCm)
        assertEquals(18.0, first.snowHighCm)
        assertEquals(70.0, first.gustMaxKmh)
        assertNull(first.baseRainMm, "the base has nothing for that day")
        assertEquals(4.5, forecast.days[1].baseRainMm)
        assertEquals(8.0, forecast.days[1].baseHighC)
        // An amount is stamped at the end of its hour: the hour starting at 1000 got the 1.5.
        assertEquals(1.5, forecast.hours[0].snowCm)
        assertEquals(2, forecast.hours.size)
        assertEquals(1900.0, forecast.hours[0].freezingLevelM)
    }

    @Test
    fun anAnswerWithNoDaysIsNoForecast() {
        assertNull(weatherJson.decodeFromString<MountainDto>("""{"daily":{"time":[]}}""").toForecast(null, 1L))
        assertNull(weatherJson.decodeFromString<MountainDto>("{}").toForecast(null, 1L))
    }

    // ---- The road --------------------------------------------------------------------------------

    @Test
    fun decodesARoad() {
        // Google's own worked example.
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude)
        assertEquals(-120.2, points[0].longitude)
        assertEquals(43.252, points[2].latitude)
        assertEquals(-126.453, points[2].longitude)
    }

    @Test
    fun aCutOffRoadStopsWhereItWasCut() {
        assertEquals(1, decodePolyline("_p~iF~ps|U_ul").size)
        assertTrue(decodePolyline("").isEmpty())
    }

    @Test
    fun aCheckpointCountsIfTheRoadPassesIt() {
        val road = decodePolyline("_p~iF~ps|U_ulLnnqC")
        // Halfway along the leg, off by a few hundred metres.
        val midLatitude = (road[0].latitude + road[1].latitude) / 2
        val midLongitude = (road[0].longitude + road[1].longitude) / 2
        assertTrue(passesNear(road, midLatitude + 0.003, midLongitude))
        assertFalse(passesNear(road, midLatitude + 0.2, midLongitude), "about 22 km off is another road")
        assertFalse(passesNear(road.take(1), road[0].latitude, road[0].longitude), "a single point isn't a road")
    }

    @Test
    fun aDistrictIsFetchedOnlyIfTheRoadRunsThroughIt() {
        val box = assertNotNull(boxAround(decodePolyline("_p~iF~ps|U_ulLnnqC")))
        assertTrue(GeoBox(38.0, 39.0, -121.0, -120.0).overlaps(box))
        assertFalse(GeoBox(33.0, 34.0, -118.0, -117.0).overlaps(box))
    }

    @Test
    fun onlyAChainControlInForceCounts() {
        fun point(status: String, inService: String = "true") = ChainPointDto(
            location = ChainLocationDto(locationName = "Donner Summit", route = "I-80", direction = "East", latitude = "39.33", longitude = "-120.34"),
            inService = inService,
            statusData = ChainStatusDto(status, "Chains required."),
        )
        assertNull(point("R-0").toControl())
        assertNull(point("R-2", inService = "false").toControl())
        val control = assertNotNull(point("R-2").toControl())
        assertEquals(ChainLevel.R2, control.level)
        assertEquals("I-80", control.route)
        assertEquals("Donner Summit", control.place)
        assertEquals(39.33, control.latitude)
    }
}
