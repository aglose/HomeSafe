package com.meticulouscreations.homesafe.atlas

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalysis
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalyzer
import com.meticulouscreations.homesafe.atlas.domain.AtlasDensity
import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.AtlasRepository
import com.meticulouscreations.homesafe.atlas.domain.AtlasSyncState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.Track
import com.meticulouscreations.homesafe.atlas.domain.localDay
import com.meticulouscreations.homesafe.weather.data.MapTileStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlin.time.Clock

/** Every path, ready to draw, and where they go counted for the far-out views. */
@Immutable
class AtlasPaths(val tracks: List<Track>, val density: AtlasDensity) {
    /** For each of [tracks], the bit that stands for its year in the density's `years`. */
    val trackYears: IntArray = IntArray(tracks.size) { density.yearBit(CivilDate.of(localDay(tracks[it].start, tracks[it].offsetMinutes)).year) }

    companion object {
        val Empty = AtlasPaths(emptyList(), AtlasDensity.Empty)
    }
}

@Immutable
data class AtlasUiState(
    /** What the device holds has been read (it may be nothing). */
    val loaded: Boolean = false,
    val history: AtlasHistory = AtlasHistory.Empty,
    val analysis: AtlasAnalysis = AtlasAnalysis.Empty,
    /** The history is in and what the screens show of it is still being worked out. */
    val analyzing: Boolean = false,
    /** Null until the paths have been read: the map draws places alone until then. */
    val paths: AtlasPaths? = null,
    val sync: AtlasSyncState = AtlasSyncState(),
    /** Today, as a day counted from 1970-01-01 on this device's clock. */
    val today: Int = 0,
    val nowEpochSeconds: Long = 0,
) {
    val empty: Boolean get() = loaded && history.isEmpty
}

/**
 * The atlas app: where its owner has been, from the Google Maps Timeline history the relay keeps.
 * Opens on the device's copy at once; [setActive] brings it up to date behind that.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class AtlasViewModel(
    private val repository: AtlasRepository,
    private val clock: Clock,
    /** The base map's tiles, shared with the weather radar's. */
    val tiles: MapTileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AtlasUiState(today = today(), nowEpochSeconds = clock.now().epochSeconds))
    val uiState: StateFlow<AtlasUiState> = _uiState.asStateFlow()

    private var follow: Job? = null
    private var pathsJob: Job? = null

    /** The app itself has been up, so the paths are wanted for whatever history comes. */
    private var wantPaths = false
    private var pathsOf: AtlasHistory? = null
    private var refreshedAt = 0L

    private fun today(): Int {
        val now = clock.now()
        return localDay(now.epochSeconds, TimeZone.currentSystemDefault().offsetAt(now).totalSeconds / 60)
    }

    /**
     * [active] while the drawer's card or the app is on screen, which is when the relay is asked
     * for anything new; [full] once the app itself is up, which is when the paths are read.
     */
    fun setActive(active: Boolean, full: Boolean = true) {
        if (!active) return
        _uiState.update { it.copy(today = today(), nowEpochSeconds = clock.now().epochSeconds) }
        if (follow == null) {
            follow = viewModelScope.launch {
                launch { repository.sync.collect { sync -> _uiState.update { it.copy(sync = sync) } } }
                launch { repository.load() }
                repository.history.collectLatest { history ->
                    if (history == null) {
                        // Not read yet, or not this sign-in's to see until the relay says it is.
                        pathsOf = null
                        _uiState.update { it.copy(loaded = false, history = AtlasHistory.Empty, analysis = AtlasAnalysis.Empty, analyzing = false, paths = null) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(loaded = true, history = history, analyzing = !history.isEmpty, paths = it.paths.takeIf { pathsOf === history }) }
                    // Beside the working out, not after it: the map's glow has nothing to wait for in the analysis.
                    if (wantPaths) loadPaths(history)
                    val analysis = withContext(Dispatchers.Default) { AtlasAnalyzer.analyze(history) }
                    _uiState.update { it.copy(analysis = analysis, analyzing = false) }
                }
            }
        }
        // From the drawer's card too: one small question when nothing has changed, and what says
        // the copy on the device is this sign-in's to show.
        if (clock.now().epochSeconds - refreshedAt > REFRESH_AFTER_SECONDS) refresh()
        if (!full) return
        wantPaths = true
        if (_uiState.value.loaded) loadPaths(_uiState.value.history)
    }

    private fun loadPaths(history: AtlasHistory) {
        if (pathsOf === history) return
        pathsJob?.cancel()
        pathsJob = viewModelScope.launch {
            val paths = if (history.isEmpty) {
                AtlasPaths.Empty
            } else {
                val tracks = repository.tracks()
                AtlasPaths(tracks, AtlasDensity.counted(tracks))
            }
            pathsOf = history
            _uiState.update { if (it.history === history) it.copy(paths = paths) else it }
        }
    }

    /** Asks the relay what it has. With [askDrive] it is also told to look in the Drive folder now. */
    fun refresh(askDrive: Boolean = false) {
        refreshedAt = clock.now().epochSeconds
        viewModelScope.launch { repository.refresh(askDrive) }
    }

    private companion object {
        /** Opening the app again sooner than this doesn't ask the relay again: the export changes when its owner makes a new one. */
        const val REFRESH_AFTER_SECONDS = 600L
    }
}
