package com.meticulouscreations.homesafe.fitness.live

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.fitness.ui.FitnessFormat
import com.meticulouscreations.homesafe.text.TextLoader
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.fitness_live_first_up
import homesafe.shared.generated.resources.fitness_live_first_up_aim
import homesafe.shared.generated.resources.fitness_live_heart
import homesafe.shared.generated.resources.fitness_live_heart_down
import homesafe.shared.generated.resources.fitness_live_heart_zone
import homesafe.shared.generated.resources.fitness_live_lift_aim
import homesafe.shared.generated.resources.fitness_live_lift_last
import homesafe.shared.generated.resources.fitness_live_no_sets
import homesafe.shared.generated.resources.fitness_live_open
import homesafe.shared.generated.resources.fitness_live_records
import homesafe.shared.generated.resources.fitness_live_rest_over
import homesafe.shared.generated.resources.fitness_live_resting
import homesafe.shared.generated.resources.fitness_live_resting_after
import homesafe.shared.generated.resources.fitness_live_sets
import homesafe.shared.generated.resources.fitness_live_up_next
import homesafe.shared.generated.resources.fitness_live_up_next_aim
import homesafe.shared.generated.resources.fitness_rest_go
import homesafe.shared.generated.resources.fitness_rest_label
import homesafe.shared.generated.resources.fitness_rest_minus
import homesafe.shared.generated.resources.fitness_rest_minus_short
import homesafe.shared.generated.resources.fitness_rest_plus
import homesafe.shared.generated.resources.fitness_rest_plus_short
import homesafe.shared.generated.resources.fitness_rest_skip
import homesafe.shared.generated.resources.fitness_workout_title

/**
 * What the live surfaces say about a [LiveWorkout], in words from the resources, so the
 * notification, the Live Activity and the Mac's island all say the same thing in the reader's
 * language. Each surface puts them together the same way, by [headline] and [detail] for the
 * phase it is in.
 */
@Immutable
data class LiveWorkoutLines(
    /** "Chest day". */
    val title: UiText,
    /** What it has come to: "6 sets · 2 records", or "No sets yet". */
    val summary: UiText,
    /** "Resting after Bench press". */
    val resting: UiText,
    /** "Rest's up". */
    val restOver: UiText,
    /** The exercise in hand and where it stands: "Bench press · last 185 lb × 8". */
    val lift: UiText?,
    /** What comes after it: "Up next: Incline press · aim for 70 lb × 10". */
    val next: UiText?,
    /** "142 bpm · zone 3", or during a rest "142 bpm · down 24". */
    val heart: UiText?,
) {
    companion object {
        fun of(workout: LiveWorkout): LiveWorkoutLines {
            val lift = workout.lift
            val next = workout.next
            val sets = UiText.plural(Res.plurals.fitness_live_sets, workout.sets)
            val summary = when {
                workout.sets == 0 -> UiText.of(Res.string.fitness_live_no_sets)
                workout.records > 0 -> UiText.Joined(listOf(sets, UiText.plural(Res.plurals.fitness_live_records, workout.records)), UiText.of(Res.string.common_dot_separator))
                else -> sets
            }
            val first = workout.sets == 0
            return LiveWorkoutLines(
                title = UiText.of(Res.string.fitness_workout_title, UiText.of(workout.focus.label)),
                summary = summary,
                resting = lift?.let { UiText.of(Res.string.fitness_live_resting_after, it.name.asUiText()) } ?: UiText.of(Res.string.fitness_live_resting),
                restOver = UiText.of(Res.string.fitness_live_rest_over),
                lift = lift?.let(::liftLine),
                next = next?.let { upNext(it, first) },
                heart = workout.heart?.let(::heartLine),
            )
        }

        private fun liftLine(lift: LiveLift): UiText {
            val last = lift.last
            val target = lift.target
            return when {
                last != null -> UiText.of(Res.string.fitness_live_lift_last, lift.name.asUiText(), FitnessFormat.set(lift.loadKind, last.weight, last.reps))
                target != null -> UiText.of(Res.string.fitness_live_lift_aim, lift.name.asUiText(), FitnessFormat.set(lift.loadKind, target.weight, target.reps))
                else -> lift.name.asUiText()
            }
        }

        private fun upNext(next: LiveLift, first: Boolean): UiText {
            val target = next.target
            val name = next.name.asUiText()
            return when {
                target == null -> UiText.of(if (first) Res.string.fitness_live_first_up else Res.string.fitness_live_up_next, name)
                else -> UiText.of(if (first) Res.string.fitness_live_first_up_aim else Res.string.fitness_live_up_next_aim, name, FitnessFormat.set(next.loadKind, target.weight, target.reps))
            }
        }

        private fun heartLine(heart: LiveHeart): UiText {
            val drop = heart.drop
            val zone = heart.zone
            return when {
                drop != null && drop > 0 -> UiText.of(Res.string.fitness_live_heart_down, heart.bpm, drop)
                zone != null -> UiText.of(Res.string.fitness_live_heart_zone, heart.bpm, zone.number)
                else -> UiText.of(Res.string.fitness_live_heart, heart.bpm)
            }
        }
    }
}

/** The labels every surface's rest buttons wear, short and spoken, and the way into the app. */
@Immutable
data class LiveWorkoutLabels(
    val rest: String = "",
    val go: String = "",
    val skip: String = "",
    val minusShort: String = "",
    val minus: String = "",
    val plusShort: String = "",
    val plus: String = "",
    val open: String = "",
) {
    companion object {
        suspend fun load(text: TextLoader = TextLoader.Resources): LiveWorkoutLabels = LiveWorkoutLabels(
            rest = text.load(UiText.of(Res.string.fitness_rest_label)),
            go = text.load(UiText.of(Res.string.fitness_rest_go)),
            skip = text.load(UiText.of(Res.string.fitness_rest_skip)),
            minusShort = text.load(UiText.of(Res.string.fitness_rest_minus_short)),
            minus = text.load(UiText.of(Res.string.fitness_rest_minus)),
            plusShort = text.load(UiText.of(Res.string.fitness_rest_plus_short)),
            plus = text.load(UiText.of(Res.string.fitness_rest_plus)),
            open = text.load(UiText.of(Res.string.fitness_live_open)),
        )
    }
}

/** [LiveWorkoutLines] loaded, for platform code that is handed finished strings. */
@Immutable
data class LiveWorkoutWords(
    val title: String,
    val summary: String,
    val resting: String,
    val restOver: String,
    val lift: String?,
    val next: String?,
    val heart: String?,
    val labels: LiveWorkoutLabels,
) {
    /** The line in large type, for [phase]: the workout between rests, the rest while it counts, and that it is over once it is. */
    fun headline(phase: LivePhase): String = when (phase) {
        LivePhase.TRAINING -> title
        LivePhase.RESTING -> resting
        LivePhase.REST_OVER -> restOver
    }

    /**
     * The line under it, for [phase]: between rests the exercise in hand (or, before the first
     * set, what to start on); while resting, what comes after; once it is over, what to go back to.
     */
    fun detail(phase: LivePhase): String = when (phase) {
        LivePhase.TRAINING -> lift ?: next ?: summary
        LivePhase.RESTING -> next ?: lift ?: summary
        LivePhase.REST_OVER -> lift ?: next ?: summary
    }

    companion object {
        suspend fun load(lines: LiveWorkoutLines, labels: LiveWorkoutLabels, text: TextLoader = TextLoader.Resources): LiveWorkoutWords = LiveWorkoutWords(
            title = text.load(lines.title),
            summary = text.load(lines.summary),
            resting = text.load(lines.resting),
            restOver = text.load(lines.restOver),
            lift = lines.lift?.let { text.load(it) },
            next = lines.next?.let { text.load(it) },
            heart = lines.heart?.let { text.load(it) },
            labels = labels,
        )
    }
}
