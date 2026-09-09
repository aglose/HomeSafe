package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCameraPlacementsUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.PlaceCameraUseCase
import com.meticulouscreations.homesafe.domain.usecase.RemoveCameraPlacementUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetHomeLayoutUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A camera on the property plan: the same [CameraTile] the list layout uses, plus where on the
 * plan it sits. [placement] is null for a camera nobody has put anywhere yet — those wait in the
 * arrange tray rather than being scattered somewhere arbitrary.
 */
@Immutable
data class MappedCamera(val tile: CameraTile, val placement: CameraPlacement?)

/**
 * What the property plan needs: every camera the server reports, each carrying its placement.
 * Null while the first read of the local caches is in flight, matching [HomeViewModel.cameras]
 * so the two layouts share one loading story.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PropertyMapViewModel(
    observeCamerasUseCase: ObserveCamerasUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    observeCameraPlacementsUseCase: ObserveCameraPlacementsUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    private val placeCameraUseCase: PlaceCameraUseCase,
    private val removeCameraPlacementUseCase: RemoveCameraPlacementUseCase,
    private val setHomeLayoutUseCase: SetHomeLayoutUseCase,
) : ViewModel() {

    val cameras: StateFlow<List<MappedCamera>?> = combine(
        observeCamerasUseCase(),
        observeCurrentServerUrlUseCase(),
        observeCameraPlacementsUseCase(),
    ) { cameras, serverUrl, placements ->
        val byName = placements.associateBy { it.cameraName }
        cameras.map { camera ->
            val tile = if (serverUrl == null || !camera.enabled) {
                CameraTile(camera, streamUrl = null, posterUrl = null)
            } else {
                CameraTile(
                    camera = camera,
                    // The plan's markers are small, so they take the grid stream, not full quality.
                    streamUrl = getLiveStreamUrlUseCase(serverUrl, camera.gridStreamName),
                    posterUrl = getCameraSnapshotUrlUseCase(serverUrl, camera.name, height = MARKER_POSTER_HEIGHT),
                )
            }
            MappedCamera(tile, byName[camera.name])
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Drops a camera at [x], [y] — fractions of the plan — or moves one already there. */
    fun place(cameraName: String, x: Float, y: Float) {
        viewModelScope.launch {
            placeCameraUseCase(CameraPlacement(cameraName, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
        }
    }

    /** Takes a camera back off the plan; it returns to the arrange tray. */
    fun removePlacement(cameraName: String) {
        viewModelScope.launch { removeCameraPlacementUseCase(cameraName) }
    }

    /** Switches the Home tab back to the stacked list. */
    fun showListLayout() {
        viewModelScope.launch { setHomeLayoutUseCase(HomeLayout.LIST) }
    }

    private companion object {
        /** Server-side downscale for the plan's markers — smaller than the list's cards, and smaller again than a detect frame. */
        const val MARKER_POSTER_HEIGHT = 240
    }
}
