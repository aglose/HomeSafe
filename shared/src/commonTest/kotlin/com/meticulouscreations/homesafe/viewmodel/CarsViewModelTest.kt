package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.repository.CarRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveCarsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshCarsUseCase
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.household_cars_title
import homesafe.shared.generated.resources.household_cars_unreachable
import homesafe.shared.generated.resources.household_cars_unreachable_kept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The relay's cars, in memory: what the cars' map and Home's car pills are tested against. */
internal class FakeCarRepository(cars: List<HouseholdCar>? = null) : CarRepository {
    override val cars = MutableStateFlow(cars)
    var refreshes = 0
    var refreshFailure: Throwable? = null

    override suspend fun refresh(): Result<Unit> {
        refreshes++
        return refreshFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val now = 1_793_847_600L

    private val tesla = HouseholdCar(
        id = "andrews_tesla",
        name = "Andrew's Tesla",
        lastSeen = CarFix(37.77, -122.258, 270f, now - 600, moving = false, home = false),
        parked = null,
        asleep = false,
    )

    private fun viewModel(repo: FakeCarRepository) = CarsViewModel(
        ObserveCarsUseCase(repo),
        RefreshCarsUseCase(repo),
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(now)
        },
    )

    private fun TestScope.collecting(viewModel: CarsViewModel): StateFlow<CarsUiState> {
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel.uiState
    }

    @Test
    fun nothingIsSaidLoadedUntilTheRelayHasAnswered() = runTest(dispatcher) {
        val repo = FakeCarRepository()
        val state = collecting(viewModel(repo))
        assertFalse(state.value.loaded)
        repo.cars.value = listOf(tesla)
        runCurrent()
        assertTrue(state.value.loaded)
        assertEquals(listOf(tesla), state.value.cars)
        assertEquals(now, state.value.nowEpochSeconds)
    }

    @Test
    fun aRefreshThatFailsSaysSoAndStillCountsAsAnswered() = runTest(dispatcher) {
        val repo = FakeCarRepository()
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        repo.refreshFailure = IllegalStateException("Request timeout has expired [url=http://relay:8787/parking]")
        viewModel.refresh()
        runCurrent()
        assertEquals(1, repo.refreshes)
        assertTrue(state.value.loaded, "no cars to show, but not still loading either")
        assertEquals(UiText.of(Res.string.household_cars_unreachable), state.value.error, "the timeout's own text is for the logs")
        repo.refreshFailure = null
        viewModel.refresh()
        runCurrent()
        assertNull(state.value.error)
    }

    @Test
    fun aRefreshThatFailsWithCarsOnTheMapSaysTheyAreTheLastAnswer() = runTest(dispatcher) {
        val repo = FakeCarRepository()
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        repo.cars.value = listOf(tesla)
        repo.refreshFailure = IllegalStateException("HTTP 502")
        viewModel.refresh()
        runCurrent()
        assertEquals(listOf(tesla), state.value.cars)
        assertEquals(UiText.of(Res.string.household_cars_unreachable_kept), state.value.error)
    }

    @Test
    fun aFailureMeantForTheReaderIsShownAsItIs() = runTest(dispatcher) {
        val repo = FakeCarRepository()
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        // Any resource will do: what matters is that the exception's own text is what's shown.
        val said = UiText.of(Res.string.household_cars_title)
        repo.refreshFailure = LocalizedException(said, technical = "HTTP 401")
        viewModel.refresh()
        runCurrent()
        assertEquals(said, state.value.error)
    }
}
