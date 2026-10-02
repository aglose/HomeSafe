package com.meticulouscreations.homesafe.domain.model

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.cars_check_confirmed_looks
import homesafe.shared.generated.resources.cars_check_confirmed_looks_saw
import homesafe.shared.generated.resources.cars_check_confirmed_plate
import homesafe.shared.generated.resources.cars_check_confirmed_plate_saw
import homesafe.shared.generated.resources.cars_check_mismatch
import homesafe.shared.generated.resources.cars_check_mismatch_saw
import homesafe.shared.generated.resources.cars_check_other_car_looks
import homesafe.shared.generated.resources.cars_check_other_car_plate
import homesafe.shared.generated.resources.cars_check_unchecked
import homesafe.shared.generated.resources.cars_check_unconfirmed_dark
import homesafe.shared.generated.resources.cars_check_unconfirmed_saw
import homesafe.shared.generated.resources.cars_colour_beige
import homesafe.shared.generated.resources.cars_colour_black
import homesafe.shared.generated.resources.cars_colour_blue
import homesafe.shared.generated.resources.cars_colour_brown
import homesafe.shared.generated.resources.cars_colour_gold
import homesafe.shared.generated.resources.cars_colour_green
import homesafe.shared.generated.resources.cars_colour_grey
import homesafe.shared.generated.resources.cars_colour_orange
import homesafe.shared.generated.resources.cars_colour_purple
import homesafe.shared.generated.resources.cars_colour_red
import homesafe.shared.generated.resources.cars_colour_silver
import homesafe.shared.generated.resources.cars_colour_white
import homesafe.shared.generated.resources.cars_colour_yellow
import homesafe.shared.generated.resources.cars_looks_colour_car
import org.jetbrains.compose.resources.StringResource

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
    /** "blue Tesla Model Y": what's on file, for a line under the car's name; null when nothing is. */
    val looks: UiText? get() = carLooks(colour, make, model)

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
    /** "black Tesla Model Y": what the vision model saw, with what it couldn't tell left out; null when it told nothing. */
    val saw: UiText?
        get() = carLooks(sawColour?.takeIf { it !in NOT_SEEN }, sawMake?.takeIf { it !in NOT_SEEN }, sawModel?.takeIf { it !in NOT_SEEN })

    private companion object {
        /** What the vision model answers when it can't tell. */
        val NOT_SEEN = setOf("unknown", "other")
    }

    /** Whether the check confirmed the car is [category]. */
    fun confirms(category: String): Boolean = verified != null && name == category
}

/** A make as the relay keys it (`tesla`, `bmw`) the way it's written: "Tesla", "BMW". A brand name, so data, not copy. */
fun makeName(make: String): String = if (make.length <= 3) make.uppercase() else make.replaceFirstChar { it.uppercase() }

/**
 * A colour as the relay keys it (`blue`), in the reader's language: one of the colours the vision
 * model can answer. One the app has no word for is shown as the relay sent it.
 */
fun carColourName(colour: String): UiText = carColourRes(colour)?.let { UiText.of(it) } ?: colour.asUiText()

private fun carColourRes(colour: String): StringResource? = when (colour.lowercase()) {
    "white" -> Res.string.cars_colour_white
    "black" -> Res.string.cars_colour_black
    "grey", "gray" -> Res.string.cars_colour_grey
    "silver" -> Res.string.cars_colour_silver
    "red" -> Res.string.cars_colour_red
    "blue" -> Res.string.cars_colour_blue
    "green" -> Res.string.cars_colour_green
    "brown" -> Res.string.cars_colour_brown
    "beige" -> Res.string.cars_colour_beige
    "gold" -> Res.string.cars_colour_gold
    "yellow" -> Res.string.cars_colour_yellow
    "orange" -> Res.string.cars_colour_orange
    "purple" -> Res.string.cars_colour_purple
    else -> null
}

/**
 * "blue Tesla Model Y" from a colour, make and model, any of them blank; null when all are. The
 * make and model are names, so they're kept together as written; the colour is a word, translated
 * and placed by [Res.string.cars_looks_colour_car].
 */
private fun carLooks(colour: String?, make: String?, model: String?): UiText? {
    val car = listOfNotNull(make?.takeIf { it.isNotBlank() }?.let(::makeName), model?.takeIf { it.isNotBlank() }).joinToString(" ")
    val colourName = colour?.takeIf { it.isNotBlank() }?.let(::carColourName)
    return when {
        colourName != null && car.isNotEmpty() -> UiText.of(Res.string.cars_looks_colour_car, colourName, car)
        colourName != null -> colourName
        car.isNotEmpty() -> car.asUiText()
        else -> null
    }
}

/**
 * One line on a queued crop of a car the classifier named, saying what the car check made of it,
 * so a person can see why a 100% guess is asking to be corrected. Null when there's nothing to
 * say: no check at all (an older relay), or a crop of no car in particular.
 *
 * The check's `verified` is "plate" or "looks"; anything else reads as looks.
 */
fun UnlabeledCrop.checkNote(): UiText? {
    val guess = guessedCategory?.takeIf { checksKnown && it.isNotBlank() && !MomentVisits.isPlaceholderName(it) } ?: return null
    val car = subLabelDisplayName(guess)
    val check = check ?: return if (isDoubted) UiText.of(Res.string.cars_check_unchecked, car) else null
    val saw = check.saw
    val named = check.name
    val byPlate = check.verified == PLATE_VERIFIED
    return when {
        check.confirms(guess) -> when {
            saw == null -> UiText.of(if (byPlate) Res.string.cars_check_confirmed_plate else Res.string.cars_check_confirmed_looks)
            else -> UiText.of(if (byPlate) Res.string.cars_check_confirmed_plate_saw else Res.string.cars_check_confirmed_looks_saw, saw)
        }

        check.verified != null && named != null ->
            UiText.of(if (byPlate) Res.string.cars_check_other_car_plate else Res.string.cars_check_other_car_looks, subLabelDisplayName(named), car)

        named != guess -> if (saw != null) UiText.of(Res.string.cars_check_mismatch_saw, saw, car) else UiText.of(Res.string.cars_check_mismatch, car)

        saw != null -> UiText.of(Res.string.cars_check_unconfirmed_saw, car, saw)

        else -> UiText.of(Res.string.cars_check_unconfirmed_dark, car)
    }
}

/** The car check's `verified` when the plate confirmed the car. */
private const val PLATE_VERIFIED = "plate"
