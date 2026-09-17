package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.LiveCandidate
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierQueueImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetTrackedObjectsUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelClassifierCropUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One object in view and the crop that would name it, with what it takes to draw and file it. */
@Immutable
data class LiveLabelCard(
    val modelName: String,
    val candidate: LiveCandidate,
    /** What the crop can be filed under: the model's [ClassifierDataset.categories]. */
    val categories: List<String>,
    val imageUrl: String?,
) {
    /** Per model as well as per object: two models running on cars would each ask about the same car. */
    val key: String get() = "$modelName/${candidate.tracked.eventId}"
}

@Immutable
data class LiveLabelingUiState(
    /** Every object in view with a queued crop, in the order Frigate lists them; filed ones already gone. */
    val cards: List<LiveLabelCard> = emptyList(),
    /** Cards whose label call is in flight, so a second chip tap doesn't file the crop twice. */
    val busyKeys: Set<String> = emptySet(),
    /** Whether the objects the model is sure about are listed too, as on the labelling screen. */
    val showConfident: Boolean = false,
    /** The category the last car went under, for "Filed as Sarah's Tesla"; clears itself. */
    val filedAs: String? = null,
    /** Why the last filing failed; clears itself like [filedAs]. */
    val error: String? = null,
) {
    val uncertainCards: List<LiveLabelCard> get() = cards.filterNot { it.candidate.isConfident }
    val confidentCards: List<LiveLabelCard> get() = cards.filter { it.candidate.isConfident }
}

/**
 * Labelling from the live view: the cars a camera is tracking right now, each with its newest
 * queued crop, so a car that's in the driveway can be named without finding it among the 200 crops
 * on the labelling screen.
 *
 * Nothing polls on its own. The screen calls [refresh] on a timer while it's started, which keeps
 * a backgrounded app off the server and keeps tests free of a flow that never completes. Each
 * refresh is one cheap request when the camera tracks nothing a classifier runs on; the datasets,
 * which cost several, are read only when it does.
 */
@AssistedInject
class LiveLabelingViewModel(
    @Assisted private val cameraName: String,
    private val getTrackedObjectsUseCase: GetTrackedObjectsUseCase,
    private val getClassifierModelsUseCase: GetClassifierModelsUseCase,
    private val getClassifierDatasetUseCase: GetClassifierDatasetUseCase,
    private val getClassifierQueueImageUrlUseCase: GetClassifierQueueImageUrlUseCase,
    private val labelClassifierCropUseCase: LabelClassifierCropUseCase,
) : ViewModel() {

    /** One view model per camera; the camera screen keys it by [cameraName]. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String): LiveLabelingViewModel
    }

    private val _uiState = MutableStateFlow(LiveLabelingUiState())
    val uiState: StateFlow<LiveLabelingUiState> = _uiState.asStateFlow()

    /** The server's classifiers. They only change with a config edit, so one read serves the visit. */
    private var models: List<ClassifierModel>? = null

    /**
     * Cards filed this visit. Frigate keeps classifying a car after it's been filed, and the model
     * hasn't learnt anything until it's retrained, so the car's next crop would otherwise put the
     * card straight back with the same wrong guess.
     */
    private var filedKeys: Set<String> = emptySet()

    private var noticeJob: Job? = null

    /**
     * Re-reads what the camera is tracking and the crops that would label it. A failed read keeps
     * the cards already shown rather than flashing the section away over one dropped request.
     */
    suspend fun refresh() {
        val tracked = getTrackedObjectsUseCase(cameraName).getOrElse { return }
        val labels = tracked.mapTo(HashSet()) { it.label }
        val relevant = if (labels.isEmpty()) {
            emptyList()
        } else {
            classifierModels()?.filter { model -> model.enabled && model.objects.any { it in labels } } ?: return
        }
        val cards = relevant.flatMap { model ->
            val dataset = getClassifierDatasetUseCase(model.name).getOrElse { return }
            // Walks the whole queue, so it's read once per model rather than once per card.
            val categories = dataset.categories
            dataset.liveCandidates(tracked).map { candidate ->
                LiveLabelCard(
                    modelName = model.name,
                    candidate = candidate,
                    categories = categories,
                    imageUrl = getClassifierQueueImageUrlUseCase(model.name, candidate.crop.fileName),
                )
            }
        }
        _uiState.update { state -> state.copy(cards = cards.filterNot { it.key in filedKeys }) }
    }

    fun toggleConfident() = _uiState.update { it.copy(showConfident = !it.showConfident) }

    /** Files [card]'s crop under [category] and hides the car for the rest of the visit. */
    fun label(card: LiveLabelCard, category: String) {
        if (card.key in _uiState.value.busyKeys) return
        _uiState.update { it.copy(busyKeys = it.busyKeys + card.key, error = null) }
        viewModelScope.launch {
            labelClassifierCropUseCase(card.modelName, card.candidate.crop.fileName, category)
                .onSuccess {
                    filedKeys = filedKeys + card.key
                    _uiState.update { state ->
                        state.copy(
                            busyKeys = state.busyKeys - card.key,
                            cards = state.cards.filterNot { it.key == card.key },
                            filedAs = category,
                        )
                    }
                }
                .onFailure { e ->
                    // Usually the crop aged out of Frigate's capped queue since the last refresh.
                    _uiState.update { it.copy(busyKeys = it.busyKeys - card.key, filedAs = null, error = "Couldn't save: ${e.message}") }
                }
            clearNoticeLater()
        }
    }

    private suspend fun classifierModels(): List<ClassifierModel>? =
        models ?: getClassifierModelsUseCase().getOrNull()?.also { models = it }

    private fun clearNoticeLater() {
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _uiState.update { it.copy(filedAs = null, error = null) }
        }
    }

    companion object {
        /** How long "Filed as ..." stays up: long enough to read, short enough not to linger over the next car. */
        const val NOTICE_MS = 4_000L
    }
}
