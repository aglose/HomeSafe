package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable

/** A set to be saved, before the store has given it an id. */
@Immutable
data class SetDraft(
    val exerciseId: String,
    val weight: Double,
    val reps: Int,
    val epochSeconds: Long,
    val bodyweight: Double? = null,
    val note: String = "",
    val imported: Boolean = false,
    val workoutId: Long? = null,
)

/** One exercise of an import, as it will land: [isNew] when the app hasn't got it yet, [sets] the ones it hasn't. */
@Immutable
data class ImportedExercise(val exercise: Exercise, val isNew: Boolean, val sets: List<SetDraft>, val known: Int)

/** What importing a note would do, worked out before anything is saved so it can be looked over first. */
@Immutable
data class ImportPlan(val exercises: List<ImportedExercise> = emptyList(), val skipped: List<String> = emptyList()) {
    val newExercises: Int get() = exercises.count { it.isNew }
    val newSets: Int get() = exercises.sumOf { it.sets.size }
    val isEmpty: Boolean get() = exercises.none { it.isNew || it.sets.isNotEmpty() }
}

/**
 * Turns parsed notes into exercises and sets for the app. The notes are the model: an exercise
 * is whatever the notes call it, on the shelf of the heading it sat under, its rep band and its
 * usual jump in weight read off its own ladder ([Progression.inferRepBand],
 * [Progression.inferIncrement]) and only the rest guessed from its name ([ExerciseClassifier]).
 *
 * Importing is safe to repeat: an exercise the app already has is left as the lifter last
 * edited it, and a set it already has (same exercise, weight, reps and date) is not added again.
 */
object NotesImport {
    fun plan(parsed: ParsedNotes, exercises: List<Exercise>, sets: List<LoggedSet>, nowEpochSeconds: Long): ImportPlan {
        val existing = exercises.associateBy { it.id }
        val have = sets.mapTo(HashSet()) { SetKey(it.exerciseId, it.weight, it.reps, it.epochSeconds) }
        val batch = HashSet<SetKey>()
        // By id: the same exercise can turn up twice in the notes (under its shelf's heading, and again in an old log with none).
        val planned = LinkedHashMap<String, ImportedExercise>()
        for (note in parsed.exercises) {
            val guess = ExerciseClassifier.classify(note.name, note.section)
            val id = Exercise.idFor(guess.bodyPart, note.name)
            val known = existing[id]
            val earlier = planned[id]
            val exercise = known ?: earlier?.exercise ?: newExercise(id, note, guess)
            val drafts = ArrayList<SetDraft>(earlier?.sets.orEmpty())
            var already = earlier?.known ?: 0
            for (set in note.sets) {
                // "Recently" is stamped with today, so that it stands as where the lifter is now.
                val at = set.epochSeconds ?: if (set.recent) nowEpochSeconds else 0L
                val saved = if (set.recent) {
                    // Imported again another day it would carry another date: any dated copy of it is the same set.
                    have.any { it.exerciseId == id && it.weight == set.weight && it.reps == set.reps && it.epochSeconds != 0L }
                } else {
                    SetKey(id, set.weight, set.reps, at) in have
                }
                if (saved || !batch.add(SetKey(id, set.weight, set.reps, at))) {
                    already++
                    continue
                }
                drafts += SetDraft(id, set.weight, set.reps, at, set.bodyweight, set.note, imported = true)
            }
            planned[id] = ImportedExercise(exercise, isNew = known == null, sets = drafts, known = already)
        }
        return ImportPlan(planned.values.toList(), parsed.skipped)
    }

    private fun newExercise(id: String, note: ParsedExercise, guess: Classification): Exercise {
        val loadKind = when {
            note.sets.any { it.pair } -> LoadKind.PER_HAND
            note.sets.any { it.load == ParsedLoad.PLATES } -> LoadKind.PLATES
            guess.equipment == Equipment.BODYWEIGHT || note.sets.any { it.bodyweight != null } -> LoadKind.BODYWEIGHT
            note.sets.isNotEmpty() && note.sets.all { it.load == ParsedLoad.NONE } -> LoadKind.BODYWEIGHT
            note.sets.isNotEmpty() && note.sets.all { it.load == ParsedLoad.LEVEL || it.load == ParsedLoad.NONE } -> LoadKind.LEVEL
            else -> guess.loadKind
        }
        val ladder = note.sets.groupBy { it.weight }.mapValues { (_, at) -> at.maxOf { it.reps } }
        val band = Progression.inferRepBand(ladder.values.toList(), guess.repBand)
        val fallbackStep = when (loadKind) {
            LoadKind.LEVEL -> 1.0
            LoadKind.PLATES -> 50.0
            else -> guess.increment
        }
        return Exercise(
            id = id,
            name = note.name,
            bodyPart = guess.bodyPart,
            // Written as a pair, it is dumbbells unless its name says it is a bar loaded a side.
            equipment = if (loadKind == LoadKind.PER_HAND && !note.name.contains("bar", ignoreCase = true) && !note.name.contains("ez", ignoreCase = true)) Equipment.DUMBBELL else guess.equipment,
            loadKind = loadKind,
            primary = guess.primary,
            secondary = guess.secondary,
            repLow = band.first,
            repHigh = band.last,
            increment = Progression.inferIncrement(ladder.keys.filter { it > 0.0 } + note.openWeights, fallbackStep),
            restSeconds = guess.restSeconds,
        )
    }

    private data class SetKey(val exerciseId: String, val weight: Double, val reps: Int, val epochSeconds: Long)
}
