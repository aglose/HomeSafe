package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.weather.ski.data.ResortPages
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The resorts' own sites, read from what they really sent on 1 November 2026, cut down. */
class ResortPagesTest {
    private val pacific = TimeZone.of("America/Los_Angeles")
    private val url = "https://resort.example/report"

    private fun inches(value: Double) = value * 2.54

    private fun epoch(iso: String) = Instant.parse(iso).epochSeconds

    // ---- Vail ---------------------------------------------------------------------------------------

    private val vailSnow = """
        <script>FR.snowReportSettings = {"Show12Hr":false};
        FR.snowReportData = {"OverallSnowConditions":"Early Season","OvernightSnowfall":{"Inches":"1","Centimeters":"2"},"TwentyFourHourSnowfall":{"Inches":"8","Centimeters":"20"},"FortyEightHourSnowfall":{"Inches":"9","Centimeters":"22"},"SevenDaySnowfall":{"Inches":"10","Centimeters":"25"},"BaseDepth":{"Inches":"18","Centimeters":"45"},"CurrentSeason":{"Inches":"12","Centimeters":"30"},"LastUpdatedText":"Updated November 01, 2026 at 10:20 AM PST"};
        FR.unitsOfMeasurement = 1;</script>
    """.trimIndent()

    private val moreTrails = (1..9).joinToString(",") { """{"Name":"Run $it","IsOpen":false}""" }

    private val vailTerrain = """
        TerrainStatusFeed = {"Date":"x","ResortId":8,"GroomingAreas":[{"Id":1,"Name":"A","Trails":[{"Name":"One","IsOpen":true},{"Name":"Two","IsOpen":false},{"Name":"Three \"Quoted\"","IsOpen":false},$moreTrails],"Lifts":[]},{"Id":2,"Name":"B","Trails":[],"Lifts":[]}],"Lifts":[{"Name":"Gondola","Status":3},{"Name":"Express","Status":1},{"Name":"Hold","Status":2},{"Name":"Shut","Status":0},{"Name":"Who knows","Status":4}],"IsSuccessful":true};
    """.trimIndent()

    @Test
    fun readsAVailReport() {
        val news = assertNotNull(ResortPages.vailReport(vailSnow, vailTerrain, url, pacific))
        assertEquals(inches(8.0), news.last24Cm)
        assertEquals(inches(9.0), news.last48Cm)
        assertEquals(inches(10.0), news.last7DaysCm)
        assertEquals(inches(12.0), news.seasonCm)
        assertEquals(inches(18.0), news.baseDepthCm)
        assertEquals("Early Season", news.conditions)
        assertEquals(url, news.pageUrl)
        assertEquals(epoch("2026-11-01T18:20:00Z"), news.updatedAtEpochSeconds, "10:20 AM in California, after the clocks went back")
    }

    @Test
    fun countsVailsLiftsAndRuns() {
        val news = assertNotNull(ResortPages.vailReport(vailSnow, vailTerrain, url, pacific))
        assertEquals(3, news.liftsOpen, "open, on hold and scheduled are all running today; closed and unknown aren't")
        assertEquals(5, news.liftsTotal)
        assertEquals(1, news.runsOpen)
        assertEquals(12, news.runsTotal)
    }

    @Test
    fun aSummerTrailIsntAMountainsRuns() {
        val terrain = """TerrainStatusFeed = {"GroomingAreas":[{"Trails":[{"Name":"Bike path","IsOpen":false}]}],"Lifts":[]};"""
        val news = assertNotNull(ResortPages.vailReport(vailSnow, terrain, url, pacific))
        assertNull(news.runsTotal)
        assertNull(news.runsOpen)
    }

    @Test
    fun aVailReportWithoutItsTerrainPageStillHasItsSnow() {
        val news = assertNotNull(ResortPages.vailReport(vailSnow, null, url, pacific))
        assertEquals(inches(8.0), news.last24Cm)
        assertNull(news.liftsOpen)
    }

    @Test
    fun anEmptyConditionIsNoCondition() {
        val news = assertNotNull(ResortPages.vailReport("""FR.snowReportData = {"OverallSnowConditions":"","BaseDepth":{"Inches":"0"}};""", null, url, pacific))
        assertNull(news.conditions)
        assertEquals(0.0, news.baseDepthCm, "a reported 0 is a report")
    }

    @Test
    fun aPageWithoutTheReportIsntOne() {
        assertNull(ResortPages.vailReport("<html>Down for maintenance</html>", null, url, pacific))
    }

    // ---- The feed -----------------------------------------------------------------------------------

    private val feed = """
        {"Resorts":[{"Name":"Mammoth","OperatingStatus":"Closed","SnowReport":{"SeasonTotalIn":"12","SeasonTotalCm":"30","BaseConditions":"Variable Conditions","TotalOpenLifts":0,"TotalLifts":25,"TotalOpenTrails":2,"TotalTrails":181,"LastUpdate":"2026-11-01T07:15:35-0800",
        "BaseArea":{"Name":"Main Lodge","BaseIn":"4","Last24HoursIn":"9","Last48HoursIn":"0","Last72HoursIn":"0","Last7DaysIn":"--","Last24HoursCm":"23"},
        "MidMountainArea":{"Name":"McCoy","BaseIn":"6","Last24HoursIn":"12","Last48HoursIn":"0","Last72HoursIn":"0","Last7DaysIn":"--"},
        "SummitArea":{"Name":"The Summit","BaseIn":"--","BaseCm":"0","Last24HoursIn":"15","Last48HoursIn":"0","Last72HoursIn":"0","Last7DaysIn":"--","Last7DaysCm":"0"}}}]}
    """.trimIndent()

    @Test
    fun readsAFeedReportAsTheMostAnyAreaHad() {
        val news = assertNotNull(ResortPages.feedReport(feed, url))
        assertEquals(inches(15.0), news.last24Cm)
        assertEquals(inches(12.0), news.seasonCm)
        assertEquals(inches(4.0), news.baseDepthCm)
        assertEquals(inches(6.0), news.middleDepthCm)
        assertEquals("Variable Conditions", news.conditions)
        assertEquals(25, news.liftsTotal)
        assertEquals(2, news.runsOpen)
        assertEquals(false, news.running)
        assertEquals(epoch("2026-11-01T15:15:35Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun aDashIsNothingReportedNotZero() {
        val news = assertNotNull(ResortPages.feedReport(feed, url))
        assertNull(news.last7DaysCm, "every area says --, with its centimetres defaulting to 0")
        assertNull(news.summitDepthCm)
    }

    @Test
    fun aFeedWithNoResortIsNoReport() {
        assertNull(ResortPages.feedReport("""{"Resorts":[]}""", url))
        assertNull(ResortPages.feedReport("not json", url))
    }

    // ---- POWDR ------------------------------------------------------------------------------------

    @Test
    fun readsAPowdrReport() {
        val reports = """[{"date":"2026-11-01 06:00:00","overnight_amount":5,"base_depth":5,"unit_of_measurement":"in","updated":1793567560,"computed":{"24_hour":5,"48_hour":6,"72_hour":7,"7_day":8,"30_day":9,"month":5,"total":10}}]"""
        val status = """{"sector":[],"lift":[{"status":"open"},{"status":"closed"}],"trail":[{"status":"closed"},{"status":"Open"},{"status":"closed"}]}"""
        val news = assertNotNull(ResortPages.powdrReport(reports, status, url))
        assertEquals(inches(5.0), news.last24Cm)
        assertEquals(inches(7.0), news.last72Cm)
        assertEquals(inches(10.0), news.seasonCm)
        assertEquals(inches(5.0), news.baseDepthCm)
        assertEquals(1, news.liftsOpen)
        assertEquals(2, news.liftsTotal)
        assertEquals(1, news.runsOpen)
        assertEquals(1793567560, news.updatedAtEpochSeconds)
    }

    @Test
    fun noPowdrReportsIsNoReport() {
        assertNull(ResortPages.powdrReport("[]", null, url))
    }

    @Test
    fun aPowdrDayTicketIsAStartingPrice() {
        val data = """{"title":"Go-Time Lift Tickets","url":"https://book.example","buttonText":"Buy Now","price":"${'$'}99","priceDescription":"(starting at)"},{"title":"Tubing","price":"${'$'}59","priceDescription":"(starting at)"}"""
        val tier = ResortPages.powdrPrices(data).single()
        assertEquals(99.0, tier.price.adultOnline)
        assertTrue(tier.onlineIsLowest)
    }

    // ---- Plain pages ----------------------------------------------------------------------------------

    @Test
    fun readsSugarBowl() {
        val html = """<div>Last Updated: 04/05/2026 03:57 PM</div> 64 &deg; Sunny <b>2</b> /11 Lifts Open 9 /105 Trails Open 0 /105 Trails Groomed Mountain Status Closed Surface Conditions Variable / Groomed Road Conditions I-80 Open Summit 8,383' 40 &quot; Base Depth 100 &quot; Year to Date 64 ° Sunny Snowfall Totals 1 &quot; Overnight Daytime 2 &quot; 24 Hour 3 &quot; 7-Day Base 6,887' 20 &quot; Base Depth 0 &quot; Year to Date 64 ° Snowfall Totals 0 &quot; Overnight Daytime 0 &quot; 24 Hour 0 &quot; 7-Day"""
        val news = assertNotNull(ResortPages.sugarBowlReport(html, url, pacific))
        assertEquals(inches(2.0), news.last24Cm, "the summit's, which is listed first")
        assertEquals(inches(3.0), news.last7DaysCm)
        assertEquals(inches(100.0), news.seasonCm)
        assertEquals(inches(40.0), news.summitDepthCm)
        assertEquals(inches(20.0), news.baseDepthCm)
        assertEquals(2, news.liftsOpen)
        assertEquals(11, news.liftsTotal)
        assertEquals(9, news.runsOpen)
        assertEquals("Variable / Groomed", news.conditions)
        assertEquals(false, news.running)
        assertEquals(epoch("2026-04-05T22:57:00Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun readsSierra() {
        val html = """Powered by OpenSnow 0″ 24 Hour Snowfall Summit Base 12 Hour 1″ 0″ 24 Hour 2″ 1″ 48 Hour 3″ 2″ 7-Day 4″ 3″ Depth 20″ 10″ YTD – – 33° High 35° Low 25° Wind 4 mph Lifts Open 3 /14 Runs Open 7 /50 Runs Groomed 0 /50 Fri Oct 16, 2026 7:45 AM Snow Reporter’s Message Our season"""
        val news = assertNotNull(ResortPages.sierraReport(html, url, pacific))
        assertEquals(inches(2.0), news.last24Cm)
        assertEquals(inches(3.0), news.last48Cm)
        assertEquals(inches(4.0), news.last7DaysCm)
        assertEquals(inches(20.0), news.summitDepthCm)
        assertEquals(inches(10.0), news.baseDepthCm)
        assertNull(news.seasonCm, "a dash isn't a figure")
        assertEquals(3, news.liftsOpen)
        assertEquals(50, news.runsTotal)
        assertEquals(epoch("2026-10-16T14:45:00Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun readsMtRosesRanges() {
        val xml = """
            <Skirose><Updated_Time>October 13, 2026 8:12 am</Updated_Time>
            <Snow_Total><New_Snow>2-4"</New_Snow><Storm_Total></Storm_Total><Base_Depth>30-44"</Base_Depth><Season_Total>228-284”</Season_Total></Snow_Total>
            <Lift_Status><Northwest_Express>Open</Northwest_Express><Zephyr_Express>Closed</Zephyr_Express></Lift_Status></Skirose>
        """.trimIndent()
        val news = assertNotNull(ResortPages.roseReport(xml, url, pacific))
        assertEquals(inches(4.0), news.last24Cm)
        assertEquals(inches(284.0), news.seasonCm)
        assertEquals(inches(30.0), news.baseDepthCm)
        assertEquals(inches(44.0), news.summitDepthCm)
        assertEquals(1, news.liftsOpen)
        assertEquals(2, news.liftsTotal)
        assertEquals(epoch("2026-10-13T15:12:00Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun aDashInMtRosesFeedIsNoNewSnow() {
        val xml = """<Skirose><Updated_Time>October 13, 2026 8:12 am</Updated_Time><New_Snow>-</New_Snow><Base_Depth>30-44"</Base_Depth></Skirose>"""
        assertNull(ResortPages.roseReport(xml, url, pacific)?.last24Cm)
    }

    @Test
    fun readsHomewood() {
        val html = """Snow Report ❄ 12 hr ❄ 24 hr 📏 Current Depth 🏔 Season Total Base 0.5 in 1.0 in 11.0 in 2.0 in Summit 1.5 in 3.0 in 17.0 in 4.0 in Weather Forecast Snow Forecast: 4.7 in"""
        val news = assertNotNull(ResortPages.homewoodReport(html, url))
        assertEquals(inches(3.0), news.last24Cm)
        assertEquals(inches(17.0), news.summitDepthCm)
        assertEquals(inches(11.0), news.baseDepthCm)
        assertEquals(inches(4.0), news.seasonCm)
        assertNull(news.updatedAtEpochSeconds, "the page has no date")
    }

    @Test
    fun readsDonnerSkiRanch() {
        val html = """Updated 11/01/2026 Chair 1: Open Chair 2: Closed Ski Moving Carpet: Closed View the trail map 24-hour snow total: 3 in Season total: 4 in Chairlift Hours"""
        val news = assertNotNull(ResortPages.donnerRanchReport(html, url, pacific))
        assertEquals(inches(3.0), news.last24Cm)
        assertEquals(inches(4.0), news.seasonCm)
        assertEquals(1, news.liftsOpen)
        assertEquals(2, news.liftsTotal)
        assertNotNull(news.updatedAtEpochSeconds)
    }

    @Test
    fun readsDonnerSkiRanchsRates() {
        val html = """Pricing 2025 - 2026 Lift Tickets All Day 9 am to 4 pm Half Day 12:30 pm to 4:00 pm Old School Days Adult (18-69) ${'$'}119 ${'$'}99 ${'$'}69 Youth (13-17) ${'$'}109 ${'$'}89 ${'$'}69 Child (6-12) ${'$'}69 ${'$'}59 ${'$'}39"""
        val tier = ResortPages.donnerRanchPrices(html).single()
        assertEquals(119.0, tier.price.adultWindow)
        assertEquals(69.0, tier.price.childWindow)
        assertNull(tier.price.adultOnline, "it only sells at the window")
    }

    @Test
    fun readsTahoeDonner() {
        val html = """Snow Report 1 inches 24 HRS 2 inches 48 HRS 3 inches BASE Report updated: Sun Nov 1 | 4:40 pm Current Weather"""
        val news = assertNotNull(ResortPages.tahoeDonnerReport(html, url, pacific, epoch("2026-11-02T00:00:00Z")))
        assertEquals(inches(1.0), news.last24Cm)
        assertEquals(inches(2.0), news.last48Cm)
        assertEquals(inches(3.0), news.baseDepthCm)
        assertEquals(epoch("2026-11-02T00:40:00Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun tahoeDonnersDateIsLastYearsIfThisYearsHasntHappened() {
        val html = """Snow Report 1 inches 24 HRS 2 inches 48 HRS 3 inches BASE Report updated: Fri Mar 13 | 8:00 am"""
        val news = assertNotNull(ResortPages.tahoeDonnerReport(html, url, pacific, epoch("2026-01-15T00:00:00Z")))
        assertEquals(epoch("2025-03-13T15:00:00Z"), news.updatedAtEpochSeconds)
    }

    @Test
    fun readsDiamondPeak() {
        val html = """Storm Total Depth 0" Base 0" Peak 0" Season Total View Full Report Mountain Report New Snow Overnight 1 Inches New Snow 24H Total 2 Inches New Snow Storm Total 3 Inches Base Depth 4 Inches Peak Depth 5 Inches Season Total 6 Inches Lift & Trail Status"""
        val news = assertNotNull(ResortPages.diamondPeakReport(html, url))
        assertEquals(inches(2.0), news.last24Cm)
        assertEquals(inches(4.0), news.baseDepthCm)
        assertEquals(inches(5.0), news.summitDepthCm)
        assertEquals(inches(6.0), news.seasonCm)
    }

    @Test
    fun readsDodgeRidge() {
        val html = """DAILY Snow Report Snowfall Totals 24 hrs 1″ 72 hrs 2″ Season Total 3″ 26/27 Preseason update"""
        val news = assertNotNull(ResortPages.dodgeRidgeReport(html, url))
        assertEquals(inches(1.0), news.last24Cm)
        assertEquals(inches(2.0), news.last72Cm)
        assertEquals(inches(3.0), news.seasonCm)
    }

    @Test
    fun readsChinaPeak() {
        val html = """Snow Totals: Last 24 Hours: 4" Last 7 Days: 5" Season Total: 6" Lift Status Chair 1: CLOSED Academy - Upper C Chair 2: OPEN"""
        val news = assertNotNull(ResortPages.chinaPeakReport(html, url))
        assertEquals(inches(4.0), news.last24Cm)
        assertEquals(inches(5.0), news.last7DaysCm)
        assertEquals(inches(6.0), news.seasonCm)
        assertEquals(1, news.liftsOpen)
        assertEquals(2, news.liftsTotal)
    }

    @Test
    fun readsChinaPeaksRates() {
        val html = """SINGLE DAY PASSES ADULT AGES 13-69 Pricing as low as ${'$'}109 (early/late season as low as ${'$'}99) RESORT RATE - Up to ${'$'}179 CHILD AGES 6-12 Pricing as low as ${'$'}59 (early/late season as low as ${'$'}49) RESORT RATE - Up to ${'$'}79 SENIOR"""
        val tier = ResortPages.chinaPeakPrices(html).single()
        assertEquals(109.0, tier.price.adultOnline)
        assertEquals(179.0, tier.price.adultWindow)
        assertEquals(59.0, tier.price.childOnline)
        assertEquals(79.0, tier.price.childWindow)
        assertTrue(tier.onlineIsLowest)
    }

    @Test
    fun readsSugarBowlsRates() {
        val html = """Sun-Fri Online Starting Rates Sun-Fri Window Rates Sat+Holiday Online Starting Rates Sat+Holiday Window Rates Adult (23-64) ${'$'}89 ${'$'}189 ${'$'}114 ${'$'}249 Young Adult (13-22) ${'$'}77 ${'$'}149 ${'$'}90 ${'$'}189 Senior (65-74) ${'$'}84 ${'$'}159 ${'$'}96 ${'$'}199 Child (6-12) ${'$'}49 ${'$'}109 ${'$'}66 ${'$'}139 Super Senior (75+) ${'$'}49 ${'$'}109"""
        val tiers = ResortPages.sugarBowlPrices(html)
        val weekday = tiers.first { it.kind == SkiPriceTier.Kind.WEEKDAY }.price
        val weekend = tiers.first { it.kind == SkiPriceTier.Kind.WEEKEND }.price
        assertEquals(89.0, weekday.adultOnline)
        assertEquals(189.0, weekday.adultWindow)
        assertEquals(49.0, weekday.childOnline)
        assertEquals(249.0, weekend.adultWindow)
        assertEquals(139.0, weekend.childWindow)
        assertTrue(tiers.all { it.onlineIsLowest }, "these are the rates they start at")
    }

    @Test
    fun readsSierrasRates() {
        val html = """2026/27 LIFT TICKET RATES Age Range Single Day Ticket Non-Peak/Peak Lift Ticket 3-PAK* 2-of-3 Day Ticket** Afternoon Ticket (12:30-4 PM) Non-Peak/Peak Adult (23-64) ${'$'}167/${'$'}191 ${'$'}97/day ${'$'}145/day ${'$'}147/${'$'}166 Young Adult (13-22) ${'$'}152/${'$'}176 ${'$'}91/day Child (5-12) ${'$'}70/79 ${'$'}70/day"""
        val tiers = ResortPages.sierraPrices(html)
        assertEquals(167.0, tiers.first { it.kind == SkiPriceTier.Kind.NON_PEAK }.price.adultWindow)
        assertEquals(191.0, tiers.first { it.kind == SkiPriceTier.Kind.PEAK }.price.adultWindow)
        assertEquals(70.0, tiers.first { it.kind == SkiPriceTier.Kind.NON_PEAK }.price.childWindow)
        assertEquals(79.0, tiers.first { it.kind == SkiPriceTier.Kind.PEAK }.price.childWindow, "one of its dollar signs is missing on the page")
    }

    @Test
    fun aPageThatHasntPublishedRatesHasNone() {
        assertTrue(ResortPages.sugarBowlPrices("Coming soon").isEmpty())
        assertTrue(ResortPages.sierraPrices("Coming soon").isEmpty())
        assertTrue(ResortPages.chinaPeakPrices("Coming soon").isEmpty())
        assertTrue(ResortPages.donnerRanchPrices("Coming soon").isEmpty())
    }

    // ---- The browser's answers --------------------------------------------------------------------

    @Test
    fun readsDatedPrices() {
        val days = ResortPages.datedPrices("""{"2026-12-26":[205,284,144,199],"2027-02-09":[139,219,null,null],"not a date":[1,2,3,4],"2027-03-01":[null,null,null,null],"2027-03-02":[1,2]}""")
        assertEquals(setOf(20_813, 20_858), days.keys)
        assertEquals(205.0, days.getValue(20_813).adultOnline)
        assertEquals(199.0, days.getValue(20_813).childWindow)
        assertNull(days.getValue(20_858).childOnline)
    }

    @Test
    fun anUnreadableAnswerIsNoPrices() {
        assertTrue(ResortPages.datedPrices("{}").isEmpty())
        assertTrue(ResortPages.datedPrices("nope").isEmpty())
    }

    // ---- Small things -------------------------------------------------------------------------------

    @Test
    fun takesAPageApartIntoWhatsRead() {
        assertEquals("a \" b ° c", ResortPages.textOf("""<p>a</p><script>var x = 1;</script> &quot;<style>.x{}</style> b &deg; <b>c</b>"""))
        assertEquals("8″", ResortPages.textOf("8&#8243;"))
    }

    @Test
    fun aNoonBeyondTheClockIsNoDate() {
        assertNull(ResortPages.slashDate("13/40/2026 03:57 PM", pacific))
        assertFalse(ResortPages.slashDate("04/05/2026 12:30 AM", pacific) == ResortPages.slashDate("04/05/2026 12:30 PM", pacific))
    }
}
