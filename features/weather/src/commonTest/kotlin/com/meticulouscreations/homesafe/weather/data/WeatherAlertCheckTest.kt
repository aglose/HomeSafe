package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.text.KeyedTextLoader
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticePlanner
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherSoonClaims
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_minutes_short
import homesafe.shared.generated.resources.weather_notice_place_home
import homesafe.shared.generated.resources.weather_notice_soon_body_lasting
import homesafe.shared.generated.resources.weather_notice_soon_here
import homesafe.shared.generated.resources.weather_notice_soon_here_lasting
import homesafe.shared.generated.resources.weather_now
import homesafe.shared.generated.resources.weather_story_rain_starting
import homesafe.shared.generated.resources.weather_time_pm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WeatherAlertCheckTest {

    /** Three in the afternoon on 9 October 2025: outside the quiet hours, in neither the morning nor evening outlook. */
    private val now = at(0, 15)

    private val tornado = WeatherAlert(id = "urn:tornado", event = "Tornado Warning", headline = "Tornado warning until 5 PM", severity = AlertSeverity.SEVERE)

    /** A forecast with a warning in force and rain due in half an hour. */
    private fun stormyReport() = WeatherData.report(
        now,
        alerts = listOf(tornado),
        minutely = WeatherData.slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0),
    )

    /** The relay, as far as the check can tell. Null is a relay out of reach. */
    private class FakeClaims : WeatherSoonClaims {
        val asked = mutableListOf<String>()
        var answer: WeatherSoonClaims.Claim? = WeatherSoonClaims.Claim(granted = true)

        override suspend fun claim(key: String): WeatherSoonClaims.Claim? {
            asked += key
            return answer
        }
    }

    private class Harness(scope: CoroutineScope, now: Long) {
        val dao = InMemoryWeatherDao()
        val repository = FakeWeatherRepository().apply { onName = { _, _ -> "Portland" to "OR" } }
        val fence = FakeGeofence()
        val identity = DeviceIdentityStore(InMemorySettingsDao())
        val preferences = WeatherPreferencesRepositoryImpl(dao)
        val places = WeatherPlacesRepositoryImpl(dao)
        val ledger = WeatherNoticeLedgerImpl(dao)
        val notifier = FakeWeatherNotifier()
        val scheduler = FakeWeatherCheckScheduler()
        val clock = MutableClock(now)
        val locator = WeatherLocator(fence, identity, repository, preferences)
        val muted = MutableStateFlow(false)
        private val mute = object : NotificationMute {
            override val muted: StateFlow<Boolean> = this@Harness.muted

            override fun setMuted(muted: Boolean) {
                this@Harness.muted.value = muted
            }
        }
        val claims = FakeClaims()
        val check = WeatherAlertCheck(repository, locator, places, preferences, ledger, notifier, scheduler, KeyedTextLoader, clock, scope, mute, claims)
    }

    private fun TestScope.harness(report: WeatherReport? = stormyReport()) = Harness(backgroundScope, now).apply {
        if (report != null) repository.onReport = { Result.success(report) }
    }

    private suspend fun keyed(text: UiText) = KeyedTextLoader.load(text)

    private fun pm(text: String) = UiText.of(Res.string.weather_time_pm, text)

    /** Lets what was launched on `backgroundScope` run: `advanceUntilIdle()` drives only the foreground here. */
    private suspend fun settle() {
        repeat(5) {
            repeat(20) { yield() }
            withContext(Dispatchers.Default) { delay(10) }
        }
        repeat(20) { yield() }
    }

    // ---- what the relay says ----------------------------------------------------------------

    /** What `rain_soon_data` in relay/relay.py sends: dry now, and half a millimetre a quarter-hour from half an hour on, for 45 minutes. */
    private val payload = buildString {
        val wet = listOf(0.0, 0.0, 0.5, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)
        append("""{"zone":"America/Los_Angeles","offset":-25200,"day":1,"code":3,"mm":0.0,"q0":$now,"qmm":$wet,"qcm":${wet.map { 0.0 }},""")
        append(""""h0":$now,"hmm":[0.0,0.0],"hcm":[0.0,0.0],"hp":[null,null],"hc":[3,3]}""")
    }

    private val rainKey = "soon:${at(0, 15, 30) / 3_600}"

    private suspend fun Harness.pushed(shown: Boolean = false, text: String = payload, expires: Long? = null) =
        check.onRelayPush(text, rainKey, "Rain at 3:30 PM", "Around 3:30 PM", shown, expires)

    @Test
    fun aPushedShowerIsPostedAsTheAppsOwnWithItsPictureAtHomeAndOpeningOnWhereThePhoneIs() = runTest {
        val h = harness(report = null)
        assertTrue(h.pushed())
        val posted = h.notifier.posted.single()
        assertEquals(rainKey, posted.id)
        assertEquals(keyed(UiText.of(Res.string.weather_story_rain_starting, 30)), posted.title)
        assertEquals(keyed(UiText.of(Res.string.weather_notice_soon_here_lasting, pm("3:30"), 45)), posted.body)
        assertEquals(keyed(UiText.of(Res.string.weather_notice_place_home)), posted.place)
        assertEquals(WeatherNoticeIcon.RAIN, posted.art.icon)
        assertTrue(posted.art.chart is WeatherNoticeChart.Soon)
        assertEquals(at(0, 15, 30), posted.eventEpochSeconds)
        assertEquals(WeatherDeepLink(placeId = Place.CURRENT_ID), posted.link)
        assertFalse(posted.replacesPush)
    }

    /** Word for word what `test_the_payload_is_what_the_apps_parser_is_tested_against` in relay/test_relay.py has the relay write. */
    @Test
    fun whatTheRelayWritesIsWhatThisReads() = runTest {
        val h = harness(report = null)
        val written = """{"zone":"America/Los_Angeles","offset":-25200,"day":1,"code":3,"mm":0.0,"q0":1760047200,"qmm":[0.0,0.0,0.5],"qcm":[0.0,0.0,0.0],""" +
            """"h0":1760047200,"hmm":[0.0,0.0],"hcm":[0.0,0.0],"hp":[null,null],"hc":[3,3]}"""
        assertEquals(1760047200, now)
        assertTrue(h.pushed(text = written))
        val posted = h.notifier.posted.single()
        assertEquals(keyed(UiText.of(Res.string.weather_notice_soon_here, pm("3:30"))), posted.body)
        assertEquals(at(0, 15, 30), posted.eventEpochSeconds)
    }

    @Test
    fun aPushedShowerNeedsNoForecastFromThePhone() = runTest {
        val h = harness(report = null)
        h.repository.onReport = { error("a push carries its own forecast") }
        assertTrue(h.pushed())
    }

    @Test
    fun oncePushedThePhonesOwnCheckSaysNothingOfIt() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.pushed()
        assertEquals(0, h.check.run())
        assertEquals(1, h.notifier.posted.size)
    }

    @Test
    fun aShowerThePhoneHasAlreadySaidIsntPostedAgainWhenTheRelayPushesIt() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        assertEquals(1, h.check.run())
        assertFalse(h.pushed())
        assertEquals(1, h.notifier.posted.size)
    }

    @Test
    fun aPushRespectsTheSwitchesTheMuteAndThePermission() = runTest {
        val h = harness(report = null)
        h.preferences.update { it.copy(notices = it.notices.copy(precipitationSoon = false)) }
        assertFalse(h.pushed())
        h.preferences.update { it.copy(notices = it.notices.copy(precipitationSoon = true, enabled = false)) }
        assertFalse(h.pushed())
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = true)) }
        h.muted.value = true
        assertFalse(h.pushed())
        h.muted.value = false
        h.notifier.allowed = false
        assertFalse(h.pushed())
        assertTrue(h.notifier.posted.isEmpty())
        assertTrue(h.ledger.sent(now).isEmpty())
    }

    @Test
    fun aPushThatArrivesAtNightIsHeldBackAsAnyOtherWould() = runTest {
        val h = harness(report = null)
        h.clock.seconds = at(0, 23)
        assertFalse(h.pushed())
    }

    @Test
    fun aPushAfterTheRainHasBegunSaysNothing() = runTest {
        val h = harness(report = null)
        h.clock.seconds = at(0, 15, 35)
        assertFalse(h.pushed())
    }

    /** [hour] o'clock on the clock of whatever machine runs this, which is the one an unreadable forecast goes by. */
    private fun onThisClock(hour: Int) = LocalDateTime(2025, 10, 9, hour, 0).toInstant(TimeZone.currentSystemDefault()).epochSeconds

    @Test
    fun aForecastThisBuildCantReadGoesOutAsTheRelayWordedItStillSayingHome() = runTest {
        val h = harness(report = null)
        h.clock.seconds = onThisClock(15)
        assertTrue(h.pushed(text = "a newer relay's", expires = h.clock.seconds + 3_600))
        val posted = h.notifier.posted.single()
        assertEquals(listOf(rainKey, "Rain at 3:30 PM", "Around 3:30 PM"), listOf(posted.id, posted.title, posted.body))
        assertEquals(WeatherNoticeIcon.RAIN, posted.art.icon)
        assertEquals(h.clock.seconds + 3_600, posted.expiresEpochSeconds)
        assertEquals(keyed(UiText.of(Res.string.weather_notice_place_home)), posted.place)
        assertTrue(h.ledger.sent(h.clock.seconds).containsKey(rainKey))
    }

    @Test
    fun aForecastThisBuildCantReadIsHeldBackAtNightByThisPhonesClock() = runTest {
        val h = harness(report = null)
        h.clock.seconds = onThisClock(23)
        assertFalse(h.pushed(text = "a newer relay's"))
        h.clock.seconds = onThisClock(5)
        assertFalse(h.pushed(text = "a newer relay's"))
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun aForecastThisBuildCantReadDoesntRepeatAShowerAlreadySaid() = runTest {
        val h = harness(report = null)
        h.clock.seconds = onThisClock(15)
        h.ledger.record("soon:1", h.clock.seconds - 600)
        assertFalse(h.pushed(text = "a newer relay's"))
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun aBannerIOSHasDrawnGetsItsPictureWithoutAlertingAgain() = runTest {
        val h = harness(report = null)
        assertTrue(h.pushed(shown = true))
        assertTrue(h.notifier.posted.single().replacesPush)
        assertEquals(rainKey, h.notifier.posted.single().id)
    }

    @Test
    fun aBannerIOSHasDrawnCountsAsSaidEvenWhenItCantBeImprovedOn() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.notifier.allowed = false
        assertFalse(h.pushed(shown = true))
        assertTrue(h.ledger.sent(now).containsKey(rainKey))
        h.notifier.allowed = true
        assertEquals(0, h.check.run())
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun aBannerIOSHasDrawnKeepsItsWordsWhereTheForecastCantBeRead() = runTest {
        val h = harness(report = null)
        assertFalse(h.pushed(shown = true, text = "a newer relay's"))
        assertTrue(h.notifier.posted.isEmpty())
        assertTrue(h.ledger.sent(now).containsKey(rainKey))
    }

    @Test
    fun theCheckAsksTheRelayBeforeAnnouncingAShowerAndGoesAheadWhenGranted() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        assertEquals(1, h.check.run())
        assertEquals(listOf(rainKey), h.claims.asked)
    }

    @Test
    fun aShowerTheRelayHasIsntAnnouncedAndIsAskedAboutAgainNextTime() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.claims.answer = WeatherSoonClaims.Claim(granted = false)
        assertEquals(0, h.check.run())
        assertEquals(0, h.check.run())
        assertTrue(h.notifier.posted.isEmpty())
        assertTrue(h.ledger.sent(now).isEmpty())
        assertEquals(2, h.claims.asked.size)
    }

    @Test
    fun whenTheRelaysPushFailsAndItLetsGoTheNextCheckSaysIt() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.claims.answer = WeatherSoonClaims.Claim(granted = false)
        assertEquals(0, h.check.run())
        h.claims.answer = WeatherSoonClaims.Claim(granted = true)
        assertEquals(1, h.check.run())
        assertEquals(rainKey, h.notifier.posted.single().id)
    }

    @Test
    fun aPushLandingAfterTheCheckWasToldToStayQuietIsStillPostedAndThenTheCheckStaysQuiet() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.claims.answer = WeatherSoonClaims.Claim(granted = false)
        assertEquals(0, h.check.run())
        assertTrue(h.pushed())
        assertEquals(rainKey, h.notifier.posted.single().id)
        assertEquals(0, h.check.run())
        assertEquals(1, h.notifier.posted.size)
    }

    @Test
    fun aRelayOutOfReachIsNoReasonToStayQuiet() = runTest {
        val h = harness(stormyReport().copy(alerts = emptyList()))
        h.claims.answer = null
        assertEquals(1, h.check.run())
    }

    @Test
    fun aWarningIsNobodyElsesToClaim() = runTest {
        val h = harness(stormyReport().copy(minutely = emptyList()))
        assertEquals(1, h.check.run())
        assertTrue(h.claims.asked.isEmpty())
    }

    // ---- run ----------------------------------------------------------------------------------

    @Test
    fun theWarningAndTheComingRainAreBothPosted() = runTest {
        val h = harness()
        assertEquals(2, h.check.run())
        val (warning, rain) = h.notifier.posted
        assertEquals(listOf("alert:urn:tornado", "Tornado Warning", "Tornado warning until 5 PM"), listOf(warning.id, warning.title, warning.body))
        assertTrue(warning.urgent)
        assertEquals("soon:${at(0, 15, 30) / 3_600}", rain.id)
        assertEquals(keyed(UiText.of(Res.string.weather_story_rain_starting, 30)), rain.title)
        assertEquals(keyed(UiText.of(Res.string.weather_notice_soon_body_lasting, pm("3:30"), "Portland", 30)), rain.body)
        assertFalse(rain.urgent)
    }

    @Test
    fun withEveryNotificationMutedNothingIsPostedOrMarkedAsSaid() = runTest {
        val h = harness()
        h.muted.value = true
        assertEquals(0, h.check.run())
        assertTrue(h.notifier.posted.isEmpty())

        // Unmuted, the same warning and rain are still there to tell.
        h.muted.value = false
        assertEquals(2, h.check.run())
    }

    @Test
    fun aNotificationCarriesItsPictureInTheReadersWords() = runTest {
        val h = harness()
        h.check.run()
        val (warning, rain) = h.notifier.posted
        assertEquals(WeatherNoticeArt(icon = WeatherNoticeIcon.TORNADO), warning.art)
        assertEquals(WeatherNoticeIcon.RAIN, rain.art.icon)
        assertEquals(keyed(pm("3:30")), rain.art.headline)
        assertEquals(listOf(WeatherNoticeArt.Fact(WeatherNoticeIcon.RAIN, keyed(UiText.of(Res.string.weather_minutes_short, 30)))), rain.art.facts)
        // Now, then each half hour of the two the chart covers.
        assertEquals(listOf(keyed(UiText.of(Res.string.weather_now)), keyed(pm("3:30")), keyed(pm("4")), keyed(pm("4:30")), keyed(pm("5"))), rain.art.ticks)
        assertEquals(at(0, 15, 30), (rain.art.chart as WeatherNoticeChart.Soon).startEpochSeconds)
    }

    @Test
    fun aNotificationSaysWhereItIsForWhenItHappensAndWhenItIsOver() = runTest {
        val h = harness()
        h.check.run()
        val (warning, rain) = h.notifier.posted
        assertEquals("Portland", rain.place)
        assertEquals(at(0, 15, 30), rain.eventEpochSeconds)
        assertEquals(at(0, 16), rain.expiresEpochSeconds, "down once the shower has passed")
        assertEquals(null, warning.expiresEpochSeconds, "a warning with no end stays until it is dismissed")
    }

    @Test
    fun aTapLandsOnWhatTheNotificationWasAbout() = runTest {
        val h = harness()
        h.check.run()
        val (warning, rain) = h.notifier.posted
        assertEquals(WeatherDeepLink(WeatherDeepLink.View.TODAY, placeId = Place.CURRENT_ID, alertKey = "urn:tornado"), warning.link)
        assertEquals(WeatherDeepLink(WeatherDeepLink.View.TODAY, placeId = Place.CURRENT_ID), rain.link)
    }

    @Test
    fun whatIsOverIsSweptBeforeAnythingNewIsPosted() = runTest {
        val h = harness()
        h.check.run()
        assertEquals(listOf(now), h.notifier.swept)
    }

    // ---- sample --------------------------------------------------------------------------------

    @Test
    fun aSampleIsPostedWhateverTheSwitchesSayAndTheLedgerNeverHearsOfIt() = runTest {
        val h = harness(WeatherData.report(now))
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        assertTrue(h.check.sample())
        val sample = h.notifier.posted.single()
        assertEquals(WeatherNoticePlanner.SAMPLE_KEY, sample.id)
        assertEquals("Portland", sample.place)
        assertEquals(WeatherDeepLink(WeatherDeepLink.View.TODAY, placeId = Place.CURRENT_ID), sample.link)
        assertTrue(sample.art.chart is WeatherNoticeChart.Day)
        assertTrue(h.ledger.sent(now).isEmpty())
    }

    @Test
    fun aSampleIsTakenDownWhenItIsOverEvenWithNoCheckScheduledToCome() = runTest {
        val h = harness(WeatherData.report(now))
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        assertTrue(h.check.sample())
        val over = assertNotNull(h.notifier.posted.single().expiresEpochSeconds)
        assertTrue(h.notifier.swept.isEmpty(), "not before it is over")
        h.clock.seconds = over
        testScheduler.advanceTimeBy((over - now + 1).seconds)
        settle()
        assertEquals(listOf(over), h.notifier.swept)
    }

    @Test
    fun aCheckSweepsWhatIsOverEvenWhileNoticesAreSwitchedOff() = runTest {
        val h = harness()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        assertEquals(0, h.check.run())
        assertEquals(listOf(now), h.notifier.swept, "a sample, or a notice from before the switch went off, still comes down")
        assertTrue(h.repository.reportRequests.isEmpty(), "and still nothing is fetched")
    }

    @Test
    fun noSampleGoesOutWhereTheOsWouldNotShowIt() = runTest {
        val h = harness(WeatherData.report(now)).apply { notifier.allowed = false }
        assertFalse(h.check.sample())
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun aSampleThatCannotGetAForecastSaysSoRatherThanThrowing() = runTest {
        val h = harness(report = null).apply { repository.onReport = { Result.failure(IllegalStateException("offline")) } }
        assertFalse(h.check.sample())
    }

    @Test
    fun aNoticeFromAnUnnamedPlaceSaysHere() = runTest {
        val h = harness().apply { repository.onName = { _, _ -> null } }
        h.check.run()
        assertEquals(keyed(UiText.of(Res.string.weather_notice_soon_here_lasting, pm("3:30"), 30)), h.notifier.posted.last().body)
    }

    @Test
    fun eachNoticeIsPostedExactlyOnceAcrossRuns() = runTest {
        val h = harness()
        assertEquals(2, h.check.run())
        assertEquals(0, h.check.run())
        h.clock.seconds += 300
        assertEquals(0, h.check.run())
        assertEquals(2, h.notifier.posted.size)
    }

    @Test
    fun postedNoticesAreRecordedInTheLedgerAtTheTimeTheyWentOut() = runTest {
        val h = harness()
        h.check.run()
        assertEquals(setOf("alert:urn:tornado", "soon:${at(0, 15, 30) / 3_600}"), h.ledger.sent(now).keys)
        assertTrue(h.ledger.sent(now).values.all { it == now })
    }

    @Test
    fun aNoticeTheOsWouldNotTakeIsNotMarkedAsSaidAndGoesOutNextTime() = runTest {
        val h = harness().apply { notifier.accepts = false }
        assertEquals(0, h.check.run(), "nothing went out")
        assertTrue(h.ledger.sent(now).isEmpty(), "so nothing is marked as said")
        h.notifier.accepts = true
        assertEquals(2, h.check.run())
        assertEquals(setOf("alert:urn:tornado", "soon:${at(0, 15, 30) / 3_600}"), h.ledger.sent(now).keys)
    }

    @Test
    fun aNoticeAlreadyInTheLedgerIsNotPostedAgain() = runTest {
        val h = harness()
        h.ledger.record("alert:urn:tornado", now - 600)
        assertEquals(1, h.check.run())
        assertEquals("soon:${at(0, 15, 30) / 3_600}", h.notifier.posted.single().id)
    }

    @Test
    fun aQuietForecastPostsNothing() = runTest {
        val h = harness(WeatherData.report(now))
        assertEquals(0, h.check.run())
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun nothingIsPostedWhileNoticesAreSwitchedOff() = runTest {
        val h = harness()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        assertEquals(0, h.check.run())
        assertTrue(h.notifier.posted.isEmpty())
        // And the forecast is not even fetched.
        assertTrue(h.repository.reportRequests.isEmpty())
    }

    @Test
    fun withoutPermissionNothingIsFetchedOrRecorded() = runTest {
        val h = harness().apply { notifier.allowed = false }
        assertEquals(0, h.check.run())
        assertTrue(h.repository.reportRequests.isEmpty(), "the forecast is not even fetched")
        assertTrue(h.notifier.posted.isEmpty())
        assertTrue(h.ledger.sent(now).isEmpty(), "and nothing is marked as said")
    }

    @Test
    fun whenPermissionComesLaterTheNoticesThatWereDueAreStillSent() = runTest {
        val h = harness().apply { notifier.allowed = false }
        h.check.run()
        h.notifier.allowed = true
        assertEquals(2, h.check.run())
        assertEquals(2, h.notifier.posted.size)
    }

    @Test
    fun nothingIsDoneWhereTheyCannotBePosted() = runTest {
        val h = harness().apply { notifier.isSupported = false }
        assertEquals(0, h.check.run())
        assertTrue(h.repository.reportRequests.isEmpty())
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun theUsersSwitchesDecideWhichNoticesAreSent() = runTest {
        val h = harness()
        h.preferences.update { it.copy(notices = it.notices.copy(severeAlerts = false)) }
        assertEquals(1, h.check.run())
        assertEquals(false, h.notifier.posted.single().urgent)
    }

    @Test
    fun theForecastIsNeverMoreThanFiveMinutesOld() = runTest {
        val h = harness()
        h.check.run()
        assertEquals(300L, h.repository.reportRequests.single().second)
    }

    @Test
    fun aFailedForecastPostsNothingAndIsTriedAgainNextTime() = runTest {
        val h = harness(report = null)
        assertEquals(0, h.check.run())
        h.repository.onReport = { Result.success(stormyReport()) }
        assertEquals(2, h.check.run())
    }

    @Test
    fun aCheckThatThrowsIsZeroNotACrash() = runTest {
        val h = harness().apply { repository.onReport = { throw IllegalStateException("boom") } }
        assertEquals(0, h.check.run())
    }

    // ---- Which place --------------------------------------------------------------------------

    @Test
    fun theForecastIsForWhereThePhoneIs() = runTest {
        val h = harness()
        h.check.run()
        val asked = h.repository.reportRequests.single().first
        assertEquals(Place(Place.CURRENT_ID, "Portland", "OR", 45.5234, -122.6762), asked)
    }

    @Test
    fun withoutAFixItIsWhereThePhoneLastWas() = runTest {
        val h = harness().apply { fence.here = null }
        val salem = Place(Place.CURRENT_ID, "Salem", "OR", 44.94, -123.03)
        h.preferences.update { it.copy(lastKnown = salem) }
        h.places.add(Place(Place.idFor(47.6, -122.3), "Seattle", "WA", 47.6, -122.3))
        h.check.run()
        assertEquals(salem, h.repository.reportRequests.single().first)
    }

    @Test
    fun aFixThatFailsFallsBackToo() = runTest {
        val h = harness().apply { fence.failure = IllegalStateException("location services off") }
        val salem = Place(Place.CURRENT_ID, "Salem", "OR", 44.94, -123.03)
        h.preferences.update { it.copy(lastKnown = salem) }
        h.check.run()
        assertEquals(salem, h.repository.reportRequests.single().first)
    }

    @Test
    fun withNoFixAndNothingRememberedItIsTheHouseholdsHome() = runTest {
        val h = harness().apply { fence.here = null }
        h.identity.saveCachedHome(HomeLocation(45.0, -122.0, 150.0))
        h.places.add(Place(Place.idFor(47.6, -122.3), "Seattle", "WA", 47.6, -122.3))
        h.check.run()
        assertEquals(Place(Place.CURRENT_ID, "Portland", "OR", 45.0, -122.0), h.repository.reportRequests.single().first)
    }

    @Test
    fun withNoPositionAtAllItIsTheFirstSavedCity() = runTest {
        val h = harness().apply { fence.here = null }
        val seattle = Place(Place.idFor(47.6, -122.3), "Seattle", "WA", 47.6, -122.3)
        h.places.add(seattle)
        h.places.add(Place(Place.idFor(35.7, 139.7), "Tokyo", "Japan", 35.7, 139.7))
        h.check.run()
        assertEquals(seattle, h.repository.reportRequests.single().first)
    }

    @Test
    fun aSavedCitysNoticeNamesTheCity() = runTest {
        val h = harness().apply { fence.here = null }
        h.places.add(Place(Place.idFor(47.6, -122.3), "Seattle", "WA", 47.6, -122.3))
        h.check.run()
        assertEquals(keyed(UiText.of(Res.string.weather_notice_soon_body_lasting, pm("3:30"), "Seattle", 30)), h.notifier.posted.last().body)
    }

    @Test
    fun withNowhereToForecastForNothingHappens() = runTest {
        val h = harness().apply { fence.here = null }
        assertEquals(0, h.check.run())
        assertTrue(h.repository.reportRequests.isEmpty())
    }

    @Test
    fun whereTheMonitorIsUnsupportedTheCityIsUsed() = runTest {
        val h = harness().apply { fence.isSupported = false }
        val seattle = Place(Place.idFor(47.6, -122.3), "Seattle", "WA", 47.6, -122.3)
        h.places.add(seattle)
        h.check.run()
        assertEquals(seattle, h.repository.reportRequests.single().first)
    }

    // ---- The background schedule --------------------------------------------------------------

    @Test
    fun theScheduleIsOnWhileNoticesAreWanted() = runTest {
        val h = harness()
        h.check.syncSchedule()
        assertEquals(listOf(true), h.scheduler.calls)
    }

    @Test
    fun theScheduleFollowsTheSwitch() = runTest {
        val h = harness()
        h.check.syncSchedule()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        h.check.syncSchedule()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = true)) }
        h.check.syncSchedule()
        assertEquals(listOf(true, false, true), h.scheduler.calls)
    }

    @Test
    fun thereIsNothingToScheduleWhereNoticesCannotBePosted() = runTest {
        val h = harness().apply { notifier.isSupported = false }
        h.check.syncSchedule()
        assertEquals(listOf(false), h.scheduler.calls)
    }

    @Test
    fun startingSyncsTheScheduleInTheBackground() = runTest {
        val h = harness()
        h.check.start()
        settle()
        assertEquals(listOf(true), h.scheduler.calls)
    }

    @Test
    fun startingWithTheSwitchOffCancelsTheSchedule() = runTest {
        val h = harness()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        h.check.start()
        settle()
        assertEquals(listOf(false), h.scheduler.calls)
    }

    @Test
    fun theLastKnownPlaceIsKeptByTheCheck() = runTest {
        val h = harness()
        h.check.run()
        assertEquals("Portland", h.preferences.observe().first().lastKnown?.name)
    }
}
