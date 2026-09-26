package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.usecase.FakeCarTagClassifiers
import com.meticulouscreations.homesafe.domain.usecase.FakeNamingMoments
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.TagMomentCarUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The tag picker for a moment's unnamed car: who it opens for, the known cars it offers, tagging
 * as one of them or as a new car by name, and the landing prompt a notification's detection gets.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MomentCarTagViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val start = 1_789_399_000.0

    private fun event(id: String, label: String = "car", subLabel: String? = null) = MomentEvent(
        id = id,
        cameraName = "hikvision_1",
        label = label,
        subLabel = subLabel,
        startEpochSeconds = start,
        endEpochSeconds = start + 20,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
        zones = listOf("driveway"),
    )

    private val car = event("1789399000.5-abc123")

    private fun viewModel(classifiers: FakeCarTagClassifiers, moments: FakeNamingMoments = FakeNamingMoments()) = MomentCarTagViewModel(
        getClassifierModelsUseCase = GetClassifierModelsUseCase(classifiers),
        getClassifierDatasetUseCase = GetClassifierDatasetUseCase(classifiers),
        getDetectionUseCase = GetDetectionUseCase(classifiers),
        tagMomentCarUseCase = TagMomentCarUseCase(classifiers, moments),
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_789_400_000)
        },
    )

    @Test
    fun opensForAnUnnamedCarWithTheKnownCarsButNotNone() = runTest(dispatcher) {
        val vm = viewModel(FakeCarTagClassifiers())

        vm.open(car)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(car.id, state.target?.eventId)
        assertTrue(state.target!!.summary.startsWith("Car in the driveway · "), state.target.summary)
        assertEquals(listOf("andrews_tesla", "sarahs_car"), state.knownCars, "\"Not ours\" is what an unnamed car already is")
        assertFalse(state.isLoading)
    }

    @Test
    fun ignoresAnythingButAnUnnamedCar() = runTest(dispatcher) {
        val vm = viewModel(FakeCarTagClassifiers())

        vm.open(event("n", subLabel = "andrews_tesla"))
        vm.open(event("p", label = "person"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.target)
    }

    @Test
    fun theKnownCarsAreReadOnce() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers()
        val vm = viewModel(classifiers)

        vm.open(car)
        advanceUntilIdle()
        vm.dismiss()
        vm.open(event("1789399500.0-def456"))
        advanceUntilIdle()

        assertEquals(1, classifiers.datasetReads)
    }

    @Test
    fun taggingAsAKnownCarSaysWhatHappenedAndRemembersIt() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers()
        val moments = FakeNamingMoments()
        val vm = viewModel(classifiers, moments)
        vm.open(car)
        advanceUntilIdle()

        vm.tag("sarahs_car")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.done)
        assertEquals("Tagged as Sarah's Car. Retraining now.", state.notice)
        assertFalse(state.noticeIsError)
        assertEquals(mapOf(car.id to "sarahs_car"), state.tagged)
        assertEquals(listOf(car.id to "sarahs_car"), moments.named)

        vm.dismiss()
        assertNull(vm.uiState.value.target)
    }

    @Test
    fun aNewCarIsFiledUnderItsSluggedNameAndJoinsTheKnownCars() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers()
        val vm = viewModel(classifiers)
        vm.open(car)
        advanceUntilIdle()

        vm.setNewCarDraft("  Grandma's Van ")
        vm.tagAsNewCar()
        advanceUntilIdle()

        assertEquals(listOf("grandmas_van" to classifiers.frame.box), classifiers.examples)
        assertEquals(listOf("andrews_tesla", "grandmas_van", "sarahs_car"), vm.uiState.value.knownCars)
        assertEquals("Tagged as Grandma's Van. Retraining now.", vm.uiState.value.notice)
        assertEquals("", vm.uiState.value.newCarDraft)
    }

    @Test
    fun aNewCarNeedsAName() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers()
        val vm = viewModel(classifiers)
        vm.open(car)
        advanceUntilIdle()

        vm.setNewCarDraft("none")
        vm.tagAsNewCar()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.noticeIsError)
        assertFalse(vm.uiState.value.done)
        assertTrue(classifiers.examples.isEmpty())
    }

    @Test
    fun aFailedTagKeepsThePickerOpenToTryAgain() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers().apply {
            frameFails = true
        }
        val vm = viewModel(classifiers)
        vm.open(car)
        advanceUntilIdle()

        vm.tag("sarahs_car")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Couldn't save the tag: no recording", state.notice)
        assertTrue(state.noticeIsError)
        assertFalse(state.done)
        assertFalse(state.isSaving)
        assertNotNull(state.target)
    }

    @Test
    fun aServerWithoutACarClassifierSaysSo() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers().apply { models = emptyList() }
        val vm = viewModel(classifiers)

        vm.open(car)
        advanceUntilIdle()

        assertEquals("This server has no classifier that runs on cars.", vm.uiState.value.loadError)
        vm.tag("sarahs_car")
        advanceUntilIdle()
        assertTrue(classifiers.examples.isEmpty(), "nothing to file into")
    }

    @Test
    fun aNotificationsUnnamedCarGetsAPromptAndItsTagButtonOpensThePicker() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers(detections = mapOf(car.id to car))
        val vm = viewModel(classifiers)

        vm.lookUp(car.id, openPicker = true)
        advanceUntilIdle()

        assertEquals(car.id, vm.uiState.value.landed?.eventId)
        assertEquals(car.id, vm.uiState.value.target?.eventId)

        vm.tag("andrews_tesla")
        advanceUntilIdle()
        vm.dismiss()
        vm.openLanded()
        assertNull(vm.uiState.value.target, "already tagged: the prompt says so instead")
    }

    @Test
    fun aLookupThatFailsIsAskedAgainUntilTheServerAnswers() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers(detections = mapOf(car.id to car)).apply { detectionFailures = 2 }
        val vm = viewModel(classifiers)

        vm.lookUp(car.id, openPicker = true)
        runCurrent()
        assertNull(vm.uiState.value.landed, "nothing to offer yet")
        assertEquals(1, classifiers.detectionReads)

        // The screen asking again while the retries run doesn't start a second round.
        vm.lookUp(car.id, openPicker = true)
        advanceTimeBy(5_001)
        assertEquals(2, classifiers.detectionReads)
        assertNull(vm.uiState.value.landed)

        advanceUntilIdle()
        assertEquals(3, classifiers.detectionReads, "backed off, then answered")
        assertEquals(car.id, vm.uiState.value.landed?.eventId)
        assertEquals(car.id, vm.uiState.value.target?.eventId, "the notification's Tag car still opens the picker")
    }

    @Test
    fun anAnswerSettlesTheLookupEvenWhenFrigateNoLongerHasTheDetection() = runTest(dispatcher) {
        val classifiers = FakeCarTagClassifiers()
        val vm = viewModel(classifiers)

        vm.lookUp(car.id, openPicker = false)
        advanceUntilIdle()
        classifiers.detections = mapOf(car.id to car)
        vm.lookUp(car.id, openPicker = false)
        advanceUntilIdle()

        assertEquals(1, classifiers.detectionReads)
        assertNull(vm.uiState.value.landed)
    }

    @Test
    fun aNotificationOfANamedCarOrAPersonOffersNothing() = runTest(dispatcher) {
        val named = event("n", subLabel = "andrews_tesla")
        val person = event("p", label = "person")
        val vm = viewModel(FakeCarTagClassifiers(detections = mapOf(named.id to named, person.id to person)))

        vm.lookUp(named.id, openPicker = true)
        advanceUntilIdle()
        vm.lookUp(person.id, openPicker = false)
        advanceUntilIdle()
        vm.lookUp("gone", openPicker = false)
        advanceUntilIdle()

        assertNull(vm.uiState.value.landed)
        assertNull(vm.uiState.value.target)
    }
}
