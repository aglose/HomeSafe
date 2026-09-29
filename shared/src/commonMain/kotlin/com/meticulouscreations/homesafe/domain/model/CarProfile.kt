package com.meticulouscreations.homesafe.domain.model

import androidx.compose.runtime.Immutable

/**
 * What one of the household's cars looks like, so the relay's vision model can check the
 * classifier's name for it: a make, a model name, one colour and a plate. [name] is the known-cars
 * category (`andrews_tesla`). A blank field isn't checked.
 *
 * The colour is strict on purpose: the household has a red and a dark blue Model Y, and a blue
 * Tesla must never pass as a black one. A car the camera can't see the colour of (dusk, infrared)
 * is left for the plate, or for a person in the labelling queue.
 */
@Immutable
data class CarProfile(
    val name: String,
    val make: String = "",
    val model: String = "",
    val colour: String = "",
    val plate: String = "",
) {
    /** "blue Tesla Model Y": what's on file, for a line under the car's name; blank when nothing is. */
    val looks: String get() = listOf(colour, makeName(make), model).filter { it.isNotBlank() }.joinToString(" ")

    /** Whether there's anything to check the car against. */
    val isEmpty: Boolean get() = make.isBlank() && model.isBlank() && colour.isBlank() && plate.isBlank()

    companion object {
        /** The relay's own rule: letters and digits, upper case, at most ten. "8abc-123" is 8ABC123. */
        fun normalPlate(plate: String): String = plate.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(PLATE_MAX_LENGTH)

        /** A plate shorter than this is refused: the relay can't tell it from a scrap of another. */
        const val PLATE_MIN_LENGTH = 4
        const val PLATE_MAX_LENGTH = 10
        const val MODEL_MAX_LENGTH = 40
    }
}

/** Every car's profile, and the makes and colours a profile may name (all the vision model can answer). */
@Immutable
data class CarProfiles(
    val profiles: List<CarProfile>,
    val makes: List<String>,
    val colours: List<String>,
) {
    fun profileOf(name: String): CarProfile = profiles.firstOrNull { it.name == name } ?: CarProfile(name)
}

/**
 * What the relay's car check made of one detection: the vision model's look at the car in the 4K
 * recording, checked against the profile of the car the classifier named. [verified] is what
 * confirmed [name] — "plate" or "looks" — or null when nothing did (a colour that doesn't match,
 * infrared, no profile). Only verified detections count as sure.
 */
@Immutable
data class CarCheck(
    val verdict: String,
    val classifierName: String?,
    val name: String?,
    val verified: String?,
    val sawColour: String? = null,
    val sawMake: String? = null,
    val sawModel: String? = null,
) {
    /** "black Tesla Model Y": what the vision model saw, with what it couldn't tell left out. */
    val saw: String
        get() = listOf(sawColour, sawMake?.let { if (it in NOT_SEEN) it else makeName(it) }, sawModel)
            .filter { !it.isNullOrBlank() && it !in NOT_SEEN }
            .joinToString(" ")

    private companion object {
        /** What the vision model answers when it can't tell. */
        val NOT_SEEN = setOf("unknown", "other")
    }

    /** Whether the check confirmed the car is [category]. */
    fun confirms(category: String): Boolean = verified != null && name == category
}

/** A make as the relay keys it (`tesla`, `bmw`) the way it's written: "Tesla", "BMW". */
fun makeName(make: String): String = if (make.length <= 3) make.uppercase() else make.replaceFirstChar { it.uppercase() }

/**
 * One line on a queued crop of a car the classifier named, saying what the car check made of it,
 * so a person can see why a 100% guess is asking to be corrected. Null when there's nothing to
 * say: no check at all (an older relay), or a crop of no car in particular.
 */
fun UnlabeledCrop.checkNote(): String? {
    val guess = guessedCategory?.takeIf { checksKnown && it.isNotBlank() && !MomentVisits.isPlaceholderName(it) } ?: return null
    val car = subLabelDisplayName(guess)
    val check = check ?: return if (isDoubted) "Sure it's $car, but not confirmed by its plate or looks yet" else null
    val saw = check.saw.takeIf { it.isNotBlank() }?.let { "looked like a $it" }
    val named = check.name
    return when {
        check.confirms(guess) -> "Confirmed by its ${check.verified}" + (saw?.let { ": $it" } ?: "")
        check.verified != null && named != null -> "Car check: ${subLabelDisplayName(named)} by its ${check.verified}, not $car"
        named != guess -> "Car check: ${saw ?: "didn't look like it"}, not $car"
        saw != null -> "Car check couldn't confirm $car: $saw"
        else -> "Car check couldn't confirm $car: too dark to tell its colour"
    }
}
