package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierQueueImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetTrackedObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelClassifierCropUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The camera screen's "On camera now" section: which cars in view get a card, that the server is
 * only asked for datasets when something a classifier runs on is in view, and that a filed car
 * stays gone although Frigate keeps queueing crops of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveLabelingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeClassifiers(
        var tracked: List<TrackedObject>,
        var dataset: ClassifierDataset,
    ) : ClassifierRepository {
        var modelReads = 0
        var datasetReads = 0
        var trackedFails = false
        var labelFails = false
        val labelled = mutableListOf<Triple<String, String, String>>()

        override suspend fun getModels(): Result<List<ClassifierModel>> {
            modelReads++
            return Result.success(listOf(dataset.model))
        }

        override suspend fun getDataset(modelName: String): Result<ClassifierDataset> {
            datasetReads++
            return Result.success(dataset)
        }

        override suspend fun getTrackedObjects(cameraName: String): Result<List<TrackedObject>> =
            if (trackedFails) Result.failure(IllegalStateException("offline")) else Result.success(tracked)

        override suspend fun createCategory(modelName: String, category: String) = Result.success(Unit)

        override suspend fun label(modelName: String, fileName: String, category: String): Result<Unit> {
            if (labelFails) return Result.failure(IllegalStateException("file not found"))
            labelled += Triple(modelName, fileName, category)
            dataset = dataset.copy(queue = dataset.queue.filterNot { it.fileName == fileName })
            return Result.success(Unit)
        }

        override suspend fun discard(modelName: String, fileNames: List<String>) = Result.success(Unit)
        override suspend fun train(modelName: String) = Result.success(Unit)
        override suspend fun getLatestFrame(cameraName: String): Result<ByteArray> = fail("unused")
        override suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> = fail("unused")
        override suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit> = fail("unused")
        override fun queueImageUrl(modelName: String, fileName: String) = "http://frigate/clips/$modelName/train/$fileName"
    }

    private val arriving = TrackedObject("1788832130.376381-6wpol8", "car", subLabel = null)
    private val parkedNeighbour = TrackedObject("1788830000.123456-hkvbhs", "car", subLabel = null)
    private val person = TrackedObject("1788832140.000000-p3rs0n", "person", subLabel = null)

    private val arrivingCrop = UnlabeledCrop.fromFileName("${arriving.eventId}-1788832131.5-sarahs_tesla-0.64.webp")
    private val parkedCrop = UnlabeledCrop.fromFileName("${parkedNeighbour.eventId}-1788832120.1-none-1.0.webp")

    private val dataset = ClassifierDataset(
        model = ClassifierModel("known_cars", listOf("car")),
        categoryCounts = mapOf("none" to 34, "sarahs_tesla" to 8),
        queue = listOf(arrivingCrop, parkedCrop),
        hasTrained = true,
        newImagesSinceTraining = 0,
    )

    private fun viewModel(repo: ClassifierRepository) = LiveLabelingViewModel(
        cameraName = "driveway",
        getTrackedObjectsUseCase = GetTrackedObjectsUseCase(repo),
        getClassifierModelsUseCase = GetClassifierModelsUseCase(repo),
        getClassifierDatasetUseCase = GetClassifierDatasetUseCase(repo),
        getClassifierQueueImageUrlUseCase = GetClassifierQueueImageUrlUseCase(repo),
        labelClassifierCropUseCase = LabelClassifierCropUseCase(repo),
    )

    @Test
    fun nothingInViewAsksForNeitherModelsNorDatasets() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = emptyList(), dataset = dataset)
        val vm = viewModel(repo)

        vm.refresh()

        assertTrue(vm.uiState.value.cards.isEmpty())
        assertEquals(0, repo.modelReads)
        assertEquals(0, repo.datasetReads)
    }

    @Test
    fun somethingNoClassifierRunsOnDoesNotReadADataset() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(person), dataset = dataset)
        val vm = viewModel(repo)

        vm.refresh()

        assertTrue(vm.uiState.value.cards.isEmpty())
        assertEquals(0, repo.datasetReads, "a person alone isn't worth the dataset's several requests")
    }

    @Test
    fun carsInViewBecomeCardsWithTheSureOnesFoldedAway() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(parkedNeighbour, person, arriving), dataset = dataset)
        val vm = viewModel(repo)

        vm.refresh()
        vm.refresh()

        val state = vm.uiState.value
        assertEquals(listOf(arrivingCrop), state.uncertainCards.map { it.candidate.crop })
        assertEquals(listOf(parkedCrop), state.confidentCards.map { it.candidate.crop }, "the parked neighbour's none-1.0")
        val card = state.uncertainCards.single()
        assertEquals("known_cars/${arriving.eventId}", card.key)
        assertEquals(listOf("sarahs_tesla", "none"), card.categories)
        assertEquals("http://frigate/clips/known_cars/train/${arrivingCrop.fileName}", card.imageUrl)
        assertEquals(1, repo.modelReads, "the models are read once for the visit")
        assertEquals(1, repo.datasetReads, "the same cars in view don't re-read the queue")
    }

    /** A parked car stays tracked for hours; the datasets are several requests each. */
    @Test
    fun theQueueIsReReadWhenTheCarsChangeOrEveryFewRefreshes() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(parkedNeighbour), dataset = dataset)
        val vm = viewModel(repo)

        vm.refresh()
        assertEquals(1, repo.datasetReads)

        repo.tracked = listOf(parkedNeighbour, arriving)
        vm.refresh()
        assertEquals(2, repo.datasetReads, "a car pulled in")
        assertEquals(2, vm.uiState.value.cards.size)

        repeat(LiveLabelingViewModel.DATASET_REREAD_EVERY - 1) { vm.refresh() }
        assertEquals(2, repo.datasetReads, "nothing changed")
        vm.refresh()
        assertEquals(3, repo.datasetReads, "but a crop saved since still turns up")

        repo.tracked = listOf(parkedNeighbour)
        vm.refresh()
        assertEquals(listOf(parkedCrop), vm.uiState.value.cards.map { it.candidate.crop }, "a car that left goes at once")
    }

    @Test
    fun aFiledCarStaysHiddenThoughFrigateKeepsQueueingItsCrops() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(arriving), dataset = dataset)
        val vm = viewModel(repo)
        vm.refresh()

        vm.label(vm.uiState.value.cards.single(), "sarahs_tesla")
        runCurrent()

        assertEquals(listOf(Triple("known_cars", arrivingCrop.fileName, "sarahs_tesla")), repo.labelled)
        assertTrue(vm.uiState.value.cards.isEmpty())
        assertTrue(vm.uiState.value.busyKeys.isEmpty())
        assertEquals("sarahs_tesla", vm.uiState.value.filedAs)

        // The car is still in the driveway and Frigate classified it again.
        val nextCrop = UnlabeledCrop.fromFileName("${arriving.eventId}-1788832141.2-none-0.55.webp")
        repo.dataset = repo.dataset.copy(queue = repo.dataset.queue + nextCrop)
        vm.refresh()
        assertTrue(vm.uiState.value.cards.isEmpty(), "filed once is enough for this visit")

        advanceTimeBy(LiveLabelingViewModel.NOTICE_MS + 1)
        assertNull(vm.uiState.value.filedAs, "the notice clears itself")
    }

    @Test
    fun aFailedFilingKeepsTheCarAndSaysWhy() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(arriving), dataset = dataset).apply { labelFails = true }
        val vm = viewModel(repo)
        vm.refresh()

        vm.label(vm.uiState.value.cards.single(), "sarahs_tesla")
        runCurrent()

        val state = vm.uiState.value
        assertEquals(1, state.cards.size, "still there to try again")
        assertTrue(state.busyKeys.isEmpty())
        assertNull(state.filedAs)
        assertEquals("Couldn't save: file not found", state.error)
    }

    @Test
    fun aDroppedReadKeepsTheCardsAlreadyShown() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(arriving), dataset = dataset)
        val vm = viewModel(repo)
        vm.refresh()

        repo.trackedFails = true
        vm.refresh()

        assertEquals(1, vm.uiState.value.cards.size)
    }

    @Test
    fun aCarThatDroveOffTakesItsCardWithIt() = runTest(dispatcher) {
        val repo = FakeClassifiers(tracked = listOf(arriving), dataset = dataset)
        val vm = viewModel(repo)
        vm.refresh()

        repo.tracked = emptyList()
        vm.refresh()

        assertTrue(vm.uiState.value.cards.isEmpty())
    }
}
