package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.canMarkNotPerson
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.summary
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionUseCase
import com.meticulouscreations.homesafe.domain.usecase.MarkNotAPersonUseCase
import com.meticulouscreations.homesafe.domain.usecase.UndoNotAPersonUseCase
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.userMessage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_not_a_person_mark_failed
import homesafe.shared.generated.resources.moments_not_a_person_undo_failed
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

@Immutable
data class LandedPersonUiState(
    /** The detection the camera screen was opened on, when it's a person nobody named; null otherwise. */
    val eventId: String? = null,
    /** "Person at the front door · 4:23 PM" */
    val summary: UiText = UiText.Empty,
    /** What the camera is called, for "Front Door won't alert for this again". Data, not copy. */
    val cameraDisplayName: String = "",
    /** The mark (or its Undo) is on its way to the relay. */
    val isSaving: Boolean = false,
    /** The relay has it as not a person. */
    val marked: Boolean = false,
    /** Why the last mark or Undo didn't land: "Couldn't mark it: …", said in full. */
    val error: UiText? = null,
)

/**
 * "Not a person" on the detection a notification (or the Moments tab's full-screen button) opened
 * the camera screen on: the person in it is one nobody named, and the reader, looking at the
 * clip, can say nobody was there. What a mark does is [MarkNotAPersonUseCase]'s; the same mark a
 * Moments card and a notification's own button make, so the screen it lands on offers it too.
 */
@OptIn(ExperimentalTime::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class LandedPersonViewModel(
    private val getDetectionUseCase: GetDetectionUseCase,
    private val markNotAPersonUseCase: MarkNotAPersonUseCase,
    private val undoNotAPersonUseCase: UndoNotAPersonUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LandedPersonUiState())
    val uiState: StateFlow<LandedPersonUiState> = _uiState.asStateFlow()

    private var lookingUp: String? = null
    private var lookUpJob: Job? = null

    /**
     * Looks up the detection [eventId] the screen was opened on, and offers the mark when it's a
     * person nobody named. As with the car prompt (`MomentCarTagViewModel.lookUp`), only an answer
     * settles it: a failed ask — the phone back online after a notification woke it — is asked
     * again, backing off, for as long as the screen is open. The same detection again changes nothing.
     */
    fun lookUp(eventId: String) {
        if (eventId.isBlank() || eventId == lookingUp) return
        lookingUp = eventId
        lookUpJob?.cancel()
        _uiState.value = LandedPersonUiState()
        lookUpJob = viewModelScope.launch {
            var wait = LOOKUP_RETRY_FIRST_MS
            var answer = getDetectionUseCase(eventId)
            while (answer.isFailure) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(LOOKUP_RETRY_MAX_MS)
                answer = getDetectionUseCase(eventId)
            }
            val event = answer.getOrNull()?.takeIf { it.canMarkNotPerson } ?: return@launch
            _uiState.value = event.toState()
        }
    }

    /** Marks the landed person not a person. */
    fun mark() = save(marking = true)

    /** Takes the mark back. */
    fun undo() = save(marking = false)

    private fun save(marking: Boolean) {
        val state = _uiState.value
        val eventId = state.eventId ?: return
        if (state.isSaving || state.marked == marking) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val result = if (marking) markNotAPersonUseCase(eventId).map { } else undoNotAPersonUseCase(eventId)
            _uiState.update {
                result.fold(
                    onSuccess = { _ -> it.copy(isSaving = false, marked = marking) },
                    onFailure = { e ->
                        val failed = if (marking) Res.string.moments_not_a_person_mark_failed else Res.string.moments_not_a_person_undo_failed
                        it.copy(isSaving = false, error = e.userMessage(failed))
                    },
                )
            }
        }
    }

    private fun MomentEvent.toState(): LandedPersonUiState {
        val presentation = present(clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
        return LandedPersonUiState(eventId = id, summary = presentation.summary, cameraDisplayName = cameraDisplayName)
    }

    private companion object {
        const val LOOKUP_RETRY_FIRST_MS = 5_000L
        const val LOOKUP_RETRY_MAX_MS = 60_000L
    }
}
