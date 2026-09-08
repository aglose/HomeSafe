package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs

/**
 * Folds one vehicle's repeated detections at one spot into a single moment — a visit.
 *
 * Why (verified on the Front Yard camera, 2026-09-07): Frigate made 742 car events in a day, and
 * one car produced seven cards in the five minutes it took to pull in and park. Two things cause
 * that. A passing car steals a parked car's tracker id, which ends the event and starts a fresh
 * one at the same spot seconds later; and a car actually manoeuvring is picked up, lost and
 * re-acquired several times on the way in. Neither is something Frigate can be configured out of.
 *
 * So a vehicle detected again on an overlapping box, on the same camera, soon after the last
 * sighting, is treated as the same visit rather than a new card. "Soon" depends on whether the
 * vehicle is moving: a car that is sitting still is the same parked car for [PARKED_GAP_SECONDS],
 * while one that is moving only counts as the same visit for [VISIT_GAP_SECONDS] — long enough to
 * cover the gaps while it manoeuvres, short enough that coming home this evening is not folded
 * into leaving this morning.
 */
object VehicleVisits {
    /** Share of the path that must sit within the box's size of the path's median for [isStill]. */
    const val STILL_FRACTION = 0.7

    /** How much two sightings' boxes must overlap to be the same vehicle in the same place. */
    const val MERGE_IOU = 0.3

    /** A still vehicle re-detected this soon after the last sighting is the same parked car. */
    const val PARKED_GAP_SECONDS = 30.0 * 60.0

    /** A moving vehicle re-detected this soon at the same spot is still the same visit. */
    const val VISIT_GAP_SECONDS = 5.0 * 60.0
}

/**
 * True when the object barely moved: at least [VehicleVisits.STILL_FRACTION] of its path points lie
 * within r = max(box width, box height) of the path's median point on both axes. A path of fewer
 * than two points is still; an event with no box is never still (there is nothing to match it on).
 *
 * This no longer decides whether a sighting folds — it decides how long the moment stays open for
 * another one. A parked car is expected to be re-detected for hours; a moving one is not.
 */
fun MomentEvent.isStill(): Boolean {
    val box = box ?: return false
    if (pathPoints.size < 2) return true
    val mx = median(pathPoints.map { it.x })
    val my = median(pathPoints.map { it.y })
    val r = maxOf(box.w, box.h)
    val near = pathPoints.count { abs(it.x - mx) <= r && abs(it.y - my) <= r }
    return near.toDouble() / pathPoints.size >= VehicleVisits.STILL_FRACTION
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

/**
 * The same vehicle in the same place, time aside: same camera, both vehicles, and boxes overlapping
 * by at least [VehicleVisits.MERGE_IOU]. Callers add their own window — the feed uses [repeats], the
 * notification poller its own, because it judges an event at its start, when it has no end yet.
 */
internal fun MomentEvent.atSameSpotAs(other: MomentEvent): Boolean {
    if (cameraName != other.cameraName) return false
    if (category != MomentCategory.VEHICLES || other.category != MomentCategory.VEHICLES) return false
    val mine = box ?: return false
    val theirs = other.box ?: return false
    return mine.iou(theirs) >= VehicleVisits.MERGE_IOU
}

/**
 * Whether this sighting continues [previous]: the same vehicle at the same spot, close enough in
 * time. A still sighting gets [VehicleVisits.PARKED_GAP_SECONDS]; a moving one, which is a car on
 * its way in or out, only [VehicleVisits.VISIT_GAP_SECONDS]. A [previous] still in progress counts
 * as ongoing.
 */
fun MomentEvent.repeats(previous: MomentEvent): Boolean {
    if (!atSameSpotAs(previous)) return false
    val gap = startEpochSeconds - (previous.endEpochSeconds ?: startEpochSeconds)
    return gap <= if (isStill()) VehicleVisits.PARKED_GAP_SECONDS else VehicleVisits.VISIT_GAP_SECONDS
}

/**
 * [anchor] with this sighting folded in: the end becomes the later one (null if either is still in
 * progress), the sightings add up, the better-scored name wins (a named sighting beats an unnamed
 * one, a tie keeps the anchor's — the classifier flips between two similar cars), the top score is
 * the max, and any zone this sighting adds is appended. Id, start, box, path and clip stay the
 * anchor's, so its thumbnail and clip stay valid and the visit stays anchored to one place.
 */
internal fun MomentEvent.foldedInto(anchor: MomentEvent): MomentEvent {
    val end = if (anchor.endEpochSeconds == null || endEpochSeconds == null) null else maxOf(anchor.endEpochSeconds, endEpochSeconds)
    val nameWins = isRecognized && (!anchor.isRecognized || (subLabelScore ?: 0.0) > (anchor.subLabelScore ?: 0.0))
    return anchor.copy(
        endEpochSeconds = end,
        sightings = anchor.sightings + sightings,
        subLabel = if (nameWins) subLabel else anchor.subLabel,
        subLabelScore = if (nameWins) subLabelScore else anchor.subLabelScore,
        topScore = listOfNotNull(anchor.topScore, topScore).maxOrNull(),
        zones = (anchor.zones + zones).distinct(),
    )
}

/**
 * Collapses each vehicle's visit into one moment. Walks oldest-first: a vehicle folds into the most
 * recent moment it [repeats]; anything else — a person, a car somewhere else, a return hours later —
 * starts a moment of its own. Returns newest-first by start, like the API. Run this after [inZones],
 * so the street cars the zones reject never anchor a visit.
 */
fun List<MomentEvent>.mergeVehicleVisits(): List<MomentEvent> {
    val moments = ArrayList<MomentEvent>(size)
    for (event in sortedBy { it.startEpochSeconds }) {
        val index = if (event.category == MomentCategory.VEHICLES) moments.indexOfLast { event.repeats(it) } else -1
        if (index >= 0) moments[index] = event.foldedInto(moments[index]) else moments += event
    }
    return moments.sortedByDescending { it.startEpochSeconds }
}
