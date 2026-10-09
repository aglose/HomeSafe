package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole training log as it stood at one moment, to be carried to another install of the app:
 * another phone, or the release build beside the debug one. Everything the log keeps is in it:
 * the exercises as they were last edited, every set, the workouts they were done in, the phases,
 * the weigh-ins, the heart-rate settings and what each workout's heart added up to
 * ([heartSummaries], by the workout's id).
 */
@Immutable
data class LogCopy(
    val exercises: List<Exercise> = emptyList(),
    val sets: List<LoggedSet> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val phases: List<Phase> = emptyList(),
    val bodyweights: List<BodyweightEntry> = emptyList(),
    val heart: HeartSettings = HeartSettings(),
    val heartSummaries: Map<Long, HeartSummary> = emptyMap(),
)

/**
 * What bringing a [LogCopy] into a log writes there, worked out before anything is saved so it
 * can be looked over first. Every row already has the id it will be saved under. [exercises] are
 * the ones the log hasn't got ([newExercises] of them) and the ones it has differently; [heart]
 * is null when the settings stay as they are.
 */
@Immutable
data class LogMerge(
    val exercises: List<Exercise> = emptyList(),
    val newExercises: Int = 0,
    val sets: List<LoggedSet> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val phases: List<Phase> = emptyList(),
    val bodyweights: List<BodyweightEntry> = emptyList(),
    val heart: HeartSettings? = null,
    val heartSummaries: Map<Long, HeartSummary> = emptyMap(),
) {
    /** Exercises the log already has, which the copy has set up differently. */
    val changedExercises: Int get() = exercises.size - newExercises

    val isEmpty: Boolean
        get() = exercises.isEmpty() && sets.isEmpty() && workouts.isEmpty() && phases.isEmpty() && bodyweights.isEmpty() && heart == null && heartSummaries.isEmpty()
}

/**
 * Works out what a [LogCopy] adds to a log. The rule is the notes import's, carried through the
 * rest of the log: only what isn't there yet is added, so the same copy brought in twice adds
 * nothing twice.
 *
 * - A set is the same set when its exercise, weight, reps and time are.
 * - A workout is the same one when it has the same focus and began at the same second, a phase
 *   when it is the same kind begun at the same second, a weigh-in when it is the same day's.
 *   Ids are the receiving log's own: the copy's workouts are given new ones, and its sets and
 *   heart summaries follow them there.
 * - Heart-rate settings fill in what the log hasn't got, and leave what it has.
 *
 * Exercises are the exception, because the copy is the log as its owner last arranged it: one
 * the log already has is set up the way the copy has it (its shelf, its rep band, its note).
 */
object LogCopies {
    fun merge(copy: LogCopy, into: LogCopy): LogMerge {
        val here = into.exercises.associateBy { it.id }
        val exercises = copy.exercises.distinctBy { it.id }.filter { here[it.id] != it }
        val known = here.keys + copy.exercises.map { it.id }

        var nextWorkout = (into.workouts.maxOfOrNull { it.id } ?: 0L) + 1
        val sameWorkout = into.workouts.associate { WorkoutKey(it.focus, it.startedAtEpochSeconds) to it.id }
        // The copy's id for a workout, to the id it has (or is given) here.
        val workoutIds = HashMap<Long, Long>()
        val workouts = ArrayList<Workout>()
        for (workout in copy.workouts.sortedWith(compareBy({ it.startedAtEpochSeconds }, { it.id }))) {
            if (workout.id in workoutIds) continue
            val same = sameWorkout[WorkoutKey(workout.focus, workout.startedAtEpochSeconds)]
            if (same != null) {
                workoutIds[workout.id] = same
            } else {
                workoutIds[workout.id] = nextWorkout
                workouts += workout.copy(id = nextWorkout++)
            }
        }

        var nextSet = (into.sets.maxOfOrNull { it.id } ?: 0L) + 1
        val have = into.sets.mapTo(HashSet()) { SetKey(it.exerciseId, it.weight, it.reps, it.epochSeconds) }
        // A set of an exercise neither side knows has nothing to show under, and is left behind.
        val sets = copy.sets
            .filter { it.exerciseId in known && have.add(SetKey(it.exerciseId, it.weight, it.reps, it.epochSeconds)) }
            .map { it.copy(id = nextSet++, workoutId = it.workoutId?.let(workoutIds::get)) }

        var nextPhase = (into.phases.maxOfOrNull { it.id } ?: 0L) + 1
        val phasesHere = into.phases.mapTo(HashSet()) { it.kind to it.startedAtEpochSeconds }
        val phases = copy.phases.sortedWith(compareBy({ it.startedAtEpochSeconds }, { it.id }))
            .filter { phasesHere.add(it.kind to it.startedAtEpochSeconds) }
            .map { it.copy(id = nextPhase++) }

        val weighedHere = into.bodyweights.mapTo(HashSet()) { it.epochDay }
        val bodyweights = copy.bodyweights.filter { weighedHere.add(it.epochDay) }

        val mine = into.heart
        val theirs = copy.heart
        val heart = HeartSettings(
            HeartProfile(mine.profile.maxBpm ?: theirs.profile.maxBpm, mine.profile.age ?: theirs.profile.age, mine.profile.restingBpm ?: theirs.profile.restingBpm),
            mine.sensor ?: theirs.sensor,
        )
        val heartSummaries = HashMap<Long, HeartSummary>()
        for ((workoutId, summary) in copy.heartSummaries) {
            val id = workoutIds[workoutId] ?: continue
            if (!summary.isEmpty && id !in into.heartSummaries) heartSummaries[id] = summary
        }

        return LogMerge(
            exercises = exercises,
            newExercises = exercises.count { it.id !in here },
            sets = sets,
            workouts = workouts,
            phases = phases,
            bodyweights = bodyweights,
            heart = heart.takeIf { it != mine },
            heartSummaries = heartSummaries,
        )
    }

    private data class SetKey(val exerciseId: String, val weight: Double, val reps: Int, val epochSeconds: Long)

    private data class WorkoutKey(val focus: WorkoutFocus, val startedAtEpochSeconds: Long)
}

/** What a piece of text handed to the import page turned out to be. */
sealed interface LogCopyRead {
    data class Copy(val copy: LogCopy) : LogCopyRead

    /** It says it is a copy of a log, but can't be read as one: cut short, or from a version of the app newer than this. */
    data object Unreadable : LogCopyRead

    /** Something else: notes, most likely. */
    data object NotACopy : LogCopyRead
}

/**
 * A [LogCopy] as text, which is how it travels: through the share sheet into the other install's
 * import page (the door notes come in by), or pasted there. JSON, opening with [MARKER] and the
 * format's version, so the page can tell a copy from notes at a glance.
 *
 * It has to fit in what one app can hand another (see `FitnessShares.MAX_LENGTH`), so a set is
 * written inside its exercise under one-letter names, with whatever it hasn't got left out: about
 * thirty characters a set, which is room for several thousand.
 */
object LogCopyText {
    private const val MARKER = "percysafeTrainingLog"
    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(copy: LogCopy): String {
        val sets = copy.sets.groupBy { it.exerciseId }
        return json.encodeToString(
            CopyDto.serializer(),
            CopyDto(
                version = VERSION,
                exercises = copy.exercises.map { exercise ->
                    ExerciseDto(
                        id = exercise.id,
                        name = exercise.name,
                        part = exercise.bodyPart.name,
                        equipment = exercise.equipment.name,
                        load = exercise.loadKind.name,
                        primary = exercise.primary.name,
                        secondary = exercise.secondary.map { it.name },
                        repLow = exercise.repLow,
                        repHigh = exercise.repHigh,
                        step = exercise.increment,
                        rest = exercise.restSeconds,
                        note = exercise.note,
                        archived = exercise.archived,
                        sets = sets[exercise.id].orEmpty().map { SetDto(it.weight, it.reps, it.epochSeconds, it.workoutId, it.bodyweight, it.note, it.imported) },
                    )
                },
                workouts = copy.workouts.map { WorkoutDto(it.id, it.focus.name, it.startedAtEpochSeconds, it.finishedAtEpochSeconds) },
                phases = copy.phases.map { PhaseDto(it.kind.name, it.startedAtEpochSeconds) },
                weighIns = copy.bodyweights.map { WeighInDto(it.epochDay, it.pounds) },
                heart = HeartDto(
                    max = copy.heart.profile.maxBpm,
                    age = copy.heart.profile.age,
                    resting = copy.heart.profile.restingBpm,
                    sensorAddress = copy.heart.sensor?.address,
                    sensorName = copy.heart.sensor?.name,
                    workouts = copy.heartSummaries.map { (id, summary) -> HeartSummaryDto(id, summary.belowMillis, summary.zoneMillis, summary.bpmMillis, summary.peakBpm) },
                ),
            ),
        )
    }

    /**
     * Reads [text] as a copy. Whatever a notes app or a share sheet put round it (a title on the
     * line above, say) is stepped over. What comes back can be saved as it is: anything the log
     * couldn't hold (a set with no reps, a name this build doesn't know) is dropped or defaulted
     * here, as the database does when it reads a row.
     */
    fun read(text: String): LogCopyRead {
        val marker = text.indexOf("\"$MARKER\"")
        if (marker < 0) return LogCopyRead.NotACopy
        val start = text.lastIndexOf('{', marker)
        val end = text.lastIndexOf('}')
        if (start < 0 || end < marker) return LogCopyRead.Unreadable
        val dto = runCatching { json.decodeFromString(CopyDto.serializer(), text.substring(start, end + 1)) }.getOrNull()
        if (dto == null || dto.version != VERSION) return LogCopyRead.Unreadable

        val exercises = ArrayList<Exercise>()
        val sets = ArrayList<LoggedSet>()
        for (row in dto.exercises.distinctBy { it.id }) {
            if (row.id.isBlank() || row.name.isBlank()) continue
            val repLow = row.repLow.coerceAtLeast(1)
            exercises += Exercise(
                id = row.id,
                name = row.name,
                bodyPart = enumOr(row.part, BodyPart.CORE),
                equipment = enumOr(row.equipment, Equipment.OTHER),
                loadKind = enumOr(row.load, LoadKind.WEIGHT),
                primary = enumOr(row.primary, Muscle.ABS),
                secondary = row.secondary.mapNotNull { name -> Muscle.entries.firstOrNull { it.name == name } },
                repLow = repLow,
                repHigh = row.repHigh.coerceAtLeast(repLow),
                increment = row.step.takeIf { it > 0.0 } ?: 5.0,
                restSeconds = row.rest.coerceAtLeast(0),
                note = row.note,
                archived = row.archived,
            )
            for (set in row.sets) {
                if (set.reps <= 0 || set.weight < 0.0) continue
                // The copy's own ids don't travel: the log it is brought into gives each set one of its own.
                sets += LoggedSet(sets.size + 1L, row.id, set.weight, set.reps, set.at.coerceAtLeast(0L), set.workout, set.bodyweight, set.note, set.imported)
            }
        }
        val heart = dto.heart ?: HeartDto()
        return LogCopyRead.Copy(
            LogCopy(
                exercises = exercises,
                sets = sets,
                workouts = dto.workouts.distinctBy { it.id }.map { Workout(it.id, enumOr(it.focus, WorkoutFocus.CHEST), it.start, it.end) },
                phases = dto.phases.mapIndexed { index, it -> Phase(index + 1L, enumOr(it.kind, PhaseKind.MAINTAIN), it.start) },
                bodyweights = dto.weighIns.filter { it.pounds > 0.0 }.distinctBy { it.day }.map { BodyweightEntry(it.day, it.pounds) },
                heart = HeartSettings(
                    HeartProfile(heart.max, heart.age, heart.resting),
                    heart.sensorAddress?.takeIf { it.isNotBlank() }?.let { HeartSensor(it, heart.sensorName.orEmpty()) },
                ),
                heartSummaries = heart.workouts.associate { row ->
                    row.workout to HeartSummary(row.below, List(HeartZone.entries.size) { row.zones.getOrElse(it) { 0L } }, row.bpmMillis, row.peak)
                },
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

    @Serializable
    private class CopyDto(
        @SerialName(MARKER) val version: Int,
        val exercises: List<ExerciseDto> = emptyList(),
        val workouts: List<WorkoutDto> = emptyList(),
        val phases: List<PhaseDto> = emptyList(),
        val weighIns: List<WeighInDto> = emptyList(),
        val heart: HeartDto? = null,
    )

    /** Enums travel by name, so a value this build doesn't know degrades to a default instead of failing to read. */
    @Serializable
    private class ExerciseDto(
        val id: String,
        val name: String,
        val part: String = "",
        val equipment: String = "",
        val load: String = "",
        val primary: String = "",
        val secondary: List<String> = emptyList(),
        val repLow: Int = 8,
        val repHigh: Int = 12,
        val step: Double = 5.0,
        val rest: Int = 120,
        val note: String = "",
        val archived: Boolean = false,
        val sets: List<SetDto> = emptyList(),
    )

    /** A set: weight, reps, when ([at], 0 for a benchmark), the workout's id, bodyweight, note, and whether it came from the notes. */
    @Serializable
    private class SetDto(
        @SerialName("w") val weight: Double,
        @SerialName("r") val reps: Int,
        @SerialName("t") val at: Long = 0,
        @SerialName("k") val workout: Long? = null,
        @SerialName("b") val bodyweight: Double? = null,
        @SerialName("n") val note: String = "",
        @SerialName("i") val imported: Boolean = false,
    )

    @Serializable
    private class WorkoutDto(val id: Long, val focus: String, val start: Long, val end: Long? = null)

    @Serializable
    private class PhaseDto(val kind: String, val start: Long)

    @Serializable
    private class WeighInDto(val day: Long, val pounds: Double)

    @Serializable
    private class HeartDto(
        val max: Int? = null,
        val age: Int? = null,
        val resting: Int? = null,
        val sensorAddress: String? = null,
        val sensorName: String? = null,
        val workouts: List<HeartSummaryDto> = emptyList(),
    )

    @Serializable
    private class HeartSummaryDto(val workout: Long, val below: Long = 0, val zones: List<Long> = emptyList(), val bpmMillis: Long = 0, val peak: Int = 0)
}
