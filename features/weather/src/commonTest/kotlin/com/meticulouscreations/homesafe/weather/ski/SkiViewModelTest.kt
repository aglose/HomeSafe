package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.weather.data.MutableClock
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.ski.domain.ChainControl
import com.meticulouscreations.homesafe.weather.ski.domain.ChainLevel
import com.meticulouscreations.homesafe.weather.ski.domain.SkiAlerts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiChainReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDrive
import com.meticulouscreations.homesafe.weather.ski.domain.SkiDriveSource
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
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
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStartingPoint
import com.meticulouscreations.homesafe.weather.ski.domain.SkiTicketPrice
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SkiViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Tuesday 6 January 2026, noon in California. */
    private val now = 1_767_729_600L

    private val palisades = SkiResortRef("palisades-tahoe", "california", "Palisades Tahoe")
    private val alta = SkiResortRef("alta", "utah", "Alta")
    private val facts = SkiResortFacts(39.2, -120.2, baseElevationM = 1_900.0, summitElevationM = 2_700.0)

    private inner class FakeSki : SkiRepository {
        val planFlow = MutableStateFlow(SkiPlan(resorts = listOf(palisades), regions = emptyList()))
        val failing = mutableSetOf<String>()
        val reportCalls = mutableListOf<Pair<String, Boolean>>()
        val driveCalls = mutableListOf<Long?>()
        var drive = SkiDrive(12_000, 300_000.0, SkiDriveSource.FREE_FLOW, path = "road")

        /** Holds up the resort pages of these slugs for as long as it isn't completed. */
        val held = mutableMapOf<String, CompletableDeferred<Unit>>()

        override fun plan(): Flow<SkiPlan> = planFlow

        override suspend fun updatePlan(change: (SkiPlan) -> SkiPlan) = planFlow.update(change)

        override suspend fun reports(regionSlug: String, force: Boolean): Result<List<SkiReport>> {
            reportCalls += regionSlug to force
            if (regionSlug in failing) return Result.failure(IllegalStateException("down"))
            return Result.success(
                when (regionSlug) {
                    "california" -> listOf(SkiReport("palisades-tahoe", "california", "Palisades Tahoe", last72Cm = 9.0), SkiReport("other", "california", "Other"))
                    "utah" -> listOf(SkiReport("alta", "utah", "Alta", last72Cm = 30.0))
                    else -> emptyList()
                },
            )
        }

        override suspend fun prices(regionSlug: String) = Result.success(mapOf("palisades-tahoe" to SkiTicketPrice(adultDay = 269.0)))

        override suspend fun resortPage(ref: SkiResortRef, force: Boolean): Result<SkiResortPage> {
            held[ref.slug]?.await()
            if (ref.slug in failing) return Result.failure(IllegalStateException("down"))
            return Result.success(SkiResortPage(facts, SkiReport(ref.slug, ref.regionSlug, ref.name)))
        }

        override suspend fun forecast(ref: SkiResortRef, facts: SkiResortFacts, force: Boolean) = Result.success(SkiForecast(now, -28_800))

        override suspend fun alerts(facts: SkiResortFacts): List<WeatherAlert> = listOf(WeatherAlert("1", event = "Winter Storm Warning"))

        var driveFailing = false

        /** Holds up the drives leaving at these times for as long as it isn't completed. */
        val heldDrives = mutableMapOf<Long, CompletableDeferred<Unit>>()

        override suspend fun drive(from: SkiPoint, to: SkiPoint, departEpochSeconds: Long?): Result<SkiDrive> {
            driveCalls += departEpochSeconds
            departEpochSeconds?.let { heldDrives[it]?.await() }
            if (driveFailing) return Result.failure(IllegalStateException("down"))
            return Result.success(drive.copy(departEpochSeconds = departEpochSeconds))
        }

        var chainsComplete = true

        override suspend fun chainControls(path: String): SkiChainReport {
            heldChains?.await()
            return SkiChainReport(listOf(ChainControl(ChainLevel.R2, "I-80", "Donner Summit", latitude = 39.3, longitude = -120.3)), complete = chainsComplete)
        }

        var news: SkiResortNews? = null

        /** Holds up the shop, as its browser can for most of a minute. */
        var heldPrices: CompletableDeferred<Unit>? = null

        /** Holds up the road's chain controls. */
        var heldChains: CompletableDeferred<Unit>? = null
        var resortPrices: SkiResortPrices? = null
        val pricedDays = mutableListOf<List<Int>>()

        override fun hasResortSite(slug: String) = slug == "palisades-tahoe"

        override suspend fun resortNews(slug: String, force: Boolean) = news.takeIf { hasResortSite(slug) }

        override suspend fun resortPrices(slug: String, epochDays: List<Int>): SkiResortPrices? {
            pricedDays += epochDays
            heldPrices?.await()
            return resortPrices
        }

        override suspend fun passResorts(pass: SkiPass) = Result.success(if (pass == SkiPass.IKON) setOf("palisades-tahoe") else emptySet())
    }

    private inner class FakeStart(var point: SkiPoint? = SkiPoint(37.8, -122.3), override val canLocate: Boolean = true) : SkiStartingPoint {
        val asked = mutableListOf<SkiOrigin>()
        var accessAsked = 0

        override suspend fun requestAccess() {
            accessAsked++
        }

        override suspend fun of(origin: SkiOrigin): SkiPoint? {
            asked += origin
            return point
        }
    }

    private val client = HttpClient(MockEngine { respondOk() })

    private fun skiTest(ski: FakeSki = FakeSki(), start: FakeStart = FakeStart(), body: suspend TestScope.(SkiViewModel, FakeSki, FakeStart) -> Unit) = runTest(dispatcher) {
        val vm = SkiViewModel(ski, start, MutableClock(now), client)
        try {
            runCurrent()
            body(vm, ski, start)
        } finally {
            vm.setActive(false)
            runCurrent()
        }
    }

    @Test
    fun nothingIsFetchedUntilTheTabIsUp() = skiTest { vm, ski, _ ->
        assertTrue(ski.reportCalls.isEmpty())
        vm.setActive(true)
        runCurrent()
        assertEquals(listOf("california" to false), ski.reportCalls)
    }

    @Test
    fun aFollowedResortFillsInFromEveryService() = skiTest { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertEquals(9.0, entry.report?.last72Cm)
        assertEquals(269.0, entry.price?.adultDay)
        assertEquals(facts, entry.page?.facts)
        assertNotNull(entry.forecast)
        assertEquals(12_000, entry.drive?.seconds)
        assertEquals("I-80", entry.chains.single().route)
        assertEquals("Winter Storm Warning", entry.alerts.single().event)
        assertFalse(entry.loading)
    }

    @Test
    fun theResortsOwnWordGoesOverTheListing() = skiTest(FakeSki().apply { news = SkiResortNews("https://resort.example/report", last24Cm = 20.0, seasonCm = 20.0) }) { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        val latest = assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.latest)
        assertEquals(20.0, latest.last24Cm)
        assertEquals(20.0, latest.seasonCm)
        assertEquals("https://resort.example/report", latest.resortPageUrl)
        // The listing's figure doesn't sit beside it as though the resort had said so.
        assertNull(latest.last72Cm)
    }

    @Test
    fun chainsThatCouldntBeCheckedAreSaidToBe() = skiTest(FakeSki().apply { chainsComplete = false }) { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertTrue(entry.chainsUnavailable)
        assertEquals("I-80", entry.chains.single().route)
    }

    @Test
    fun theResortsPricesDontWaitOnItsListingPage() = skiTest(
        FakeSki().apply {
            failing += "palisades-tahoe"
            resortPrices = SkiResortPrices("https://resort.example/tickets")
        },
    ) { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertNull(entry.page)
        assertEquals("https://resort.example/tickets", entry.prices?.pageUrl)
    }

    @Test
    fun theResortsPricesAreAskedForEveryDayThatCanBeChosen() = skiTest(FakeSki().apply { resortPrices = SkiResortPrices("https://resort.example/tickets") }) { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        val state = vm.uiState.value
        assertEquals("https://resort.example/tickets", state.entries["palisades-tahoe"]?.prices?.pageUrl)
        val asked = ski.pricedDays.single()
        assertTrue(state.todayEpochDay in asked)
        assertTrue(state.todayEpochDay + 9 in asked)
        assertTrue(state.tripEpochDay in asked)
    }

    @Test
    fun aRegionsResortsAreOverlaidWithTheirOwnWord() = skiTest(FakeSki().apply { news = SkiResortNews("https://resort.example/report", last24Cm = 5.0) }) { vm, _, _ ->
        vm.openRegion("california")
        runCurrent()
        val load = assertNotNull(vm.uiState.value.regions["california"])
        assertEquals(setOf("palisades-tahoe"), load.news.keys, "only those with a site the app can read")
    }

    @Test
    fun oneResortsFailureIsThatResortsAlone() = skiTest(FakeSki().apply { failing += "palisades-tahoe" }) { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertNotNull(entry.error)
        assertNull(entry.forecast)
        assertFalse(entry.loading)
        // The region's page still answered for it.
        assertEquals(9.0, entry.report?.last72Cm)
    }

    @Test
    fun noWhereToStartFromSaysSoAndLeavesTheRest() = skiTest(start = FakeStart(point = null)) { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        assertFalse(vm.uiState.value.hasStart)
        assertTrue(ski.driveCalls.isEmpty())
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.forecast)
    }

    @Test
    fun aDeviceWithNoPositionSaysSoAndNamesTheCityItGoesBy() = skiTest(start = FakeStart(point = SkiPoint(39.5, -119.8, "Reno"), canLocate = false)) { vm, ski, _ ->
        assertFalse(vm.uiState.value.canLocate)
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.hasStart)
        assertEquals("Reno", vm.uiState.value.startCity)
        assertTrue(ski.driveCalls.isNotEmpty())
    }

    @Test
    fun aStartThatTurnsUpLaterIsUsedOnTheNextLook() = skiTest(start = FakeStart(point = null)) { vm, ski, start ->
        vm.setActive(true)
        runCurrent()
        assertFalse(vm.uiState.value.hasStart)
        start.point = SkiPoint(37.8, -122.3)
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()
        assertTrue(vm.uiState.value.hasStart)
        assertTrue(ski.driveCalls.isNotEmpty())
    }

    @Test
    fun aHomeSetLaterTakesOverFromACity() = skiTest(start = FakeStart(point = SkiPoint(39.5, -119.8, "Reno"))) { vm, _, start ->
        vm.setActive(true)
        runCurrent()
        assertEquals("Reno", vm.uiState.value.startCity)
        start.point = SkiPoint(37.8, -122.3)
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()
        assertNull(vm.uiState.value.startCity)
    }

    @Test
    fun drivesFromHomeNameNoCity() = skiTest { vm, _, _ ->
        assertTrue(vm.uiState.value.canLocate)
        vm.setActive(true)
        runCurrent()
        assertNull(vm.uiState.value.startCity)
    }

    @Test
    fun theDayStartsOnTheComingSaturday() = skiTest { vm, _, _ ->
        // The zone's offset can move today by one either way; the trip is that week's Saturday regardless.
        val state = vm.uiState.value
        assertTrue(state.tripEpochDay - state.todayEpochDay in 0..6)
        assertEquals(5, (state.tripEpochDay + 3).mod(7), "a Saturday")
    }

    @Test
    fun aDayPickedStays() = skiTest { vm, _, _ ->
        val picked = vm.uiState.value.todayEpochDay + 2
        vm.pickDay(picked)
        assertEquals(picked, vm.uiState.value.tripEpochDay)
    }

    @Test
    fun aDayPickedIsKeptForTheRelayAndForNextTime() = skiTest { vm, ski, _ ->
        val picked = vm.uiState.value.todayEpochDay + 2
        vm.pickDay(picked)
        runCurrent()
        assertEquals(picked, ski.planFlow.value.tripEpochDay)
    }

    @Test
    fun aDayPickedLastTimeComesBackUnlessItHasGoneBy() = skiTest { vm, ski, _ ->
        val today = vm.uiState.value.todayEpochDay
        val saturday = vm.uiState.value.tripEpochDay
        ski.planFlow.value = ski.planFlow.value.copy(tripEpochDay = today - 1)
        runCurrent()
        assertEquals(saturday, vm.uiState.value.tripEpochDay)
        ski.planFlow.value = ski.planFlow.value.copy(tripEpochDay = today + 3)
        runCurrent()
        assertEquals(today + 3, vm.uiState.value.tripEpochDay)
    }

    @Test
    fun theTabComingUpIsWhatLetsItsNotificationsStart() = skiTest { vm, ski, _ ->
        assertFalse(ski.planFlow.value.tabOpened)
        vm.setActive(true)
        runCurrent()
        assertTrue(ski.planFlow.value.tabOpened)
    }

    @Test
    fun theSwitchesAreKept() = skiTest { vm, ski, _ ->
        vm.setAlerts(SkiAlerts(bigSnow = false))
        runCurrent()
        assertEquals(SkiAlerts(bigSnow = false), ski.planFlow.value.alerts)
    }

    @Test
    fun followingAResortFetchesIt() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        vm.follow(alta, true)
        runCurrent()
        assertTrue(ski.planFlow.value.follows("alta"))
        assertEquals(30.0, vm.uiState.value.entries["alta"]?.report?.last72Cm)
        vm.follow(alta, false)
        runCurrent()
        assertFalse(ski.planFlow.value.follows("alta"))
    }

    @Test
    fun aResortFollowedWhileAnotherIsStillLoadingIsntKeptWaiting() = skiTest { vm, ski, _ ->
        ski.held["palisades-tahoe"] = CompletableDeferred()
        vm.setActive(true)
        runCurrent()
        vm.follow(alta, true)
        runCurrent()
        assertNotNull(vm.uiState.value.entries["alta"]?.forecast)
        ski.held.getValue("palisades-tahoe").complete(Unit)
        runCurrent()
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.forecast)
    }

    @Test
    fun followingTwiceIsOnce() = skiTest { vm, ski, _ ->
        vm.follow(palisades, true)
        runCurrent()
        assertEquals(1, ski.planFlow.value.resorts.size)
    }

    @Test
    fun aPassMarksTheResortsItCovers() = skiTest { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        vm.setPass(SkiPass.IKON, true)
        runCurrent()
        assertEquals(setOf(SkiPass.IKON), vm.uiState.value.entries["palisades-tahoe"]?.passes)
        vm.setPass(SkiPass.IKON, false)
        runCurrent()
        assertEquals(emptySet(), vm.uiState.value.entries["palisades-tahoe"]?.passes)
    }

    @Test
    fun aRegionLoadsItsResortsAndSaysWhenItCant() = skiTest { vm, ski, _ ->
        vm.openRegion("utah")
        runCurrent()
        assertEquals(listOf("alta"), vm.uiState.value.regions["utah"]?.reports?.map { it.slug })
        ski.failing += "colorado"
        vm.openRegion("colorado")
        runCurrent()
        assertNotNull(vm.uiState.value.regions["colorado"]?.error)
    }

    @Test
    fun aWatchedRegionLoadsWithTheTab() = skiTest { vm, ski, _ ->
        ski.planFlow.update { it.copy(regions = listOf("utah")) }
        runCurrent()
        vm.setActive(true)
        runCurrent()
        assertEquals(1, vm.uiState.value.regions["utah"]?.reports?.size)
    }

    @Test
    fun aResortNotFollowedCanStillBeOpened() = skiTest { vm, _, _ ->
        vm.setActive(true)
        runCurrent()
        vm.openResort(SkiResortRef("other", "california", "Other"))
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["other"])
        assertNotNull(entry.page)
        assertNotNull(entry.report)
        assertFalse(vm.uiState.value.plan.follows("other"))
    }

    @Test
    fun anOpenResortAsksForTheTripsOwnHours() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        vm.openResort(palisades)
        runCurrent()
        val state = vm.uiState.value
        // 7 AM and 4 PM on the Saturday, by the mountain's clock (UTC-8 in the fake forecast).
        val midnight = state.tripEpochDay * SkiForecast.DAY_SECONDS + 28_800
        assertTrue(midnight + 7 * 3_600 in ski.driveCalls)
        assertTrue(midnight + 16 * 3_600 in ski.driveCalls)
        assertEquals(midnight + 7 * 3_600, state.entries["palisades-tahoe"]?.driveOut?.departEpochSeconds)
        assertEquals(midnight + 16 * 3_600, state.entries["palisades-tahoe"]?.driveBack?.departEpochSeconds)
    }

    @Test
    fun anEarlierDaysDrivesArrivingLateDontLandOnTheDayNowChosen() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        vm.openResort(palisades)
        runCurrent()
        val first = vm.uiState.value.tripEpochDay
        val second = first + 1
        val hours = { day: Int -> day * SkiForecast.DAY_SECONDS + 28_800 + 7 * 3_600 }
        val gate = CompletableDeferred<Unit>()
        ski.heldDrives[hours(first + 7)] = gate

        vm.pickDay(first + 7)
        vm.planTrip(palisades)
        runCurrent()
        vm.pickDay(second)
        vm.planTrip(palisades)
        runCurrent()
        assertEquals(hours(second), vm.uiState.value.entries["palisades-tahoe"]?.driveOut?.departEpochSeconds)

        gate.complete(Unit)
        runCurrent()
        assertEquals(hours(second), vm.uiState.value.entries["palisades-tahoe"]?.driveOut?.departEpochSeconds)
    }

    @Test
    fun anotherDaysDrivesAreClearedWhileTheNewOnesCome() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        vm.openResort(palisades)
        runCurrent()
        val next = vm.uiState.value.tripEpochDay + 7
        ski.heldDrives[next * SkiForecast.DAY_SECONDS + 28_800 + 7 * 3_600] = CompletableDeferred()
        vm.pickDay(next)
        vm.planTrip(palisades)
        runCurrent()
        assertNull(vm.uiState.value.entries["palisades-tahoe"]?.driveOut)
        ski.heldDrives.values.forEach { it.complete(Unit) }
        runCurrent()
    }

    @Test
    fun aSlowShopDoesntHoldUpTheTripsDrives() = skiTest(FakeSki().apply { heldPrices = CompletableDeferred() }) { vm, ski, _ ->
        vm.openResort(palisades)
        runCurrent()
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.driveOut)
        ski.heldPrices?.complete(Unit)
        runCurrent()
    }

    @Test
    fun aDriveWaitsForItsChainCheckSoItsNeverAFalseAllClear() = skiTest(FakeSki().apply { heldChains = CompletableDeferred() }) { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        assertNull(vm.uiState.value.entries["palisades-tahoe"]?.drive)
        ski.heldChains?.complete(Unit)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertNotNull(entry.drive)
        assertEquals("I-80", entry.chains.single().route)
    }

    @Test
    fun drivesFromTheOldStartGoWhenItChangesEvenIfNoNewOnesCome() = skiTest { vm, ski, start ->
        vm.setActive(true)
        runCurrent()
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.drive)
        start.point = SkiPoint(39.5, -119.8)
        ski.driveFailing = true
        vm.setOrigin(SkiOrigin.HERE)
        runCurrent()
        val entry = assertNotNull(vm.uiState.value.entries["palisades-tahoe"])
        assertNull(entry.drive)
        assertTrue(entry.chains.isEmpty())
    }

    @Test
    fun aHomeSetLaterClearsDrivesFromTheCityItWentByBefore() = skiTest(start = FakeStart(point = SkiPoint(39.5, -119.8, "Reno"))) { vm, ski, start ->
        vm.setActive(true)
        runCurrent()
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.drive)
        start.point = SkiPoint(37.8, -122.3)
        ski.driveFailing = true
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()
        assertNull(vm.uiState.value.entries["palisades-tahoe"]?.drive, "the origin setting never changed, but the start did")
    }

    @Test
    fun aPositionMovingALittleKeepsTheDrives() = skiTest { vm, ski, start ->
        vm.setActive(true)
        runCurrent()
        start.point = SkiPoint(37.8001, -122.3001)
        ski.driveFailing = true
        vm.refresh()
        runCurrent()
        assertNotNull(vm.uiState.value.entries["palisades-tahoe"]?.drive)
    }

    @Test
    fun choosingWhereIAmAsksForTheLocation() = skiTest { vm, ski, start ->
        vm.setOrigin(SkiOrigin.HERE)
        runCurrent()
        assertEquals(1, start.accessAsked)
        assertEquals(SkiOrigin.HERE, ski.planFlow.value.origin)
        vm.setOrigin(SkiOrigin.HOME)
        runCurrent()
        assertEquals(1, start.accessAsked, "going home needs no leave")
    }

    @Test
    fun aDepartureAlreadyPastIsntAskedAbout() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        val today = vm.uiState.value.todayEpochDay
        vm.pickDay(today - 1)
        ski.driveCalls.clear()
        vm.planTrip(palisades)
        runCurrent()
        assertTrue(ski.driveCalls.isEmpty())
        assertNull(vm.uiState.value.entries["palisades-tahoe"]?.driveOut)
    }

    @Test
    fun aPullAsksEverythingAgain() = skiTest { vm, ski, _ ->
        vm.setActive(true)
        runCurrent()
        ski.reportCalls.clear()
        vm.refresh()
        runCurrent()
        assertEquals(listOf("california" to true), ski.reportCalls)
        assertFalse(vm.uiState.value.refreshing)
    }

    @Test
    fun changingWhereDrivesStartAsksAgain() = skiTest { vm, _, start ->
        vm.setActive(true)
        runCurrent()
        vm.setOrigin(SkiOrigin.HERE)
        runCurrent()
        assertEquals(SkiOrigin.HERE, start.asked.last())
    }

    @Test
    fun partySizesStayInBounds() = skiTest { vm, ski, _ ->
        vm.setParty(-1, 40)
        runCurrent()
        assertEquals(0, ski.planFlow.value.adults)
        assertEquals(12, ski.planFlow.value.children)
    }

    @Test
    fun aPriceCanBeSetAndCleared() = skiTest { vm, ski, _ ->
        vm.setOwnPrice("palisades-tahoe", 150)
        runCurrent()
        assertEquals(150, ski.planFlow.value.ownPrices["palisades-tahoe"])
        vm.setOwnPrice("palisades-tahoe", null)
        runCurrent()
        assertTrue(ski.planFlow.value.ownPrices.isEmpty())
    }
}
