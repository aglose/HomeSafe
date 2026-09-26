package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CarTagging
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.carClassifier
import com.meticulouscreations.homesafe.domain.model.isGenericCar
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierDatasetUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetClassifierModelsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.MomentCarTag
import com.meticulouscreations.homesafe.domain.usecase.TagMomentCarUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The detection a tag is about, as the picker and the landing prompt name it. */
@Immutable
data class CarTagTarget(
    val eventId: String,
    /** "Car in the driveway · 8:42 PM" */
    val summary: String,
)

@Immutable
data class MomentCarTagUiState(
    /** The detection the picker is open for; null while it is closed. */
    val target: CarTagTarget? = null,
    /** The known cars a tag can name, `none` left out: a car nobody knows is already what "Car" means. */
    val knownCars: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val loadError: String? = null,
    /** What's typed for a car the classifier doesn't know yet. */
    val newCarDraft: String = "",
    val isSaving: Boolean = false,
    /** What the tag did, or why it failed. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    /** [target] has been tagged: the picker only says how it went, and offers Done. */
    val done: Boolean = false,
    /** The unnamed car a notification (or the Moments tab's full-screen button) opened the camera screen on; null otherwise. */
    val landed: CarTagTarget? = null,
    /** Detections tagged here, by event id, and what as. */
    val tagged: Map<String, String> = emptyMap(),
)

/**
 * Tagging the car of a moment the classifier left as plain "Car": pick one of the known cars, or
 * type a new one's name (see [TagMomentCarUseCase] for what a tag does on the server). One per
 * screen that shows moments: the Moments tab, and a camera's screen, where it also looks up the
 * detection a notification opened so the tag is one tap away there too.
 *
 * The known cars are read once, when the picker first opens, from the classifier the live
 * tagging screen uses; a car added here joins them without a reload.
 */
@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MomentCarTagViewModel(
    private val getClassifierModelsUseCase: GetClassifierModelsUseCase,
    private val getClassifierDatasetUseCase: GetClassifierDatasetUseCase,
    private val getDetectionUseCase: GetDetectionUseCase,
    private val tagMomentCarUseCase: TagMomentCarUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentCarTagUiState())
    val uiState: StateFlow<MomentCarTagUiState> = _uiState.asStateFlow()

    private var model: ClassifierModel? = null
    private var loadJob: Job? = null

    /** The detection [lookUp] is asking about or has had its answer for; see there. */
    private var lookingUp: String? = null
    private var lookUpJob: Job? = null

    /** Opens the picker for [event]; anything but an unnamed car is ignored, and so is a tap while a tag is saving. */
    fun open(event: MomentEvent) {
        if (!event.isGenericCar || _uiState.value.isSaving) return
        openFor(event.toTarget())
    }

    /** Opens the picker for the car this screen was opened on, from its prompt. */
    fun openLanded() {
        val landed = _uiState.value.landed ?: return
        if (landed.eventId in _uiState.value.tagged || _uiState.value.isSaving) return
        openFor(landed)
    }

    /**
     * Looks up the detection [eventId] this screen was opened on, and offers to tag it when it's
     * an unnamed car — straight away, with [openPicker], which is a notification's "Tag car"
     * button.
     *
     * Only an answer settles it, "Frigate no longer has it" included. A failed ask — the phone
     * coming back online after a notification woke it, the server restarting — is asked again,
     * [LOOKUP_RETRY_FIRST_MS] later and backing off to [LOOKUP_RETRY_MAX_MS], for as long as the
     * screen is open: the screen asks once, on the way in, and giving up on the first error would
     * lose the prompt and the notification's tag for the whole visit. Asking again about the same
     * detection meanwhile, as a recomposition does, changes nothing.
     */
    fun lookUp(eventId: String, openPicker: Boolean) {
        if (eventId.isBlank() || eventId == lookingUp) return
        lookingUp = eventId
        lookUpJob?.cancel()
        lookUpJob = viewModelScope.launch {
            var wait = LOOKUP_RETRY_FIRST_MS
            var answer = getDetectionUseCase(eventId)
            while (answer.isFailure) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(LOOKUP_RETRY_MAX_MS)
                answer = getDetectionUseCase(eventId)
            }
            val event = answer.getOrNull()?.takeIf { it.isGenericCar } ?: return@launch
            val target = event.toTarget()
            _uiState.update { it.copy(landed = target) }
            if (openPicker) openFor(target)
        }
    }

    /** Closes the picker, unless a tag is on its way to the server. */
    fun dismiss() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(target = null, done = false, notice = null, newCarDraft = "") }
    }

    fun setNewCarDraft(text: String) = _uiState.update { it.copy(newCarDraft = text) }

    /** Tags the car as the one typed in: "Grandma's Van" becomes the known car `grandmas_van`, or joins it if it exists. */
    fun tagAsNewCar() {
        val key = CarTagging.knownCarKey(_uiState.value.newCarDraft)
        if (key == null) {
            _uiState.update { it.copy(notice = "Type the car's name, e.g. Grandma's Van.", noticeIsError = true) }
            return
        }
        tag(key)
    }

    /** Tags the open detection's car as [category]; see [TagMomentCarUseCase]. */
    fun tag(category: String) {
        val state = _uiState.value
        val target = state.target ?: return
        val model = model ?: return
        if (state.isSaving || state.done) return
        _uiState.update { it.copy(isSaving = true, notice = null) }
        viewModelScope.launch {
            tagMomentCarUseCase(MomentCarTag(model.name, category, target.eventId))
                .onSuccess { outcome ->
                    val named = if (outcome.named) "" else "Couldn't rename this detection; it'll show once the model recognises the car. "
                    val training = if (outcome.trainingStarted) "Retraining now." else "Saved for the next training."
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            done = true,
                            notice = "Tagged as ${subLabelDisplayName(category)}. $named$training",
                            noticeIsError = false,
                            newCarDraft = "",
                            knownCars = if (category in it.knownCars) it.knownCars else (it.knownCars + category).sorted(),
                            tagged = it.tagged + (target.eventId to category),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, notice = "Couldn't save the tag: ${e.message}", noticeIsError = true) }
                }
        }
    }

    /** Reads the known cars again after a failed load. */
    fun retry() {
        _uiState.update { it.copy(loadError = null) }
        loadKnownCars()
    }

    private fun openFor(target: CarTagTarget) {
        _uiState.update { it.copy(target = target, done = false, notice = null, newCarDraft = "") }
        loadKnownCars()
    }

    private fun loadKnownCars() {
        val state = _uiState.value
        if (model != null || loadJob?.isActive == true || state.loadError != null) return
        _uiState.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            val found = getClassifierModelsUseCase().getOrElse { e -> return@launch failLoad("Couldn't load the classifiers: ${e.message}") }
                .carClassifier()
                ?: return@launch failLoad("This server has no classifier that runs on cars.")
            val dataset = getClassifierDatasetUseCase(found.name).getOrElse { e -> return@launch failLoad("Couldn't load the known cars: ${e.message}") }
            model = found
            _uiState.update { it.copy(isLoading = false, loadError = null, knownCars = dataset.knownCars) }
        }
    }

    private fun failLoad(message: String) = _uiState.update { it.copy(isLoading = false, loadError = message) }

    private fun MomentEvent.toTarget(): CarTagTarget {
        val presentation = present(clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
        return CarTagTarget(eventId = id, summary = "${presentation.title} · ${presentation.timeLabel}")
    }

    private companion object {
        const val LOOKUP_RETRY_FIRST_MS = 5_000L
        const val LOOKUP_RETRY_MAX_MS = 60_000L
    }
}
