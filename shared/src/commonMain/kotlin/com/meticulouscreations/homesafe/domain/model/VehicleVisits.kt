package com.meticulouscreations.homesafe.domain.model

import kotlin.math.abs

/**
 * Folds one vehicle's repeated detections at one spot into a single moment — a visit — and drops
 * the sightings of a vehicle that never went anywhere.
 *
 * Why (verified on the Front Yard camera, 2026-09-07): Frigate made 742 car events in a day, and
 * one car produced seven cards in the five minutes it took to pull in and park. Two things cause
 * that. A passing car steals a parked car's tracker id, which ends the event and starts a fresh
 * one at the same spot seconds later; and a car actually manoeuvring is picked up, lost and
 * re-acquired several times on the way in. Neither is something Frigate can be configured out of.
 * And (2026-09-15) a car parked all day is re-detected every few minutes as the detector's box on
 * it flickers: each is a brand-new object with a two-point path and no travel, filed as an alert.
 *
 * So a vehicle detected again on an overlapping box, on the same camera, soon after the last
 * sighting, is treated as the same visit rather than a new card. "Soon" depends on whether the
 * vehicle is moving: a car that is sitting still is the same parked car for [PARKED_GAP_SECONDS],
 * while one that is moving only counts as the same visit for [VISIT_GAP_SECONDS] — long enough to
 * cover the gaps while it manoeuvres, short enough that coming home this evening is not folded
 * into leaving this morning. And a still sighting that continues no visit is no moment at all: a
 * vehicle is only news once it has moved. The push relay applies the same rule (`is_still` in
 * relay/relay.py), so a phone hears about the same vehicles the feed shows.
 *
 * [isStill] asks whether a sighting *ever* went anywhere, which is what news is made of. Whether
 * the vehicle is sitting there *now* is a different question with a different answer for the one
 * sighting that is both: the car that drove in and has been parked, in a single Frigate event,
 * ever since. That is [isParked], and it is what the home screen's strip and the zones ask.
 */
object VehicleVisits {
    /** Share of a path that must sit within the box's size of the path's median for it to be gathered ([isStill]). */
    const val STILL_FRACTION = 0.7

    /**
     * The most "the box's size" may be for [isStill], as a share of the frame. The box is the best
     * frame's, and for a car that drives in and parks by the camera that is the close-up, not the
     * car as it was along the way. Front Yard, 2026-10-05: Andrew's Tesla came up the street,
     * turned and came down to the garage, three quarters of the frame in 32 seconds, with a box
     * 0.36 of the frame tall — which held 22 of the arrival's 25 points, so the arrival was
     * "still": no moment, no notification. A parked car's jitter doesn't grow with its box like
     * that. In the 6,000 vehicle events of that day and the one before, no path that stayed in one
     * place needed more than 0.17 to hold [STILL_FRACTION] of its points, whatever its box; at 0.2
     * every one of them stays still, and each of the 28 that become moved crossed a third of the
     * frame or more.
     */
    const val STILL_RADIUS_CAP = 0.2

    /**
     * A path shorter than this is still whatever its shape. Frigate records a point when the
     * object first appears, another on its next look, and then one per ~5% of the frame travelled,
     * so three points is a single jump — the detector's box flipping between the whole car and
     * part of it — and only from four is there a journey to judge.
     */
    const val MOVED_MIN_POINTS = 4

    /** How much two sightings' boxes must overlap to be the same vehicle in the same place. */
    const val MERGE_IOU = 0.3

    /** A still vehicle re-detected this soon after the last sighting is the same parked car. */
    const val PARKED_GAP_SECONDS = 30.0 * 60.0

    /** A moving vehicle re-detected this soon at the same spot is still the same visit. */
    const val VISIT_GAP_SECONDS = 5.0 * 60.0

    /**
     * How long a vehicle that drove must have stayed within its box's size of one spot to count as
     * parked there ([isParked]). The time a manoeuvre is given in [VISIT_GAP_SECONDS]: a car that
     * hasn't gone a car's length in that long is not on its way anywhere.
     */
    const val SETTLED_SECONDS = 5.0 * 60.0
}

/**
 * True when the object never went anywhere. A path is *gathered* when at least
 * [VehicleVisits.STILL_FRACTION] of its points lie within r = max(box width, box height), and no
 * more than [VehicleVisits.STILL_RADIUS_CAP], of its median point on both axes. The object is
 * still when its whole path is gathered and it had not travelled before: no earlier stretch of the
 * path, from its first point on, both reached further than 2r across and was not gathered. A path
 * of fewer than [VehicleVisits.MOVED_MIN_POINTS] points is still; an event with no box is never
 * still (there is nothing to match it on).
 *
 * The earlier stretches are there because sitting doesn't undo a drive. Frigate can keep one event
 * on a car from the street until hours after it parked, and every flicker of its box adds a point
 * at the resting place: Andrew's Tesla (Front Yard, 2026-10-05) took 25 points to arrive and had
 * 12 more from the garage four and a half hours later. Enough of those and the whole path is
 * gathered around the garage, arrival and all. The 2r is the width gathered points fit in; without
 * it a short path decides on a single point, and a far car whose box jumps between two places
 * just over r apart would be "travel" the moment it had an odd number of them.
 *
 * This decides two things: how long a moment stays open for another sighting (a parked car is
 * expected to be re-detected for hours; a moving one is not), and whether a sighting that continues
 * nothing is a moment at all (only if it moved — see [mergeVehicleVisits]).
 */
fun MomentEvent.isStill(): Boolean {
    val box = box ?: return false
    if (pathPoints.size < VehicleVisits.MOVED_MIN_POINTS) return true
    val r = minOf(maxOf(box.w, box.h), VehicleVisits.STILL_RADIUS_CAP)
    if (!pathPoints.isGathered(r)) return false
    return (VehicleVisits.MOVED_MIN_POINTS until pathPoints.size).none { count ->
        val stretch = pathPoints.subList(0, count)
        stretch.span() > 2 * r && !stretch.isGathered(r)
    }
}

/**
 * True when the vehicle is sitting still at [nowEpochSeconds] — or was when the sighting ended —
 * whatever it did before: it is within r on both axes of where it stood
 * [VehicleVisits.SETTLED_SECONDS] before it was last seen, r being [isStill]'s — max(box width,
 * box height), and no more than [VehicleVisits.STILL_RADIUS_CAP]. A sighting whose path doesn't
 * reach back that far, or has no times ([MomentEvent.pathEpochSeconds] empty), has only its shape
 * to go by, and is parked when it is [isStill].
 *
 * The shape alone can't answer this for a car Frigate keeps in one event for hours, in either
 * direction. Frigate only records a path point when the object travels ~5% of the frame, so the
 * path of a car tracked from the street to long after it parked is its arrival and little else,
 * and the share of it near the median says how long the drive in was, not whether the car is
 * parked. Measured on the Front Yard, 2026-10-05: Sarah's car, in view for 151 minutes, had 17
 * points, 15 of them from its first 23 seconds; Andrew's Tesla, 270 minutes, had 27 with 25 from
 * its first 32. And a car that has sat in one event all day, its box flickering a point or two at
 * a time, has a path that stays [isStill] while it drives off, until the drive is 30% of it.
 *
 * The times say what the shape can't: where the car stood [VehicleVisits.SETTLED_SECONDS] ago,
 * and so whether it has gone anywhere since. A flicker of the box comes as a pair, away and back,
 * and leaves the car within r of where it was; a car pulling out has left that behind within a
 * box's length, however long it sat first; and one that only just pulled in has no "where it
 * stood" that long ago at all. So once the path reaches back that far, the comparison decides and
 * the shape is not asked.
 *
 * The relay has no counterpart: its `is_still` mirrors [isStill], for the same "is this news"
 * question, and where the cars are *now* it answers from its own memory of arrivals and departures.
 */
fun MomentEvent.isParked(nowEpochSeconds: Double): Boolean {
    val box = box ?: return false
    val settledSince = (endEpochSeconds ?: nowEpochSeconds) - VehicleVisits.SETTLED_SECONDS
    val then = pathPoints.takeIf { it.size == pathEpochSeconds.size }?.getOrNull(pathEpochSeconds.indexOfLast { it <= settledSince })
        ?: return isStill()
    val here = pathPoints.last()
    val r = minOf(maxOf(box.w, box.h), VehicleVisits.STILL_RADIUS_CAP)
    return abs(here.x - then.x) <= r && abs(here.y - then.y) <= r
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

/** At least [VehicleVisits.STILL_FRACTION] of these points within [r] of their median on both axes. */
private fun List<MaskPoint>.isGathered(r: Double): Boolean {
    val mx = median(map { it.x })
    val my = median(map { it.y })
    val near = count { abs(it.x - mx) <= r && abs(it.y - my) <= r }
    return near.toDouble() / size >= VehicleVisits.STILL_FRACTION
}

/** How far these points reach across the frame: the larger of their extents along the two axes. */
private fun List<MaskPoint>.span(): Double = maxOf(maxOf { it.x } - minOf { it.x }, maxOf { it.y } - minOf { it.y })

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
 * one, a tie keeps the anchor's — the classifier flips between two similar cars), and the top score
 * is the max. Id, start, box, path and clip stay the anchor's, so its thumbnail and clip stay valid
 * and the visit stays anchored to one place.
 *
 * Zones are ordered so this sighting's come last, because the card says where the object *ended up*
 * ("Andrew's Tesla in the driveway") and the newest sighting is the best answer to that. A car
 * crossing the lawn on its way to the driveway keeps both, in that order; a sighting that landed in
 * no zone at all changes nothing, so the last place it was actually seen still names the card.
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
        zones = anchor.zones.filterNot { it in zones } + zones,
    )
}

/**
 * Collapses each vehicle's visit into one moment. Walks oldest-first: a vehicle folds into the most
 * recent moment it [repeats]; a vehicle that repeats nothing starts a moment of its own if it moved
 * — a car somewhere else, a return hours later — and is dropped if it is [isStill], because a car
 * that was only ever seen sitting there did nothing worth a card. Everything else — a person, a
 * dog — passes through. Returns newest-first by start, like the API. Run this after [inZones], so
 * the street cars the zones reject never anchor a visit.
 */
fun List<MomentEvent>.mergeVehicleVisits(): List<MomentEvent> {
    val moments = ArrayList<MomentEvent>(size)
    for (event in sortedBy { it.startEpochSeconds }) {
        if (event.category != MomentCategory.VEHICLES) {
            moments += event
            continue
        }
        val index = moments.indexOfLast { event.repeats(it) }
        when {
            index >= 0 -> moments[index] = event.foldedInto(moments[index])
            !event.isStill() -> moments += event
        }
    }
    return moments.sortedByDescending { it.startEpochSeconds }
}
