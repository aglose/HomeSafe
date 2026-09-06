package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.model.KnownPerson
import com.meticulouscreations.homesafe.domain.usecase.CreatePersonUseCase
import com.meticulouscreations.homesafe.domain.usecase.DeleteFaceImagesUseCase
import com.meticulouscreations.homesafe.domain.usecase.DiscardFaceAttemptsUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetFaceImageUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetFaceLibraryUseCase
import com.meticulouscreations.homesafe.domain.usecase.LabelFaceAttemptUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FaceLibraryUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val library: FaceLibrary? = null,
    /** Attempts whose name/discard call is in flight, so their tile can't be tapped twice. */
    val busyFiles: Set<String> = emptySet(),
    /** Attempts named or discarded this visit and the person (null = discarded), for a "done" tick before the reload. */
    val decided: Map<String, String?> = emptyMap(),
    /** The person whose registered images are unfolded in the People card; one at a time. */
    val expandedPerson: String? = null,
    /** "Added Andrew" / "Couldn't save: ..." — the last outcome, cleared on the next action. */
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val newPersonDraft: String = "",
) {
    val people: List<KnownPerson> get() = library?.people.orEmpty()
}

/**
 * The human half of Frigate's face recognition: show the faces it saw, take a name for each, and
 * keep each person's registered images tidy. There is no training step — a face filed under a
 * name is recognised from then on — so every decision goes straight to the server and the
 * library is re-read so the screen never drifts from the box.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FaceLibraryViewModel(
    private val getFaceLibraryUseCase: GetFaceLibraryUseCase,
    private val getFaceImageUrlUseCase: GetFaceImageUrlUseCase,
    private val labelFaceAttemptUseCase: LabelFaceAttemptUseCase,
    private val discardFaceAttemptsUseCase: DiscardFaceAttemptsUseCase,
    private val createPersonUseCase: CreatePersonUseCase,
    private val deleteFaceImagesUseCase: DeleteFaceImagesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FaceLibraryUiState())
    val uiState: StateFlow<FaceLibraryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = it.library == null, loadError = null) }
        viewModelScope.launch {
            getFaceLibraryUseCase()
                .onSuccess { data -> _uiState.update { it.copy(isLoading = false, library = data, decided = emptyMap()) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, loadError = e.message ?: "Couldn't load the face library") } }
        }
    }

    fun imageUrl(folder: String, fileName: String): String? = getFaceImageUrlUseCase(folder, fileName)

    /** Names this face, then re-reads so Frigate's moved copy shows under the person. */
    fun label(fileName: String, name: String) = decide(fileName, name) { labelFaceAttemptUseCase(fileName, name) }

    fun discard(fileName: String) = decide(fileName, null) { discardFaceAttemptsUseCase(listOf(fileName)) }

    fun setNewPersonDraft(text: String) = _uiState.update { it.copy(newPersonDraft = text) }

    /** "Ron & Judy" -> person key `ron_judy`, created on the server as an empty folder to file faces into. */
    fun createPerson() {
        val draft = _uiState.value.newPersonDraft.trim()
        if (draft.isEmpty()) return
        val key = FaceLibrary.personKey(draft)
        viewModelScope.launch {
            createPersonUseCase(key)
                .onSuccess {
                    _uiState.update { it.copy(newPersonDraft = "", notice = "Added $draft. File a face under them to start recognising.", noticeIsError = false) }
                    load()
                }
                .onFailure { e -> _uiState.update { it.copy(notice = "Couldn't add: ${e.message}", noticeIsError = true) } }
        }
    }

    fun togglePerson(name: String) = _uiState.update { it.copy(expandedPerson = if (it.expandedPerson == name) null else name) }

    /** Removes one registered image; Frigate stops matching against it at once. */
    fun deleteImage(name: String, fileName: String) {
        if (fileName in _uiState.value.busyFiles) return
        _uiState.update { it.copy(busyFiles = it.busyFiles + fileName, notice = null) }
        viewModelScope.launch {
            deleteFaceImagesUseCase(name, listOf(fileName))
                .onSuccess {
                    _uiState.update { state ->
                        val library = state.library
                        val updated = library?.copy(
                            people = library.people.map { person ->
                                if (person.name == name) person.copy(imageFiles = person.imageFiles - fileName) else person
                            },
                        )
                        state.copy(busyFiles = state.busyFiles - fileName, library = updated)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(busyFiles = it.busyFiles - fileName, notice = "Couldn't delete: ${e.message}", noticeIsError = true) } }
        }
    }

    private fun decide(fileName: String, name: String?, call: suspend () -> Result<Unit>) {
        if (fileName in _uiState.value.busyFiles) return
        _uiState.update { it.copy(busyFiles = it.busyFiles + fileName, notice = null) }
        viewModelScope.launch {
            call()
                .onSuccess {
                    _uiState.update { state ->
                        val library = state.library
                        // Optimistic: drop the tile and bump the count now; the next load reconciles
                        // (Frigate renames the file on the way into the person's folder).
                        val updated = library?.copy(
                            attempts = library.attempts.filterNot { it.fileName == fileName },
                            people = if (name != null) {
                                library.people.map { person ->
                                    if (person.name == name) person.copy(imageFiles = listOf(fileName) + person.imageFiles) else person
                                }
                            } else {
                                library.people
                            },
                        )
                        state.copy(busyFiles = state.busyFiles - fileName, decided = state.decided + (fileName to name), library = updated)
                    }
                    if (name != null) load()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(busyFiles = it.busyFiles - fileName, notice = "Couldn't save: ${e.message}", noticeIsError = true) }
                }
        }
    }
}
