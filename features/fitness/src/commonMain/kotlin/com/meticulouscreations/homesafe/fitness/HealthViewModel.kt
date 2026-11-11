package com.meticulouscreations.homesafe.fitness

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.fitness.domain.BodyBoard
import com.meticulouscreations.homesafe.fitness.domain.BodyBoards
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthReadings
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.HealthUpload
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.ReadinessScore
import com.meticulouscreations.homesafe.fitness.domain.RecordedRun
import com.meticulouscreations.homesafe.fitness.domain.RecordedRuns
import com.meticulouscreations.homesafe.fitness.domain.RecorderProblem
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.Run
import com.meticulouscreations.homesafe.fitness.domain.RunDetail
import com.meticulouscreations.homesafe.fitness.domain.RunEnds
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunRecorder
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.RunTracker
import com.meticulouscreations.homesafe.fitness.domain.Runs
import com.meticulouscreations.homesafe.fitness.domain.RunsBoard
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.fitness.domain.Workout
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
import kotlinx.coroutines.flow.first
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
    /** Runs can be recorded here with the phone's GPS. */
    val canRecord: Boolean = false,
    /** The user wouldn't let the app know where the phone is, the last time a run was started. */
    val locationRefused: Boolean = false,
    /** When the phone read what is shown, on a device with no store of its own; null where the store is this device's. */
    val fromPhoneAtEpochSeconds: Long? = null,
) {
    val supported: Boolean get() = access != HealthAccess.UNSUPPORTED

    /** What is shown came from another device by way of the server. Nothing here can change what is shared. */
    val fromPhone: Boolean get() = fromPhoneAtEpochSeconds != null

    /** Today has a body card: an invitation or the readings on a device with a store, and only the readings on one without. */
    val bodyCardShown: Boolean get() = supported && (!fromPhone || bodyShared)

    /** There are runs to show, or a way to record one: the runs door is there. */
    val runsShown: Boolean get() = runsShared || canRecord || runs.runs.isNotEmpty()

    /** Runs can be read at all. */
    val runsShared: Boolean get() = access == HealthAccess.ON && HealthKind.EXERCISE in granted

    /** Something Today's body card would show can be read. */
    val bodyShared: Boolean get() = access == HealthAccess.ON && BODY_KINDS.any { it in granted }

    /** Some of what the app reads is shared and some isn't: only the store's own page can add the rest. */
    val partlyShared: Boolean get() = access == HealthAccess.ON && !fromPhone && !granted.containsAll(HealthKind.entries)

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
 * is kept on the device: it is the store's, and is asked for again. What was read goes to the
 * server ([HealthUpload]), for this login's devices with no store, which read it through a
 * [HealthSource] of their own ([HealthAvailability.REMOTE]) and so come through here the same way.
 *
 * A run recorded here that was left recording after it was over is ended where the running stopped
 * ([settleRuns]): the recorder does that by the phone's positions when the run is finished, and
 * here it is held against the heart and the band's own session once those have been read.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class HealthViewModel(
    private val source: HealthSource,
    private val repository: FitnessRepository,
    private val recordedRuns: RecordedRuns,
    private val recorder: RunRecorder,
    private val upload: HealthUpload,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthUiState(access = accessOf(source.availability(), granted = null), canRecord = recorder.isSupported))
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    /** The run being recorded here, apart from [uiState] because it changes every second. */
    val recording: StateFlow<Recording?> = recorder.recording

    /** The runs recorded here, as last read. */
    private var recorded: List<RecordedRun> = emptyList()

    private var reading: Job? = null
    private var opening: Job? = null
    private var following: Job? = null
    private var sending: Job? = null
    private var settling: Job? = null

    /** The recorded runs whose ends have been looked at, each with what there was to tell by ([settleKey]). */
    private val settled = HashSet<String>()

    /** The store shares nothing, and the server has been told so since it last shared anything. */
    private var cleared = false
    private var lastReadAt = 0L
    private var records: Map<String, RunRecord> = emptyMap()
    private var bounds: ZoneBounds? = null

    /** When each workout logged here began and ended: a session the band called a run inside one was that workout. */
    private var workouts: List<LongRange> = emptyList()

    /** What the store last gave, kind by kind, to fall back on when it can't be reached. */
    private var held = Readings()

    /** How many times running the user has been asked and nothing more was allowed. */
    private var fruitless = 0

    /** The open run's track as it was read, kept so the zones can be drawn again when their numbers change. */
    private var openTrack: OpenTrack? = null

    /** The fitness app or the drawer came on screen, or both left it. */
    fun setActive(active: Boolean) {
        // Followed everywhere, not only where runs can be recorded or read: a run recorded on the phone comes to the rest from the server.
        if (!active) return
        if (following == null) {
            following = viewModelScope.launch {
                launch {
                    recordedRuns.runs.collect {
                        recorded = it
                        rebuildRuns()
                        rereadIfEnded()
                        settleRuns()
                    }
                }
                launch {
                    recorder.problem.collect { problem -> _uiState.update { it.copy(locationRefused = problem == RecorderProblem.LOCATION_REFUSED) } }
                }
                launch {
                    repository.heartSettings.collect { settings ->
                        bounds = HeartZones.bounds(settings.profile)
                        redrawDetail()
                    }
                }
                repository.workouts.collect { logged ->
                    workouts = workoutSpans(logged, clock.now().epochSeconds)
                    rebuildRuns()
                }
            }
        }
        if (source.availability() != HealthAvailability.UNSUPPORTED) refresh()
    }

    /** Starts recording a run with the phone's GPS, asking for its location first where it has to. */
    fun startRun() = recorder.start()

    fun pauseRun() = recorder.pause()

    fun resumeRun() = recorder.resume()

    /** Ends the run being recorded and keeps it. */
    fun finishRun() = recorder.finish()

    /** Ends the run being recorded and throws it away. */
    fun discardRun() = recorder.discard()

    /** Takes a run recorded here off the phone for good. A run read from the health store is the store's, and stays. */
    fun deleteRun(id: String) {
        if (_uiState.value.runs.run(id)?.recorded != true) return
        if (openTrack?.id == id || _uiState.value.detail?.runId == id) closeRun()
        viewModelScope.launch { recordedRuns.delete(id) }
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
        val run = _uiState.value.runs.run(id) ?: return
        val record = records[id]
        if (record == null && !run.recorded) return
        opening?.cancel()
        val granted = _uiState.value.granted
        // The run that was open is no longer the one to keep up to date.
        openTrack = null
        _uiState.update { it.copy(detail = null) }
        opening = viewModelScope.launch {
            // A run whose track can't be read still opens, on its totals alone.
            var of = run
            var track = if (record != null) attempt { source.track(record) } ?: RunTrack() else recordedTrack(of, granted)
            // A run recorded here may have been ended earlier while its track was read: the track is the run's as it now stands.
            while (record == null) {
                val now = _uiState.value.runs.run(id)?.takeIf { it.endEpochSeconds != of.endEpochSeconds } ?: break
                of = now
                track = recordedTrack(of, granted)
            }
            openTrack = OpenTrack(id, track, granted, of.endEpochSeconds)
            _uiState.update { it.copy(detail = Runs.detail(_uiState.value.runs.run(id) ?: of, track, bounds)) }
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
            // The store won't give this up unasked, so this is the one chance to send it along.
            records[id]?.let { record -> attempt { upload.sendRoute(record, route) } }
        }
    }

    /** The runs page from what was last read and what was recorded here, held against the workouts logged here. */
    private fun board(): RunsBoard {
        val at = clock.now().epochSeconds
        val offsetAt = { seconds: Long -> localUtcOffsetSeconds(seconds.toDouble()) }
        return Runs.board(held.runs.orEmpty(), (at + offsetAt(at)).floorDiv(SECONDS_PER_DAY), offsetAt, workouts, recorded)
    }

    /** The runs page sorted again, when the workouts it is held against or the runs recorded here have changed. */
    private fun rebuildRuns() {
        _uiState.update { it.copy(runs = board()) }
        // A run recorded here may have just been deleted.
        _uiState.value.detail?.runId?.let { open -> if (_uiState.value.runs.run(open) == null) closeRun() }
    }

    /**
     * Ends the runs recorded here where the running stopped, for those left recording after ([RunEnds]): by
     * their positions, by the band's session of the same run, and (for a run of the last [HEART_DAYS]) by the
     * heart through it. Each is looked at once for what there is to tell by, and again when there is more: a
     * band's readings come a quarter of an hour or more after the run. A run only ever gets shorter, so two
     * devices doing this come to the same end.
     */
    private fun settleRuns() {
        if (settling?.isActive == true) return
        val at = clock.now().epochSeconds
        val state = _uiState.value
        val shared = state.access == HealthAccess.ON
        val worn = if (shared) held.runs.orEmpty() else emptyList()
        val heartShared = shared && HealthKind.HEART_RATE in state.granted

        fun wornThrough(run: RecordedRun): List<LongRange> {
            val start = run.startEpochMillis / 1_000
            val end = run.endEpochMillis / 1_000
            // A band's session that was mostly inside the recording was this run, however long the recording went on.
            return worn.filter { (minOf(it.endEpochSeconds, end) - maxOf(it.startEpochSeconds, start)) * 2 > it.endEpochSeconds - it.startEpochSeconds }
                .map { it.startEpochSeconds..it.endEpochSeconds }
        }

        fun settleKey(run: RecordedRun): String {
            val age = at - run.endEpochMillis / 1_000
            // A run just finished is looked at again every quarter of an hour for a day: its heart is still coming.
            return "${run.id}/${run.endEpochMillis}/$shared/$heartShared/${wornThrough(run)}/${if (age < SECONDS_PER_DAY) at / RESETTLE_SECONDS else 0}"
        }

        val todo = recorded.filter { it.endEpochMillis - it.startEpochMillis >= RunEnds.TAIL_MILLIS && settleKey(it) !in settled }
        if (todo.isEmpty()) return
        settling = viewModelScope.launch {
            for (run in todo) {
                val events = attempt { recordedRuns.events(run.id) } ?: continue
                val recent = at - run.endEpochMillis / 1_000 < HEART_DAYS * SECONDS_PER_DAY
                val heart = if (heartShared && recent) attempt { source.heartRate(run.startEpochMillis / 1_000, run.endEpochMillis / 1_000) }.orEmpty() else emptyList()
                RunEnds.settled(run, events, heart, wornThrough(run))?.let { shorter -> attempt { recordedRuns.shorten(shorter) } }
                settled += settleKey(run)
            }
        }
    }

    /** A run recorded here, worked out again from what was recorded up to its end, with the heart a band took through it where that is shared. */
    private suspend fun recordedTrack(run: Run, granted: Set<HealthKind>): RunTrack {
        val all = attempt { recordedRuns.events(run.id) }.orEmpty()
        // The run's end is kept to the millisecond and shown to the second.
        val events = RunEnds.upTo(run.endEpochSeconds * 1_000 + 999, all)
        // How long the recording went on: to where it was finished, or for one from before that was written down, to its last position.
        val recordedOn = ((all.maxOfOrNull { it.epochMillis } ?: 0) / 1_000 - run.endEpochSeconds).takeIf { it >= RunEnds.TAIL_MILLIS / 1_000 } ?: 0
        val heart = if (_uiState.value.access == HealthAccess.ON && HealthKind.HEART_RATE in granted) {
            attempt { source.heartRate(run.startEpochSeconds, run.endEpochSeconds) }.orEmpty()
        } else {
            emptyList()
        }
        return RunTracker.replay(run.startEpochSeconds * 1_000, events).track(heart).copy(recordedOnSeconds = recordedOn)
    }

    /** The open run was recorded here and has been ended earlier since its track was read: its route, splits and heart are read again up to where it now ends. */
    private fun rereadIfEnded() {
        val open = openTrack ?: return
        val run = _uiState.value.runs.run(open.id)?.takeIf { it.recorded } ?: return
        if (run.endEpochSeconds != open.endEpochSeconds) rereadOpenTrack(open, open.granted)
    }

    private fun redrawDetail() {
        val open = openTrack ?: return
        val run = _uiState.value.runs.run(open.id) ?: return
        _uiState.update { state -> if (state.detail?.runId == open.id) state.copy(detail = Runs.detail(run, open.track, bounds)) else state }
    }

    /**
     * The open run's track was read under other permissions than are in force now. What is no
     * longer shared goes from the page at once, and the track is read again under what is. The
     * distance of a run recorded here is the phone's own, whatever the store shares.
     */
    private fun rereadOpenTrack(open: OpenTrack, granted: Set<HealthKind>) {
        val run = _uiState.value.runs.run(open.id) ?: return
        val kept = open.track.copy(
            heart = if (HealthKind.HEART_RATE in granted) open.track.heart else emptyList(),
            distance = if (run.recorded || HealthKind.DISTANCE in granted) open.track.distance else emptyList(),
        )
        openTrack = OpenTrack(open.id, kept, granted, open.endEpochSeconds)
        redrawDetail()
        val record = records[open.id]
        if (record == null && !run.recorded) return
        opening?.cancel()
        opening = viewModelScope.launch {
            val track = if (record != null) attempt { source.track(record) } ?: return@launch else recordedTrack(run, granted)
            val still = openTrack?.takeIf { it.id == open.id } ?: return@launch
            // A route the user handed over when asked isn't in what the store gives unasked. A run recorded here has its own, up to its end.
            openTrack = still.copy(track = if (run.recorded) track else track.copy(route = track.route.ifEmpty { still.track.route }), endEpochSeconds = run.endEpochSeconds)
            redrawDetail()
            // Ended earlier again while that was read.
            rereadIfEnded()
        }
    }

    private fun refresh(force: Boolean = false) {
        if (reading?.isActive == true) return
        val at = clock.now().epochSeconds
        if (!force && lastReadAt != 0L && at - lastReadAt < FRESH_SECONDS) return
        reading = viewModelScope.launch {
            val availability = source.availability()
            val hasStore = availability == HealthAvailability.AVAILABLE
            val granted = if (hasStore || availability == HealthAvailability.REMOTE) {
                attempt { source.granted() } ?: run {
                    // The store couldn't say what is allowed. That isn't being told nothing is: what is shown stands,
                    // still checking on a first look, and is asked again the next time the app comes on screen.
                    _uiState.update { it.copy(failed = true) }
                    return@launch
                }
            } else {
                emptySet()
            }
            val access = accessOf(availability, granted)
            if (access != HealthAccess.ON) {
                val wasOpen = openTrack?.id ?: _uiState.value.detail?.runId
                records = emptyMap()
                held = Readings()
                openTrack = null
                _uiState.update {
                    HealthUiState(access = access, refused = it.refused && access == HealthAccess.OFF, canRecord = it.canRecord, locationRefused = it.locationRefused)
                }
                rebuildRuns()
                // A run recorded here is still there to show, from the phone's own positions, without the band's heart.
                if (wasOpen != null && _uiState.value.runs.run(wasOpen)?.recorded == true) openRun(wasOpen)
                if (access == HealthAccess.OFF && !cleared) {
                    // One on its way began before this: it lands first, so that this is what stands.
                    sending?.join()
                    cleared = attempt { upload.clear() } != null
                }
                return@launch
            }
            // Shared, then, before any of it is read, which takes a call to the store a run: the card says it is
            // reading rather than offering to connect. What is shared is set with what was read, so nothing that
            // goes by it (the runs door) shows ahead of its readings. A copy on the server has come already.
            if (_uiState.value.access != HealthAccess.ON && hasStore) _uiState.update { it.copy(access = HealthAccess.ON, refused = false) }
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
                    runs = board(),
                    refused = false,
                    failed = failed,
                    fromPhoneAtEpochSeconds = source.readAtEpochSeconds(),
                )
            }
            // A kind that couldn't be read just now would go from the server as though it had stopped being shared: it waits for a whole read.
            if (hasStore && !failed) send(at, granted, now)
            val open = openTrack
            val openId = open?.id ?: _uiState.value.detail?.runId
            when {
                // The open run may have been deleted in the store since.
                openId != null && _uiState.value.runs.run(openId) == null -> closeRun()

                open != null && open.granted != granted -> rereadOpenTrack(open, granted)

                else -> redrawDetail()
            }
            settleRuns()
        }
    }

    /** Puts what was just read on the server, behind the screen: nothing waits for it, and what fails goes with the next read. */
    private fun send(at: Long, granted: Set<HealthKind>, read: Readings) {
        cleared = false
        if (sending?.isActive == true) return
        sending = viewModelScope.launch {
            attempt {
                val offsetAt = { seconds: Long -> localUtcOffsetSeconds(seconds.toDouble()) }
                val today = (at + offsetAt(at)).floorDiv(SECONDS_PER_DAY)
                val ours = Runs.board(read.runs.orEmpty(), today, offsetAt, workouts, recordedRuns.runs.first()).runs.filter { it.recorded }
                upload.send(
                    HealthReadings(at, granted, read.steps.orEmpty(), read.sleep.orEmpty(), read.resting.orEmpty(), read.variability.orEmpty(), read.runs.orEmpty()),
                    ours,
                )
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

        /** A run's heart is held against its end for this many days after it; an older run's end is told by its positions and the band's session. */
        const val HEART_DAYS = 14L

        /** A run of the last day has its end looked at again this often, as the band's readings come in. */
        const val RESETTLE_SECONDS = 900L

        /** How far back runs are read: the weeks the runs page adds up, and as far again to scroll through. */
        const val RUN_DAYS = Runs.WEEKS * 7L * 2

        /**
         * When each dated workout in [logged] ran. One still open is taken to run until now, or for
         * [Workout.STALE_AFTER_SECONDS] at most if it was walked away from.
         */
        fun workoutSpans(logged: List<Workout>, nowEpochSeconds: Long): List<LongRange> = logged.filter { it.startedAtEpochSeconds > 0 }.map {
            val end = it.finishedAtEpochSeconds ?: minOf(nowEpochSeconds, it.startedAtEpochSeconds + Workout.STALE_AFTER_SECONDS)
            it.startedAtEpochSeconds..maxOf(end, it.startedAtEpochSeconds)
        }

        /** [granted] is null when the store hasn't been asked yet. With no store here and nothing from a phone, there is simply nothing to show. */
        fun accessOf(availability: HealthAvailability, granted: Set<HealthKind>?): HealthAccess = when {
            availability == HealthAvailability.UNSUPPORTED -> HealthAccess.UNSUPPORTED
            availability == HealthAvailability.REMOTE && granted.isNullOrEmpty() -> HealthAccess.UNSUPPORTED
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
private data class OpenTrack(val id: String, val track: RunTrack, val granted: Set<HealthKind>, val endEpochSeconds: Long)
