package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionConfigUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveDetectionMasksUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveDetectionZonesUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
data class DetectionZonesUiState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val config: CameraDetectionConfig? = null,
    /** Opens on the zones layer: naming areas is the common job; ignore areas are the exception. */
    val editor: MaskEditorState = MaskEditorState(layer = MaskLayer.ZONES),
    /** A fresh frame from the camera to draw over; re-issued on every (re)load so it isn't a stale cache hit. */
    val snapshotUrl: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** True right after a successful save until the next edit, for a "Saved" confirmation. */
    val justSaved: Boolean = false,
)

/**
 * Drives the detection-zones editor for one camera. Frigate is the source of truth: the editor
 * loads the camera's masks and zones from the server, edits happen locally in
 * [MaskEditorState], and a save pushes each changed layer back and then re-reads the server's
 * copy.
 */
@OptIn(ExperimentalTime::class)
@AssistedInject
class DetectionZonesViewModel(
    @Assisted private val cameraName: String,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    private val getDetectionConfigUseCase: GetDetectionConfigUseCase,
    private val saveDetectionMasksUseCase: SaveDetectionMasksUseCase,
    private val saveDetectionZonesUseCase: SaveDetectionZonesUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** One view model per camera; the screen keys it by [cameraName]. */
    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(cameraName: String): DetectionZonesViewModel
    }

    private val serverUrl: StateFlow<String?> = observeCurrentServerUrlUseCase()

    private val _uiState = MutableStateFlow(DetectionZonesUiState())
    val uiState: StateFlow<DetectionZonesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, loadError = null, justSaved = false, saveError = null) }
        viewModelScope.launch {
            getDetectionConfigUseCase(cameraName)
                .onSuccess { config ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            config = config,
                            editor = it.editor.loadedFrom(config),
                            snapshotUrl = freshSnapshotUrl(),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, loadError = error.message ?: "Couldn't load detection zones") }
                }
        }
    }

    fun refreshSnapshot() = _uiState.update { it.copy(snapshotUrl = freshSnapshotUrl()) }

    /**
     * Called each time the screen is entered. The view model outlives the screen, so without
     * this a second visit would show the first visit's frame and "Saved" line — and miss any
     * zones changed meanwhile in Frigate's own UI. Edits in progress are never thrown away.
     */
    fun reloadIfClean() {
        val state = _uiState.value
        if (!state.editor.isDirty && !state.isSaving && !state.isLoading) load()
    }

    fun switchLayer(layer: MaskLayer) = edit { it.switchLayer(layer) }
    fun tapAt(point: MaskPoint) = edit { it.tapAt(point) }
    fun startDraft() = edit { it.startDraft() }
    fun undoDraftPoint() = edit { it.undoDraftPoint() }
    fun cancelDraft() = edit { it.cancelDraft() }
    fun finishDraft() = edit { it.finishDraft() }
    fun select(index: Int?) = edit { it.select(index) }
    fun moveVertex(shapeIndex: Int, vertexIndex: Int, to: MaskPoint) = edit { it.moveVertex(shapeIndex, vertexIndex, to) }
    fun moveDraftVertex(vertexIndex: Int, to: MaskPoint) = edit { it.moveDraftVertex(vertexIndex, to) }
    fun selectVertex(vertexIndex: Int?) = edit { it.selectVertex(vertexIndex) }
    fun insertVertex(shapeIndex: Int, edgeIndex: Int, at: MaskPoint) = edit { it.insertVertex(shapeIndex, edgeIndex, at) }
    fun removeSelectedVertex() = edit { it.removeSelectedVertex() }
    fun deleteSelected() = edit { it.deleteSelected() }
    fun renameSelectedZone(friendlyName: String) = edit { it.renameSelectedZone(friendlyName) }
    fun setSelectedZoneObjects(objects: List<String>) = edit { it.setSelectedZoneObjects(objects) }

    /** Toggles one label on the selected zone's object filter. */
    fun toggleSelectedZoneObject(label: String) {
        val current = _uiState.value.editor.selectedShape?.zone?.objects ?: return
        setSelectedZoneObjects(if (label in current) current - label else current + label)
    }

    /** Throws away local edits and shows the server's masks and zones again. */
    fun discardChanges() {
        val config = _uiState.value.config ?: return
        _uiState.update { it.copy(editor = it.editor.loadedFrom(config), saveError = null) }
    }

    /** Pushes every dirty layer to Frigate (an unfinished draft is dropped), then re-reads the server's copy. */
    fun save() {
        val state = _uiState.value
        val config = state.config ?: return
        if (state.isSaving || !state.editor.isDirty) return
        val editor = state.editor.cancelDraft()
        _uiState.update { it.copy(editor = editor, isSaving = true, saveError = null, justSaved = false) }
        viewModelScope.launch {
            for (layer in editor.dirtyLayers) {
                val result = when (layer) {
                    MaskLayer.ZONES -> saveDetectionZonesUseCase(cameraName, editor.zones(), config.zones)
                    else -> saveDetectionMasksUseCase(cameraName, layer, editor.masks(layer))
                }
                result.onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, saveError = error.message ?: "Couldn't save ${layer.label.lowercase()}") }
                    return@launch
                }
            }
            val reloaded = getDetectionConfigUseCase(cameraName).getOrNull()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    justSaved = true,
                    config = reloaded ?: it.config,
                    editor = if (reloaded != null) it.editor.loadedFrom(reloaded) else it.editor.copy(dirtyLayers = emptySet()),
                )
            }
        }
    }

    private inline fun edit(crossinline transform: (MaskEditorState) -> MaskEditorState) =
        _uiState.update { it.copy(editor = transform(it.editor), justSaved = false, saveError = null) }

    private fun freshSnapshotUrl(): String? =
        serverUrl.value?.let { getCameraSnapshotUrlUseCase(it, cameraName, cacheBuster = clock.now().epochSeconds) }
}
