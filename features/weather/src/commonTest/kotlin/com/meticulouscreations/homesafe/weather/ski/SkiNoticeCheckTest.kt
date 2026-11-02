package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.data.SkiDao
import com.meticulouscreations.homesafe.data.SkiKeptEntity
import com.meticulouscreations.homesafe.domain.platform.NotificationMute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.network.SkiWishes
import com.meticulouscreations.homesafe.text.KeyedTextLoader
import com.meticulouscreations.homesafe.weather.data.FakeWeatherNotifier
import com.meticulouscreations.homesafe.weather.data.MutableClock
import com.meticulouscreations.homesafe.weather.data.WeatherNoticeLedgerImpl
import com.meticulouscreations.homesafe.weather.data.WeatherPreferencesRepositoryImpl
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import com.meticulouscreations.homesafe.weather.ski.data.SkiNoticeCheck
import com.meticulouscreations.homesafe.weather.ski.data.SkiNoticeSyncImpl
import com.meticulouscreations.homesafe.weather.ski.data.SkiWishesRelay
import com.meticulouscreations.homesafe.weather.ski.domain.SkiAlerts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiChainReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPlan
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiRepository
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortFacts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortNews
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPage
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortPrices
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A push landing on the phone, and the phone telling the relay what it wants. */
class SkiNoticeCheckTest {
    private val alpha = SkiResortRef("alpha", "california", "Alpha")
    private val now = 1_793_721_600L
    private val snow = """{"k":"snow","d":20764,"r":[{"n":"Alpha","s":"alpha","cm":45.7,"lo":30.0,"hi":61.0}],"m":0,"x":1794000000}"""

    private inner class FakeSki(plan: SkiPlan) : SkiRepository {
        val planFlow = MutableStateFlow(plan)
        var pageAnswers = true
        var pagesAsked = 0
        var state = SkiOpenState.OPEN
        var reportedAt: Long? = 1_793_700_000L

        override fun plan(): Flow<SkiPlan> = planFlow

        override suspend fun updatePlan(change: (SkiPlan) -> SkiPlan) = planFlow.update(change)

        override suspend fun reports(regionSlug: String, force: Boolean) = Result.success(listOf(SkiReport("alpha", regionSlug, "Alpha", state = state, openingEpochDay = 20700, closingEpochDay = 20900, updatedAtEpochSeconds = reportedAt)))

        override suspend fun prices(regionSlug: String) = Result.success(emptyMap<String, SkiTicketPrice>())

        override suspend fun resortPage(ref: SkiResortRef, force: Boolean): Result<SkiResortPage> {
            pagesAsked++
            if (!pageAnswers) return Result.failure(IllegalStateException("no"))
            return Result.success(SkiResortPage(SkiResortFacts(latitude = 39.2, longitude = -120.2, summitElevationM = 2750.0), SkiReport("alpha", ref.regionSlug, "Alpha")))
        }

        override suspend fun forecast(ref: SkiResortRef, facts: SkiResortFacts, force: Boolean) = Result.success(SkiForecast(0, 0))

        override suspend fun alerts(facts: SkiResortFacts) = emptyList<com.meticulouscreations.homesafe.weather.domain.WeatherAlert>()

        override suspend fun drive(from: SkiPoint, to: SkiPoint, departEpochSeconds: Long?) = Result.failure<SkiDrive>(IllegalStateException("not here"))

        override suspend fun chainControls(path: String) = SkiChainReport()

        override suspend fun resortNews(slug: String, force: Boolean): SkiResortNews? = null

        override fun hasResortSite(slug: String) = true

        override suspend fun resortPrices(slug: String, epochDays: List<Int>): SkiResortPrices? = null

        override suspend fun passResorts(pass: SkiPass) = Result.success(emptySet<String>())
    }

    private class InMemorySkiDao : SkiDao {
        val rows = MutableStateFlow(emptyMap<String, SkiKeptEntity>())

        override suspend fun kept(key: String) = rows.value[key]

        override fun observe(key: String): Flow<SkiKeptEntity?> = rows.map { it[key] }

        override suspend fun keep(entity: SkiKeptEntity) {
            rows.value = rows.value + (entity.key to entity)
        }
    }

    private class FakeRelay : SkiWishesRelay {
        val told = mutableListOf<SkiWishes>()
        var answer = true

        override suspend fun tell(wishes: SkiWishes): Boolean {
            told += wishes
            return answer
        }
    }

    private inner class Harness(scope: CoroutineScope, plan: SkiPlan = SkiPlan(resorts = listOf(alpha), tabOpened = true)) {
        val weather = InMemoryWeatherDao()
        val ski = FakeSki(plan)
        val preferences = WeatherPreferencesRepositoryImpl(weather)
        val ledger = WeatherNoticeLedgerImpl(weather)
        val notifier = FakeWeatherNotifier()
        val clock = MutableClock(now)
        val muted = MutableStateFlow(false)
        val relay = FakeRelay()
        val dao = InMemorySkiDao()
        private val mute = object : NotificationMute {
            override val muted: StateFlow<Boolean> = this@Harness.muted

            override fun setMuted(muted: Boolean) {
                this@Harness.muted.value = muted
            }
        }
        val sync = SkiNoticeSyncImpl(ski, preferences, relay, dao, clock, scope)
        val check = SkiNoticeCheck(ski, preferences, ledger, notifier, KeyedTextLoader, clock, mute, sync)
    }

    // ---- a push landing ---------------------------------------------------------------------

    @Test
    fun aPushIsPostedAndMarkedSaid() = runTest {
        val h = Harness(backgroundScope)
        assertTrue(h.check.onRelayPush(snow, "ski:snow:20764", "Big snow by Saturday", "Alpha 18 in", shown = false))
        val posted = h.notifier.posted.single()
        assertEquals("ski:snow:20764", posted.id)
        assertEquals(WeatherNoticeIcon.SNOW, posted.art.icon)
        assertEquals(WeatherDeepLink(view = WeatherDeepLink.View.SKI, day = 20764, resort = "alpha"), posted.link)
        assertEquals(1794000000L, posted.expiresEpochSeconds)
        assertTrue("Alpha" in posted.art.caption.orEmpty(), posted.art.caption)
        assertTrue(posted.art.headline.orEmpty().isNotEmpty())
        assertFalse(posted.headsUp)
        assertFalse(posted.urgent)
        assertTrue("ski:snow:20764" in h.ledger.sent(now))
    }

    @Test
    fun theSamePushAgainSaysNothing() = runTest {
        val h = Harness(backgroundScope)
        h.check.onRelayPush(snow, "k", "t", "b", shown = false)
        assertFalse(h.check.onRelayPush(snow, "k", "t", "b", shown = false))
        assertEquals(1, h.notifier.posted.size)
    }

    @Test
    fun itsSwitchOffItIsDropped() = runTest {
        val h = Harness(backgroundScope, SkiPlan(resorts = listOf(alpha), alerts = SkiAlerts(bigSnow = false)))
        assertFalse(h.check.onRelayPush(snow, "k", "t", "b", shown = false))
        assertTrue(h.notifier.posted.isEmpty())
    }

    @Test
    fun mutedNothingIsPostedOrMarked() = runTest {
        val h = Harness(backgroundScope)
        h.muted.value = true
        assertFalse(h.check.onRelayPush(snow, "k", "t", "b", shown = false))
        assertTrue(h.notifier.posted.isEmpty())
        assertTrue("k" !in h.ledger.sent(now))
    }

    @Test
    fun notAllowedNothingIsMarked() = runTest {
        val h = Harness(backgroundScope)
        h.notifier.allowed = false
        assertFalse(h.check.onRelayPush(snow, "k", "t", "b", shown = false))
        assertTrue("k" !in h.ledger.sent(now))
    }

    @Test
    fun aBannerIOSDrewIsReplacedWithoutAlertingAgain() = runTest {
        val h = Harness(backgroundScope)
        assertTrue(h.check.onRelayPush(snow, "k", "", "", shown = true))
        assertTrue(h.notifier.posted.single().replacesPush)
    }

    @Test
    fun whatANewerRelaySendsGoesOutInItsOwnWordsOnce() = runTest {
        val h = Harness(backgroundScope)
        assertTrue(h.check.onRelayPush("""{"k":"avalanche"}""", "k", "Avalanche", "Somewhere", shown = false))
        val posted = h.notifier.posted.single()
        assertEquals("Avalanche" to "Somewhere", posted.title to posted.body)
        assertEquals(WeatherDeepLink.View.SKI, posted.link.view)
        assertFalse(h.check.onRelayPush("""{"k":"avalanche"}""", "k", "Avalanche", "Somewhere", shown = false))
    }

    @Test
    fun whatANewerRelaySendsStaysAsIOSDrewIt() = runTest {
        val h = Harness(backgroundScope)
        assertFalse(h.check.onRelayPush("""{"k":"avalanche"}""", "k", "", "", shown = true))
        assertTrue(h.notifier.posted.isEmpty())
    }

    // ---- telling the relay ------------------------------------------------------------------

    @Test
    fun aDeviceThatHasNeverOpenedTheTabTellsNothing() = runTest {
        val h = Harness(backgroundScope, SkiPlan(resorts = listOf(alpha), tabOpened = false))
        h.sync.sync()
        assertTrue(h.relay.told.isEmpty())
        assertEquals(0, h.ski.pagesAsked)
    }

    @Test
    fun itTellsTheSwitchesTheDayAndTheResortsFacts() = runTest {
        val h = Harness(backgroundScope, SkiPlan(resorts = listOf(alpha), tabOpened = true, tripEpochDay = 20764, alerts = SkiAlerts(chains = false)))
        h.sync.sync()
        val wishes = h.relay.told.single()
        assertEquals(Triple(true, true, false), Triple(wishes.snow, wishes.fresh, wishes.chains))
        assertEquals(20764, wishes.tripDay)
        val resort = wishes.resorts.single()
        assertEquals(listOf("alpha", "Alpha"), listOf(resort.slug, resort.name))
        assertEquals(Triple(39.2, -120.2, 2750.0), Triple(resort.lat, resort.lng, resort.summitM))
        assertEquals("OPEN", resort.state)
        assertEquals(20700 to 20900, resort.opens to resort.closes)
        assertEquals(1_793_700_000L, resort.asOf)
    }

    @Test
    fun anOldReportSaysHowOldItIs() = runTest {
        val h = Harness(backgroundScope)
        h.ski.reportedAt = now - 200 * 86_400
        h.sync.sync()
        val resort = h.relay.told.single().resorts.single()
        assertEquals("OPEN" to now - 200 * 86_400, resort.state to resort.asOf)
        assertEquals(20700 to 20900, resort.opens to resort.closes)
    }

    @Test
    fun whereDrivesStartIsNeverTold() = runTest {
        val h = Harness(backgroundScope, SkiPlan(resorts = listOf(alpha), tabOpened = true, origin = SkiOrigin.HERE))
        h.sync.sync()
        val fields = h.relay.told.single().toString()
        assertFalse("HERE" in fields || "origin" in fields, fields)
    }

    @Test
    fun theMasterSwitchOffForgetsItAll() = runTest {
        val h = Harness(backgroundScope)
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        h.sync.sync()
        val wishes = h.relay.told.single()
        assertFalse(wishes.snow || wishes.fresh || wishes.chains)
        assertTrue(wishes.resorts.isEmpty())
        assertEquals(0, h.ski.pagesAsked)
    }

    @Test
    fun everySwitchOffForgetsItAll() = runTest {
        val h = Harness(backgroundScope, SkiPlan(resorts = listOf(alpha), tabOpened = true, alerts = SkiAlerts(false, false, false)))
        h.sync.sync()
        assertTrue(h.relay.told.single().resorts.isEmpty())
    }

    @Test
    fun itSaysItsUnits() = runTest {
        val h = Harness(backgroundScope)
        h.preferences.update { it.copy(units = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)) }
        h.sync.sync()
        assertFalse(h.relay.told.single().imperial)
    }

    @Test
    fun nothingChangedItWaitsADay() = runTest {
        val h = Harness(backgroundScope)
        h.sync.sync()
        h.sync.sync()
        assertEquals(1, h.relay.told.size)
        h.clock.seconds = now + 86_400
        h.sync.sync()
        assertEquals(2, h.relay.told.size)
    }

    @Test
    fun aChangeIsToldAtOnce() = runTest {
        val h = Harness(backgroundScope)
        h.sync.sync()
        h.ski.updatePlan { it.copy(alerts = SkiAlerts(freshSnow = false)) }
        h.sync.sync()
        assertFalse(h.relay.told.last().fresh)
    }

    @Test
    fun aRelayOutOfReachIsAskedAgainNextTime() = runTest {
        val h = Harness(backgroundScope)
        h.relay.answer = false
        h.sync.sync()
        h.relay.answer = true
        h.sync.sync()
        assertEquals(2, h.relay.told.size)
        h.sync.sync()
        assertEquals(2, h.relay.told.size)
    }

    @Test
    fun notificationsOffKeepsTellingARelayOutOfReachUntilItHears() = runTest {
        val h = Harness(backgroundScope)
        h.sync.sync()
        h.relay.answer = false
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        h.sync.watch()
        advanceTimeBy(3_000)
        runCurrent()
        val first = h.relay.told.size
        assertTrue(h.relay.told.last().resorts.isEmpty())
        advanceTimeBy(70_000)
        runCurrent()
        assertTrue(h.relay.told.size > first, "asked again, with no background check to do it")
        h.relay.answer = true
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        val heard = h.relay.told.size
        advanceTimeBy(6 * 3_600_000L)
        runCurrent()
        assertEquals(heard, h.relay.told.size, "it heard, so that's the end of it")
    }

    @Test
    fun notificationsOnLeavesARelayOutOfReachToTheBackgroundCheck() = runTest {
        val h = Harness(backgroundScope)
        h.relay.answer = false
        h.sync.watch()
        advanceTimeBy(6 * 3_600_000L)
        runCurrent()
        assertEquals(1, h.relay.told.size)
    }

    @Test
    fun aPushWithNotificationsOffTellsTheRelayItMissedThat() = runTest {
        val h = Harness(backgroundScope)
        h.sync.sync()
        h.preferences.update { it.copy(notices = it.notices.copy(enabled = false)) }
        assertFalse(h.check.onRelayPush(snow, "k", "", "", shown = true))
        assertTrue(h.notifier.posted.isEmpty())
        assertEquals(2, h.relay.told.size)
        assertFalse(h.relay.told.last().snow)
    }

    @Test
    fun aPageThatWontAnswerLeavesItsResortOutAndIsTriedAgainSoon() = runTest {
        val h = Harness(backgroundScope)
        h.ski.pageAnswers = false
        h.sync.sync()
        assertTrue(h.relay.told.single().resorts.isEmpty())
        h.ski.pageAnswers = true
        h.sync.sync()
        assertEquals("alpha", h.relay.told.last().resorts.single().slug)
        assertNull(h.relay.told.last().tripDay)
    }
}
