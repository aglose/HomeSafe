package com.meticulouscreations.homesafe.fitness

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.fitness.domain.BodyBoard
import com.meticulouscreations.homesafe.fitness.domain.BodyBoards
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.ReadinessScore
import com.meticulouscreations.homesafe.fitness.domain.RunDetail
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.Runs
import com.meticulouscreations.homesafe.fitness.domain.RunsBoard
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.ZoneBounds
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Where the app stands with the device's health store. */
enum class HealthAccess {
    /** There is none on this platform: nothing about the body or runs is shown. */
    UNSUPPORTED,

    /** There is one for this device, and it has to be installed or updated first. */
    MISSING,

    /** It is there, and the app hasn't been allowed to read anything from it. */
    OFF,

    ON,
}

/**
 * What the device's health store says, for the fitness app: the body today ([body]: readiness,
 * steps, last night's sleep) and the runs ([runs]), with the one that is open ([detail]).
 *
 * Apart from [FitnessUiState] because it comes from somewhere else and at its own pace: the
 * training log is the app's own, and this is read from Health Connect when the app comes on
 * screen.
 */
@Immutable
data class HealthUiState(
    val access: HealthAccess = HealthAccess.UNSUPPORTED,
    val granted: Set<HealthKind> = emptySet(),
    /** The store has been read since access came on: an empty board now means there is nothing, not that it is still coming. */
    val loaded: Boolean = false,
    val nowEpochSeconds: Long = 0,
    val utcOffsetSeconds: Int = 0,
    val body: BodyBoard = BodyBoard(),
    val runs: RunsBoard = RunsBoard(),
    /** The run that is open, once its track has been read. */
    val detail: RunDetail? = null,
    /** The user was asked and nothing came of it: Android stops showing its sheet after two refusals, and only the store's own page can allow the app then. */
    val refused: Boolean = false,
    /** The last read couldn't reach the store; what is shown is from before. */
    val failed: Boolean = false,
) {
    val supported: Boolean get() = access != HealthAccess.UNSUPPORTED

    /** Runs can be read at all. */
    val runsShared: Boolean get() = access == HealthAccess.ON && HealthKind.EXERCISE in granted

    /** Something Today's body card would show can be read. */
    val bodyShared: Boolean get() = access == HealthAccess.ON && BODY_KINDS.any { it in granted }

    companion object {
        val BODY_KINDS = setOf(HealthKind.STEPS, HealthKind.SLEEP, HealthKind.RESTING_HEART_RATE, HealthKind.HEART_RATE_VARIABILITY)
    }
}

/**
 * The fitness app's reading of the device's health store ([HealthSource]): Health Connect on
 * Android, where the Google Health app puts what a Fitbit band records.
 *
 * Scoped to the activity like [FitnessViewModel], which it sits beside. Nothing is read until
 * [setActive] says the fitness app is on screen, and then again each time it comes back after
 * [FRESH_SECONDS]: the band syncs through the phone every quarter of an hour or so, and there
 * is no telling when from here. Nothing read is kept: it is the store's, and is asked for again.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HealthViewModel(
    private val source: HealthSource,
    private val repository: FitnessRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState(access = accessOf(source.availability(), emptySet())))
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    private var reading: Job? = null
    private var opening: Job? = null
    private var following: Job? = null
    private var lastReadAt = 0L
    private var records: Map<String, RunRecord> = emptyMap()
    private var bounds: ZoneBounds? = null

    /** The open run's track as it was read, kept so the zones can be drawn again when their numbers change. */
    private var openTrack: Pair<String, RunTrack>? = null

    /** The fitness app came on screen, or left it. */
    fun setActive(active: Boolean) {
        if (!active || !_uiState.value.supported) return
        if (following == null) {
            following = viewModelScope.launch {
                repository.heartSettings.collect { settings ->
                    bounds = HeartZones.bounds(settings.profile)
                    redrawDetail()
                }
            }
        }
        refresh()
    }

    /**
     * The Connect button: asks the user to share what the app reads, then reads it. Where the
     * store isn't installed this opens the place to get it instead.
     */
    fun connect() {
        if (_uiState.value.access == HealthAccess.MISSING) return manage()
        viewModelScope.launch {
            val before = _uiState.value.granted
            val after = attempt { source.request() } ?: before
            _uiState.update { it.copy(refused = after.isEmpty() || (after == before && !after.containsAll(HealthKind.entries))) }
            // A read already on its way began before the answer: it is let finish, and then it is read afresh.
            reading?.join()
            refresh(force = true)
        }
    }

    /** Opens the store's own page for the app. What was changed there is read when the app comes back on screen. */
    fun manage() {
        source.manage()
        lastReadAt = 0
    }

    /** Opens a run: its track is read, and [HealthUiState.detail] follows. */
    fun openRun(id: String) {
        if (_uiState.value.detail?.runId == id) return
        val record = records[id] ?: return
        val run = _uiState.value.runs.run(id) ?: return
        opening?.cancel()
        _uiState.update { it.copy(detail = null) }
        opening = viewModelScope.launch {
            // A run whose track can't be read still opens, on its totals alone.
            val track = attempt { source.track(record) } ?: RunTrack()
            openTrack = id to track
            _uiState.update { it.copy(detail = Runs.detail(run, track, bounds)) }
        }
    }

    fun closeRun() {
        opening?.cancel()
        openTrack = null
        _uiState.update { it.copy(detail = null) }
    }

    /** Asks the user for the open run's route, for a store that hands each one over only when asked. */
    fun askForRoute() {
        val (id, track) = openTrack ?: return
        viewModelScope.launch {
            val route = attempt { source.requestRoute(id) }.orEmpty()
            if (route.isEmpty() || openTrack?.first != id) return@launch
            openTrack = id to track.copy(route = route)
            redrawDetail()
        }
    }

    private fun redrawDetail() {
        val (id, track) = openTrack ?: return
        val run = _uiState.value.runs.run(id) ?: return
        _uiState.update { state -> if (state.detail?.runId == id) state.copy(detail = Runs.detail(run, track, bounds)) else state }
    }

    private fun refresh(force: Boolean = false) {
        if (reading?.isActive == true) return
        val at = clock.now().epochSeconds
        if (!force && lastReadAt != 0L && at - lastReadAt < FRESH_SECONDS) return
        reading = viewModelScope.launch {
            val availability = source.availability()
            val granted = if (availability == HealthAvailability.AVAILABLE) attempt { source.granted() }.orEmpty() else emptySet()
            val access = accessOf(availability, granted)
            if (access != HealthAccess.ON) {
                records = emptyMap()
                _uiState.update { HealthUiState(access = access, refused = it.refused && access == HealthAccess.OFF) }
                return@launch
            }
            val offsetAt = { seconds: Long -> localUtcOffsetSeconds(seconds.toDouble()) }
            val today = (at + offsetAt(at)).floorDiv(SECONDS_PER_DAY)
            var failed = false

            suspend fun <T> read(kind: HealthKind, block: suspend () -> T): T? {
                if (kind !in granted) return null
                return attempt(block).also { if (it == null) failed = true }
            }

            val bodyFrom = at - BODY_DAYS * SECONDS_PER_DAY
            val steps = read(HealthKind.STEPS) { source.stepsByDay(today - BodyBoards.STEP_DAYS + 1, today) }
            val sleep = read(HealthKind.SLEEP) { source.sleep(bodyFrom, at) }
            val resting = read(HealthKind.RESTING_HEART_RATE) { source.restingHeartRates(bodyFrom, at) }
            val variability = read(HealthKind.HEART_RATE_VARIABILITY) { source.heartRateVariability(bodyFrom, at) }
            val runs = read(HealthKind.EXERCISE) { source.runs(at - RUN_DAYS * SECONDS_PER_DAY, at) }
            lastReadAt = at
            val was = _uiState.value
            if (failed && was.loaded) {
                // Something couldn't be read: what was on screen stays, and says it may be behind.
                _uiState.update { it.copy(access = access, granted = granted, failed = true) }
                return@launch
            }
            records = runs.orEmpty().associateBy { it.id }
            _uiState.update { state ->
                state.copy(
                    access = access,
                    granted = granted,
                    loaded = true,
                    nowEpochSeconds = at,
                    utcOffsetSeconds = offsetAt(at),
                    body = BodyBoards.build(at, offsetAt, steps, sleep, resting, variability),
                    runs = Runs.board(runs.orEmpty(), today, offsetAt),
                    refused = false,
                    failed = failed,
                )
            }
            // The open run may have been deleted in the store since.
            val open = openTrack?.first ?: _uiState.value.detail?.runId
            if (open != null && records[open] == null) closeRun() else redrawDetail()
        }
    }

    /** [block]'s answer, or null when the store couldn't give one: gone, updating, or the kind no longer allowed. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        null
    }

    private companion object {
        /** How long what was read stands before the app coming back on screen reads it again. */
        const val FRESH_SECONDS = 60L

        /** How far back sleep and the heart's readings are read: the baseline readiness is held against, and a night or two to spare. */
        const val BODY_DAYS = ReadinessScore.BASELINE_DAYS + 2L

        /** How far back runs are read: the weeks the runs page adds up, and as far again to scroll through. */
        const val RUN_DAYS = Runs.WEEKS * 7L * 2

        fun accessOf(availability: HealthAvailability, granted: Set<HealthKind>): HealthAccess = when {
            availability == HealthAvailability.UNSUPPORTED -> HealthAccess.UNSUPPORTED
            availability == HealthAvailability.MISSING -> HealthAccess.MISSING
            granted.isEmpty() -> HealthAccess.OFF
            else -> HealthAccess.ON
        }
    }
}
