package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.usecase.GetCameraSnapshotUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.GetLiveStreamUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCamerasUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveCurrentServerUrlUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHomeLayoutUseCase
import com.meticulouscreations.homesafe.domain.usecase.ObserveHouseholdPresenceUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetAwayUseCase
import com.meticulouscreations.homesafe.domain.usecase.SetHomeLayoutUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A camera plus what its grid card needs to play it. [streamUrl] and [posterUrl] are null while
 * disconnected or while the camera is disabled on the server — the card shows a placeholder then.
 */
@Immutable
data class CameraTile(val camera: Camera, val streamUrl: String?, val posterUrl: String?)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HomeViewModel(
    observeCamerasUseCase: ObserveCamerasUseCase,
    observeCurrentServerUrlUseCase: ObserveCurrentServerUrlUseCase,
    private val getLiveStreamUrlUseCase: GetLiveStreamUrlUseCase,
    private val getCameraSnapshotUrlUseCase: GetCameraSnapshotUrlUseCase,
    observeHouseholdPresenceUseCase: ObserveHouseholdPresenceUseCase,
    observeHomeLayoutUseCase: ObserveHomeLayoutUseCase,
    private val setAwayUseCase: SetAwayUseCase,
    private val setHomeLayoutUseCase: SetHomeLayoutUseCase,
) : ViewModel() {

    /**
     * Which layout the Home tab draws. Null until the stored choice has been read, so the tab
     * shows the loading skeleton for that moment rather than putting up the list and then
     * yanking it away for the map.
     */
    val layout: StateFlow<HomeLayout?> = observeHomeLayoutUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLayout(layout: HomeLayout) {
        viewModelScope.launch { setHomeLayoutUseCase(layout) }
    }

    /** Away mode banner: true while the relay says nobody is home. Collecting it keeps presence polled. */
    val everyoneAway: StateFlow<Boolean> = observeHouseholdPresenceUseCase()
        .map { it.everyoneAway }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "I'm back": marks this phone home, which ends away mode for the household. Failures leave the banner up. */
    fun markBack() {
        viewModelScope.launch { setAwayUseCase(false) }
    }

    /**
     * Null until the first read of the local camera cache lands (a few milliseconds), so the
     * screen can tell "not loaded yet" from "this server has no cameras" and never flashes the
     * empty-state message on the way in. URLs are built here, not in the card, so a LAN/Tailscale
     * route flip re-points every player at once.
     */
    val cameras: StateFlow<List<CameraTile>?> = combine(
        observeCamerasUseCase(),
        observeCurrentServerUrlUseCase(),
    ) { cameras, serverUrl ->
        cameras.map { camera -> tile(camera, serverUrl) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun tile(camera: Camera, serverUrl: String?): CameraTile {
        if (serverUrl == null || !camera.enabled) return CameraTile(camera, streamUrl = null, posterUrl = null)
        // The grid uses the camera's (possibly lower-quality) grid stream — full quality is
        // reserved for the single-camera detail view.
        return CameraTile(
            camera = camera,
            streamUrl = getLiveStreamUrlUseCase(serverUrl, camera.gridStreamName),
            posterUrl = getCameraSnapshotUrlUseCase(serverUrl, camera.name, height = GRID_POSTER_HEIGHT),
        )
    }

    private companion object {
        /** Server-side downscale for grid posters: plenty for a card, ~30 KB per refresh instead of a full detect frame. */
        const val GRID_POSTER_HEIGHT = 480
    }
}
