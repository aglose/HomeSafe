package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.TrackedObject
import com.meticulouscreations.homesafe.domain.model.contains
import com.meticulouscreations.homesafe.domain.model.isTaggable
import com.meticulouscreations.homesafe.domain.model.jpegSize
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.domain.model.trackedObjectAt
import com.meticulouscreations.homesafe.domain.usecase.CarTag
import com.meticulouscreations.homesafe.domain.usecase.GetCameraFrameUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetTrackedObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.TagCarUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The frame a car is tagged on. Compared by identity: a fresh frame is a different picture even
 * when the bytes happen to match, and comparing bytes on every state update would be waste.
 */
@Immutable
class CameraFrame(val jpeg: ByteArray, val width: Int, val height: Int)

@Immutable
data class CarTaggingUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    /** The classifier a tag teaches: the server's first enabled one that runs on cars. */
    val modelName: String? = null,
    /** What can be tagged: the model's categories, `none` ("Not ours") last. */
    val categories: List<String> = emptyList(),
    val frame: CameraFrame? = null,
    /** The cars Frigate is tracking on [frame], with their boxes and what it calls them now. */
    val trackedCars: List<TrackedObject> = emptyList(),
    /** The car being tagged: a tracked car's box, or a rectangle the person drew. */
    val selection: SeenBox? = null,
    /** The tracked car [selection] is about, when there is one; naming it is what puts it in view. */
    val selectedEventId: String? = null,
    val isSaving: Boolean = false,
    /** What the last tag did, or why it failed. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
) {
    val selectedCar: TrackedObject? get() = selectedEventId?.let { id -> trackedCars.firstOrNull { it.eventId == id } }
}

/**
 * Tagging a car on a live camera by hand (see [com.meticulouscreations.homesafe.domain.model.CarTagging]).
 * The screen freezes one frame — boxing a car on moving video is a game nobody wins — along with
 * what Frigate is tracking on it, so the boxes drawn over the picture are where the cars were on
 * that very frame. A new frame is on request, and after every tag, so the name just given shows
 * up on its box.
 */
@AssistedInject
class CarTaggingViewModel(
    @Assisted private val cameraName: String,
    private val getClassifierModelsUseCase: GetClassifierModelsUseCase,
    private val getClassifierDatasetUseCase: GetClassifierDatasetUseCase,
    private val getCameraFrameUseCase: GetCameraFrameUseCase,
    private val getTrackedObjectsUseCase: GetTrackedObjectsUseCase,
    private val tagCarUseCase: TagCarUseCase,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String): CarTaggingViewModel
    }

    private val _uiState = MutableStateFlow(CarTaggingUiState())
    val uiState: StateFlow<CarTaggingUiState> = _uiState.asStateFlow()

    private var model: ClassifierModel? = null
    private var loadJob: Job? = null

    init {
        load()
    }

    /** The classifier and its categories (once), then a frame and what's tracked on it. */
    fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = it.frame == null, loadError = null) }
        loadJob = viewModelScope.launch {
            val model = model ?: run {
                val models = getClassifierModelsUseCase().getOrElse { e -> return@launch fail("Couldn't load the classifiers: ${e.message}") }
                models.firstOrNull { it.enabled && CAR_LABEL in it.objects }
                    ?: return@launch fail("This server has no classifier that runs on cars.")
            }
            this@CarTaggingViewModel.model = model
            if (_uiState.value.categories.isEmpty()) {
                val dataset = getClassifierDatasetUseCase(model.name).getOrElse { e -> return@launch fail("Couldn't load the known cars: ${e.message}") }
                _uiState.update { it.copy(modelName = model.name, categories = dataset.categories) }
            }
            refreshFrame(model)
        }
    }

    /** A fresh frame and fresh boxes; the current selection goes, since it was drawn on the old picture. */
    fun refresh() {
        val model = model ?: return load()
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshFrame(model) }
    }

    /** A tap on the frame ([x], [y] in fractions): picks the smallest tracked car under it, or clears the selection. */
    fun tapAt(x: Double, y: Double) {
        val car = _uiState.value.trackedCars
            .filter { it.box?.contains(x, y) == true }
            .minByOrNull { it.box!!.width * it.box.height }
        _uiState.update { it.copy(selection = car?.box, selectedEventId = car?.eventId, notice = null) }
    }

    /** A rectangle dragged round a car; it is about whichever tracked car it overlaps enough. Too small to be a car, it's ignored. */
    fun drawBox(box: SeenBox) {
        if (!box.isTaggable) return
        val car = _uiState.value.trackedCars.trackedObjectAt(box)
        _uiState.update { it.copy(selection = box, selectedEventId = car?.eventId, notice = null) }
    }

    fun clearSelection() = _uiState.update { it.copy(selection = null, selectedEventId = null) }

    /** Tags the selected car as [category]; see [TagCarUseCase]. */
    fun tag(category: String) {
        val state = _uiState.value
        val model = model ?: return
        val frame = state.frame ?: return
        val box = state.selection ?: return
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true, notice = null) }
        viewModelScope.launch {
            tagCarUseCase(CarTag(model.name, category, frame.jpeg, box, state.selectedEventId))
                .onSuccess { outcome ->
                    val name = categoryName(category)
                    val inView = when {
                        state.selectedEventId == null -> "Frigate isn't tracking a car there, so it can't show in view until it detects one."
                        !outcome.namedInView -> "Couldn't rename the tracked car; it'll show once the model recognises it."
                        category == NONE -> "Taken off In view now."
                        else -> "Showing as in view now."
                    }
                    val training = if (outcome.trainingStarted) "Retraining now." else "Saved for the next training."
                    _uiState.update {
                        it.copy(isSaving = false, notice = "Tagged as $name. $inView $training", noticeIsError = false, selection = null, selectedEventId = null)
                    }
                    // The frame again, so the name just given shows on the car's box.
                    refreshFrame(model, keepNotice = true)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, notice = "Couldn't save the tag: ${e.message}", noticeIsError = true) }
                }
        }
    }

    private suspend fun refreshFrame(model: ClassifierModel, keepNotice: Boolean = false) {
        val (frameResult, trackedResult) = coroutineScope {
            val frame = async { getCameraFrameUseCase(cameraName) }
            val tracked = async { getTrackedObjectsUseCase(cameraName) }
            frame.await() to tracked.await()
        }
        val jpeg = frameResult.getOrElse { e -> return fail("Couldn't load the camera: ${e.message}") }
        val (width, height) = jpegSize(jpeg) ?: return fail("The camera sent a picture the app can't read.")
        // A failed read of the tracked cars still leaves a frame to draw a rectangle on.
        val cars = trackedResult.getOrElse { emptyList() }.filter { it.label in model.objects && it.box != null }
        _uiState.update {
            it.copy(
                isLoading = false,
                loadError = null,
                modelName = model.name,
                frame = CameraFrame(jpeg, width, height),
                trackedCars = cars,
                selection = null,
                selectedEventId = null,
                notice = if (keepNotice) it.notice else null,
            )
        }
    }

    /** With a frame already up, a failed reload is a notice over it rather than an error in place of it. */
    private fun fail(message: String) = _uiState.update {
        if (it.frame != null) it.copy(isLoading = false, notice = message, noticeIsError = true) else it.copy(isLoading = false, loadError = message)
    }

    private fun categoryName(category: String): String =
        if (category == NONE) "not ours" else subLabelDisplayName(category)

    private companion object {
        const val CAR_LABEL = "car"
        const val NONE = ClassifierDataset.NONE_CATEGORY
    }
}
