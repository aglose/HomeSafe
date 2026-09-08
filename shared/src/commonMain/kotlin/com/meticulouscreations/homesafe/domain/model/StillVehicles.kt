package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs

/**
 * Folds a parked vehicle's repeated re-detections into one moment.
 *
 * Why (verified on the Front Yard camera, 2026-09-07): a car parked at the curb produced nine
 * separate Frigate events in one morning. Each time another car drove past, the tracker handed
 * the parked car's id to the passer-by — the recorded path shows a short burst of far-away points
 * right at the end — the event ended, and Frigate re-detected the parked car as a brand-new event
 * at the same spot seconds later. Nothing in Frigate's config prevents that, so the feed does the
 * folding: a still vehicle that repeats a recent sighting at the same spot becomes another
 * sighting of that moment rather than a card of its own.
 *
 * The thresholds were chosen against that day's 735 car events: 34 visible vehicle cards became
 * 15, and the parked car's nine became one. A tighter overlap (0.5) or a longer gap (an hour)
 * moved the count by one either way.
 */
object StillVehicles {
    /** Share of the path that must sit within the box's size of the path's median point. */
    const val STILL_FRACTION = 0.7

    /** How much two sightings' boxes must overlap to be the same parked vehicle. */
    const val MERGE_IOU = 0.3

    /** A re-detection this soon after the previous sighting ended is the same parked vehicle, not a return. */
    const val MERGE_GAP_SECONDS = 30.0 * 60.0
}

/**
 * True when the object barely moved: at least [StillVehicles.STILL_FRACTION] of its path points lie
 * within r = max(box width, box height) of the path's median point on both axes. A path of fewer
 * than two points is still; an event without a box is never still (there's nothing to match it on).
 *
 * The majority test is what makes this robust. A parked car's path is jitter pairs around one spot
 * plus, when its tracker gets stolen, a few far-away points at the end — a small minority. A car
 * driving through spreads its samples along the road and fails the test.
 */
fun MomentEvent.isStill(): Boolean {
    val box = box ?: return false
    if (pathPoints.size < 2) return true
    val mx = median(pathPoints.map { it.x })
    val my = median(pathPoints.map { it.y })
    val r = maxOf(box.w, box.h)
    val near = pathPoints.count { abs(it.x - mx) <= r && abs(it.y - my) <= r }
    return near.toDouble() / pathPoints.size >= StillVehicles.STILL_FRACTION
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

/**
 * Whether this is a re-detection of [previous]: same camera, both vehicles, boxes overlapping by at
 * least [StillVehicles.MERGE_IOU], and started no more than [StillVehicles.MERGE_GAP_SECONDS] after
 * [previous] ended (a [previous] still in progress counts as ongoing). Stillness is the caller's
 * test — the feed and the alert service both require it of the newer event first.
 */
fun MomentEvent.repeats(previous: MomentEvent): Boolean {
    if (cameraName != previous.cameraName) return false
    if (category != MomentCategory.VEHICLES || previous.category != MomentCategory.VEHICLES) return false
    val mine = box ?: return false
    val theirs = previous.box ?: return false
    if (mine.iou(theirs) < StillVehicles.MERGE_IOU) return false
    val previousEnd = previous.endEpochSeconds ?: startEpochSeconds
    return startEpochSeconds - previousEnd <= StillVehicles.MERGE_GAP_SECONDS
}

/**
 * [anchor] with this sighting folded in: the end becomes the later one (null if either is still in
 * progress), the sightings add up, the better-scored name wins (a named sighting beats an unnamed
 * one, a tie keeps the anchor's — the classifier flips between two similar cars), the top score is
 * the max, and any zone this sighting adds is appended. Id, start, box, path and clip stay the
 * anchor's, so its thumbnail and clip stay valid.
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
 * Collapses a parked vehicle's repeated re-detections into one moment. Walks oldest-first: a still
 * vehicle folds into the most recent moment it [repeats]; anything else — a person, an arrival, a
 * departure — starts a moment of its own. Returns newest-first by start, like the API. Run this
 * after [inZones], so the street cars the zones reject never anchor anything.
 */
fun List<MomentEvent>.mergeStillVehicles(): List<MomentEvent> {
    val moments = ArrayList<MomentEvent>(size)
    for (event in sortedBy { it.startEpochSeconds }) {
        val index = if (event.category == MomentCategory.VEHICLES && event.isStill()) moments.indexOfLast { event.repeats(it) } else -1
        if (index >= 0) moments[index] = event.foldedInto(moments[index]) else moments += event
    }
    return moments.sortedByDescending { it.startEpochSeconds }
}
