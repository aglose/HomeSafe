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
import com.meticulouscreations.homesafe.fitness.domain.ImportPlan
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.NotesImport
import com.meticulouscreations.homesafe.fitness.domain.NotesParser
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.SetDraft
import com.meticulouscreations.homesafe.fitness.domain.Strength
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
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

/** The notes being brought in: what was pasted, the shelf it was said to be for, and what importing it would do. */
@Immutable
data class ImportState(
    val text: String = "",
    val part: BodyPart? = null,
    val plan: ImportPlan? = null,
    /** How many exercises and sets the last import added, until the page is left. */
    val importedExercises: Int? = null,
    val importedSets: Int = 0,
)

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
 * work out from it (see [FitnessBoardBuilder]), the workout in progress, the rest timer, and the
 * notes import.
 *
 * Scoped to the activity, as the other drawer apps' are, so the drawer's card and the full app
 * share one. Nothing is read until [setActive] says the drawer or the app is on screen; from
 * then the log is followed, and while it is on screen the clock is moved on each minute so "3
 * days ago" and a break's ease-in stay true.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FitnessViewModel(
    private val repository: FitnessRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FitnessUiState(nowEpochSeconds = now()))
    val uiState: StateFlow<FitnessUiState> = _uiState.asStateFlow()

    private var log = FitnessLog()
    private var follow: Job? = null
    private var tick: Job? = null
    private var flashes = 0

    /** The drawer ([full] false) or the app itself came on screen; [active] false when both are gone. */
    fun setActive(active: Boolean, full: Boolean = true) {
        tick?.cancel()
        tick = null
        if (!active) return
        if (follow == null) {
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
        if (_uiState.value.workout != null) return
        viewModelScope.launch { repository.startWorkout(focus, now()) }
    }

    fun finishWorkout() {
        val workout = _uiState.value.workout ?: return
        _uiState.update { it.copy(rest = null) }
        viewModelScope.launch {
            // One with nothing in it was opened by mistake: it leaves no trace.
            if (workout.setCount == 0) repository.discardWorkout(workout.workout.id) else repository.finishWorkout(workout.workout.id, now())
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

    fun skipRest() = _uiState.update { it.copy(rest = null) }

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

    /** The notes pasted (or shared) so far, read as they are typed so the page can show what they would bring in. */
    fun setImportText(text: String) = _uiState.update { state ->
        state.copy(import = state.import.copy(text = text, plan = planFor(text, state.import.part), importedExercises = null))
    }

    /** The shelf the pasted notes are for, when they don't say themselves; null to go by each exercise's name. */
    fun setImportPart(part: BodyPart?) = _uiState.update { state ->
        state.copy(import = state.import.copy(part = part, plan = planFor(state.import.text, part)))
    }

    fun confirmImport() {
        val plan = _uiState.value.import.plan ?: return
        if (plan.isEmpty) return
        viewModelScope.launch {
            repository.saveExercises(plan.exercises.filter { it.isNew }.map { it.exercise })
            repository.addSets(plan.exercises.flatMap { it.sets })
            _uiState.update { it.copy(import = ImportState(importedExercises = plan.newExercises, importedSets = plan.newSets)) }
        }
    }

    fun clearImport() = _uiState.update { it.copy(import = ImportState()) }

    private fun planFor(text: String, part: BodyPart?): ImportPlan? {
        if (text.isBlank()) return null
        return NotesImport.plan(NotesParser.parse(text, part), log.exercises, log.sets, now())
    }

    /** What the lifter weighs now, kept with a set where the body is the load, so the set still reads right after the scale has moved. */
    private fun bodyweightFor(exercise: Exercise): Double? =
        if (exercise.loadKind == LoadKind.BODYWEIGHT) _uiState.value.bodyweight.latest?.pounds else null

    /** A workout left open for hours was walked away from: it is closed at its last set, or dropped if it has none. */
    private fun closeAbandonedWorkouts(log: FitnessLog) {
        val at = now()
        val abandoned = log.workouts.filter { it.finishedAtEpochSeconds == null && at - it.startedAtEpochSeconds >= FitnessBoardBuilder.STALE_WORKOUT_SECONDS }
        if (abandoned.isEmpty()) return
        viewModelScope.launch {
            for (workout in abandoned) {
                val lastSet = log.sets.filter { it.workoutId == workout.id }.maxOfOrNull { it.epochSeconds }
                if (lastSet == null) repository.discardWorkout(workout.id) else repository.finishWorkout(workout.id, lastSet)
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
                import = if (state.import.text.isBlank()) state.import else state.import.copy(plan = planFor(state.import.text, state.import.part)),
            )
        }
    }

    private fun now(): Long = clock.now().epochSeconds

    private companion object {
        const val TICK_MS = 60_000L

        /** How long a finished rest stays on screen, saying it is over, before it is cleared. */
        const val REST_LINGER_MS = 5 * 60_000L
    }
}
