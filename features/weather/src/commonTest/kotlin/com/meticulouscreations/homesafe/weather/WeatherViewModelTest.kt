package com.meticulouscreations.homesafe.weather

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.text.KeyedTextLoader
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.data.FakeAlertNotifier
import com.meticulouscreations.homesafe.weather.data.FakeGeofence
import com.meticulouscreations.homesafe.weather.data.FakeWeatherCheckScheduler
import com.meticulouscreations.homesafe.weather.data.FakeWeatherNotifier
import com.meticulouscreations.homesafe.weather.data.FakeWeatherRepository
import com.meticulouscreations.homesafe.weather.data.FakeWeb
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import com.meticulouscreations.homesafe.weather.data.MutableClock
import com.meticulouscreations.homesafe.weather.data.WeatherAlertCheck
import com.meticulouscreations.homesafe.weather.data.WeatherLocator
import com.meticulouscreations.homesafe.weather.data.WeatherNoticeLedgerImpl
import com.meticulouscreations.homesafe.weather.data.WeatherPlacesRepositoryImpl
import com.meticulouscreations.homesafe.weather.data.WeatherPreferencesRepositoryImpl
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarFrame
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherAlert
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_error_forecast
import homesafe.shared.generated.resources.weather_error_forecast_unavailable
import homesafe.shared.generated.resources.weather_error_radar
import homesafe.shared.generated.resources.weather_error_search
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class WeatherViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val now = at(0, 15)

    private val seattle = Place(Place.idFor(47.6062, -122.3321), "Seattle", "Washington, United States", 47.6062, -122.3321)
    private val tokyo = Place(Place.idFor(35.6762, 139.6503), "Tokyo", "Japan", 35.6762, 139.6503)
    private val salem = Place(Place.CURRENT_ID, "Salem", "OR", 44.94, -123.03)

    /** A forecast whose temperature is the place's latitude, to tell which place it was fetched for. */
    private fun reportFor(place: Place): WeatherReport = WeatherData.report(now, current = WeatherData.current(now, temperatureC = place.latitude))

    private class Harness(scope: TestScope, now: Long) {
        val dao = InMemoryWeatherDao()
        val repository = FakeWeatherRepository()
        val fence = FakeGeofence()
        val identity = DeviceIdentityStore(InMemorySettingsDao())
        val preferences = WeatherPreferencesRepositoryImpl(dao)
        val places = WeatherPlacesRepositoryImpl(dao)
        val notifier = FakeWeatherNotifier()
        val scheduler = FakeWeatherCheckScheduler()
        val alertNotifier = FakeAlertNotifier()
        val clock = MutableClock(now)
        val locator = WeatherLocator(fence, identity, repository, preferences)
        val alertCheck = WeatherAlertCheck(repository, locator, places, preferences, WeatherNoticeLedgerImpl(dao), notifier, scheduler, KeyedTextLoader, clock, scope.backgroundScope)
        val tiles = MapTileStore(FakeWeb().client, dao, clock)

        private val made = mutableListOf<WeatherViewModel>()

        fun viewModel() = WeatherViewModel(repository, places, preferences, locator, alertCheck, alertNotifier, clock, tiles).also { made += it }

        /** Stops the view models' polling loops, which would otherwise keep a finished test's clock turning for ever. */
        fun stop() = made.forEach {
            it.setActive(false)
            it.setRadarVisible(false)
        }
    }

    /** The harness set up as most tests want it: a fix in Portland and a forecast for any place. */
    private fun runWeatherTest(
        saved: List<Place> = emptyList(),
        setUp: suspend Harness.() -> Unit = {},
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest(dispatcher) {
        val h = Harness(this, now)
        h.repository.onReport = { place -> Result.success(reportFor(place)) }
        h.repository.onName = { _, _ -> "Portland" to "OR" }
        saved.forEach { h.places.add(it) }
        try {
            h.setUp()
            body(h)
        } finally {
            h.stop()
        }
    }

    private fun TestScope.finish(vm: WeatherViewModel) {
        vm.setActive(false)
        vm.setRadarVisible(false)
        runCurrent()
    }

    // ---- The places ---------------------------------------------------------------------------

    @Test
    fun theListIsTheCurrentLocationThenTheSavedCitiesInOrder() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(listOf(Place.CURRENT_ID, seattle.id, tokyo.id), vm.uiState.value.places.map { it.place.id })
        assertEquals("Portland", vm.uiState.value.places.first().place.name)
        finish(vm)
    }

    @Test
    fun withNothingChosenTheAppOpensOnWhereThePhoneIs() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        // The saved cities are read before a fix comes back; the phone's place still leads once it does.
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(Place.CURRENT_ID, vm.uiState.value.selectedId)
        finish(vm)
    }

    @Test
    fun withoutAFixTheListIsJustTheSavedCities() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(listOf(seattle.id, tokyo.id), vm.uiState.value.places.map { it.place.id })
        finish(vm)
    }

    @Test
    fun whereThePhoneLastWasStandsInUntilAFixComes() = runWeatherTest(saved = listOf(seattle), setUp = { preferences.update { it.copy(lastKnown = salem) } }) { h ->
        val vm = h.viewModel()
        runCurrent()
        // Not active: nothing locates, but the remembered place is already first.
        assertEquals(listOf(Place.CURRENT_ID, seattle.id), vm.uiState.value.places.map { it.place.id })
        assertEquals("Salem", vm.uiState.value.places.first().place.name)
    }

    @Test
    fun aFixReplacesTheRememberedPlace() = runWeatherTest(setUp = { preferences.update { it.copy(lastKnown = salem) } }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val here = vm.uiState.value.places.first().place
        assertEquals(45.5234, here.latitude)
        assertEquals(Place.CURRENT_ID, here.id)
        finish(vm)
    }

    @Test
    fun theFirstLookIsNotSettledUntilPlacesAndPositionAreKnown() = runWeatherTest { h ->
        val vm = h.viewModel()
        assertFalse(vm.uiState.value.settled)
        runCurrent()
        assertTrue(vm.uiState.value.settled)
    }

    @Test
    fun withNoPlacesAtAllTheListIsEmptyAndSettled() = runWeatherTest(setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.places.isEmpty())
        assertTrue(vm.uiState.value.settled)
        assertNull(vm.uiState.value.selected)
        finish(vm)
    }

    @Test
    fun aSavedChoiceOfTheCurrentLocationSurvivesItArrivingAfterTheSavedCities() = runWeatherTest(
        saved = listOf(seattle),
        setUp = { preferences.update { it.copy(selectedPlaceId = Place.CURRENT_ID) } },
    ) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(Place.CURRENT_ID, vm.uiState.value.selectedId)
        finish(vm)
    }

    @Test
    fun theSavedChoiceWinsWhicheverOfThePlacesAndThePreferencesIsReadFirst() = runWeatherTest(
        saved = listOf(seattle, tokyo),
        setUp = { preferences.update { it.copy(selectedPlaceId = tokyo.id) } },
    ) { h ->
        // Several view models over the same stores, to land on different interleavings.
        repeat(3) {
            val vm = h.viewModel()
            repeat(it) { runCurrent() }
            runCurrent()
            assertEquals(tokyo.id, vm.uiState.value.selectedId)
        }
    }

    @Test
    fun theSelectionOpensOnWhatWasShownLastTime() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { preferences.update { it.copy(selectedPlaceId = tokyo.id) } }) { h ->
        val vm = h.viewModel()
        runCurrent()
        assertEquals(tokyo.id, vm.uiState.value.selectedId)
        assertEquals(tokyo.id, vm.uiState.value.selected?.place?.id)
        assertEquals(1, vm.uiState.value.selectedIndex)
    }

    @Test
    fun aSelectionThatNoLongerExistsFallsBackToTheFirstPlace() = runWeatherTest(saved = listOf(seattle), setUp = { preferences.update { it.copy(selectedPlaceId = "gone") } }) { h ->
        val vm = h.viewModel()
        runCurrent()
        assertEquals(seattle.id, vm.uiState.value.selectedId)
    }

    @Test
    fun stateHelpersReadTheListSensibly() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        runCurrent()
        val state = vm.uiState.value
        assertTrue(state.isSaved(seattle))
        assertTrue(state.isSaved(tokyo.copy(name = "Edo")))
        assertFalse(state.isSaved(salem))
        assertEquals(WeatherUnits(), state.units)
    }

    // ---- Loading ------------------------------------------------------------------------------

    @Test
    fun theDrawersCardLoadsOnlyThePlaceInView() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        val state = vm.uiState.value
        assertEquals(2, state.places.size)
        val inView = state.selected?.place?.id
        assertEquals(setOf(inView), h.repository.reportRequests.map { it.first.id }.toSet())
        assertEquals(listOf(inView), state.places.filter { it.report != null }.map { it.place.id })
        finish(vm)
    }

    @Test
    fun theFullAppLoadsEveryPlace() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = true)
        runCurrent()
        assertEquals(setOf(Place.CURRENT_ID, seattle.id, tokyo.id), h.repository.reportRequests.map { it.first.id }.toSet())
        assertTrue(vm.uiState.value.places.all { it.report != null && !it.loading && it.error == null })
        finish(vm)
    }

    @Test
    fun openingTheFullAppAfterTheDrawerLoadsTheRest() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        assertNull(vm.uiState.value.places[1].report)
        vm.setActive(true, full = true)
        runCurrent()
        assertNotNull(vm.uiState.value.places[1].report)
        finish(vm)
    }

    @Test
    fun eachPlacesReportIsItsOwn() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(listOf(45.5234, 47.6062, 35.6762), vm.uiState.value.places.map { it.report?.current?.temperatureC })
        finish(vm)
    }

    @Test
    fun nothingIsFetchedUntilTheAppIsActive() = runWeatherTest(saved = listOf(seattle)) { h ->
        val vm = h.viewModel()
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        assertTrue(h.repository.reportRequests.isEmpty())
        assertEquals(0, h.fence.accessRequests)
        assertNull(vm.uiState.value.places.first().report)
    }

    @Test
    fun aPlaceShowsTheLastSavedReportWhileTheNetworkAnswers() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val old = WeatherData.report(now - 3_000, current = WeatherData.current(now - 3_000, temperatureC = 3.0))
        h.repository.stored = { old }
        h.repository.gate = CompletableDeferred()
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val waiting = vm.uiState.value.places.single()
        assertEquals(old, waiting.report)
        assertTrue(waiting.loading)
        h.repository.gate?.complete(Unit)
        runCurrent()
        val loaded = vm.uiState.value.places.single()
        assertEquals(47.6062, loaded.report?.current?.temperatureC)
        assertFalse(loaded.loading)
        finish(vm)
    }

    @Test
    fun aFailedFirstFetchSetsTheErrorAndLeavesNoReport() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        h.repository.onReport = { Result.failure(IllegalStateException("Unable to resolve host")) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val place = vm.uiState.value.places.single()
        assertNull(place.report)
        assertFalse(place.loading)
        // A dropped connection's own message is in English and of no use: the app's words stand in.
        assertEquals(UiText.of(Res.string.weather_error_forecast), place.error)
        finish(vm)
    }

    @Test
    fun aServiceThatSaidWhatWentWrongIsQuotedInItsOwnWords() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val said = UiText.of(Res.string.weather_error_forecast_unavailable)
        h.repository.onReport = { Result.failure(LocalizedException(said, technical = "HTTP 503")) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(said, vm.uiState.value.places.single().error)
        finish(vm)
    }

    @Test
    fun aFailedRefreshKeepsTheOlderReportAndSetsTheError() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val before = vm.uiState.value.places.single().report
        assertNotNull(before)

        h.repository.onReport = { Result.failure(IllegalStateException("timeout")) }
        vm.refresh()
        runCurrent()
        val after = vm.uiState.value.places.single()
        assertEquals(before, after.report, "the old forecast is still on screen")
        assertEquals(UiText.of(Res.string.weather_error_forecast), after.error)
        assertFalse(after.loading)
        finish(vm)
    }

    @Test
    fun aSuccessfulFetchClearsTheError() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        h.repository.onReport = { Result.failure(IllegalStateException("timeout")) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertNotNull(vm.uiState.value.places.single().error)
        h.repository.onReport = { place -> Result.success(reportFor(place)) }
        vm.refresh()
        runCurrent()
        assertNull(vm.uiState.value.places.single().error)
        assertNotNull(vm.uiState.value.places.single().report)
        finish(vm)
    }

    @Test
    fun whileActiveTheForecastsAreLookedAtEveryMinuteAndTheClockMovesOn() = runWeatherTest(setUp = {
        fence.here = null
        places.add(seattle)
    }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val first = h.repository.reportRequests.size
        h.clock.seconds += 61
        advanceTimeBy(60_001)
        assertTrue(h.repository.reportRequests.size > first, "asked again after a minute")
        assertTrue(h.repository.reportRequests.none { it.second == 0L }, "and never insisting on a new one")
        assertEquals(now + 61, vm.uiState.value.nowEpochSeconds)
        finish(vm)
    }

    @Test
    fun whenInactiveNothingIsFetchedAnyMore() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setActive(false)
        val count = h.repository.reportRequests.size
        advanceTimeBy(10 * 60_000L)
        assertEquals(count, h.repository.reportRequests.size)
    }

    // ---- Refresh ------------------------------------------------------------------------------

    @Test
    fun refreshFetchesEveryPlaceAgainWhateverItsAge() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        h.repository.reportRequests.clear()
        vm.refresh()
        runCurrent()
        val forced = h.repository.reportRequests.filter { it.second == 0L }.map { it.first.id }
        assertEquals(setOf(Place.CURRENT_ID, seattle.id, tokyo.id), forced.toSet())
        finish(vm)
    }

    @Test
    fun refreshingIsOnWhileItWorksAndOffWhenDone() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        h.repository.gate = CompletableDeferred()
        vm.refresh()
        runCurrent()
        assertTrue(vm.uiState.value.refreshing)
        h.repository.gate?.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        finish(vm)
    }

    @Test
    fun refreshingEvenWhenEverythingFailsStopsRefreshing() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        h.repository.onReport = { Result.failure(IllegalStateException("no network")) }
        vm.refresh()
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        finish(vm)
    }

    @Test
    fun refreshMovesTheClockOn() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        h.clock.seconds += 30
        vm.refresh()
        runCurrent()
        assertEquals(now + 30, vm.uiState.value.nowEpochSeconds)
        finish(vm)
    }

    @Test
    fun refreshTakesAFixAndForecastsForWhereThePhoneIsNow() = runWeatherTest(setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.places.isEmpty())
        h.fence.here = GeoPoint(47.6062, -122.3321)
        h.repository.onName = { _, _ -> "Seattle" to "WA" }
        vm.refresh()
        runCurrent()
        val here = vm.uiState.value.places.single()
        assertEquals(47.6062, here.place.latitude)
        assertEquals("Seattle", here.place.name)
        assertEquals(47.6062, here.report?.current?.temperatureC)
        finish(vm)
    }

    @Test
    fun refreshReloadsTheRadarOnlyIfItWasLoaded() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        val timeline = RadarTimeline(RadarSource.US_MRMS, listOf(RadarFrame(now, false, "https://example.test/{z}/{x}/{y}.png", 8)))
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(0, h.repository.radarCalls)
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(1, h.repository.radarCalls)
        vm.refresh()
        runCurrent()
        assertEquals(2, h.repository.radarCalls)
        finish(vm)
    }

    // ---- Selecting, adding, removing, moving ----------------------------------------------------

    @Test
    fun selectingAPlaceSelectsItAndSavesTheChoice() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        vm.select(tokyo.id)
        runCurrent()
        assertEquals(tokyo.id, vm.uiState.value.selectedId)
        assertEquals(tokyo.id, h.preferences.observe().first().selectedPlaceId)
        finish(vm)
    }

    @Test
    fun selectingAPlaceLoadsItsForecastEvenInTheDrawer() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        vm.select(tokyo.id)
        runCurrent()
        assertNotNull(vm.uiState.value.places.single { it.place.id == tokyo.id }.report)
        finish(vm)
    }

    @Test
    fun selectingAPlaceThatIsNotInTheListDoesNothing() = runWeatherTest(saved = listOf(seattle)) { h ->
        val vm = h.viewModel()
        runCurrent()
        val before = vm.uiState.value.selectedId
        vm.select("nowhere")
        runCurrent()
        assertEquals(before, vm.uiState.value.selectedId)
        assertNull(h.preferences.observe().first().selectedPlaceId)
    }

    @Test
    fun selectingAPlaceDropsTheRadarLoopOfTheOneBefore() = runWeatherTest(saved = listOf(seattle)) { h ->
        h.repository.onRadar = { Result.success(RadarTimeline(RadarSource.US_MRMS, listOf(RadarFrame(now, false, "u", 8)))) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        assertNotNull(vm.uiState.value.radar.timeline)
        vm.select(seattle.id)
        assertNull(vm.uiState.value.radar.timeline)
        finish(vm)
    }

    @Test
    fun addingAPlaceSavesAndSelectsIt() = runWeatherTest(setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addPlace(seattle)
        runCurrent()
        assertEquals(listOf(seattle.id), vm.uiState.value.places.map { it.place.id })
        assertEquals(seattle.id, vm.uiState.value.selectedId)
        assertEquals(seattle.id, h.preferences.observe().first().selectedPlaceId)
        assertEquals(listOf(seattle), h.places.observe().first())
        finish(vm)
    }

    @Test
    fun anAddedPlaceGetsItsForecast() = runWeatherTest(setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addPlace(seattle)
        runCurrent()
        assertEquals(47.6062, vm.uiState.value.selected?.report?.current?.temperatureC)
        finish(vm)
    }

    @Test
    fun addingAPlaceAlreadyThereJustSelectsIt() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.addPlace(tokyo)
        runCurrent()
        assertEquals(listOf(seattle, tokyo), h.places.observe().first())
        assertEquals(tokyo.id, vm.uiState.value.selectedId)
        finish(vm)
    }

    @Test
    fun removingAPlaceTakesItOffTheList() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.removePlace(seattle.id)
        runCurrent()
        assertEquals(listOf(tokyo.id), vm.uiState.value.places.map { it.place.id })
        finish(vm)
    }

    @Test
    fun removingTheSelectedPlaceSelectsTheFirstThatIsLeft() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.select(tokyo.id)
        runCurrent()
        vm.removePlace(tokyo.id)
        runCurrent()
        assertEquals(seattle.id, vm.uiState.value.selectedId)
        finish(vm)
    }

    @Test
    fun aSavedCityMovesUpAndDownItsList() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        runCurrent()
        vm.movePlace(tokyo.id, up = true)
        runCurrent()
        assertEquals(listOf(tokyo.id, seattle.id), vm.uiState.value.places.map { it.place.id })
        vm.movePlace(tokyo.id, up = false)
        runCurrent()
        assertEquals(listOf(seattle.id, tokyo.id), vm.uiState.value.places.map { it.place.id })
    }

    @Test
    fun movingTheFirstCityUpOrTheLastDownChangesNothing() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        runCurrent()
        vm.movePlace(seattle.id, up = true)
        vm.movePlace(tokyo.id, up = false)
        runCurrent()
        assertEquals(listOf(seattle.id, tokyo.id), vm.uiState.value.places.map { it.place.id })
    }

    @Test
    fun theCurrentLocationCannotBeMovedAndStaysFirst() = runWeatherTest(saved = listOf(seattle, tokyo)) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.movePlace(Place.CURRENT_ID, up = false)
        vm.movePlace(seattle.id, up = true)
        runCurrent()
        assertEquals(Place.CURRENT_ID, vm.uiState.value.places.first().place.id)
        assertEquals(listOf(seattle, tokyo), h.places.observe().first())
        finish(vm)
    }

    // ---- Search -------------------------------------------------------------------------------

    @Test
    fun aSearchWaitsOutTheTypingBeforeAskingOnce() = runWeatherTest { h ->
        h.repository.onSearch = { Result.success(listOf(seattle)) }
        val vm = h.viewModel()
        runCurrent()
        vm.search("Se")
        advanceTimeBy(100)
        vm.search("Sea")
        advanceTimeBy(100)
        vm.search("Seat")
        advanceTimeBy(319)
        assertTrue(h.repository.searches.isEmpty(), "still within the pause")
        assertTrue(vm.uiState.value.search.loading)
        assertEquals("Seat", vm.uiState.value.search.query)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Seat"), h.repository.searches)
        val search = vm.uiState.value.search
        assertEquals(listOf(seattle), search.results)
        assertFalse(search.loading)
        assertNull(search.error)
    }

    @Test
    fun fewerThanTwoLettersAreNeverSearched() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        vm.search("")
        vm.search("S")
        vm.search("  S ")
        advanceTimeBy(1_000)
        assertTrue(h.repository.searches.isEmpty())
        assertEquals(PlaceSearch(query = "  S "), vm.uiState.value.search)
    }

    @Test
    fun shorteningTheQueryCancelsTheSearchInFlightAndClearsTheResults() = runWeatherTest { h ->
        h.repository.onSearch = { Result.success(listOf(seattle)) }
        val vm = h.viewModel()
        runCurrent()
        vm.search("Sea")
        advanceTimeBy(400)
        assertEquals(listOf(seattle), vm.uiState.value.search.results)
        vm.search("S")
        advanceTimeBy(1_000)
        assertEquals(emptyList(), vm.uiState.value.search.results)
        assertEquals(1, h.repository.searches.size)
    }

    @Test
    fun aSearchThatFailsSaysSo() = runWeatherTest { h ->
        h.repository.onSearch = { Result.failure(IllegalStateException("offline")) }
        val vm = h.viewModel()
        runCurrent()
        vm.search("Sea")
        advanceTimeBy(400)
        val search = vm.uiState.value.search
        assertEquals(UiText.of(Res.string.weather_error_search), search.error)
        assertEquals(emptyList(), search.results)
        assertFalse(search.loading)
    }

    @Test
    fun clearingTheSearchStopsOneThatIsWaiting() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        vm.search("Sea")
        advanceTimeBy(100)
        vm.clearSearch()
        advanceTimeBy(1_000)
        assertTrue(h.repository.searches.isEmpty())
        assertEquals(PlaceSearch(), vm.uiState.value.search)
    }

    // ---- Radar --------------------------------------------------------------------------------

    private val timeline = RadarTimeline(RadarSource.US_MRMS, listOf(RadarFrame(at(0, 15), false, "https://example.test/{z}/{x}/{y}.png", 8)))

    @Test
    fun theRadarLoopLoadsWhenTheRadarIsVisible() = runWeatherTest(saved = listOf(seattle)) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(0, h.repository.radarCalls)
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(timeline, vm.uiState.value.radar.timeline)
        assertFalse(vm.uiState.value.radar.loading)
        assertNull(vm.uiState.value.radar.error)
        finish(vm)
    }

    @Test
    fun theRadarLoopIsKeptCurrentWhileVisibleAndLeftAloneWhenNot() = runWeatherTest(saved = listOf(seattle)) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        advanceTimeBy(150_001)
        assertEquals(2, h.repository.radarCalls)
        vm.setRadarVisible(false)
        advanceTimeBy(10 * 60_000L)
        assertEquals(2, h.repository.radarCalls)
        finish(vm)
    }

    @Test
    fun leavingTheAppStopsRadarPollingAndComingBackResumesIt() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(1, h.repository.radarCalls)

        vm.setActive(false)
        advanceTimeBy(10 * 60_000L)
        assertEquals(1, h.repository.radarCalls, "nothing polled while the app is off screen")

        vm.setActive(true)
        runCurrent()
        assertEquals(2, h.repository.radarCalls, "the radar was still wanted, so it starts again at once")
        advanceTimeBy(150_001)
        assertEquals(3, h.repository.radarCalls)
        finish(vm)
    }

    @Test
    fun aRadarNoLongerWantedIsNotResumedWhenTheAppReturns() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        vm.setRadarVisible(false)
        vm.setActive(false)
        vm.setActive(true)
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(1, h.repository.radarCalls)
        finish(vm)
    }

    @Test
    fun theRadarWantedWhileTheAppIsOffScreenStartsWhenItComesOn() = runWeatherTest(saved = listOf(seattle), setUp = { fence.here = null }) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(0, h.repository.radarCalls)
        vm.setActive(true)
        runCurrent()
        assertEquals(1, h.repository.radarCalls)
        finish(vm)
    }

    @Test
    fun aRadarFailureSaysSoAndKeepsNoLoop() = runWeatherTest(saved = listOf(seattle)) { h ->
        h.repository.onRadar = { Result.failure(IllegalStateException("down")) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        val radar = vm.uiState.value.radar
        assertEquals(UiText.of(Res.string.weather_error_radar), radar.error)
        assertNull(radar.timeline)
        assertFalse(radar.loading)
        finish(vm)
    }

    @Test
    fun aRadarFailureAfterASuccessKeepsTheLoopOnScreen() = runWeatherTest(saved = listOf(seattle)) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        h.repository.onRadar = { Result.failure(IllegalStateException("blip")) }
        advanceTimeBy(150_001)
        assertEquals(timeline, vm.uiState.value.radar.timeline)
        assertEquals(UiText.of(Res.string.weather_error_radar), vm.uiState.value.radar.error)
        finish(vm)
    }

    @Test
    fun theRadarIsForThePlaceInView() = runWeatherTest(saved = listOf(seattle, tokyo), setUp = { fence.here = null }) { h ->
        h.repository.onRadar = { Result.success(timeline) }
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.select(tokyo.id)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(listOf(35.6762 to 139.6503), h.repository.radarRequests)
        finish(vm)
    }

    @Test
    fun withNoPlaceThereIsNoRadarToLoad() = runWeatherTest(setUp = { fence.here = null }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.setRadarVisible(true)
        runCurrent()
        assertEquals(0, h.repository.radarCalls)
        finish(vm)
    }

    // ---- Preferences --------------------------------------------------------------------------

    @Test
    fun theUnitsAreSavedAndShownBack() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)
        vm.setUnits(metric)
        runCurrent()
        assertEquals(metric, vm.uiState.value.units)
        assertEquals(metric, h.preferences.observe().first().units)
    }

    @Test
    fun theStillSkyIsSavedAndShownBack() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        assertFalse(vm.uiState.value.preferences.stillSky)
        vm.setStillSky(true)
        runCurrent()
        assertTrue(vm.uiState.value.preferences.stillSky)
    }

    // ---- Notifications ------------------------------------------------------------------------

    @Test
    fun changingTheNoticeSettingsSavesThemAndSyncsTheSchedule() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        val off = WeatherNoticeSettings(enabled = false)
        vm.setNotices(off)
        runCurrent()
        assertEquals(off, h.preferences.observe().first().notices)
        assertEquals(listOf(false), h.scheduler.calls)
        vm.setNotices(WeatherNoticeSettings(enabled = true, extremes = false))
        runCurrent()
        assertEquals(listOf(false, true), h.scheduler.calls)
        assertEquals(WeatherNoticeSettings(enabled = true, extremes = false), vm.uiState.value.preferences.notices)
    }

    @Test
    fun turningNoticesOnAsksForPermissionWhenItIsNotGiven() = runWeatherTest(setUp = { }) { h ->
        h.alertNotifier.permission = NotificationPermission.NOT_DETERMINED
        val vm = h.viewModel()
        runCurrent()
        vm.setNotices(WeatherNoticeSettings(enabled = true))
        runCurrent()
        assertEquals(1, h.alertNotifier.asked)
        assertEquals(NotificationPermission.GRANTED, vm.uiState.value.notificationPermission)
    }

    @Test
    fun aRefusedPermissionIsShownAsDenied() = runWeatherTest { h ->
        h.alertNotifier.permission = NotificationPermission.NOT_DETERMINED
        h.alertNotifier.onAsk = NotificationPermission.DENIED
        val vm = h.viewModel()
        runCurrent()
        vm.setNotices(WeatherNoticeSettings(enabled = true))
        runCurrent()
        assertEquals(NotificationPermission.DENIED, vm.uiState.value.notificationPermission)
    }

    @Test
    fun noPermissionIsAskedForWhenItIsAlreadyGivenOrNoticesAreOff() = runWeatherTest { h ->
        val vm = h.viewModel()
        runCurrent()
        vm.setNotices(WeatherNoticeSettings(enabled = true))
        runCurrent()
        assertEquals(0, h.alertNotifier.asked)
        h.alertNotifier.permission = NotificationPermission.NOT_DETERMINED
        vm.setNotices(WeatherNoticeSettings(enabled = false))
        runCurrent()
        assertEquals(0, h.alertNotifier.asked)
    }

    @Test
    fun whereNoticesAreImpossibleNothingIsAskedAndThePermissionIsNull() = runWeatherTest { h ->
        h.alertNotifier.isSupported = false
        val vm = h.viewModel()
        runCurrent()
        vm.setNotices(WeatherNoticeSettings(enabled = true))
        runCurrent()
        assertEquals(0, h.alertNotifier.asked)
        assertNull(vm.uiState.value.notificationPermission)
    }

    @Test
    fun turningNoticesOnChecksForOneThatIsDueAtOnce() = runWeatherTest(setUp = {
        fence.here = null
        places.add(seattle)
    }) { h ->
        val warning = WeatherAlert(id = "urn:1", event = "Tornado Warning", severity = AlertSeverity.SEVERE)
        h.repository.onReport = { Result.success(WeatherData.report(now, alerts = listOf(warning))) }
        h.notifier.isSupported = true
        val vm = h.viewModel()
        runCurrent()
        vm.setNotices(WeatherNoticeSettings(enabled = true))
        runCurrent()
        assertEquals(listOf("alert:urn:1"), h.notifier.posted.map { it.id })
    }

    @Test
    fun theNotificationPermissionIsShownWhenTheFullAppOpens() = runWeatherTest { h ->
        h.alertNotifier.permission = NotificationPermission.DENIED
        val vm = h.viewModel()
        vm.setActive(true, full = true)
        runCurrent()
        assertEquals(NotificationPermission.DENIED, vm.uiState.value.notificationPermission)
        finish(vm)
    }

    @Test
    fun theDrawersCardDoesNotCheckPermissionOrNotices() = runWeatherTest(saved = listOf(seattle)) { h ->
        val warning = WeatherAlert(id = "urn:1", event = "Tornado Warning", severity = AlertSeverity.SEVERE)
        h.repository.onReport = { Result.success(WeatherData.report(now, alerts = listOf(warning))) }
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        assertNull(vm.uiState.value.notificationPermission)
        assertTrue(h.notifier.posted.isEmpty())
        finish(vm)
    }

    @Test
    fun openingTheFullAppChecksForDueNotices() = runWeatherTest { h ->
        val warning = WeatherAlert(id = "urn:1", event = "Tornado Warning", severity = AlertSeverity.SEVERE)
        h.repository.onReport = { Result.success(WeatherData.report(now, alerts = listOf(warning))) }
        val vm = h.viewModel()
        vm.setActive(true, full = true)
        runCurrent()
        assertEquals(listOf("alert:urn:1"), h.notifier.posted.map { it.id })
        finish(vm)
    }

    @Test
    fun theNotificationPermissionIsRequestedOrItsSettingsOpened() = runWeatherTest { h ->
        h.alertNotifier.permission = NotificationPermission.NOT_DETERMINED
        val vm = h.viewModel()
        runCurrent()
        vm.requestNotificationPermission()
        runCurrent()
        assertEquals(1, h.alertNotifier.asked)
        assertEquals(0, h.alertNotifier.settingsOpened)

        h.alertNotifier.permission = NotificationPermission.DENIED
        vm.requestNotificationPermission()
        runCurrent()
        assertEquals(1, h.alertNotifier.asked)
        assertEquals(1, h.alertNotifier.settingsOpened)
    }

    // ---- Location -----------------------------------------------------------------------------

    @Test
    fun thePhonesLocationSupportAndAccessAreShown() = runWeatherTest(setUp = { fence.access.value = LocationAccess.NOT_ASKED }) { h ->
        val vm = h.viewModel()
        runCurrent()
        assertTrue(vm.uiState.value.locationSupported)
        assertEquals(LocationAccess.NOT_ASKED, vm.uiState.value.locationAccess)
        h.fence.access.value = LocationAccess.DENIED
        runCurrent()
        assertEquals(LocationAccess.DENIED, vm.uiState.value.locationAccess)
    }

    @Test
    fun withoutPermissionNoFixIsTakenAndSavedCitiesStillLoad() = runWeatherTest(saved = listOf(seattle), setUp = { fence.access.value = LocationAccess.DENIED }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(listOf(seattle.id), vm.uiState.value.places.map { it.place.id })
        assertNotNull(vm.uiState.value.places.single().report)
        assertEquals(0, h.repository.nameRequests.size)
        finish(vm)
    }

    @Test
    fun grantingPermissionWhileOpenTakesAFixAtOnce() = runWeatherTest(setUp = { fence.access.value = LocationAccess.NOT_ASKED }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.uiState.value.places.isEmpty())
        h.fence.access.value = LocationAccess.WHILE_IN_USE
        runCurrent()
        assertEquals(listOf(Place.CURRENT_ID), vm.uiState.value.places.map { it.place.id })
        assertNotNull(vm.uiState.value.places.single().report)
        finish(vm)
    }

    @Test
    fun askingForLocationRequestsAccessThenUsesIt() = runWeatherTest(setUp = { fence.access.value = LocationAccess.NOT_ASKED }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        vm.requestLocation()
        runCurrent()
        assertEquals(1, h.fence.accessRequests)
        finish(vm)
    }

    @Test
    fun aFixIsNotRetakenEveryTimeTheDrawerOpens() = runWeatherTest { h ->
        val vm = h.viewModel()
        vm.setActive(true, full = false)
        runCurrent()
        val names = h.repository.nameRequests.size
        h.fence.here = GeoPoint(47.6062, -122.3321)
        vm.setActive(true, full = false)
        runCurrent()
        // Moved on, but only moments since the last fix: the first place is where it was.
        assertEquals(45.5234, vm.uiState.value.places.first().place.latitude)
        assertEquals(names, h.repository.nameRequests.size)
        assertEquals(1, h.fence.fixes)
        finish(vm)
    }

    @Test
    fun aRememberedHomeIsNamedWhenNoFixIsToBeHad() = runWeatherTest(setUp = {
        fence.here = null
        identity.saveCachedHome(HomeLocation(45.0, -122.0, 150.0))
    }) { h ->
        val vm = h.viewModel()
        vm.setActive(true)
        runCurrent()
        val here = vm.uiState.value.places.single().place
        assertEquals("Portland", here.name)
        assertEquals(45.0, here.latitude)
        finish(vm)
    }
}
