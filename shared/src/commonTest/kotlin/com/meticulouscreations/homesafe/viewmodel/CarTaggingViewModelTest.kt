package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.EventFrame
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.domain.usecase.GetCameraFrameUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetTrackedObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.TagCarUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tagging a car on a frozen frame: picking a tracked car by tap or by rectangle, and what a tag
 * sends where — the example to the dataset, the name to the tracked event, a retrain, and a
 * nudge to the in-view strip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarTaggingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun box(x: Double, y: Double, w: Double, h: Double) = SeenBox(x, y, w, h, epochSeconds = null)

    private val tesla = TrackedObject("1.0-tesla", "car", subLabel = "andrews_tesla", box = box(0.10, 0.50, 0.30, 0.25))
    private val unnamed = TrackedObject("2.0-merc", "car", subLabel = null, box = box(0.45, 0.52, 0.28, 0.22))
    private val person = TrackedObject("3.0-person", "person", subLabel = null, box = box(0.8, 0.3, 0.05, 0.2))

    /** SOI + SOF0 for a 1280x720 frame: all the view model needs of a JPEG. */
    private val frame = listOf(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x02, 0xD0, 0x05, 0x00, 0x03).map { it.toByte() }.toByteArray()

    private class FakeClassifiers(var tracked: List<TrackedObject>, val jpeg: ByteArray) : ClassifierRepository {
        var models = listOf(ClassifierModel("known_cars", listOf("car")))
        var exampleFails = false
        var trainFails = false
        val examples = mutableListOf<Triple<String, String, SeenBox>>()
        val names = mutableListOf<Pair<String, String?>>()
        var trained = 0
        var frameReads = 0

        override suspend fun getModels(): Result<List<ClassifierModel>> = Result.success(models)
        override suspend fun getDataset(modelName: String): Result<ClassifierDataset> = Result.success(
            ClassifierDataset(
                model = models.first(),
                categoryCounts = mapOf("none" to 20, "andrews_tesla" to 12, "sarahs_car" to 6),
                queue = emptyList(),
                hasTrained = true,
                newImagesSinceTraining = 0,
            ),
        )
        override suspend fun getTrackedObjects(cameraName: String): Result<List<TrackedObject>> = Result.success(tracked)
        override suspend fun getLatestFrame(cameraName: String): Result<ByteArray> {
            frameReads++
            return Result.success(jpeg)
        }
        override suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> {
            if (exampleFails) return Result.failure(IllegalStateException("relay down"))
            examples += Triple(modelName, category, box)
            return Result.success(Unit)
        }
        override suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit> {
            names += eventId to subLabel
            tracked = tracked.map { if (it.eventId == eventId) it.copy(subLabel = subLabel) else it }
            return Result.success(Unit)
        }
        override suspend fun train(modelName: String): Result<Unit> {
            if (trainFails) return Result.failure(IllegalStateException("already training"))
            trained++
            return Result.success(Unit)
        }
        override suspend fun createCategory(modelName: String, category: String) = fail("unused")
        override suspend fun label(modelName: String, fileName: String, category: String) = fail("unused")
        override suspend fun discard(modelName: String, fileNames: List<String>) = fail("unused")
        override fun queueImageUrl(modelName: String, fileName: String): String? = null
        override suspend fun getQueue(modelName: String): Result<List<UnlabeledCrop>> = fail("unused")
        override suspend fun getDetection(eventId: String): Result<MomentEvent?> = fail("unused")
        override suspend fun getEventFrame(eventId: String): Result<EventFrame> = fail("unused")
    }

    private class FakeMoments : MomentsRepository {
        var nudges = 0
        override fun refreshStationaryObjects() {
            nudges++
        }
        override fun nameCar(eventId: String, subLabel: String) = fail("unused")
        override fun observeMoments(): Flow<List<MomentEvent>> = fail("unused")
        override fun observeError(): Flow<String?> = fail("unused")
        override fun observePaging(): Flow<MomentsPaging> = fail("unused")
        override suspend fun loadOlder() = fail("unused")
        override fun showBefore(epochSeconds: Double?) = fail("unused")
        override fun showCamera(cameraName: String?) = fail("unused")
        override fun observeRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double): Flow<List<MomentEvent>> = fail("unused")
        override fun observeStationaryObjects(): Flow<List<StationaryObject>> = fail("unused")
        override fun observeLatestMoment(): Flow<MomentEvent?> = fail("unused")
        override suspend fun refresh() = fail("unused")
        override suspend fun getClipStream(eventId: String): RecordingStream = fail("unused")
        override suspend fun getClipDownloadUrl(eventId: String): RecordingStream = fail("unused")
    }

    private fun viewModel(classifiers: ClassifierRepository, moments: MomentsRepository = FakeMoments()) = CarTaggingViewModel(
        cameraName = "hikvision_1",
        getClassifierModelsUseCase = GetClassifierModelsUseCase(classifiers),
        getClassifierDatasetUseCase = GetClassifierDatasetUseCase(classifiers),
        getCameraFrameUseCase = GetCameraFrameUseCase(classifiers),
        getTrackedObjectsUseCase = GetTrackedObjectsUseCase(classifiers),
        tagCarUseCase = TagCarUseCase(classifiers, moments),
    )

    @Test
    fun opensOnAFrameWithTheCarsTrackedOnIt() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla, person, unnamed), jpeg = frame)
        val vm = viewModel(repo)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertSame(frame, state.frame?.jpeg)
        assertEquals(1280, state.frame?.width)
        assertEquals(720, state.frame?.height)
        assertEquals(listOf(tesla, unnamed), state.trackedCars, "only what the car classifier runs on")
        assertEquals(listOf("andrews_tesla", "sarahs_car", "none"), state.categories)
    }

    @Test
    fun aServerWithNoCarClassifierSaysSo() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = emptyList(), jpeg = frame).apply { models = listOf(ClassifierModel("birds", listOf("bird"))) }
        val vm = viewModel(repo)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.loadError)
        assertNull(vm.uiState.value.frame)
    }

    @Test
    fun aTapPicksTheTrackedCarUnderIt() = runTest(dispatcher) {
        val vm = viewModel(FakeClassifiers(tracked = listOf(tesla, unnamed), jpeg = frame))
        advanceUntilIdle()

        vm.tapAt(0.5, 0.6)
        assertEquals(unnamed.eventId, vm.uiState.value.selectedEventId)
        assertEquals(unnamed.box, vm.uiState.value.selection)

        vm.tapAt(0.95, 0.05)
        assertNull(vm.uiState.value.selection, "a tap on nothing clears the choice")
    }

    @Test
    fun aRectangleRoundATrackedCarIsAboutThatCar() = runTest(dispatcher) {
        val vm = viewModel(FakeClassifiers(tracked = listOf(tesla, unnamed), jpeg = frame))
        advanceUntilIdle()

        val drawn = box(0.08, 0.48, 0.34, 0.30)
        vm.drawBox(drawn)

        assertEquals(drawn, vm.uiState.value.selection, "the example is cut from what was drawn")
        assertEquals(tesla, vm.uiState.value.selectedCar)
    }

    @Test
    fun taggingATrackedCarTrainsItAndNamesItInView() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla, unnamed), jpeg = frame)
        val moments = FakeMoments()
        val vm = viewModel(repo, moments)
        advanceUntilIdle()

        vm.tapAt(0.5, 0.6)
        vm.tag("sarahs_car")
        advanceUntilIdle()

        assertEquals(listOf(Triple("known_cars", "sarahs_car", unnamed.box!!)), repo.examples)
        assertEquals<List<Pair<String, String?>>>(listOf(unnamed.eventId to "sarahs_car"), repo.names)
        assertEquals(1, repo.trained)
        assertEquals(1, moments.nudges, "the in-view strip looks again straight away")
        val state = vm.uiState.value
        assertNull(state.selection)
        assertFalse(state.noticeIsError)
        assertTrue(state.notice.orEmpty().contains("in view now"), state.notice)
        assertEquals("sarahs_car", state.trackedCars.first { it.eventId == unnamed.eventId }.subLabel, "the fresh frame shows the new name")
        assertEquals(2, repo.frameReads)
    }

    @Test
    fun aCarFrigateIsNotTrackingOnlyTeachesTheModel() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla), jpeg = frame)
        val moments = FakeMoments()
        val vm = viewModel(repo, moments)
        advanceUntilIdle()

        vm.drawBox(box(0.7, 0.1, 0.2, 0.15))
        assertNull(vm.uiState.value.selectedEventId)
        vm.tag("sarahs_car")
        advanceUntilIdle()

        assertEquals(1, repo.examples.size)
        assertTrue(repo.names.isEmpty())
        assertEquals(1, repo.trained)
        assertEquals(0, moments.nudges)
        assertTrue(vm.uiState.value.notice.orEmpty().contains("isn't tracking"), vm.uiState.value.notice)
    }

    @Test
    fun notOursClearsTheTrackedCarsName() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla), jpeg = frame)
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.tapAt(0.2, 0.6)
        vm.tag(ClassifierDataset.NONE_CATEGORY)
        advanceUntilIdle()

        assertEquals("none", repo.examples.single().second, "still a training example")
        assertEquals<List<Pair<String, String?>>>(listOf(tesla.eventId to null), repo.names, "but never a sub-label")
    }

    @Test
    fun aFailedExampleSavesNothingElse() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla), jpeg = frame).apply { exampleFails = true }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.tapAt(0.2, 0.6)
        vm.tag("andrews_tesla")
        advanceUntilIdle()

        assertTrue(repo.names.isEmpty())
        assertEquals(0, repo.trained)
        val state = vm.uiState.value
        assertTrue(state.noticeIsError)
        assertEquals(tesla.box, state.selection, "the choice stays for another try")
        assertFalse(state.isSaving)
    }

    @Test
    fun aRefusedRetrainStillKeepsTheTag() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(tesla), jpeg = frame).apply { trainFails = true }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.tapAt(0.2, 0.6)
        vm.tag("andrews_tesla")
        advanceUntilIdle()

        assertEquals(1, repo.names.size)
        assertFalse(vm.uiState.value.noticeIsError)
        assertTrue(vm.uiState.value.notice.orEmpty().contains("next training"), vm.uiState.value.notice)
    }
}
