package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.current
import com.meticulouscreations.homesafe.assistant.inputOf
import com.meticulouscreations.homesafe.assistant.intInput
import com.meticulouscreations.homesafe.assistant.integerProperty
import com.meticulouscreations.homesafe.assistant.listing
import com.meticulouscreations.homesafe.fitness.domain.FitnessRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_fitness
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * The fitness app's training log for the assistant: the workouts of the last few weeks with every
 * set in them, body weight, the training phase and the exercises known. Read from the log the app
 * keeps on the phone; the health store (steps, sleep, runs) isn't asked, since that can mean a
 * permission prompt behind the assistant.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class FitnessAssistantTool(private val fitness: FitnessRepository, private val clock: Clock) : AssistantTool {
    override val name = "training_log"
    override val description = "The Fitness app's strength-training log: workouts (focus, start and finish in Unix seconds) with " +
        "each set's exercise, weight in pounds and reps, body weight by day (pounds; epoch days), the current training phase, and " +
        "the exercises with their muscles and rep ranges."
    override val inputSchema = inputOf("days" to integerProperty("How many days back to look. Default 28, at most 365."))
    override val activity = Res.string.assistant_tool_fitness

    override suspend fun run(input: JsonObject): String {
        val days = (input.intInput("days") ?: 28).coerceIn(1, 365)
        val since = clock.now().epochSeconds - days * DAY_SECONDS
        val exercises = fitness.exercises.current().orEmpty()
        val names = exercises.associate { it.id to it.name }
        val sets = fitness.sets.current().orEmpty().filter { it.epochSeconds >= since }
        val setsByWorkout = sets.groupBy { it.workoutId }
        val workouts = fitness.workouts.current().orEmpty().filter { it.startedAtEpochSeconds >= since }.sortedByDescending { it.startedAtEpochSeconds }
        val phase = fitness.phases.current().orEmpty().maxByOrNull { it.startedAtEpochSeconds }
        val weights = fitness.bodyweights.current().orEmpty().sortedByDescending { it.epochDay }

        return buildString {
            appendLine("Now: ${clock.now().epochSeconds}. Phase: ${phase?.kind ?: "none"} since ${phase?.startedAtEpochSeconds}")
            appendLine("Workouts in the last $days days, newest first:")
            appendLine(
                listing(workouts, limit = 60) { workout ->
                    val logged = setsByWorkout[workout.id].orEmpty().joinToString("; ") { "${names[it.exerciseId] ?: it.exerciseId} ${it.weight}x${it.reps}" }
                    "${workout.focus} from ${workout.startedAtEpochSeconds} to ${workout.finishedAtEpochSeconds}: $logged"
                },
            )
            val loose = setsByWorkout[null].orEmpty()
            if (loose.isNotEmpty()) {
                appendLine("Sets logged outside a workout:")
                appendLine(listing(loose.sortedByDescending { it.epochSeconds }, limit = 100) { "${names[it.exerciseId] ?: it.exerciseId} ${it.weight}x${it.reps} at ${it.epochSeconds}" })
            }
            appendLine("Body weight, newest first:")
            appendLine(listing(weights, limit = 30) { "day ${it.epochDay}: ${it.pounds} lb" })
            appendLine("Exercises:")
            append(listing(exercises.filterNot { it.archived }, limit = 150) { "${it.name}: ${it.bodyPart}, ${it.primary}, ${it.repLow}–${it.repHigh} reps" })
        }
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}
