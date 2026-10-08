package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.text.KeyedTextLoader
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherNotification
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_notice_soon_body_lasting
import homesafe.shared.generated.resources.weather_notice_soon_here_lasting
import homesafe.shared.generated.resources.weather_story_rain_starting
import homesafe.shared.generated.resources.weather_time_pm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val check = WeatherAlertCheck(repository, locator, places, preferences, ledger, notifier, scheduler, KeyedTextLoader, clock, scope)
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

    // ---- run ----------------------------------------------------------------------------------

    @Test
    fun theWarningAndTheComingRainAreBothPosted() = runTest {
        val h = harness()
        assertEquals(2, h.check.run())
        assertEquals(
            listOf(
                WeatherNotification("alert:urn:tornado", "Tornado Warning", "Tornado warning until 5 PM", urgent = true),
                WeatherNotification(
                    "soon:${at(0, 15, 30) / 3_600}",
                    keyed(UiText.of(Res.string.weather_story_rain_starting, 30)),
                    keyed(UiText.of(Res.string.weather_notice_soon_body_lasting, pm("3:30"), "Portland", 30)),
                    urgent = false,
                ),
            ),
            h.notifier.posted,
        )
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
