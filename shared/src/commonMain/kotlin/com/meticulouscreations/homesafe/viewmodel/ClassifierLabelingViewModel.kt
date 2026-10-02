package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.model.CarTagging
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.usecase.CreateClassifierCategoryUseCase
import com.meticulouscreations.homesafe.domain.usecase.DeleteCarProfileUseCase
import com.meticulouscreations.homesafe.domain.usecase.DiscardClassifierCropsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetCarProfilesUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierQueueImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelClassifierCropUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveCarProfileUseCase
import com.meticulouscreations.homesafe.domain.usecase.TrainClassifierUseCase
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.labeling_category_add_failed
import homesafe.shared.generated.resources.labeling_category_added
import homesafe.shared.generated.resources.labeling_clear_failed
import homesafe.shared.generated.resources.labeling_cleared_confident
import homesafe.shared.generated.resources.labeling_load_failed
import homesafe.shared.generated.resources.labeling_plate_too_short
import homesafe.shared.generated.resources.labeling_profile_forget_failed
import homesafe.shared.generated.resources.labeling_save_failed
import homesafe.shared.generated.resources.labeling_train_failed
import homesafe.shared.generated.resources.labeling_trained
import homesafe.shared.generated.resources.labeling_training_slow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ClassifierLabelingUiState(
    val isLoading: Boolean = true,
    val loadError: UiText? = null,
    val dataset: ClassifierDataset? = null,
    /** Crops whose label/discard call is in flight, so their tile can't be tapped twice. */
    val busyFiles: Set<String> = emptySet(),
    /** Crops labelled or discarded this visit and their category (null = discarded), for a "done" tick before the reload. */
    val decided: Map<String, String?> = emptyMap(),
    val isTraining: Boolean = false,
    /** "Trained on 31 images" / "Couldn't train: ..." — the last outcome, cleared on the next action. */
    val notice: UiText? = null,
    val noticeIsError: Boolean = false,
    val newCategoryDraft: String = "",
    /** Whether the crops the model is sure about are listed too (see [ClassifierDataset.confidentQueue]). */
    val showConfident: Boolean = false,
    /** What each known car looks like, for a car classifier; null for any other, or while the relay can't say. */
    val carProfiles: CarProfiles? = null,
    /** The car profile being edited, while its dialog is open. */
    val profileDraft: CarProfile? = null,
    val isSavingProfile: Boolean = false,
    /** Why the last save didn't take, shown in the dialog. */
    val profileError: UiText? = null,
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
    private val getCarProfilesUseCase: GetCarProfilesUseCase,
    private val saveCarProfileUseCase: SaveCarProfileUseCase,
    private val deleteCarProfileUseCase: DeleteCarProfileUseCase,
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
                .onSuccess { data ->
                    _uiState.update { it.copy(isLoading = false, dataset = data, decided = emptyMap(), showConfident = false) }
                    if (CarTagging.CAR_LABEL in data.model.objects) {
                        getCarProfilesUseCase().onSuccess { profiles -> _uiState.update { it.copy(carProfiles = profiles) } }
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, loadError = e.userMessage(Res.string.labeling_load_failed)) } }
        }
    }

    /** Opens [category]'s make, model, colour and plate for editing; only once the relay has answered for a car classifier. */
    fun editProfile(category: String) {
        val profiles = _uiState.value.carProfiles ?: return
        _uiState.update { it.copy(profileDraft = profiles.profileOf(category), profileError = null) }
    }

    fun updateProfileDraft(profile: CarProfile) = _uiState.update { it.copy(profileDraft = profile, profileError = null) }

    fun dismissProfile() = _uiState.update { if (it.isSavingProfile) it else it.copy(profileDraft = null, profileError = null) }

    fun saveProfile() {
        val draft = _uiState.value.profileDraft ?: return
        if (_uiState.value.isSavingProfile) return
        val plate = CarProfile.normalPlate(draft.plate)
        if (plate.isNotEmpty() && plate.length < CarProfile.PLATE_MIN_LENGTH) {
            _uiState.update { it.copy(profileError = UiText.of(Res.string.labeling_plate_too_short, CarProfile.PLATE_MIN_LENGTH)) }
            return
        }
        _uiState.update { it.copy(isSavingProfile = true, profileError = null) }
        viewModelScope.launch {
            saveCarProfileUseCase(draft)
                .onSuccess { saved ->
                    _uiState.update { state ->
                        val profiles = state.carProfiles
                        state.copy(
                            isSavingProfile = false,
                            profileDraft = null,
                            carProfiles = profiles?.copy(profiles = (profiles.profiles.filterNot { it.name == saved.name } + saved).sortedBy { it.name }),
                        )
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isSavingProfile = false, profileError = e.userMessage(Res.string.labeling_save_failed)) } }
        }
    }

    /** Drops the open car's profile from the relay, so its name is no longer checked (a stale or mistaken entry). */
    fun deleteProfile() {
        val name = _uiState.value.profileDraft?.name ?: return
        if (_uiState.value.isSavingProfile) return
        _uiState.update { it.copy(isSavingProfile = true, profileError = null) }
        viewModelScope.launch {
            deleteCarProfileUseCase(name)
                .onSuccess {
                    _uiState.update { state ->
                        val profiles = state.carProfiles
                        state.copy(isSavingProfile = false, profileDraft = null, carProfiles = profiles?.copy(profiles = profiles.profiles.filterNot { it.name == name }))
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isSavingProfile = false, profileError = e.userMessage(Res.string.labeling_profile_forget_failed)) } }
        }
    }

    fun imageUrl(fileName: String): String? = getClassifierQueueImageUrlUseCase(modelName, fileName)

    /** Files this crop, then re-reads the queue so Frigate's renamed copy and the new counts show. */
    fun label(fileName: String, category: String) = decide(fileName, category) { labelClassifierCropUseCase(modelName, fileName, category) }

    fun discard(fileName: String) = decide(fileName, null) { discardClassifierCropsUseCase(modelName, listOf(fileName)) }

    fun setNewCategoryDraft(text: String) = _uiState.update { it.copy(newCategoryDraft = text) }

    fun toggleConfident() = _uiState.update { it.copy(showConfident = !it.showConfident) }

    /**
     * Throws away every crop the model is sure about, in one request. They're the noise Frigate
     * saves on every frame of every passing car; clearing them makes room in Frigate's capped queue
     * for the crops that teach it something.
     */
    fun clearConfident() {
        val state = _uiState.value
        val files = state.dataset?.confidentQueue.orEmpty().map { it.fileName }.filterNot { it in state.busyFiles }
        if (files.isEmpty()) return
        _uiState.update { it.copy(busyFiles = it.busyFiles + files, notice = null) }
        viewModelScope.launch {
            discardClassifierCropsUseCase(modelName, files)
                .onSuccess {
                    _uiState.update { current ->
                        val data = current.dataset
                        current.copy(
                            busyFiles = current.busyFiles - files.toSet(),
                            dataset = data?.copy(queue = data.queue.filterNot { it.fileName in files }),
                            showConfident = false,
                            notice = UiText.plural(Res.plurals.labeling_cleared_confident, files.size),
                            noticeIsError = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busyFiles = it.busyFiles - files.toSet(), notice = e.userMessage(Res.string.labeling_clear_failed), noticeIsError = true) }
                }
        }
    }

    /** "Ron and Judy's Mercedes" -> category key `ron_and_judys_mercedes`, created on the server. */
    fun createCategory() {
        val draft = _uiState.value.newCategoryDraft.trim()
        if (draft.isEmpty()) return
        val key = DetectionZone.slug(draft)
        viewModelScope.launch {
            createClassifierCategoryUseCase(modelName, key)
                .onSuccess {
                    _uiState.update { it.copy(newCategoryDraft = "", notice = UiText.of(Res.string.labeling_category_added, key), noticeIsError = false) }
                    load()
                }
                .onFailure { e -> _uiState.update { it.copy(notice = e.userMessage(Res.string.labeling_category_add_failed), noticeIsError = true) } }
        }
    }

    /** Kicks off training and polls the dataset until Frigate reports it has trained on the new images. */
    fun train() {
        val data = _uiState.value.dataset ?: return
        if (_uiState.value.isTraining || !data.canTrain) return
        _uiState.update { it.copy(isTraining = true, notice = null) }
        viewModelScope.launch {
            trainClassifierUseCase(modelName).onFailure { e ->
                _uiState.update { it.copy(isTraining = false, notice = e.userMessage(Res.string.labeling_train_failed), noticeIsError = true) }
                return@launch
            }
            // Training on the real box takes ~30 s; poll until the "new since training" count drops to zero.
            repeat(TRAIN_POLL_ATTEMPTS) {
                delay(TRAIN_POLL_INTERVAL_MS)
                val refreshed = getClassifierDatasetUseCase(modelName).getOrNull()
                if (refreshed != null && refreshed.hasTrained && refreshed.newImagesSinceTraining == 0) {
                    val total = refreshed.categoryCounts.values.sum()
                    _uiState.update { it.copy(isTraining = false, dataset = refreshed, notice = UiText.plural(Res.plurals.labeling_trained, total), noticeIsError = false) }
                    return@launch
                }
            }
            _uiState.update { it.copy(isTraining = false, notice = UiText.of(Res.string.labeling_training_slow), noticeIsError = true) }
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
                    _uiState.update { it.copy(busyFiles = it.busyFiles - fileName, notice = e.userMessage(Res.string.labeling_save_failed), noticeIsError = true) }
                }
        }
    }

    private companion object {
        const val TRAIN_POLL_ATTEMPTS = 12
        const val TRAIN_POLL_INTERVAL_MS = 5_000L
    }
}
