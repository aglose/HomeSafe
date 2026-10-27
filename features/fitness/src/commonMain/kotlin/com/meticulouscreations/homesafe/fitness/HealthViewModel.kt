package com.meticulouscreations.homesafe.fitness

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.fitness.domain.BodyBoard
import com.meticulouscreations.homesafe.fitness.domain.BodyBoards
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.ReadinessScore
import com.meticulouscreations.homesafe.fitness.domain.RunDetail
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.Runs
import com.meticulouscreations.homesafe.fitness.domain.RunsBoard
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
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

    /**
     * It is there, and it hasn't been asked yet what the app may read: how every view model
     * starts. Not [OFF], which would offer to connect an app that may well be connected.
     */
    CHECKING,

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
    /** The user has been asked twice and nothing came of it: Android stops showing its sheet after two refusals, and only the store's own page can allow the app then. */
    val refused: Boolean = false,
    /** The last read couldn't reach the store; what is shown is from before. */
    val failed: Boolean = false,
) {
    val supported: Boolean get() = access != HealthAccess.UNSUPPORTED

    /** Runs can be read at all. */
    val runsShared: Boolean get() = access == HealthAccess.ON && HealthKind.EXERCISE in granted

    /** Something Today's body card would show can be read. */
    val bodyShared: Boolean get() = access == HealthAccess.ON && BODY_KINDS.any { it in granted }

    /** Some of what the app reads is shared and some isn't: only the store's own page can add the rest. */
    val partlyShared: Boolean get() = access == HealthAccess.ON && !granted.containsAll(HealthKind.entries)

    companion object {
        val BODY_KINDS = setOf(HealthKind.STEPS, HealthKind.SLEEP, HealthKind.RESTING_HEART_RATE, HealthKind.HEART_RATE_VARIABILITY)
    }
}

/**
 * The fitness app's reading of the device's health store ([HealthSource]): Health Connect on
 * Android, where the Google Health app puts what a Fitbit band records.
 *
 * Scoped to the activity like [FitnessViewModel], which it sits beside. Nothing is read until
 * [setActive] says the fitness app (or the drawer, whose card shows today's readiness) is on
 * screen, and then again each time it comes back after [FRESH_SECONDS]: the band syncs through
 * the phone every quarter of an hour or so, and there is no telling when from here. Nothing read
 * is kept: it is the store's, and is asked for again.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HealthViewModel(
    private val source: HealthSource,
    private val repository: FitnessRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState(access = accessOf(source.availability(), granted = null)))
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    private var reading: Job? = null
    private var opening: Job? = null
    private var following: Job? = null
    private var lastReadAt = 0L
    private var records: Map<String, RunRecord> = emptyMap()
    private var bounds: ZoneBounds? = null

    /** What the store last gave, kind by kind, to fall back on when it can't be reached. */
    private var held = Readings()

    /** How many times running the user has been asked and nothing more was allowed. */
    private var fruitless = 0

    /** The open run's track as it was read, kept so the zones can be drawn again when their numbers change. */
    private var openTrack: OpenTrack? = null

    /** The fitness app or the drawer came on screen, or both left it. */
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
            // One refusal is an answer, and the sheet will come up again. Whether it came up at all can't be
            // asked of Android, so it is the second time nothing comes of asking that the other way in is shown.
            fruitless = if (after.size > before.size) 0 else fruitless + 1
            _uiState.update { it.copy(refused = fruitless >= REFUSALS_BEFORE_SILENCE) }
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
        val granted = _uiState.value.granted
        // The run that was open is no longer the one to keep up to date.
        openTrack = null
        _uiState.update { it.copy(detail = null) }
        opening = viewModelScope.launch {
            // A run whose track can't be read still opens, on its totals alone.
            val track = attempt { source.track(record) } ?: RunTrack()
            openTrack = OpenTrack(id, track, granted)
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
        val id = openTrack?.id ?: return
        viewModelScope.launch {
            val route = attempt { source.requestRoute(id) }.orEmpty()
            // As it stands now: the track may have been read again while the sheet was up.
            val open = openTrack?.takeIf { it.id == id } ?: return@launch
            if (route.isEmpty()) return@launch
            openTrack = open.copy(track = open.track.copy(route = route))
            redrawDetail()
        }
    }

    private fun redrawDetail() {
        val open = openTrack ?: return
        val run = _uiState.value.runs.run(open.id) ?: return
        _uiState.update { state -> if (state.detail?.runId == open.id) state.copy(detail = Runs.detail(run, open.track, bounds)) else state }
    }

    /**
     * The open run's track was read under other permissions than are in force now. What is no
     * longer shared goes from the page at once, and the track is read again under what is.
     */
    private fun rereadOpenTrack(open: OpenTrack, granted: Set<HealthKind>) {
        val kept = open.track.copy(
            heart = if (HealthKind.HEART_RATE in granted) open.track.heart else emptyList(),
            distance = if (HealthKind.DISTANCE in granted) open.track.distance else emptyList(),
        )
        openTrack = OpenTrack(open.id, kept, granted)
        redrawDetail()
        val record = records[open.id] ?: return
        opening?.cancel()
        opening = viewModelScope.launch {
            val track = attempt { source.track(record) } ?: return@launch
            val still = openTrack?.takeIf { it.id == open.id } ?: return@launch
            // A route the user handed over when asked isn't in what the store gives unasked.
            openTrack = still.copy(track = track.copy(route = track.route.ifEmpty { still.track.route }))
            redrawDetail()
        }
    }

    private fun refresh(force: Boolean = false) {
        if (reading?.isActive == true) return
        val at = clock.now().epochSeconds
        if (!force && lastReadAt != 0L && at - lastReadAt < FRESH_SECONDS) return
        reading = viewModelScope.launch {
            val availability = source.availability()
            val granted = if (availability == HealthAvailability.AVAILABLE) {
                attempt { source.granted() } ?: run {
                    // The store couldn't say what is allowed. That isn't being told nothing is: what is shown stands, and
                    // is read again next time. Only with nothing to show yet is it taken as off.
                    if (_uiState.value.access == HealthAccess.ON) {
                        _uiState.update { it.copy(failed = true) }
                        return@launch
                    }
                    emptySet()
                }
            } else {
                emptySet()
            }
            val access = accessOf(availability, granted)
            if (access != HealthAccess.ON) {
                records = emptyMap()
                held = Readings()
                openTrack = null
                _uiState.update { HealthUiState(access = access, refused = it.refused && access == HealthAccess.OFF) }
                return@launch
            }
            // Shared, then, before any of it is read, which takes a call to the store a run: the card says it is
            // reading rather than offering to connect. What is shared is set with what was read, so nothing that
            // goes by it (the runs door) shows ahead of its readings.
            if (_uiState.value.access != HealthAccess.ON) _uiState.update { it.copy(access = HealthAccess.ON, refused = false) }
            val offsetAt = { seconds: Long -> localUtcOffsetSeconds(seconds.toDouble()) }
            val today = (at + offsetAt(at)).floorDiv(SECONDS_PER_DAY)
            var failed = false

            // A kind that isn't shared any more is gone at once, whatever else happens. One that is shared and
            // couldn't be read just now keeps what it last gave, and the screen says it may be behind.
            suspend fun <T> read(kind: HealthKind, last: T?, block: suspend () -> T): T? {
                if (kind !in granted) return null
                return attempt(block) ?: last.also { failed = true }
            }

            val bodyFrom = at - BODY_DAYS * SECONDS_PER_DAY
            val was = held
            val now = Readings(
                steps = read(HealthKind.STEPS, was.steps) { source.stepsByDay(today - BodyBoards.STEP_DAYS + 1, today) },
                sleep = read(HealthKind.SLEEP, was.sleep) { source.sleep(bodyFrom, at) },
                resting = read(HealthKind.RESTING_HEART_RATE, was.resting) { source.restingHeartRates(bodyFrom, at) },
                variability = read(HealthKind.HEART_RATE_VARIABILITY, was.variability) { source.heartRateVariability(bodyFrom, at) },
                runs = read(HealthKind.EXERCISE, was.runs) { source.runs(at - RUN_DAYS * SECONDS_PER_DAY, at) },
            )
            held = now
            lastReadAt = at
            // Something is shared, so asking has not come to nothing.
            fruitless = 0
            val steps = now.steps
            val sleep = now.sleep
            val resting = now.resting
            val variability = now.variability
            val runs = now.runs
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
            val open = openTrack
            val openId = open?.id ?: _uiState.value.detail?.runId
            when {
                // The open run may have been deleted in the store since.
                openId != null && records[openId] == null -> closeRun()

                open != null && open.granted != granted -> rereadOpenTrack(open, granted)

                else -> redrawDetail()
            }
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

        /** Android shows its permission sheet for a refusal and for one more, and after that not at all. */
        const val REFUSALS_BEFORE_SILENCE = 2

        /** How far back sleep and the heart's readings are read: the baseline readiness is held against, and a night or two to spare. */
        const val BODY_DAYS = ReadinessScore.BASELINE_DAYS + 2L

        /** How far back runs are read: the weeks the runs page adds up, and as far again to scroll through. */
        const val RUN_DAYS = Runs.WEEKS * 7L * 2

        /** [granted] is null when the store hasn't been asked yet. */
        fun accessOf(availability: HealthAvailability, granted: Set<HealthKind>?): HealthAccess = when {
            availability == HealthAvailability.UNSUPPORTED -> HealthAccess.UNSUPPORTED
            availability == HealthAvailability.MISSING -> HealthAccess.MISSING
            granted == null -> HealthAccess.CHECKING
            granted.isEmpty() -> HealthAccess.OFF
            else -> HealthAccess.ON
        }
    }
}

/** What the store last gave of each kind; null for one that isn't shared, or hasn't been read yet. */
private data class Readings(
    val steps: Map<Long, Long>? = null,
    val sleep: List<SleepSession>? = null,
    val resting: List<HealthSample>? = null,
    val variability: List<HealthSample>? = null,
    val runs: List<RunRecord>? = null,
)

/** The open run's track, and what was shared when it was read: read under other permissions, it is read again. */
private data class OpenTrack(val id: String, val track: RunTrack, val granted: Set<HealthKind>)
