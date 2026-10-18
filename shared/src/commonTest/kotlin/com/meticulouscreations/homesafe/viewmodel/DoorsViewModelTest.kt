package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.repository.DoorRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetDoorNotifyUseCase
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_load_failed
import homesafe.shared.generated.resources.doors_save_failed
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The relay's doors, in memory: what the Doors page and the Home strip are tested against. */
internal class FakeDoorRepository(snapshot: DoorsSnapshot = DoorsSnapshot.EMPTY) : DoorRepository {
    override val doors = MutableStateFlow(snapshot)
    var refreshes = 0
    var refreshFailure: Throwable? = null
    var saveFailure: Throwable? = null
    val saved = mutableListOf<Pair<String, DoorNotify>>()

    override suspend fun refresh(): Result<Unit> {
        refreshes++
        return refreshFailure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun setNotify(doorId: String, notify: DoorNotify): Result<Unit> {
        saveFailure?.let { return Result.failure(it) }
        saved += doorId to notify
        doors.value = doors.value.copy(doors = doors.value.doors.map { if (it.id == doorId) it.copy(notify = notify) else it })
        return Result.success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DoorsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val zone = TimeZone.currentSystemDefault()

    /** Noon on a Friday, on whatever clock the test runs on. */
    private val noon = LocalDateTime(2026, 10, 16, 12, 0).toInstant(zone)

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val front = Door("0x01", "Front door", open = false, changedAtMillis = at(16, 11))
    private val back = Door("0x02", "Back door", open = true, changedAtMillis = at(16, 11, 50), notify = DoorNotify.ALWAYS)

    private fun viewModel(repo: FakeDoorRepository) = DoorsViewModel(
        ObserveDoorsUseCase(repo),
        RefreshDoorsUseCase(repo),
        SetDoorNotifyUseCase(repo),
        object : Clock {
            override fun now(): Instant = noon
        },
    )

    private fun TestScope.collecting(viewModel: DoorsViewModel): StateFlow<DoorsUiState> {
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel.uiState
    }

    @Test
    fun theHistoryIsGroupedByDayWithEachDoorNamed() = runTest(dispatcher) {
        val repo = FakeDoorRepository(
            DoorsSnapshot(
                doors = listOf(back, front),
                events = listOf(
                    DoorEvent("0x02", open = true, atMillis = at(16, 11, 50), pushed = "home"),
                    DoorEvent("0x01", open = false, atMillis = at(16, 11)),
                    DoorEvent("0x01", open = true, atMillis = at(15, 23, 30)),
                    DoorEvent("0xgone", open = true, atMillis = at(15, 9)),
                    DoorEvent("0x01", open = true, atMillis = at(13, 8)),
                ),
                listening = true,
                answered = true,
            ),
        )
        val state = collecting(viewModel(repo)).value
        assertEquals(listOf(0, 1, 3), state.history.map { it.daysAgo })
        assertEquals(
            listOf(DoorHistoryEntry("Back door", open = true, atMillis = at(16, 11, 50), notified = true), DoorHistoryEntry("Front door", open = false, atMillis = at(16, 11), notified = false)),
            state.history[0].entries,
        )
        assertEquals(1, state.history[1].entries.size, "a sensor no longer on the network is left out")
        assertEquals(noon.toEpochMilliseconds(), state.nowMillis)
        assertTrue(state.loaded)
        assertTrue(state.listening)
    }

    @Test
    fun beforeTheRelayAnswersNothingIsSaidAboutTheDoors() = runTest(dispatcher) {
        val state = collecting(viewModel(FakeDoorRepository())).value
        assertFalse(state.loaded)
        assertTrue(state.listening, "no warning that the sensors can't be heard before anyone has asked")
    }

    @Test
    fun aRefreshThatFailsSaysSoAndStillCountsAsAnswered() = runTest(dispatcher) {
        val repo = FakeDoorRepository().apply { refreshFailure = IllegalStateException("boom") }
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        viewModel.refresh()
        runCurrent()
        assertEquals(1, repo.refreshes)
        assertTrue(state.value.loaded)
        assertEquals(UiText.of(Res.string.doors_load_failed, "boom"), state.value.error)
    }

    @Test
    fun choosingWhenADoorNotifiesSavesItAndAFailureSaysSo() = runTest(dispatcher) {
        val repo = FakeDoorRepository(DoorsSnapshot(doors = listOf(front), listening = true))
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        viewModel.setNotify("0x01", DoorNotify.ALWAYS)
        runCurrent()
        assertEquals(listOf("0x01" to DoorNotify.ALWAYS), repo.saved)
        assertEquals(DoorNotify.ALWAYS, state.value.doors.single().notify)
        assertNull(state.value.error)

        repo.saveFailure = IllegalStateException("relay down")
        viewModel.setNotify("0x01", DoorNotify.NEVER)
        runCurrent()
        assertEquals(UiText.of(Res.string.doors_save_failed, "relay down"), state.value.error)
    }

    @Test
    fun onceTheRelayHasAnsweredItsNotListeningIsShownEvenWithNoSensors() = runTest(dispatcher) {
        val state = collecting(viewModel(FakeDoorRepository(DoorsSnapshot(listening = false, answered = true)))).value
        assertTrue(state.loaded)
        assertFalse(state.listening, "no broker yet is exactly when the warning matters")
    }

    @Test
    fun aRelayFromBeforeDoorSensorsIsToldApart() = runTest(dispatcher) {
        val state = collecting(viewModel(FakeDoorRepository(DoorsSnapshot(supported = false)))).value
        assertFalse(state.supported)
    }
}
