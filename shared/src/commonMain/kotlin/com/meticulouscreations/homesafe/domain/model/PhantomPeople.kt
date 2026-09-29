package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs

/**
 * Where the detector keeps seeing a person who isn't there, on one camera: the box of a detection
 * someone marked "Not a person". Kept by the push relay (`phantom people` in relay/relay.py).
 *
 * Why (Front Door, 2026-09-29): thirty "Person detected" clips in 51 minutes, 4:23–5:14 PM, of
 * whatever sat by the door, each one the detector's score on it creeping over the threshold and
 * falling back. A person is news; the same clutter at the same spot is not.
 */
data class PhantomSpot(
    /** The detection that was marked. */
    val eventId: String,
    val cameraName: String,
    val box: DetectionBox,
)

object PhantomPeople {
    /** How much a person's box must overlap a spot to be that phantom again. The relay's `PHANTOM_IOU`. */
    const val PHANTOM_IOU = 0.3

    /**
     * How far a phantom's path may stray from its median: this share of its box's longer side. The
     * relay's `PHANTOM_DRIFT`. Stricter than [isStill], whose allowance of a whole box would count
     * someone climbing the steps to a close camera as standing still.
     */
    const val PHANTOM_DRIFT = 0.25
}

/** A person Frigate put no name to: the only kind that can be marked "Not a person". */
val MomentEvent.canMarkNotPerson: Boolean get() = category == MomentCategory.PEOPLE && !isFamiliar

/**
 * True when every point of the path lies within [PhantomPeople.PHANTOM_DRIFT] of the box's longer
 * side of the path's median; false with no box. Same rule as the relay's `stayed_put`.
 */
fun MomentEvent.stayedPut(): Boolean {
    val box = box ?: return false
    if (pathPoints.isEmpty()) return true
    val mx = median(pathPoints.map { it.x })
    val my = median(pathPoints.map { it.y })
    val r = PhantomPeople.PHANTOM_DRIFT * maxOf(box.w, box.h)
    return pathPoints.all { abs(it.x - mx) <= r && abs(it.y - my) <= r }
}

/**
 * Whether this is a phantom person: the very detection someone marked, or an unnamed person on the
 * same camera who [stayedPut] with a box overlapping a spot by [PhantomPeople.PHANTOM_IOU]. A real
 * person at the spot walked there, and a face Frigate knows is never one. Same rule as the relay's
 * `is_phantom`, so the feed hides what the phone was never woken for.
 */
fun MomentEvent.isPhantom(spots: List<PhantomSpot>): Boolean {
    if (category != MomentCategory.PEOPLE || spots.isEmpty()) return false
    if (spots.any { it.eventId == id }) return true
    if (isFamiliar) return false
    val mine = box ?: return false
    return stayedPut() && spots.any { it.cameraName == cameraName && mine.iou(it.box) >= PhantomPeople.PHANTOM_IOU }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}
