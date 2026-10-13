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

/** An exercise as it was done in one session: its sets in the order they were logged, the best of them, and which were records. */
@Immutable
data class SessionLift(val exercise: Exercise, val sets: List<LoggedSet>, val top: LoggedSet, val records: Map<Long, Record> = emptyMap())

/**
 * One entry in the history: a [workout] with what was logged in it, or (with none) a day's sets
 * that were logged with no workout open. [epochDay] is the local day it is filed under on the
 * calendar: the day a workout began, whenever it ended, by the offset from UTC at that moment
 * ([utcOffsetSeconds], which is also what its time of day is read in: an evening in July stays an
 * evening when it is looked back on in December). [durationSeconds] is known only for a
 * workout that was finished. [pounds] is everything moved in it, rep by rep, leaving out what
 * isn't counted in pounds ([Strength.poundsMoved]).
 */
@Immutable
data class WorkoutSession(
    val workout: Workout?,
    val epochDay: Long,
    val startedAtEpochSeconds: Long,
    val utcOffsetSeconds: Int = 0,
    val durationSeconds: Long? = null,
    val lifts: List<SessionLift> = emptyList(),
    val pounds: Double = 0.0,
    /** The workout that is open now: it is carried on with, not looked back on. */
    val inProgress: Boolean = false,
) {
    val focus: WorkoutFocus? get() = workout?.focus
    val setCount: Int get() = lifts.sumOf { it.sets.size }
    val repCount: Int get() = lifts.sumOf { lift -> lift.sets.sumOf { it.reps } }
    val recordCount: Int get() = lifts.sumOf { it.records.size }

    /** Whether this is the entry for [workoutId], or with none for the sets logged outside a workout on [epochDay]. */
    fun matches(workoutId: Long?, epochDay: Long): Boolean = if (workoutId != null) workout?.id == workoutId else workout == null && this.epochDay == epochDay
}

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
    /** Every session there has been, the latest first. */
    val history: List<WorkoutSession> = emptyList(),
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
    const val STALE_WORKOUT_SECONDS = Workout.STALE_AFTER_SECONDS
    private const val RECENT_RECORD_DAYS = 30L
    private const val WEEK_SECONDS = 7 * SECONDS_PER_DAY

    /**
     * [utcOffsetSeconds] is the offset now, which is what "today" and the last few weeks are read
     * in. The history reaches back across summer time and winter time, so each of its sessions is
     * filed by the offset at its own moment ([offsetAt]); left out, that is the one offset throughout.
     */
    fun build(log: FitnessLog, nowEpochSeconds: Long, utcOffsetSeconds: Int, offsetAt: (Long) -> Int = { utcOffsetSeconds }): FitnessBoards {
        val phases = log.phases.sortedBy { it.startedAtEpochSeconds }
        val current = phases.lastOrNull { it.startedAtEpochSeconds <= nowEpochSeconds }
        val phaseKind = current?.kind ?: PhaseKind.MAINTAIN
        val phaseStart = current?.startedAtEpochSeconds ?: 0L
        val bodyweight = BodyweightTrend.of(log.bodyweights)
        val pounds = bodyweight.latest?.trend
        val setsByExercise = log.sets.groupBy { it.exerciseId }

        fun epochDay(epochSeconds: Long) = (epochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)

        val open = log.workouts.lastOrNull { it.isInProgress(nowEpochSeconds) }
        val records = ArrayList<RecordEvent>()
        val recordById = HashMap<Long, Record>()

        fun doneOf(exercise: Exercise) = setsByExercise[exercise.id].orEmpty().sortedWith(compareBy({ it.epochSeconds }, { it.id })).filter { it.reps > 0 }

        /** Judges each of [done] against what came before it. [listed] among the records to show; either way the set is known as one. */
        fun mark(exercise: Exercise, done: List<LoggedSet>, listed: Boolean) {
            for ((index, set) in done.withIndex()) {
                // Only what was logged here can be a record: the notes' numbers are what there was to beat.
                if (set.isBenchmark || set.imported) continue
                val start = phases.lastOrNull { it.startedAtEpochSeconds <= set.epochSeconds }?.startedAtEpochSeconds ?: 0L
                val record = Strength.record(exercise, set, done.subList(0, index), start, pounds) ?: continue
                recordById[set.id] = record
                if (listed) records += RecordEvent(exercise, set, record)
            }
        }

        val boards = log.exercises.filter { !it.archived }.map { exercise ->
            val done = doneOf(exercise)
            val dated = done.filter { !it.isBenchmark }
            mark(exercise, done, listed = true)
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

        // An exercise since put away has no board, but the history still shows what was done on it, records and all.
        log.exercises.filter { it.archived }.forEach { mark(it, doneOf(it), listed = false) }

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
            history = history(log, recordById, nowEpochSeconds, offsetAt, pounds),
        )
    }

    /**
     * The log as sessions, the latest first: each workout with its sets, and for the sets that
     * belong to none (logged from an exercise's page with no workout open) one entry a day. The
     * notes' own numbers are no session: they are what there was before the log. Each is filed
     * under the local day it began, by the offset at that moment ([offsetAt]).
     */
    private fun history(log: FitnessLog, records: Map<Long, Record>, nowEpochSeconds: Long, offsetAt: (Long) -> Int, bodyweight: Double?): List<WorkoutSession> {
        val exercises = log.exercises.associateBy { it.id }
        val workoutIds = log.workouts.mapTo(HashSet()) { it.id }
        val done = log.sets.filter { it.reps > 0 }.sortedWith(compareBy({ it.epochSeconds }, { it.id }))
        val (inWorkouts, apart) = done.partition { it.workoutId != null && it.workoutId in workoutIds }
        val byWorkout = inWorkouts.groupBy { it.workoutId }

        fun epochDay(epochSeconds: Long) = (epochSeconds + offsetAt(epochSeconds)).floorDiv(SECONDS_PER_DAY)

        fun lifts(sets: List<LoggedSet>): List<SessionLift> = sets.groupBy { it.exerciseId }.mapNotNull { (id, mine) ->
            val exercise = exercises[id] ?: return@mapNotNull null
            val top = Strength.best(exercise, mine, bodyweight) ?: return@mapNotNull null
            SessionLift(exercise, mine, top, mine.mapNotNull { set -> records[set.id]?.let { set.id to it } }.toMap())
        }

        fun pounds(lifts: List<SessionLift>): Double = lifts.sumOf { lift -> lift.sets.sumOf { Strength.poundsMoved(lift.exercise, it, bodyweight) ?: 0.0 } }

        val workouts = log.workouts.map { workout ->
            val mine = lifts(byWorkout[workout.id].orEmpty())
            WorkoutSession(
                workout = workout,
                epochDay = epochDay(workout.startedAtEpochSeconds),
                startedAtEpochSeconds = workout.startedAtEpochSeconds,
                utcOffsetSeconds = offsetAt(workout.startedAtEpochSeconds),
                durationSeconds = workout.finishedAtEpochSeconds?.let { (it - workout.startedAtEpochSeconds).coerceAtLeast(0) },
                lifts = mine,
                pounds = pounds(mine),
                inProgress = workout.isInProgress(nowEpochSeconds),
            )
        }
        val loose = apart.filter { !it.isBenchmark && !it.imported }.groupBy { epochDay(it.epochSeconds) }.mapNotNull { (day, sets) ->
            val mine = lifts(sets)
            val start = sets.first().epochSeconds
            if (mine.isEmpty()) null else WorkoutSession(workout = null, epochDay = day, startedAtEpochSeconds = start, utcOffsetSeconds = offsetAt(start), lifts = mine, pounds = pounds(mine))
        }
        return (workouts + loose).sortedWith(compareByDescending<WorkoutSession> { it.startedAtEpochSeconds }.thenByDescending { it.workout?.id ?: 0L })
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
