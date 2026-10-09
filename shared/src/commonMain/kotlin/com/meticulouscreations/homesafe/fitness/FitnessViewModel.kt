package com.meticulouscreations.homesafe.fitness

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyweightTrend
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.ExerciseClassifier
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartRateMonitor
import com.meticulouscreations.homesafe.fitness.domain.HeartRecovery
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.HeartSettings
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.ImportPlan
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LogCopies
import com.meticulouscreations.homesafe.fitness.domain.LogCopy
import com.meticulouscreations.homesafe.fitness.domain.LogCopyRead
import com.meticulouscreations.homesafe.fitness.domain.LogCopyText
import com.meticulouscreations.homesafe.fitness.domain.LogMerge
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.NotesImport
import com.meticulouscreations.homesafe.fitness.domain.NotesParser
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.Strength
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.fitness.domain.ZoneBounds
import com.meticulouscreations.homesafe.fitness.domain.ZoneTracker
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The record a set just logged turned out to be, to be celebrated once. [token] tells one flash from the next. */
@Immutable
data class RecordFlash(val token: Int, val exerciseName: String, val set: LoggedSet, val record: Record, val loadKind: LoadKind)

/** The rest between sets, counting down to [endsAtEpochMillis] from [totalSeconds]. */
@Immutable
data class RestTimer(val endsAtEpochMillis: Long, val totalSeconds: Int, val exerciseId: String)

/** A copy of a whole log handed over where notes usually are: the [log] as it was read, and what bringing it in would add here. */
@Immutable
data class CopyImport(val log: LogCopy, val merge: LogMerge)

/**
 * What is being brought in: what was pasted, the shelf it was said to be for, and what importing
 * it would do. That is a [plan] for notes; for a copy of a log sent from another install of the
 * app ([LogCopyText]) it is [logCopy], and there is no plan.
 */
@Immutable
data class ImportState(
    val text: String = "",
    val part: BodyPart? = null,
    val plan: ImportPlan? = null,
    val logCopy: CopyImport? = null,
    /** The text says it is a copy of a log, but can't be read as one. */
    val copyUnreadable: Boolean = false,
    /** How many exercises and sets the last import added, until the page is left. */
    val importedExercises: Int? = null,
    val importedSets: Int = 0,
)

/** The heart settling into another zone, to be noticed once. [token] tells one notice from the next; a zone is null under zone 1. */
@Immutable
data class ZoneNotice(val token: Int, val from: HeartZone?, val to: HeartZone?) {
    val rising: Boolean get() = (to?.number ?: 0) > (from?.number ?: 0)
}

/** A finished workout and what its heart added up to. */
@Immutable
data class WorkoutHeart(val workout: Workout, val summary: HeartSummary)

/**
 * The heart rate, live: what the sensor's link is doing, the reading and the zone it has settled
 * in, and for the workout that is open the time in each zone so far ([summary]) and, during a
 * rest, how far the heart has come back down ([recovery]). [last] is the most recent finished
 * workout a sensor was on for.
 *
 * Apart from [FitnessUiState] because it changes every second: only what shows the heart rate
 * need be redrawn for it.
 */
@Immutable
data class HeartUiState(
    val sensor: HeartSensorState = HeartSensorState.Unsupported,
    val settings: HeartSettings = HeartSettings(),
    /** The zones, once a maximum heart rate or an age has been given. */
    val bounds: ZoneBounds? = null,
    val bpm: Int? = null,
    val zone: HeartZone? = null,
    val notice: ZoneNotice? = null,
    val recovery: HeartRecovery? = null,
    val summary: HeartSummary? = null,
    val last: WorkoutHeart? = null,
) {
    val supported: Boolean get() = sensor != HeartSensorState.Unsupported

    /** A sensor has been chosen, or one is being looked for: the heart rate has a place on the workout's page. */
    val wanted: Boolean get() = supported && (settings.sensor != null || sensor != HeartSensorState.Off)
}

@Immutable
data class FitnessUiState(
    /** The log has been read: empty lists now mean there is nothing, not that it is still coming. */
    val loaded: Boolean = false,
    val nowEpochSeconds: Long = 0,
    val utcOffsetSeconds: Int = 0,
    val phase: PhaseStatus = PhaseStatus(),
    val phases: List<Phase> = emptyList(),
    val boards: List<ExerciseBoard> = emptyList(),
    val days: List<DayCard> = emptyList(),
    val workout: ActiveWorkout? = null,
    val rest: RestTimer? = null,
    val flash: RecordFlash? = null,
    val week: WeekSummary = WeekSummary(),
    val recentRecords: List<RecordEvent> = emptyList(),
    val bodyweight: BodyweightTrend = BodyweightTrend(),
    val calendar: List<TrainedDay> = emptyList(),
    val import: ImportState = ImportState(),
) {
    fun board(exerciseId: String): ExerciseBoard? = boards.firstOrNull { it.exercise.id == exerciseId }

    /** Nothing to train from yet: the notes haven't been brought in and no exercise has been added. */
    val isEmpty: Boolean get() = loaded && boards.isEmpty()
}

/**
 * The fitness app's state: the training log read from the device and everything the screens
 * work out from it (see [FitnessBoardBuilder]), the workout in progress, the rest timer, the
 * notes import, and the heart rate ([heart]).
 *
 * Scoped to the activity, as the other drawer apps' are, so the drawer's card and the full app
 * share one. Nothing is read until [setActive] says the drawer or the app is on screen; from
 * then the log is followed, and while it is on screen the clock is moved on each minute so "3
 * days ago" and a break's ease-in stay true.
 *
 * The heart-rate sensor is listened to only while the app itself is on screen: there is no
 * service behind it, so with the phone locked or another app in front the link is let go, and
 * found again on coming back. What the heart did in between isn't known and isn't counted.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FitnessViewModel(
    private val repository: FitnessRepository,
    private val monitor: HeartRateMonitor,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FitnessUiState(nowEpochSeconds = now()))
    val uiState: StateFlow<FitnessUiState> = _uiState.asStateFlow()

    private val _heart = MutableStateFlow(HeartUiState(sensor = monitor.state.value))
    val heart: StateFlow<HeartUiState> = _heart.asStateFlow()

    private var log = FitnessLog()
    private var follow: Job? = null
    private var tick: Job? = null
    private var starting: Job? = null
    private var importing: Job? = null
    private var flashes = 0

    private var heartSettings = HeartSettings()
    private var heartSummaries: Map<Long, HeartSummary>? = null

    /** The app itself is on screen, which is when the sensor is listened to. */
    private var watching = false

    /** Sensors are being looked for, to choose one. */
    private var searching = false

    /** The sensor the monitor was last told to hold a link to. */
    private var following: HeartSensor? = null
    private var tally: HeartTally? = null
    private var zones = ZoneTracker()
    private var notices = 0

    /** The highest reading since the rest that is counting began. */
    private var restPeak: Int? = null

    /** A workout being closed: readings that arrive before the log says so are not added to it. */
    private var closing: Long? = null

    /** The drawer ([full] false) or the app itself came on screen; [active] false when both are gone. */
    fun setActive(active: Boolean, full: Boolean = true) {
        tick?.cancel()
        tick = null
        watching = active && full
        syncHeartLink()
        if (!active) return
        if (follow == null) {
            followHeart()
            follow = viewModelScope.launch {
                combine(repository.exercises, repository.sets, repository.workouts, repository.phases, repository.bodyweights, ::FitnessLog).collect { latest ->
                    log = latest
                    closeAbandonedWorkouts(latest)
                    publish(loaded = true)
                }
            }
        }
        if (!full) return
        tick = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                closeAbandonedWorkouts(log)
                publish()
            }
        }
    }

    fun startWorkout(focus: WorkoutFocus) {
        // A second tap before the first has been read back is the same request, not another workout.
        if (_uiState.value.workout != null || starting?.isActive == true) return
        starting = viewModelScope.launch { repository.startWorkout(focus, now()) }
    }

    fun finishWorkout() {
        val workout = _uiState.value.workout ?: return
        _uiState.update { it.copy(rest = null) }
        val id = workout.workout.id
        val heart = tally?.takeIf { it.workoutId == id }?.summary
        closing = id
        tally = null
        restPeak = null
        _heart.update { it.copy(summary = null, recovery = null) }
        // Whether it has anything in it is the repository's to say, at the moment it closes it: a set still on its way in counts.
        viewModelScope.launch {
            // Its heart first, so that a workout closed as empty takes that with it.
            if (heart != null && !heart.isEmpty) repository.saveHeartSummary(id, heart)
            repository.finishWorkout(id, now())
        }
    }

    /**
     * Logs a set of [exerciseId], into the workout that is open if there is one. If it turns out
     * to be a record that is flashed; either way the exercise's rest starts counting.
     */
    fun logSet(exerciseId: String, weight: Double, reps: Int) {
        if (reps <= 0) return
        val state = _uiState.value
        val exercise = state.board(exerciseId)?.exercise ?: return
        val at = now()
        viewModelScope.launch {
            val saved = repository.addSets(
                listOf(SetDraft(exerciseId, weight.coerceAtLeast(0.0), reps, at, bodyweight = bodyweightFor(exercise), workoutId = state.workout?.workout?.id)),
            ).firstOrNull() ?: return@launch
            // The log's own flow brings the set to the screens; the record is worked out here against what stood before it.
            val before = log.sets.filter { it.exerciseId == exerciseId && it.id != saved.id }
            val phaseStart = log.phases.lastOrNull { it.startedAtEpochSeconds <= at }?.startedAtEpochSeconds ?: 0L
            val record = Strength.record(exercise, saved, before, phaseStart, state.bodyweight.latest?.trend)
            // The rest starts from where the heart is now; it usually climbs a few beats more before it turns.
            restPeak = _heart.value.bpm
            _uiState.update { current ->
                current.copy(
                    rest = RestTimer(clock.now().toEpochMilliseconds() + exercise.restSeconds * 1000L, exercise.restSeconds, exerciseId),
                    flash = record?.let { RecordFlash(++flashes, exercise.name, saved, it, exercise.loadKind) } ?: current.flash,
                )
            }
        }
    }

    fun deleteSet(id: Long) {
        viewModelScope.launch { repository.deleteSet(id) }
    }

    fun dismissFlash() = _uiState.update { it.copy(flash = null) }

    fun skipRest() {
        restPeak = null
        _heart.update { it.copy(recovery = null) }
        _uiState.update { it.copy(rest = null) }
    }

    /** Lengthens (or with a negative [seconds], shortens) the rest that is counting. */
    fun adjustRest(seconds: Int) = _uiState.update { state ->
        val rest = state.rest ?: return@update state
        state.copy(rest = rest.copy(endsAtEpochMillis = rest.endsAtEpochMillis + seconds * 1000L, totalSeconds = (rest.totalSeconds + seconds).coerceAtLeast(1)))
    }

    /** Adds an exercise by name alone; what it works and how it is loaded are guessed from the name and can be changed after. */
    fun addExercise(name: String, bodyPart: BodyPart, loadKind: LoadKind? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val guess = ExerciseClassifier.classify(trimmed, bodyPart)
        val kind = loadKind ?: guess.loadKind
        val exercise = Exercise(
            id = Exercise.idFor(bodyPart, trimmed),
            name = trimmed,
            bodyPart = bodyPart,
            equipment = guess.equipment,
            loadKind = kind,
            primary = guess.primary,
            secondary = guess.secondary,
            repLow = guess.repBand.first,
            repHigh = guess.repBand.last,
            increment = if (kind == LoadKind.LEVEL) 1.0 else guess.increment,
            restSeconds = guess.restSeconds,
        )
        // An exercise of that name on that shelf is already there: adding it again must not wipe what was set on it.
        if (log.exercises.any { it.id == exercise.id && !it.archived }) return
        viewModelScope.launch { repository.saveExercises(listOf(exercise)) }
    }

    fun saveExercise(exercise: Exercise) {
        viewModelScope.launch { repository.saveExercises(listOf(exercise)) }
    }

    fun deleteExercise(id: String) {
        viewModelScope.launch { repository.deleteExercise(id) }
    }

    /** A new phase begins now. Choosing the one already in force changes nothing. */
    fun startPhase(kind: PhaseKind) {
        if (_uiState.value.phase.kind == kind && _uiState.value.phase.startedAtEpochSeconds > 0) return
        viewModelScope.launch { repository.startPhase(kind, now()) }
    }

    /** Today's weigh-in, replacing an earlier one from today. */
    fun logBodyweight(pounds: Double) {
        if (pounds <= 0.0) return
        val at = now()
        val day = (at + localUtcOffsetSeconds(at.toDouble())).floorDiv(SECONDS_PER_DAY)
        viewModelScope.launch { repository.saveBodyweight(BodyweightEntry(day, pounds)) }
    }

    fun deleteBodyweight(epochDay: Long) {
        viewModelScope.launch { repository.deleteBodyweight(epochDay) }
    }

    /**
     * The notes pasted (or shared) so far, read as they are typed so the page can show what they
     * would bring in. A copy of a whole log arrives the same way, and is read as that instead.
     */
    fun setImportText(text: String) = _uiState.update { state ->
        val import = when (val read = LogCopyText.read(text)) {
            is LogCopyRead.Copy -> state.import.copy(text = text, plan = null, logCopy = CopyImport(read.copy, mergeOf(read.copy)), copyUnreadable = false)
            LogCopyRead.Unreadable -> state.import.copy(text = text, plan = null, logCopy = null, copyUnreadable = true)
            LogCopyRead.NotACopy -> state.import.copy(text = text, plan = planFor(text, state.import.part), logCopy = null, copyUnreadable = false)
        }
        state.copy(import = import.copy(importedExercises = null))
    }

    /** The shelf the pasted notes are for, when they don't say themselves; null to go by each exercise's name. */
    fun setImportPart(part: BodyPart?) = _uiState.update { state ->
        val notes = state.import.logCopy == null && !state.import.copyUnreadable
        state.copy(import = state.import.copy(part = part, plan = if (notes) planFor(state.import.text, part) else null))
    }

    fun confirmImport() {
        val copy = _uiState.value.import.logCopy
        if (copy != null) {
            if (copy.merge.isEmpty || importing?.isActive == true) return
            importing = viewModelScope.launch {
                val added = repository.bringIn(copy.log)
                _uiState.update { it.copy(import = ImportState(importedExercises = added.newExercises, importedSets = added.sets.size)) }
            }
            return
        }
        val plan = _uiState.value.import.plan ?: return
        // One import at a time: a second tap while the first is being saved has nothing more to add.
        if (plan.isEmpty || importing?.isActive == true) return
        importing = viewModelScope.launch {
            val added = repository.importNotes(plan.exercises.filter { it.isNew }.map { it.exercise }, plan.exercises.flatMap { it.sets })
            _uiState.update { it.copy(import = ImportState(importedExercises = plan.newExercises, importedSets = added)) }
        }
    }

    fun clearImport() = _uiState.update { it.copy(import = ImportState()) }

    /** The whole log as text, to send to another install of the app, whose import page reads it ([LogCopyText]). */
    suspend fun logCopyText(): String = LogCopyText.encode(repository.logCopy())

    /**
     * The Connect button: looks for sensors to choose from, or with one already chosen goes
     * after it again. Either way this is the moment the user is asked for Bluetooth.
     */
    fun connectHeart() {
        val sensor = heartSettings.sensor
        searching = sensor == null
        following = sensor
        if (sensor == null) monitor.search() else monitor.follow(sensor, ask = true)
    }

    /** One of the sensors found is the one: it is remembered, and listened to from now on. */
    fun chooseHeartSensor(sensor: HeartSensor) {
        searching = false
        following = sensor
        heartSettings = heartSettings.copy(sensor = sensor)
        monitor.follow(sensor, ask = true)
        viewModelScope.launch { repository.saveHeartSensor(sensor) }
    }

    fun forgetHeartSensor() {
        searching = false
        following = null
        heartSettings = heartSettings.copy(sensor = null)
        monitor.stop()
        viewModelScope.launch { repository.saveHeartSensor(null) }
    }

    /** The page with the list of sensors was left without one being chosen. */
    fun stopHeartSearch() {
        if (!searching) return
        searching = false
        monitor.stop()
    }

    fun saveHeartProfile(profile: HeartProfile) {
        viewModelScope.launch { repository.saveHeartProfile(profile) }
    }

    fun dismissZoneNotice() = _heart.update { it.copy(notice = null) }

    private fun followHeart() {
        viewModelScope.launch {
            repository.heartSettings.collect { settings ->
                heartSettings = settings
                _heart.update { it.copy(settings = settings, bounds = HeartZones.bounds(settings.profile)) }
                syncHeartLink()
            }
        }
        viewModelScope.launch {
            repository.heartSummaries.collect { summaries ->
                heartSummaries = summaries
                _heart.update { it.copy(last = lastHeart()) }
            }
        }
        viewModelScope.launch { monitor.state.collect(::onSensor) }
    }

    /** Has the monitor hold a link to the chosen sensor while the app is on screen, and let it go when it isn't. */
    private fun syncHeartLink() {
        if (monitor.state.value == HeartSensorState.Unsupported) return
        val sensor = heartSettings.sensor
        when {
            !watching -> {
                if (following != null || searching) monitor.stop()
                following = null
                searching = false
                saveTally()
            }

            // The user is choosing; what was chosen before, if anything, waits.
            searching -> Unit

            sensor == null -> {
                if (following != null) monitor.stop()
                following = null
            }

            sensor != following -> {
                following = sensor
                monitor.follow(sensor)
            }
        }
    }

    private fun onSensor(sensor: HeartSensorState) {
        val connected = sensor as? HeartSensorState.Connected
        val chosen = heartSettings.sensor
        if (connected != null && chosen != null && connected.sensor != chosen) {
            // It turned up at another address under its own name (see HeartRateMonitorImpl.find): that is where to look first next time.
            following = connected.sensor
            heartSettings = heartSettings.copy(sensor = connected.sensor)
            viewModelScope.launch { repository.saveHeartSensor(connected.sensor) }
        }
        val bpm = connected?.bpm
        val bounds = _heart.value.bounds
        val workout = _uiState.value.workout?.workout?.takeIf { it.id != closing }
        var notice = _heart.value.notice
        if (connected == null) zones = ZoneTracker()
        if (bpm == null || bounds == null) {
            // Nothing to add up: the next reading starts afresh instead of standing for the gap.
            tally = tally?.copy(lastAtMillis = null)
        } else {
            val zone = bounds.zoneOf(bpm)
            if (workout != null) add(workout.id, bpm, zone, connected.atEpochMillis)
            val before = zones
            zones = zones.next(zone, connected.atEpochMillis)
            if (workout != null && before.settled && zones.zone != before.zone) notice = ZoneNotice(++notices, before.zone, zones.zone)
        }
        val resting = _uiState.value.rest != null
        restPeak = when {
            !resting -> null
            bpm != null -> maxOf(restPeak ?: bpm, bpm)
            else -> restPeak
        }
        val peak = restPeak
        _heart.update {
            it.copy(
                sensor = sensor,
                settings = heartSettings,
                bpm = bpm,
                zone = if (bpm != null && bounds != null) zones.zone else null,
                notice = notice,
                recovery = if (bpm != null && peak != null) HeartRecovery(peak, bpm) else null,
                summary = workout?.let { open -> tally?.takeIf { held -> held.workoutId == open.id }?.summary },
            )
        }
    }

    /** Adds a reading to the open workout's heart, carrying on from what was saved of it if the app was away meanwhile. */
    private fun add(workoutId: Long, bpm: Int, zone: HeartZone?, atMillis: Long) {
        // Until what was saved has been read there is nothing to carry on from, and starting from nothing would write over it.
        val saved = heartSummaries ?: return
        val held = tally?.takeIf { it.workoutId == workoutId } ?: run {
            saveTally()
            (saved[workoutId] ?: HeartSummary()).let { HeartTally(workoutId, it, savedMillis = it.totalMillis) }
        }
        val summary = held.summary.plus(bpm, zone, held.lastAtMillis?.let { atMillis - it } ?: 0L)
        tally = held.copy(summary = summary, lastAtMillis = atMillis)
        if (summary.totalMillis - held.savedMillis >= HEART_SAVE_MILLIS) saveTally()
    }

    /** Writes the open workout's heart down if it has moved on since it last was. */
    private fun saveTally() {
        val held = tally ?: return
        if (held.summary.totalMillis == held.savedMillis) return
        tally = held.copy(savedMillis = held.summary.totalMillis)
        viewModelScope.launch { repository.saveHeartSummary(held.workoutId, held.summary) }
    }

    /** The most recent finished workout that a sensor was on for. */
    private fun lastHeart(): WorkoutHeart? {
        val summaries = heartSummaries ?: return null
        return log.workouts.filter { it.finishedAtEpochSeconds != null }
            .sortedByDescending { it.startedAtEpochSeconds }
            .firstNotNullOfOrNull { workout -> summaries[workout.id]?.takeIf { !it.isEmpty }?.let { WorkoutHeart(workout, it) } }
    }

    override fun onCleared() {
        monitor.stop()
    }

    private fun planFor(text: String, part: BodyPart?): ImportPlan? {
        if (text.isBlank()) return null
        return NotesImport.plan(NotesParser.parse(text, part), log.exercises, log.sets, now())
    }

    /** What [copy] would add to the log as it is known here. The repository works it out again, against what it holds, when it is brought in. */
    private fun mergeOf(copy: LogCopy): LogMerge =
        LogCopies.merge(copy, LogCopy(log.exercises, log.sets, log.workouts, log.phases, log.bodyweights, heartSettings, heartSummaries.orEmpty()))

    /** The import as it stands against the log now: the same notes or the same copy, with what is new in them worked out afresh. */
    private fun ImportState.refreshed(): ImportState = when {
        logCopy != null -> copy(logCopy = logCopy.copy(merge = mergeOf(logCopy.log)))
        copyUnreadable || text.isBlank() -> this
        else -> copy(plan = planFor(text, part))
    }

    /** What the lifter weighs now, kept with a set where the body is the load, so the set still reads right after the scale has moved. */
    private fun bodyweightFor(exercise: Exercise): Double? =
        if (exercise.loadKind == LoadKind.BODYWEIGHT) _uiState.value.bodyweight.latest?.pounds else null

    /** A workout left open for hours was walked away from: it is closed at its last set, or dropped if it has none. */
    private fun closeAbandonedWorkouts(log: FitnessLog) {
        val at = now()
        val abandoned = log.workouts.filter { it.finishedAtEpochSeconds == null && !it.isInProgress(at) }
        if (abandoned.isEmpty()) return
        viewModelScope.launch {
            for (workout in abandoned) {
                val lastSet = log.sets.filter { it.workoutId == workout.id }.maxOfOrNull { it.epochSeconds }
                repository.finishWorkout(workout.id, lastSet ?: workout.startedAtEpochSeconds)
            }
        }
    }

    private fun publish(loaded: Boolean? = null) {
        val at = now()
        val offset = localUtcOffsetSeconds(at.toDouble())
        val boards = FitnessBoardBuilder.build(log, at, offset)
        _uiState.update { state ->
            state.copy(
                loaded = loaded ?: state.loaded,
                nowEpochSeconds = at,
                utcOffsetSeconds = offset,
                phase = boards.phase,
                phases = log.phases,
                boards = boards.boards,
                days = boards.days,
                workout = boards.workout,
                // A rest that ran out a while ago has said so for long enough.
                rest = state.rest?.takeIf { it.endsAtEpochMillis > at * 1000L - REST_LINGER_MS },
                week = boards.week,
                recentRecords = boards.recentRecords,
                bodyweight = boards.bodyweight,
                calendar = boards.calendar,
                import = state.import.refreshed(),
            )
        }
        if (boards.workout?.workout?.id != closing) closing = null
        _heart.update { it.copy(last = lastHeart(), summary = it.summary.takeIf { boards.workout != null }) }
    }

    private fun now(): Long = clock.now().epochSeconds

    private companion object {
        const val TICK_MS = 60_000L

        /** How long a finished rest stays on screen, saying it is over, before it is cleared. */
        const val REST_LINGER_MS = 5 * 60_000L

        /** How much of a workout's heart may be added up before it is written down, so a killed app loses little of it. */
        const val HEART_SAVE_MILLIS = 30_000L
    }
}

/** The open workout's heart as it is added up: when the last reading came, and how much of it has been saved. */
private data class HeartTally(val workoutId: Long, val summary: HeartSummary, val lastAtMillis: Long? = null, val savedMillis: Long = 0)
