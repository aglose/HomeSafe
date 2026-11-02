package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.text.KeyedTextLoader
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ski.domain.SkiAlerts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNotice
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNoticePlanner
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNoticePush
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The relay's decisions as the phone reads them, in the words `ski_data` in relay/relay.py sends them. */
class SkiNoticePlannerTest {
    private val units = WeatherUnits()
    private val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)
    private val snow = """{"k":"snow","d":20764,"r":[{"n":"Alpha","s":"alpha","cm":45.7,"lo":30.0,"hi":61.0}],"m":0,"x":1794000000}"""
    private val several = """{"k":"snow","d":20764,"r":[{"n":"Alpha","s":"alpha","cm":45.7},{"n":"Beta","s":"beta","cm":40.0}],"m":2,"x":1794000000}"""
    private val fresh = """{"k":"fresh","d":20760,"r":[{"n":"Alpha","s":"alpha","cm":25.4}],"m":0,"x":1794000000}"""
    private val freshTwo = """{"k":"fresh","d":20760,"r":[{"n":"Alpha","s":"alpha","cm":25.4},{"n":"Beta","s":"beta","cm":15.2}],"m":0,"x":1794000000}"""
    private val chains = """{"k":"chains","c":[{"n":"I-80","p":"Donner Summit","l":2,"t":["Alpha","Beta"]},{"n":"US-50","p":"Echo Summit","l":1,"t":["Gamma"]}],"x":1794000000}"""

    private fun plan(
        payload: String,
        weather: WeatherNoticeSettings = WeatherNoticeSettings(),
        alerts: SkiAlerts = SkiAlerts(),
        units: WeatherUnits = this.units,
        sent: Map<String, Long> = emptyMap(),
    ): SkiNotice? = SkiNoticePlanner.notice(assertNotNull(SkiNoticePush.read(payload)), "key", weather, alerts, units, sent)

    private suspend fun read(notice: SkiNotice?) = assertNotNull(notice).let { KeyedTextLoader.load(it.title) to KeyedTextLoader.load(it.body) }

    @Test
    fun aKindItsSwitchTurnedOffSaysNothing() {
        assertNull(plan(snow, alerts = SkiAlerts(bigSnow = false)))
        assertNull(plan(fresh, alerts = SkiAlerts(freshSnow = false)))
        assertNull(plan(chains, alerts = SkiAlerts(chains = false)))
    }

    @Test
    fun eachSwitchIsItsOwn() {
        assertNotNull(plan(snow, alerts = SkiAlerts(freshSnow = false, chains = false)))
        assertNotNull(plan(fresh, alerts = SkiAlerts(bigSnow = false, chains = false)))
        assertNotNull(plan(chains, alerts = SkiAlerts(bigSnow = false, freshSnow = false)))
    }

    @Test
    fun theWeathersOwnMasterSwitchIsTheirsToo() {
        assertNull(plan(snow, weather = WeatherNoticeSettings(enabled = false)))
    }

    @Test
    fun theOtherWeatherSwitchesHaveNoSay() {
        assertNotNull(plan(snow, weather = WeatherNoticeSettings(precipitationSoon = false, dailyOutlook = false, severeAlerts = false, extremes = false)))
    }

    @Test
    fun whatWasSaidIsntSaidAgain() {
        assertNull(plan(snow, sent = mapOf("key" to 1L)))
        assertNotNull(plan(snow, sent = mapOf("another" to 1L)))
    }

    @Test
    fun bigSnowNamesTheDayAndTheResort() = runTest {
        val (title, body) = read(plan(snow))
        assertTrue(title.startsWith("ski_notice_snow_title("), title)
        assertTrue("Alpha" in body, body)
    }

    @Test
    fun bigSnowLandsOnThatDayAndOnTheOneResort() {
        val notice = assertNotNull(plan(snow))
        assertEquals(20764, notice.day)
        assertEquals("alpha", notice.resort)
        assertEquals(1794000000L, notice.expiresEpochSeconds)
        assertEquals(WeatherNoticeIcon.SNOW, notice.icon)
    }

    @Test
    fun severalResortsLandOnTheTabAndSayHowManyMore() = runTest {
        val notice = assertNotNull(plan(several))
        assertNull(notice.resort)
        val body = KeyedTextLoader.load(notice.body)
        assertTrue("Alpha" in body && "Beta" in body, body)
        assertTrue("ski_notice_and_more" in body, body)
    }

    @Test
    fun theModelsRangeIsThereToOpenOutIntoWhenTheyDiffer() = runTest {
        assertTrue("ski_notice_snow_range" in KeyedTextLoader.load(assertNotNull(assertNotNull(plan(snow)).detail)))
        assertNull(assertNotNull(plan(several)).detail, "no range was sent")
    }

    @Test
    fun amountsAreInTheReadersUnits() = runTest {
        val inches = KeyedTextLoader.load(assertNotNull(plan(fresh)).body)
        val centimetres = KeyedTextLoader.load(assertNotNull(plan(fresh, units = metric)).body)
        assertTrue(inches != centimetres, "$inches, $centimetres")
    }

    @Test
    fun freshSnowAtOneResortNamesIt() = runTest {
        val notice = plan(fresh)
        val (title, _) = read(notice)
        assertEquals("ski_notice_fresh_title_one(Alpha)", title)
        assertEquals("alpha", notice?.resort)
    }

    @Test
    fun freshSnowAtSeveralCountsThem() = runTest {
        val notice = plan(freshTwo)
        val (title, body) = read(notice)
        assertTrue("ski_notice_fresh_title_many" in title, title)
        assertTrue("ski_notice_fresh_body_many" in body, body)
        assertNull(notice?.resort)
    }

    @Test
    fun chainsTitleTheWorstRoadAndListEach() = runTest {
        val notice = assertNotNull(plan(chains))
        val (title, body) = read(notice)
        assertEquals("ski_notice_chains_title_r2(I-80)", title)
        assertTrue("I-80" in body && "US-50" in body && "Donner Summit" in body && "Alpha" in body && "Gamma" in body, body)
        assertEquals(WeatherNoticeIcon.CHAINS, notice.icon)
        assertNull(notice.resort)
    }

    @Test
    fun eachLevelHasItsOwnWords() = runTest {
        for ((level, word) in listOf(1 to "r1", 2 to "r2", 3 to "r3")) {
            val payload = """{"k":"chains","c":[{"n":"I-80","p":"X","l":$level,"t":["Alpha"]}]}"""
            assertEquals("ski_notice_chains_title_$word(I-80)", read(plan(payload)).first)
        }
    }

    @Test
    fun whatANewerRelaySendsIsNotReadAsOneOfThese() {
        assertNull(SkiNoticePush.read("""{"k":"avalanche"}"""))
        assertNull(SkiNoticePush.read("not json"))
    }

    @Test
    fun aFieldANewerRelayAddsIsIgnored() {
        assertNotNull(SkiNoticePush.read("""{"k":"fresh","r":[{"n":"Alpha","s":"alpha","cm":25.4,"new":1}],"new":2}"""))
    }

    @Test
    fun nothingToNameIsNothingToSay() {
        assertNull(plan("""{"k":"snow","d":20764,"r":[]}"""))
        assertNull(plan("""{"k":"snow","r":[{"n":"Alpha","cm":40.0}]}"""))
        assertNull(plan("""{"k":"fresh","r":[]}"""))
        assertNull(plan("""{"k":"chains","c":[]}"""))
    }
}
