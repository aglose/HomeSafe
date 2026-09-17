package com.meticulouscreations.homesafe.domain.model

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.ln

/** One of Frigate's custom classification models, e.g. `known_cars` labelling `car` objects. */
data class ClassifierModel(
    /** Config key, also the folder name on the server. */
    val name: String,
    /** Object labels it runs on (`car`), empty for a state model. */
    val objects: List<String>,
    val enabled: Boolean = true,
) {
    val displayName: String get() = subLabelDisplayName(name)
}

/** A crop Frigate saved from a live detection, waiting for a person to say what it is. */
data class UnlabeledCrop(
    val fileName: String,
    /** The tracked object it came from; null for crops Frigate mined from history. */
    val eventId: String?,
    /** When the frame was captured, epoch seconds; null when unknown. */
    val capturedEpochSeconds: Double?,
    /** What the current model guessed, e.g. "sarahs_tesla" / "none" / "unknown" (untrained). */
    val guessedCategory: String?,
    val guessedScore: Double?,
    /** Where the object this crop was cut around stood, so the screen can frame it; null when the event is gone. */
    val subject: CropSubject? = null,
) {
    /**
     * The model is certain of its guess: a `none-1.0` crop of a passing street car, or a 1.0 of a
     * car it already knows. Not worth a person's time by default — Frigate saves a crop on every
     * frame it classifies, so these flood the queue — but kept reachable rather than deleted: a
     * brand-new car's crops look exactly like this, and the queue is the only place it can be named.
     */
    val isConfident: Boolean get() = guessedScore != null && guessedScore >= CONFIDENT_SCORE

    companion object {
        /** Frigate rounds scores to two decimals, so 1.0 is what "100 %" looks like. */
        const val CONFIDENT_SCORE = 1.0

        /**
         * Frigate names live attempts `<eventId>-<frameEpoch>-<category>-<score>.webp`, where the
         * event id itself is `<epoch>-<6 chars>`, and history-mined examples `example_NNN.jpg`.
         */
        fun fromFileName(fileName: String): UnlabeledCrop {
            val base = fileName.substringBeforeLast('.')
            val parts = base.split('-')
            // eventEpoch, eventSuffix, frameEpoch, category..., score
            if (parts.size >= 5) {
                val score = parts.last().toDoubleOrNull()
                val frame = parts[2].toDoubleOrNull()
                if (score != null && frame != null) {
                    return UnlabeledCrop(
                        fileName = fileName,
                        eventId = "${parts[0]}-${parts[1]}",
                        capturedEpochSeconds = frame,
                        guessedCategory = parts.subList(3, parts.size - 1).joinToString("-"),
                        guessedScore = score,
                    )
                }
            }
            return UnlabeledCrop(fileName, eventId = null, capturedEpochSeconds = null, guessedCategory = null, guessedScore = null)
        }
    }
}

/**
 * The tracked object a queued crop was cut around, as its event remembers it. Frigate doesn't keep
 * the box a crop was cut from, and a crop of a car in a busy driveway holds bits of every car next
 * to it, so [boxInCrop] works out which box it was and redoes Frigate's cut to place it.
 *
 * Marked [Immutable] because [boxes] is a read-only list the compiler can't prove unchanging, and
 * without it every crop card on the labelling screens would stop skipping.
 */
@Immutable
data class CropSubject(
    /** The camera's detect resolution, the frame Frigate cut the crop from. */
    val frameWidth: Int,
    val frameHeight: Int,
    /** Every box Frigate kept for the object: its best frame's and one per lifecycle moment (seen, parked, moved, gone). */
    val boxes: List<SeenBox>,
    /** When the crop was taken, epoch seconds; null when unknown. */
    val capturedEpochSeconds: Double?,
    /** The box's bottom-centre closest before the crop was taken, as fractions of the detect frame. */
    val bottomCentre: MaskPoint,
) {
    /**
     * The object's box as fractions of a [cropWidthPx] x [cropHeightPx] crop, or null when it
     * can't be placed.
     *
     * Frigate cuts a square as wide as the box's longest edge, centred on the box and pushed back
     * inside the frame (`calculate_region` with a multiplier of 1), and keeps the detect-frame
     * pixels, so the crop's longest edge *is* the box's longest edge on the frame it came from.
     * That picks the box: the kept box whose longest edge matches the crop's, nearest in time
     * among near-equals. A match means it's that very frame, so its own shape and position are
     * used and the frame is [CropBox.exact]. With no match (the car changed size between the
     * moments Frigate kept) the closest box lends its shape, the path supplies where the car
     * stood, and the result is an estimate.
     *
     * The vertical push-back is measured against the YUV buffer, half again as tall as the
     * picture, so a car near the bottom isn't pushed up at all — its crop just comes out short.
     */
    fun boxInCrop(cropWidthPx: Int, cropHeightPx: Int): CropBox? {
        val side = maxOf(cropWidthPx, cropHeightPx).toDouble()
        if (side <= 0 || cropWidthPx <= 0 || cropHeightPx <= 0) return null
        val chosen = boxes.filter { longestEdge(it) > 0 }.minByOrNull { sizeMismatch(it, side) + timeDistancePenalty(it) } ?: return null
        val exact = sizeMismatch(chosen, side) < SAME_FRAME_MISMATCH
        val width = chosen.width * frameWidth
        val height = chosen.height * frameHeight
        val scale = side / maxOf(width, height)
        val scaledWidth = width * scale
        val scaledHeight = height * scale
        val stood = if (exact) MaskPoint(chosen.x + chosen.width / 2, chosen.y + chosen.height) else bottomCentre
        val centreX = stood.x * frameWidth
        val centreY = stood.y * frameHeight - scaledHeight / 2
        val xOffset = regionOffset(centreX, side, frameWidth.toDouble())
        val yOffset = regionOffset(centreY, side, frameHeight * YUV_HEIGHT_FACTOR)
        val box = CropBox(
            left = ((centreX - scaledWidth / 2 - xOffset) / cropWidthPx).coerceIn(0.0, 1.0),
            top = ((centreY - scaledHeight / 2 - yOffset) / cropHeightPx).coerceIn(0.0, 1.0),
            right = ((centreX + scaledWidth / 2 - xOffset) / cropWidthPx).coerceIn(0.0, 1.0),
            bottom = ((centreY + scaledHeight / 2 - yOffset) / cropHeightPx).coerceIn(0.0, 1.0),
            exact = exact,
        )
        return box.takeIf { it.right > it.left && it.bottom > it.top }
    }

    private fun longestEdge(box: SeenBox): Double = maxOf(box.width * frameWidth, box.height * frameHeight)

    /** How far apart two sizes are as a ratio, so 40 vs 44 px counts the same as 80 vs 88. */
    private fun sizeMismatch(box: SeenBox, side: Double): Double = abs(ln(longestEdge(box) / side))

    /** Only breaks ties between boxes of about the right size: a minute away costs what a 4 % size difference does. */
    private fun timeDistancePenalty(box: SeenBox): Double {
        val captured = capturedEpochSeconds ?: return 0.0
        val seen = box.epochSeconds ?: return 0.0
        return TIME_PENALTY * ln(1 + abs(seen - captured))
    }

    private fun regionOffset(centre: Double, side: Double, limit: Double): Int {
        val offset = (centre - side / 2).toInt()
        return when {
            offset < 0 -> 0
            offset > limit - side -> maxOf(0, (limit - side).toInt())
            else -> offset
        }
    }

    private companion object {
        /** An I420 frame is the picture plus two quarter-size chroma planes stacked below it. */
        const val YUV_HEIGHT_FACTOR = 1.5

        /** Within 4 %: the pixel of rounding either way on a 40 px crop, and nothing a moving car keeps between frames. */
        const val SAME_FRAME_MISMATCH = 0.04

        const val TIME_PENALTY = 0.01
    }
}

/** One box Frigate kept for a tracked object, as fractions of the detect frame. */
data class SeenBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    /** When it was seen; null for the event's best frame, whose moment Frigate doesn't say. */
    val epochSeconds: Double?,
)

/**
 * A rectangle as fractions of a crop image, origin top-left. [exact] when it is the box of the very
 * frame the crop was cut from, rather than a shape borrowed from another moment.
 */
data class CropBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val exact: Boolean = true)

/** Everything the labelling screen shows for one model. */
data class ClassifierDataset(
    val model: ClassifierModel,
    /** Category -> number of labelled images. Includes `none`. */
    val categoryCounts: Map<String, Int>,
    /** Every crop waiting for a label, newest first; see [uncertainQueue] and [confidentQueue]. */
    val queue: List<UnlabeledCrop>,
    val hasTrained: Boolean,
    /** Labelled images added since the last training; training is worthwhile when this is > 0. */
    val newImagesSinceTraining: Int,
) {
    /** The crops worth a person's eye: the model's guess is under 100 %, or it has no guess. */
    val uncertainQueue: List<UnlabeledCrop> get() = queue.filterNot { it.isConfident }

    /** The crops the model is sure about, shown on request so a new car can still be labelled from one. */
    val confidentQueue: List<UnlabeledCrop> get() = queue.filter { it.isConfident }

    /**
     * Every category a crop can be filed under, `none` last.
     *
     * Wider than [categoryCounts] on purpose, because a category with no images on the server is
     * still a category a person can file into — Frigate creates the folder on the first crop that
     * lands there. Without that the labelling screen shows a queue of crops and nothing to tap:
     * `none` can never be typed into the new-category box (it would slug to `not_ours`), and a
     * model whose dataset has been cleared keeps classifying into names the dataset no longer
     * lists.
     */
    val categories: List<String>
        get() = (categoryCounts.keys + NONE_CATEGORY + queue.mapNotNull { it.guessedCategory }.filter { isNameable(it) })
            .sortedWith(compareBy({ it == NONE_CATEGORY }, { it }))

    /** Frigate needs two classes; `none` counts as one. */
    val canTrain: Boolean get() = categoryCounts.count { it.value > 0 } >= 2

    companion object {
        /** Frigate's reserved category: "one of these objects, but not one we care about". Never becomes a sub-label. */
        const val NONE_CATEGORY = "none"

        /** What Frigate writes into a crop's file name when the model has no opinion yet. */
        const val UNKNOWN_GUESS = "unknown"

        /** A guess worth offering as a category of its own: a real name, not a placeholder. */
        private fun isNameable(category: String): Boolean =
            category.isNotBlank() && category != UNKNOWN_GUESS && category != NONE_CATEGORY
    }
}
