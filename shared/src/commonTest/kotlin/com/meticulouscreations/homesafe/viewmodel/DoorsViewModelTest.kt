package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.DoorEvent
import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.domain.repository.DoorRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveFloorPlanUseCase
import com.meticulouscreations.homesafe.domain.usecase.RefreshDoorsUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetDoorNotifyUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetDoorSpotUseCase
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_load_failed
import homesafe.shared.generated.resources.doors_save_failed
import homesafe.shared.generated.resources.doors_spot_save_failed
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
internal class FakeDoorRepository(snapshot: DoorsSnapshot = DoorsSnapshot.EMPTY, plan: FloorPlan? = null) : DoorRepository {
    override val doors = MutableStateFlow(snapshot)
    override val floorPlan = MutableStateFlow(plan)
    val spots = mutableListOf<Pair<String, DoorSpot?>>()

    override suspend fun setSpot(doorId: String, spot: DoorSpot?): Result<Unit> {
        saveFailure?.let { return Result.failure(it) }
        spots += doorId to spot
        doors.value = doors.value.copy(doors = doors.value.doors.map { if (it.id == doorId) it.copy(spot = spot) else it })
        return Result.success(Unit)
    }
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
        ObserveFloorPlanUseCase(repo),
        SetDoorSpotUseCase(repo),
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

    private val plan = FloorPlan(
        listOf(
            PlanFloor("ground", "Ground floor", listOf(PlanPoint(0f, 0f), PlanPoint(30f, 0f), PlanPoint(30f, 40f), PlanPoint(0f, 40f))),
            PlanFloor("upper", "Upper floor", listOf(PlanPoint(0f, 0f), PlanPoint(30f, 0f), PlanPoint(30f, 25f), PlanPoint(0f, 25f))),
        ),
    )

    @Test
    fun thePlanShowsItsFirstFloorUntilAnotherIsPicked() = runTest(dispatcher) {
        val viewModel = viewModel(FakeDoorRepository(DoorsSnapshot(doors = listOf(front), answered = true), plan))
        val state = collecting(viewModel)
        assertEquals("ground", state.value.floorId)
        viewModel.selectFloor("upper")
        runCurrent()
        assertEquals("upper", state.value.floor?.id)
        viewModel.selectFloor("attic")
        runCurrent()
        assertEquals("ground", state.value.floorId, "a floor the plan doesn't have falls back to the first")
    }

    @Test
    fun placingPutsEachDoorWhereItIsTappedAndMovesOnToTheNext() = runTest(dispatcher) {
        val repo = FakeDoorRepository(DoorsSnapshot(doors = listOf(back.copy(spot = DoorSpot("upper", 3f, 4f)), front), answered = true), plan)
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        viewModel.startPlacing()
        runCurrent()
        assertTrue(state.value.placing)
        assertEquals("0x01", state.value.placingDoorId, "the first door not on the plan yet")

        viewModel.placeAt(12f, 39f)
        runCurrent()
        assertEquals(listOf<Pair<String, DoorSpot?>>("0x01" to DoorSpot("ground", 12f, 39f)), repo.spots)
        assertEquals(DoorSpot("ground", 12f, 39f), state.value.doors.single { it.id == "0x01" }.spot)

        viewModel.choosePlacingDoor("0x02")
        runCurrent()
        assertEquals("upper", state.value.floorId, "picking a placed door turns to its floor")
        viewModel.removeFromPlan("0x02")
        runCurrent()
        assertEquals<Pair<String, DoorSpot?>>("0x02" to null, repo.spots.last())

        viewModel.finishPlacing()
        runCurrent()
        assertFalse(state.value.placing)
    }

    @Test
    fun aSpotThatWontSaveSaysSo() = runTest(dispatcher) {
        val repo = FakeDoorRepository(DoorsSnapshot(doors = listOf(front), answered = true), plan).apply { saveFailure = IllegalStateException("relay down") }
        val viewModel = viewModel(repo)
        val state = collecting(viewModel)
        viewModel.startPlacing()
        runCurrent()
        viewModel.placeAt(1f, 1f)
        runCurrent()
        assertEquals(UiText.of(Res.string.doors_spot_save_failed, "relay down"), state.value.error)
    }

    @Test
    fun withNoPlanThereIsNothingToPlace() = runTest(dispatcher) {
        val viewModel = viewModel(FakeDoorRepository(DoorsSnapshot(doors = listOf(front), answered = true)))
        val state = collecting(viewModel)
        viewModel.startPlacing()
        runCurrent()
        assertFalse(state.value.placing)
        assertNull(state.value.floor)
    }
}
