package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.EventFrame
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.repository.CarProfileRepository
import com.meticulouscreations.homesafe.domain.repository.ClassifierRepository
import com.meticulouscreations.homesafe.domain.usecase.CreateClassifierCategoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.DiscardClassifierCropsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetCarProfilesUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierQueueImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelClassifierCropUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveCarProfileUseCase
import com.meticulouscreations.homesafe.domain.usecase.TrainClassifierUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The confident-crops shelf: the crops the model is 100 % sure about are hidden until asked for,
 * and cleared in one request rather than one tap per crop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassifierLabelingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeClassifiers(var dataset: ClassifierDataset, var discardFails: Boolean = false) : ClassifierRepository {
        val discarded = mutableListOf<List<String>>()
        override suspend fun getModels() = Result.success(listOf(dataset.model))
        override suspend fun getDataset(modelName: String) = Result.success(dataset)
        override suspend fun getTrackedObjects(cameraName: String) = Result.success(emptyList<TrackedObject>())
        override suspend fun createCategory(modelName: String, category: String) = Result.success(Unit)
        override suspend fun label(modelName: String, fileName: String, category: String) = Result.success(Unit)
        override suspend fun discard(modelName: String, fileNames: List<String>): Result<Unit> {
            discarded += fileNames
            if (discardFails) return Result.failure(IllegalStateException("server said no"))
            dataset = dataset.copy(queue = dataset.queue.filterNot { it.fileName in fileNames })
            return Result.success(Unit)
        }
        override suspend fun train(modelName: String) = Result.success(Unit)
        override suspend fun getLatestFrame(cameraName: String): Result<ByteArray> = fail("unused")
        override suspend fun addExample(modelName: String, category: String, frame: ByteArray, box: SeenBox): Result<Unit> = fail("unused")
        override suspend fun nameTrackedObject(eventId: String, subLabel: String?): Result<Unit> = fail("unused")
        override fun queueImageUrl(modelName: String, fileName: String) = "http://frigate/clips/$modelName/train/$fileName"
        override suspend fun getQueue(modelName: String): Result<List<UnlabeledCrop>> = fail("unused")
        override suspend fun getDetection(eventId: String): Result<MomentEvent?> = fail("unused")
        override suspend fun getEventFrame(eventId: String): Result<EventFrame> = fail("unused")
    }

    private val sureNotOurs = UnlabeledCrop.fromFileName("1788832095.985398-hkvbhs-1788832096.567646-none-1.0.webp")
    private val sureOurs = UnlabeledCrop.fromFileName("1788832091.745689-e2bxi0-1788832104.148959-sarahs_tesla-1.0.webp")
    private val unsure = UnlabeledCrop.fromFileName("1788832130.376381-6wpol8-1788832130.907575-none-0.98.webp")

    private val dataset = ClassifierDataset(
        model = ClassifierModel("known_cars", listOf("car")),
        categoryCounts = mapOf("none" to 34, "sarahs_tesla" to 8),
        queue = listOf(sureNotOurs, unsure, sureOurs),
        hasTrained = true,
        newImagesSinceTraining = 0,
    )

    private class FakeCarProfiles(var profiles: CarProfiles? = CARS) : CarProfileRepository {
        val saved = mutableListOf<CarProfile>()
        var saveFails = false

        override suspend fun getProfiles(): Result<CarProfiles> = profiles?.let { Result.success(it) } ?: Result.failure(Exception("no relay"))

        override suspend fun saveProfile(profile: CarProfile): Result<CarProfile> {
            if (saveFails) return Result.failure(Exception("relay said no"))
            saved += profile
            return Result.success(profile.copy(make = profile.make.lowercase()))
        }
    }

    private fun viewModel(repo: ClassifierRepository, cars: CarProfileRepository = FakeCarProfiles()) = ClassifierLabelingViewModel(
        modelName = "known_cars",
        getClassifierDatasetUseCase = GetClassifierDatasetUseCase(repo),
        getClassifierQueueImageUrlUseCase = GetClassifierQueueImageUrlUseCase(repo),
        labelClassifierCropUseCase = LabelClassifierCropUseCase(repo),
        discardClassifierCropsUseCase = DiscardClassifierCropsUseCase(repo),
        createClassifierCategoryUseCase = CreateClassifierCategoryUseCase(repo),
        trainClassifierUseCase = TrainClassifierUseCase(repo),
        getCarProfilesUseCase = GetCarProfilesUseCase(cars),
        saveCarProfileUseCase = SaveCarProfileUseCase(cars),
    )

    @Test
    fun aCarsProfileIsEditedAndSavedWithItsPlateAsTheRelayReadsIt() = runTest(dispatcher) {
        val cars = FakeCarProfiles()
        val vm = viewModel(FakeClassifiers(dataset), cars)
        advanceUntilIdle()
        vm.editProfile("sarahs_tesla")
        assertEquals(CarProfile("sarahs_tesla"), vm.uiState.value.profileDraft, "nothing on file yet")

        vm.updateProfileDraft(CarProfile("sarahs_tesla", make = "Tesla", model = " Model Y ", colour = "red", plate = "9xyz-789"))
        vm.saveProfile()
        advanceUntilIdle()

        assertEquals(listOf(CarProfile("sarahs_tesla", make = "Tesla", model = "Model Y", colour = "red", plate = "9XYZ789")), cars.saved)
        val state = vm.uiState.value
        assertNull(state.profileDraft, "the dialog closes")
        assertEquals(
            listOf("andrews_tesla", "sarahs_tesla"),
            state.carProfiles?.profiles?.map { it.name },
        )
        assertEquals("tesla", state.carProfiles?.profileOf("sarahs_tesla")?.make, "as the relay answered it")
    }

    @Test
    fun aPlateTooShortIsRefusedBeforeAsking() = runTest(dispatcher) {
        val cars = FakeCarProfiles()
        val vm = viewModel(FakeClassifiers(dataset), cars)
        advanceUntilIdle()
        vm.editProfile("andrews_tesla")
        vm.updateProfileDraft(vm.uiState.value.profileDraft!!.copy(plate = "8-A"))
        vm.saveProfile()
        advanceUntilIdle()
        assertTrue(cars.saved.isEmpty())
        assertEquals("A plate needs at least 4 letters or digits", vm.uiState.value.profileError)
    }

    @Test
    fun aFailedSaveKeepsTheDialogOpen() = runTest(dispatcher) {
        val cars = FakeCarProfiles().apply { saveFails = true }
        val vm = viewModel(FakeClassifiers(dataset), cars)
        advanceUntilIdle()
        vm.editProfile("andrews_tesla")
        vm.saveProfile()
        advanceUntilIdle()
        assertEquals("andrews_tesla", vm.uiState.value.profileDraft?.name)
        assertEquals("Couldn't save: relay said no", vm.uiState.value.profileError)
        assertFalse(vm.uiState.value.isSavingProfile)
    }

    @Test
    fun withoutTheRelayThereAreNoProfilesToEdit() = runTest(dispatcher) {
        val vm = viewModel(FakeClassifiers(dataset), FakeCarProfiles(profiles = null))
        advanceUntilIdle()
        assertNull(vm.uiState.value.carProfiles)
        vm.editProfile("andrews_tesla")
        assertNull(vm.uiState.value.profileDraft)
    }

    @Test
    fun aClassifierOfSomethingElseDoesntAskForCarProfiles() = runTest(dispatcher) {
        val vm = viewModel(FakeClassifiers(dataset.copy(model = ClassifierModel("dogs", listOf("dog")))))
        advanceUntilIdle()
        assertNull(vm.uiState.value.carProfiles)
    }

    private companion object {
        val CARS = CarProfiles(
            profiles = listOf(CarProfile("andrews_tesla", make = "tesla", model = "Model Y", colour = "blue")),
            makes = listOf("tesla", "toyota"),
            colours = listOf("blue", "red"),
        )
    }

    @Test
    fun clearConfidentDiscardsThemInOneCallAndDropsThemFromTheQueue() = runTest(dispatcher) {
        val repo = FakeClassifiers(dataset)
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.toggleConfident()
        assertTrue(vm.uiState.value.showConfident)

        vm.clearConfident()
        advanceUntilIdle()

        assertEquals(listOf(listOf(sureNotOurs.fileName, sureOurs.fileName)), repo.discarded, "one request with exactly the confident crops")
        val state = vm.uiState.value
        assertEquals(listOf(unsure), state.dataset?.queue, "the uncertain crop is untouched")
        assertTrue(state.busyFiles.isEmpty())
        assertFalse(state.showConfident, "nothing left to show")
        assertEquals("Cleared 2 crops the model was sure about", state.notice)
        assertFalse(state.noticeIsError)
    }

    @Test
    fun clearingWithNothingConfidentDoesNothing() = runTest(dispatcher) {
        val repo = FakeClassifiers(dataset.copy(queue = listOf(unsure)))
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.clearConfident()
        advanceUntilIdle()
        assertTrue(repo.discarded.isEmpty())
    }

    @Test
    fun aFailedClearReleasesTheCropsAndSaysSo() = runTest(dispatcher) {
        val repo = FakeClassifiers(dataset, discardFails = true)
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.clearConfident()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(3, state.dataset?.queue?.size, "nothing was dropped")
        assertTrue(state.busyFiles.isEmpty(), "the crops can be tried again")
        assertEquals("Couldn't clear: server said no", state.notice)
        assertTrue(state.noticeIsError)
    }

    @Test
    fun reloadingFoldsTheShelfBackUp() = runTest(dispatcher) {
        val vm = viewModel(FakeClassifiers(dataset))
        advanceUntilIdle()
        vm.toggleConfident()
        vm.load()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showConfident)
    }
}
