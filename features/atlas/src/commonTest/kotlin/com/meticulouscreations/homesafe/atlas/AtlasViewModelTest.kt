package com.meticulouscreations.homesafe.atlas

import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.AtlasRepository
import com.meticulouscreations.homesafe.atlas.domain.AtlasSyncState
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.ui.AtlasFixtures
import com.meticulouscreations.homesafe.data.InMemoryApiKeyStore
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.maps.BaseMaps
import com.meticulouscreations.homesafe.maps.GoogleMapTiles
import com.meticulouscreations.homesafe.vault.InMemoryHouseholdKeys
import com.meticulouscreations.homesafe.weather.data.FakeWeb
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import com.meticulouscreations.homesafe.weather.data.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class AtlasViewModelTest {
    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val kept: AtlasHistory) : AtlasRepository {
        override val history = MutableStateFlow<AtlasHistory?>(null)
        override val sync = MutableStateFlow(AtlasSyncState())
        val refreshes = ArrayList<Boolean>()
        var trackReads = 0

        override suspend fun load() {
            if (history.value == null) history.value = kept
        }

        override suspend fun refresh(askDrive: Boolean) {
            refreshes += askDrive
        }

        override suspend fun tracks(): List<Track> {
            trackReads++
            return if (history.value?.isEmpty == false) AtlasFixtures.tracks else emptyList()
        }
    }

    private val clock = MutableClock(1_751_100_000)

    // The analysis on the test's own dispatcher: on Dispatchers.Default it could finish after a
    // test had reset Main, and its way back crashed the next test.
    private fun viewModel(repository: AtlasRepository) =
        AtlasViewModel(repository, clock, MapTileStore(FakeWeb().client, InMemoryWeatherDao(), clock, BaseMaps(InMemoryApiKeyStore(), InMemoryHouseholdKeys(), GoogleMapTiles(FakeWeb().client), clock)), analysisDispatcher = dispatcher)

    /** The history is worked out on another thread: steps the main one until [done], for a few seconds at most. */
    private suspend fun TestScope.until(done: () -> Boolean) {
        repeat(600) {
            runCurrent()
            if (done()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        fail("Still waiting after six seconds")
    }

    @Test
    fun theDrawersCardReadsTheDeviceAndAsksTheServerOnceButLeavesThePaths() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasFixtures.history)
        val viewModel = viewModel(repository)
        assertFalse(viewModel.uiState.value.loaded)

        viewModel.setActive(active = true, full = false)
        until { viewModel.uiState.value.loaded && !viewModel.uiState.value.analyzing }
        val state = viewModel.uiState.value
        assertEquals(AtlasFixtures.history.visits.size, state.history.visits.size)
        assertEquals(2, state.analysis.eras.size)
        assertNull(state.paths)
        // One small question: it is also what says the copy is this sign-in's to show.
        assertEquals(listOf(false), repository.refreshes)
        assertEquals(0, repository.trackReads)
    }

    @Test
    fun aHistoryTakenBackIsGoneFromTheScreen() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasFixtures.history)
        val viewModel = viewModel(repository)
        viewModel.setActive(active = true, full = true)
        until { viewModel.uiState.value.paths != null }

        // Someone else signed in: the repository holds the copy back until the relay answers for them.
        repository.history.value = null
        until { !viewModel.uiState.value.loaded }
        val state = viewModel.uiState.value
        assertTrue(state.history.isEmpty)
        assertTrue(state.analysis.places.isEmpty())
        assertNull(state.paths)
        assertFalse(state.empty)
    }

    @Test
    fun openingTheAppAsksTheServerAndReadsThePaths() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasFixtures.history)
        val viewModel = viewModel(repository)
        viewModel.setActive(active = true, full = true)
        until { viewModel.uiState.value.paths != null }
        val paths = assertNotNull(viewModel.uiState.value.paths)
        assertEquals(AtlasFixtures.tracks.size, paths.tracks.size)
        assertEquals(paths.tracks.size, paths.trackYears.size)
        assertTrue(paths.density.levels.isNotEmpty())
        assertEquals(listOf(false), repository.refreshes)

        // Closed and opened again a minute later: the same ask, not another.
        clock.seconds += 60
        viewModel.setActive(active = true, full = true)
        runCurrent()
        assertEquals(1, repository.refreshes.size)
        assertEquals(1, repository.trackReads)
        // An hour later it asks again.
        clock.seconds += 3600
        viewModel.setActive(active = true, full = true)
        runCurrent()
        assertEquals(2, repository.refreshes.size)
    }

    @Test
    fun aRefreshThatBringsNewHistoryIsWorkedOutAgainWithItsPaths() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasHistory.Empty)
        val viewModel = viewModel(repository)
        viewModel.setActive(active = true, full = true)
        until { viewModel.uiState.value.paths != null }
        assertTrue(viewModel.uiState.value.empty)
        assertTrue(viewModel.uiState.value.paths?.tracks?.isEmpty() == true)

        repository.history.value = AtlasFixtures.history
        until { viewModel.uiState.value.paths?.tracks?.isNotEmpty() == true && !viewModel.uiState.value.analyzing }
        assertFalse(viewModel.uiState.value.empty)
        assertEquals(2, viewModel.uiState.value.analysis.trips.size)
    }

    @Test
    fun lookingForANewExportTellsTheRelayToLookInDrive() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasFixtures.history)
        val viewModel = viewModel(repository)
        viewModel.refresh(askDrive = true)
        runCurrent()
        assertEquals(listOf(true), repository.refreshes)
    }

    @Test
    fun whileNothingIsOnScreenNothingIsRead() = runTest(dispatcher) {
        val repository = FakeRepository(AtlasFixtures.history)
        val viewModel = viewModel(repository)
        viewModel.setActive(active = false)
        runCurrent()
        assertFalse(viewModel.uiState.value.loaded)
        assertNull(repository.history.value)
    }
}
