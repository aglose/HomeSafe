package com.meticulouscreations.homesafe.fitness

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.fitness.domain.BodyweightEntry
import com.meticulouscreations.homesafe.fitness.domain.BodyweightTrend
import com.meticulouscreations.homesafe.fitness.domain.Exercise
import com.meticulouscreations.homesafe.fitness.domain.LoggedSet
import com.meticulouscreations.homesafe.fitness.domain.MuscleVolume
import com.meticulouscreations.homesafe.fitness.domain.Pace
import com.meticulouscreations.homesafe.fitness.domain.PaceVerdict
import com.meticulouscreations.homesafe.fitness.domain.Phase
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.Progression
import com.meticulouscreations.homesafe.fitness.domain.Record
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.Rung
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.Strength
import com.meticulouscreations.homesafe.fitness.domain.Target
import com.meticulouscreations.homesafe.fitness.domain.Volume
import com.meticulouscreations.homesafe.fitness.domain.Workout
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus

/** One day an exercise was trained: its peak set, and that set's place on the exercise's scale. */
@Immutable
data class SessionPoint(val epochSeconds: Long, val top: LoggedSet, val score: Double)

/**
 * Everything the screens show about one exercise: its [ladder], the best there has ever been
 * ([best]) and the best this phase ([phaseBest]), last session's peak set ([last]), what to aim
 * for next ([target]), and its [history] a session at a time. [standing] is last time against
 * the all-time best, 1 being level.
 */
@Immutable
data class ExerciseBoard(
    val exercise: Exercise,
    val ladder: List<Rung> = emptyList(),
    val best: LoggedSet? = null,
    val phaseBest: LoggedSet? = null,
    val last: LoggedSet? = null,
    val target: Target? = null,
    val history: List<SessionPoint> = emptyList(),
    val standing: Float = 1f,
    /** How many sets of it there are, the notes' included. */
    val setCount: Int = 0,
) {
    val lastTrainedEpochSeconds: Long? get() = last?.epochSeconds
}

/** A workout to start: when it was last done, and whether it's the one that has waited longest. */
@Immutable
data class DayCard(
    val focus: WorkoutFocus,
    val lastEpochSeconds: Long? = null,
    val daysAgo: Int? = null,
    val exercises: Int = 0,
    val due: Boolean = false,
    /** The first lifts it would open on, by how recently they were trained. */
    val lifts: List<ExerciseBoard> = emptyList(),
)

/** An exercise in the workout that is open: today's sets of it, and which of them were records. */
@Immutable
data class WorkoutEntry(val exerciseId: String, val sets: List<LoggedSet>, val records: Map<Long, Record> = emptyMap())

@Immutable
data class ActiveWorkout(val workout: Workout, val entries: List<WorkoutEntry> = emptyList()) {
    val setCount: Int get() = entries.sumOf { it.sets.size }
    val recordCount: Int get() = entries.sumOf { it.records.size }

    fun entry(exerciseId: String): WorkoutEntry? = entries.firstOrNull { it.exerciseId == exerciseId }
}

/** A record, with what it was a record on. */
@Immutable
data class RecordEvent(val exercise: Exercise, val set: LoggedSet, val record: Record)

/** The last seven days. */
@Immutable
data class WeekSummary(val sessions: Int = 0, val sets: Int = 0, val records: Int = 0, val muscles: List<MuscleVolume> = emptyList())

/**
 * The phase in force. [startedAtEpochSeconds] is 0 when none was ever set (maintenance, since
 * always). [standing] is how the lifts trained in it stand against their all-time bests on
 * average, [pace] how the bodyweight trend reads for the phase.
 */
@Immutable
data class PhaseStatus(
    val kind: PhaseKind = PhaseKind.MAINTAIN,
    val startedAtEpochSeconds: Long = 0,
    val days: Int = 0,
    val standing: Float? = null,
    val pace: PaceVerdict? = null,
)

/** A day something was trained, for the calendar. */
@Immutable
data class TrainedDay(val epochDay: Long, val focus: WorkoutFocus?, val sets: Int)

/** Everything worked out from the log at one moment. */
@Immutable
data class FitnessBoards(
    val phase: PhaseStatus = PhaseStatus(),
    val boards: List<ExerciseBoard> = emptyList(),
    val days: List<DayCard> = emptyList(),
    val workout: ActiveWorkout? = null,
    val week: WeekSummary = WeekSummary(),
    val recentRecords: List<RecordEvent> = emptyList(),
    val bodyweight: BodyweightTrend = BodyweightTrend(),
    val calendar: List<TrainedDay> = emptyList(),
)

/** The log as the repository hands it over. */
data class FitnessLog(
    val exercises: List<Exercise> = emptyList(),
    val sets: List<LoggedSet> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val phases: List<Phase> = emptyList(),
    val bodyweights: List<BodyweightEntry> = emptyList(),
)

/** Works the screens' [FitnessBoards] out of a [FitnessLog]. Pure, so it can be tested without a view model. */
object FitnessBoardBuilder {
    /** A workout left open this long was walked away from: it no longer counts as the one in progress. */
    const val STALE_WORKOUT_SECONDS = 8 * 3600L
    private const val RECENT_RECORD_DAYS = 30L
    private const val WEEK_SECONDS = 7 * SECONDS_PER_DAY

    fun build(log: FitnessLog, nowEpochSeconds: Long, utcOffsetSeconds: Int): FitnessBoards {
        val phases = log.phases.sortedBy { it.startedAtEpochSeconds }
        val current = phases.lastOrNull { it.startedAtEpochSeconds <= nowEpochSeconds }
        val phaseKind = current?.kind ?: PhaseKind.MAINTAIN
        val phaseStart = current?.startedAtEpochSeconds ?: 0L
        val bodyweight = BodyweightTrend.of(log.bodyweights)
        val pounds = bodyweight.latest?.trend
        val setsByExercise = log.sets.groupBy { it.exerciseId }

        fun epochDay(epochSeconds: Long) = (epochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)

        val open = log.workouts.lastOrNull { it.finishedAtEpochSeconds == null && nowEpochSeconds - it.startedAtEpochSeconds < STALE_WORKOUT_SECONDS }
        val records = ArrayList<RecordEvent>()
        val recordById = HashMap<Long, Record>()
        val boards = log.exercises.filter { !it.archived }.map { exercise ->
            val sets = setsByExercise[exercise.id].orEmpty().sortedWith(compareBy({ it.epochSeconds }, { it.id }))
            val done = sets.filter { it.reps > 0 }
            val dated = done.filter { !it.isBenchmark }
            for ((index, set) in done.withIndex()) {
                // Only what was logged here can be a record: the notes' numbers are what there was to beat.
                if (set.isBenchmark || set.imported) continue
                val start = phases.lastOrNull { it.startedAtEpochSeconds <= set.epochSeconds }?.startedAtEpochSeconds ?: 0L
                val record = Strength.record(exercise, set, done.subList(0, index), start, pounds) ?: continue
                recordById[set.id] = record
                records += RecordEvent(exercise, set, record)
            }
            val best = Strength.best(exercise, done, pounds)
            // While a workout is open, "last time" and the target stay what they were when it began: the sets
            // being logged are the attempt at the target, and must not move it.
            val before = if (open == null) done else done.filter { it.workoutId != open.id }
            val last = Progression.lastSessionBest(exercise, Progression.current(before, nowEpochSeconds), pounds)
            val history = dated.groupBy { epochDay(it.epochSeconds) }.toList().sortedBy { it.first }.mapNotNull { (_, day) ->
                Strength.best(exercise, day, pounds)?.let { SessionPoint(it.epochSeconds, it, Strength.score(exercise, it, pounds)) }
            }
            val bestScore = best?.let { Strength.score(exercise, it, pounds) } ?: 0.0
            ExerciseBoard(
                exercise = exercise,
                ladder = Strength.ladder(done, phaseStart),
                best = best,
                phaseBest = Strength.best(exercise, dated.filter { it.epochSeconds >= phaseStart }, pounds),
                last = last,
                target = Progression.nextTarget(exercise, before, phaseKind, nowEpochSeconds, pounds),
                history = history,
                standing = if (last != null && bestScore > 0.0) (Strength.score(exercise, last, pounds) / bestScore).toFloat().coerceIn(0f, 1f) else 1f,
                setCount = done.size,
            )
        }.sortedWith(compareBy({ it.exercise.bodyPart.ordinal }, { it.exercise.name.lowercase() }))

        val workout = open?.let { session ->
            val mine = log.sets.filter { it.workoutId == session.id }.sortedWith(compareBy({ it.epochSeconds }, { it.id }))
            val entries = mine.groupBy { it.exerciseId }.map { (id, sets) ->
                WorkoutEntry(id, sets, sets.mapNotNull { set -> recordById[set.id]?.let { set.id to it } }.toMap())
            }
            ActiveWorkout(session, entries)
        }

        val weekStart = nowEpochSeconds - WEEK_SECONDS
        val weekSets = log.sets.filter { !it.isBenchmark && !it.imported && it.epochSeconds in weekStart..nowEpochSeconds && it.reps > 0 }
        val week = WeekSummary(
            sessions = weekSets.map { epochDay(it.epochSeconds) }.distinct().size,
            sets = weekSets.size,
            records = records.count { it.set.epochSeconds >= weekStart && it.record.scope == RecordScope.ALL_TIME },
            muscles = Volume.weekly(log.exercises.associateBy { it.id }, log.sets, weekStart, nowEpochSeconds),
        )

        val trainedInPhase = boards.filter { board -> board.last?.let { it.epochSeconds >= phaseStart } == true && board.best != null }
        val phase = PhaseStatus(
            kind = phaseKind,
            startedAtEpochSeconds = phaseStart,
            days = if (phaseStart > 0) ((nowEpochSeconds - phaseStart) / SECONDS_PER_DAY).toInt() else 0,
            standing = trainedInPhase.takeIf { it.isNotEmpty() }?.map { it.standing }?.average()?.toFloat(),
            pace = bodyweight.weeklyChange?.let { Pace.verdict(phaseKind, it) },
        )

        val focusOfDay = log.workouts.associate { epochDay(it.startedAtEpochSeconds) to it.focus }
        val calendar = log.sets.filter { !it.isBenchmark && !it.imported && it.reps > 0 }
            .groupBy { epochDay(it.epochSeconds) }
            .map { (day, sets) -> TrainedDay(day, focusOfDay[day], sets.size) }
            .sortedBy { it.epochDay }

        return FitnessBoards(
            phase = phase,
            boards = boards,
            days = dayCards(log, boards, nowEpochSeconds, workout != null),
            workout = workout,
            week = week,
            recentRecords = records.filter { nowEpochSeconds - it.set.epochSeconds <= RECENT_RECORD_DAYS * SECONDS_PER_DAY }
                .sortedWith(compareByDescending<RecordEvent> { it.set.epochSeconds }.thenByDescending { it.set.id })
                .take(12),
            bodyweight = bodyweight,
            calendar = calendar,
        )
    }

    /**
     * A card for each kind of workout. The one that is [DayCard.due] is whichever of the three
     * main days has waited longest (one never done has waited longest of all): the split is a
     * rotation, so what comes next is what was done least recently.
     */
    private fun dayCards(log: FitnessLog, boards: List<ExerciseBoard>, nowEpochSeconds: Long, working: Boolean): List<DayCard> {
        val lastByFocus = log.workouts.groupBy { it.focus }.mapValues { (_, sessions) -> sessions.maxOf { it.startedAtEpochSeconds } }
        val due = if (working || boards.isEmpty()) null else WorkoutFocus.MAIN.minByOrNull { lastByFocus[it] ?: Long.MIN_VALUE }
        return WorkoutFocus.entries.map { focus ->
            val mine = boards.filter { it.exercise.bodyPart in focus.parts }
            val last = lastByFocus[focus]
            DayCard(
                focus = focus,
                lastEpochSeconds = last,
                daysAgo = last?.let { ((nowEpochSeconds - it) / SECONDS_PER_DAY).toInt().coerceAtLeast(0) },
                exercises = mine.size,
                due = focus == due,
                lifts = workoutOrder(mine).take(3),
            )
        }
    }

    /**
     * The order a workout lists a shelf's exercises in: the ones from the most recent session
     * first, in the order they were done in it (that is the current routine, and its order was
     * chosen), then the ones with the most history, then by name.
     */
    fun workoutOrder(boards: List<ExerciseBoard>): List<ExerciseBoard> =
        boards.sortedWith(
            compareByDescending<ExerciseBoard> { (it.lastTrainedEpochSeconds ?: 0L) / SECONDS_PER_DAY }
                .thenBy { it.lastTrainedEpochSeconds ?: 0L }
                .thenByDescending { it.setCount }
                .thenBy { it.exercise.name.lowercase() },
        )
}
