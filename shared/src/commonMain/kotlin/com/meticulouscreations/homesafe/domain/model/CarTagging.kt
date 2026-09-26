package com.meticulouscreations.homesafe.domain.model

import kotlin.math.max
import kotlin.math.min

/**
 * Tagging a car on a camera frame by hand: a person points at a car they can see — taps the box
 * Frigate drew around it, or drags a rectangle round one Frigate hasn't boxed — and says whose it
 * is. It is the check on the home screen's "In view now" strip, which only knows what the
 * classifier guessed: the tag both teaches the classifier (an example cut from this frame, then a
 * retrain) and, when Frigate is tracking that car, names it on the spot.
 */
object CarTagging {
    /**
     * How much a drawn rectangle has to share with a tracked car's box to be about that car. Low on
     * purpose: a rectangle dragged by thumb round a car is loose, and the next car over rarely
     * overlaps it by this much.
     */
    const val MATCH_MIN_IOU = 0.3

    /**
     * Or this much of the smaller of the two inside the other: a rectangle drawn round just the
     * body of a car whose box takes in its shadow too, or a generous one round a small car.
     */
    const val MATCH_MIN_CONTAINED = 0.7

    /** Smaller than this, as a fraction of the frame on either side, is a stray tap rather than a car. */
    const val MIN_BOX_FRACTION = 0.02

    /** The object label the known-cars classifier runs on. */
    const val CAR_LABEL = "car"

    /** The longest category key the relay files into (its `DATASET_NAME`), which is also a sane folder name. */
    private const val MAX_KEY_LENGTH = 64

    /**
     * "Grandma's Van" -> `grandmas_van`: the category a new known car is filed under, slugged the
     * way the labelling screen slugs one (see [DetectionZone.slug]), so [subLabelDisplayName] puts
     * the apostrophe back. Null when there is no name in it, or it is one of the placeholders that
     * never count as a name ([MomentVisits.NOT_A_NAME]) — `none` is "not ours", not a car.
     */
    fun knownCarKey(name: String): String? {
        if (name.none { it.isLetterOrDigit() }) return null
        val key = DetectionZone.slug(name).trim('_', '-').take(MAX_KEY_LENGTH).trimEnd('_', '-')
        return key.takeIf { it.isNotEmpty() && !MomentVisits.isPlaceholderName(it) }
    }
}

/**
 * A still of a detection's car, for a tag that can't file Frigate's own crop of it: [jpeg] is a
 * frame from the recording and [box] where the car was on it, as fractions.
 */
class EventFrame(val jpeg: ByteArray, val box: SeenBox)

/**
 * The tracked object [box] is about, if any: the one it overlaps most, as long as it overlaps
 * enough ([CarTagging.MATCH_MIN_IOU], or [CarTagging.MATCH_MIN_CONTAINED] of the smaller box).
 * Objects Frigate gave no box are never matched.
 */
fun List<TrackedObject>.trackedObjectAt(box: SeenBox): TrackedObject? =
    mapNotNull { obj -> obj.box?.let { obj to it.overlapWith(box) } }
        .filter { (_, overlap) -> overlap.iou >= CarTagging.MATCH_MIN_IOU || overlap.contained >= CarTagging.MATCH_MIN_CONTAINED }
        .maxByOrNull { (_, overlap) -> overlap.iou }
        ?.first

/** The rectangle between two points on the frame (fractions, any order), kept inside it. */
fun boxBetween(x1: Double, y1: Double, x2: Double, y2: Double): SeenBox {
    val left = min(x1, x2).coerceIn(0.0, 1.0)
    val top = min(y1, y2).coerceIn(0.0, 1.0)
    val right = max(x1, x2).coerceIn(0.0, 1.0)
    val bottom = max(y1, y2).coerceIn(0.0, 1.0)
    return SeenBox(x = left, y = top, width = right - left, height = bottom - top, epochSeconds = null)
}

/** Big enough to be a car rather than a stray touch; see [CarTagging.MIN_BOX_FRACTION]. */
val SeenBox.isTaggable: Boolean
    get() = width >= CarTagging.MIN_BOX_FRACTION && height >= CarTagging.MIN_BOX_FRACTION

/** Whether ([x], [y]), in fractions of the frame, is inside this box. */
fun SeenBox.contains(x: Double, y: Double): Boolean = x in this.x..(this.x + width) && y in this.y..(this.y + height)

private data class Overlap(val iou: Double, val contained: Double)

private fun SeenBox.overlapWith(other: SeenBox): Overlap {
    val w = min(x + width, other.x + other.width) - max(x, other.x)
    val h = min(y + height, other.y + other.height) - max(y, other.y)
    if (w <= 0 || h <= 0) return Overlap(0.0, 0.0)
    val intersection = w * h
    val a = width * height
    val b = other.width * other.height
    return Overlap(iou = intersection / (a + b - intersection), contained = intersection / min(a, b))
}

/**
 * The size of a JPEG's picture, read from its frame header, or null when [bytes] isn't one. The
 * tagging screen needs the frame's shape before it can lay the picture out, and Frigate's
 * `latest.jpg` says nothing about it anywhere else.
 */
fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    fun u8(i: Int) = bytes[i].toInt() and 0xFF
    fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    if (bytes.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
    var i = 2
    while (i + 3 < bytes.size) {
        if (u8(i) != 0xFF) return null
        val marker = u8(i + 1)
        // Fill bytes, and the markers that stand alone without a length.
        if (marker == 0xFF) {
            i++
            continue
        }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
            i += 2
            continue
        }
        val length = u16(i + 2)
        // Start-of-frame, any flavour but the three that share its range and aren't one (DHT, JPG, DAC).
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            if (i + 8 >= bytes.size) return null
            val height = u16(i + 5)
            val width = u16(i + 7)
            return if (width > 0 && height > 0) width to height else null
        }
        i += 2 + length
    }
    return null
}
