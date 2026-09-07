package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.usecase.CreateClassifierCategoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.DiscardClassifierCropsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierQueueImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelClassifierCropUseCase
import com.meticulouscreations.homesafe.domain.usecase.TrainClassifierUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ClassifierLabelingUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val dataset: ClassifierDataset? = null,
    /** Crops whose label/discard call is in flight, so their tile can't be tapped twice. */
    val busyFiles: Set<String> = emptySet(),
    /** Crops labelled or discarded this visit and their category (null = discarded), for a "done" tick before the reload. */
    val decided: Map<String, String?> = emptyMap(),
    val isTraining: Boolean = false,
    /** "Trained on 31 images" / "Couldn't train: ..." — the last outcome, cleared on the next action. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val newCategoryDraft: String = "",
)

/**
 * The human half of Frigate's classifier loop: show the crops the model has seen, take a label
 * for each, and retrain when there's something new. Every decision goes straight to the server
 * (Frigate moves the file), then the queue is re-read so the screen never drifts from the box.
 */
@AssistedInject
class ClassifierLabelingViewModel(
    @Assisted private val modelName: String,
    private val getClassifierDatasetUseCase: GetClassifierDatasetUseCase,
    private val getClassifierQueueImageUrlUseCase: GetClassifierQueueImageUrlUseCase,
    private val labelClassifierCropUseCase: LabelClassifierCropUseCase,
    private val discardClassifierCropsUseCase: DiscardClassifierCropsUseCase,
    private val createClassifierCategoryUseCase: CreateClassifierCategoryUseCase,
    private val trainClassifierUseCase: TrainClassifierUseCase,
) : ViewModel() {

    /** One view model per classifier; the screen keys it by [modelName]. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(modelName: String): ClassifierLabelingViewModel
    }

    private val _uiState = MutableStateFlow(ClassifierLabelingUiState())
    val uiState: StateFlow<ClassifierLabelingUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = it.dataset == null, loadError = null) }
        viewModelScope.launch {
            getClassifierDatasetUseCase(modelName)
                .onSuccess { data -> _uiState.update { it.copy(isLoading = false, dataset = data, decided = emptyMap()) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, loadError = e.message ?: "Couldn't load the classifier") } }
        }
    }

    fun imageUrl(fileName: String): String? = getClassifierQueueImageUrlUseCase(modelName, fileName)

    /** Files this crop, then re-reads the queue so Frigate's renamed copy and the new counts show. */
    fun label(fileName: String, category: String) = decide(fileName, category) { labelClassifierCropUseCase(modelName, fileName, category) }

    fun discard(fileName: String) = decide(fileName, null) { discardClassifierCropsUseCase(modelName, listOf(fileName)) }

    fun setNewCategoryDraft(text: String) = _uiState.update { it.copy(newCategoryDraft = text) }

    /** "Ron and Judy's Mercedes" -> category key `ron_and_judys_mercedes`, created on the server. */
    fun createCategory() {
        val draft = _uiState.value.newCategoryDraft.trim()
        if (draft.isEmpty()) return
        val key = DetectionZone.slug(draft)
        viewModelScope.launch {
            createClassifierCategoryUseCase(modelName, key)
                .onSuccess {
                    _uiState.update { it.copy(newCategoryDraft = "", notice = "Added category $key", noticeIsError = false) }
                    load()
                }
                .onFailure { e -> _uiState.update { it.copy(notice = "Couldn't add category: ${e.message}", noticeIsError = true) } }
        }
    }

    /** Kicks off training and polls the dataset until Frigate reports it has trained on the new images. */
    fun train() {
        val data = _uiState.value.dataset ?: return
        if (_uiState.value.isTraining || !data.canTrain) return
        _uiState.update { it.copy(isTraining = true, notice = null) }
        viewModelScope.launch {
            trainClassifierUseCase(modelName).onFailure { e ->
                _uiState.update { it.copy(isTraining = false, notice = "Couldn't train: ${e.message}", noticeIsError = true) }
                return@launch
            }
            // Training on the real box takes ~30 s; poll until the "new since training" count drops to zero.
            repeat(TRAIN_POLL_ATTEMPTS) {
                delay(TRAIN_POLL_INTERVAL_MS)
                val refreshed = getClassifierDatasetUseCase(modelName).getOrNull()
                if (refreshed != null && refreshed.hasTrained && refreshed.newImagesSinceTraining == 0) {
                    val total = refreshed.categoryCounts.values.sum()
                    _uiState.update { it.copy(isTraining = false, dataset = refreshed, notice = "Trained on $total images. Frigate is using the new model now.", noticeIsError = false) }
                    return@launch
                }
            }
            _uiState.update { it.copy(isTraining = false, notice = "Training is taking longer than expected; pull to refresh in a minute.", noticeIsError = true) }
        }
    }

    private fun decide(fileName: String, category: String?, call: suspend () -> Result<Unit>) {
        if (fileName in _uiState.value.busyFiles) return
        _uiState.update { it.copy(busyFiles = it.busyFiles + fileName, notice = null) }
        viewModelScope.launch {
            call()
                .onSuccess {
                    _uiState.update { state ->
                        val data = state.dataset
                        // Optimistic: drop the tile and bump the count now; the reload below reconciles.
                        val updated = data?.copy(
                            queue = data.queue.filterNot { it.fileName == fileName },
                            categoryCounts = if (category != null) data.categoryCounts + (category to (data.categoryCounts[category] ?: 0) + 1) else data.categoryCounts,
                            newImagesSinceTraining = if (category != null) data.newImagesSinceTraining + 1 else data.newImagesSinceTraining,
                        )
                        state.copy(busyFiles = state.busyFiles - fileName, decided = state.decided + (fileName to category), dataset = updated)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busyFiles = it.busyFiles - fileName, notice = "Couldn't save: ${e.message}", noticeIsError = true) }
                }
        }
    }

    private companion object {
        const val TRAIN_POLL_ATTEMPTS = 12
        const val TRAIN_POLL_INTERVAL_MS = 5_000L
    }
}
