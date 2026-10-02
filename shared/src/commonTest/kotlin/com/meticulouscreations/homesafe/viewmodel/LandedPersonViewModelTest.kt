package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentTexts
import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.repository.PhantomRepository
import com.meticulouscreations.homesafe.domain.usecase.FakeCarTagClassifiers
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.MarkNotAPersonUseCase
import com.meticulouscreations.homesafe.domain.usecase.UndoNotAPersonUseCase
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_not_a_person_mark_failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * "Not a person" on the detection a notification opened: who it's offered for, the mark and its
 * Undo, and what a mark the relay refused says.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class LandedPersonViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val start = 1_789_399_000.0

    private fun event(id: String, label: String = "person", subLabel: String? = null) = MomentEvent(
        id = id,
        cameraName = "amcrest_1",
        label = label,
        subLabel = subLabel,
        startEpochSeconds = start,
        endEpochSeconds = start + 2,
        topScore = 0.74,
        hasClip = true,
        hasSnapshot = false,
    )

    private val phantom = event("1789399000.5-p1")

    private class FakePhantoms : PhantomRepository {
        override val spots = MutableStateFlow<List<PhantomSpot>>(emptyList())
        var refuse = false
        val marked = mutableListOf<String>()
        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
        override suspend fun markNotAPerson(eventId: String): Result<PhantomSpot> {
            if (refuse) return Result.failure(IllegalStateException("Relay answered 502 Bad Gateway"))
            marked += eventId
            return Result.success(PhantomSpot(eventId, "amcrest_1", DetectionBox(0.05, 0.3, 0.22, 0.4)))
        }
        override suspend fun undoNotAPerson(eventId: String): Result<Unit> {
            if (refuse) return Result.failure(IllegalStateException("Relay answered 502 Bad Gateway"))
            marked -= eventId
            return Result.success(Unit)
        }
    }

    private fun viewModel(classifiers: FakeCarTagClassifiers, phantoms: FakePhantoms = FakePhantoms()) = LandedPersonViewModel(
        getDetectionUseCase = GetDetectionUseCase(classifiers),
        markNotAPersonUseCase = MarkNotAPersonUseCase(phantoms),
        undoNotAPersonUseCase = UndoNotAPersonUseCase(phantoms),
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_789_400_000)
        },
    )

    @Test
    fun offersTheMarkForAPersonNobodyNamed() = runTest(dispatcher) {
        val vm = viewModel(FakeCarTagClassifiers(detections = mapOf(phantom.id to phantom)))

        vm.lookUp(phantom.id)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(phantom.id, state.eventId)
        // The time's wording depends on the zone the test runs in; the moment it names doesn't.
        assertEquals(MomentTexts.detected(MomentTexts.person), (state.summary as UiText.Joined).parts.first())
        assertEquals("Front Door", state.cameraDisplayName)
        assertFalse(state.marked)
    }

    @Test
    fun offersNothingForAFaceFrigateKnowsOrAnythingButAPerson() = runTest(dispatcher) {
        val andrew = event("1789399000.5-p2", subLabel = "andrew")
        val car = event("1789399000.5-c1", label = "car")
        val vm = viewModel(FakeCarTagClassifiers(detections = mapOf(andrew.id to andrew, car.id to car)))

        vm.lookUp(andrew.id)
        advanceUntilIdle()
        assertNull(vm.uiState.value.eventId)

        vm.lookUp(car.id)
        advanceUntilIdle()
        assertNull(vm.uiState.value.eventId)
    }

    @Test
    fun marksAndTakesTheMarkBack() = runTest(dispatcher) {
        val phantoms = FakePhantoms()
        val vm = viewModel(FakeCarTagClassifiers(detections = mapOf(phantom.id to phantom)), phantoms)
        vm.lookUp(phantom.id)
        advanceUntilIdle()

        vm.mark()
        assertTrue(vm.uiState.value.isSaving)
        vm.mark()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.marked)
        assertEquals(listOf(phantom.id), phantoms.marked, "marked once, however often it's tapped")

        vm.undo()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.marked)
        assertEquals(emptyList(), phantoms.marked)
    }

    @Test
    fun aRefusedMarkSaysWhyAndCanBeTriedAgain() = runTest(dispatcher) {
        val phantoms = FakePhantoms().apply { refuse = true }
        val vm = viewModel(FakeCarTagClassifiers(detections = mapOf(phantom.id to phantom)), phantoms)
        vm.lookUp(phantom.id)
        advanceUntilIdle()

        vm.mark()
        advanceUntilIdle()
        assertEquals(UiText.of(Res.string.moments_not_a_person_mark_failed, "Relay answered 502 Bad Gateway"), vm.uiState.value.error)
        assertFalse(vm.uiState.value.marked)

        phantoms.refuse = false
        vm.mark()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.marked)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun aLookUpThatFailsIsAskedAgainUntilItIsAnswered() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers(detections = mapOf(phantom.id to phantom)).apply { detectionFailures = 2 }
        val vm = viewModel(classifiers)

        vm.lookUp(phantom.id)
        runCurrent()
        assertNull(vm.uiState.value.eventId)
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(vm.uiState.value.eventId, "asked again, failed again")
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(phantom.id, vm.uiState.value.eventId)
        assertEquals(3, classifiers.detectionReads)
    }
}
