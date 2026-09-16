package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What the home screen's "In view now" strip is built from: the vehicles the cameras can see
 * sitting where they were left — is Sarah's Tesla in the driveway, is there a car in front of the
 * house that shouldn't be — answered at a glance instead of by reading the Moments feed backwards.
 *
 * This is the other half of [mergeVehicleVisits], and deliberately not the same list. The feed
 * asks "what happened?", so a vehicle that only ever sat there is no moment at all and gets
 * dropped. This asks "what is there *now*?", where a vehicle that only ever sat there is the
 * entire answer. Both fold the same way — the same vehicle, on the same camera, on an overlapping
 * box, within [VehicleVisits.PARKED_GAP_SECONDS] — because that rule is what makes a parked car's
 * endless re-detections one thing rather than forty.
 *
 * A stay is reported when its *latest* sighting is [isStill]: a car on its way in or out is
 * moving, so it stops being furniture for as long as it is driving, and comes back the moment it
 * is re-detected parked. And it is only claimed to be in view while Frigate keeps seeing it —
 * [AT_REST_SECONDS] since the last sighting ended, or an event still in progress. Beyond that the
 * app has no evidence either way and says nothing rather than something stale.
 */
object StationaryObjects {
    /**
     * How long a stay survives its last sighting. Frigate re-detects a parked car every few
     * minutes as the detector's box on it flickers (see [VehicleVisits]), so a car that has gone
     * this long unseen is a car the app can no longer vouch for — the same window that decides
     * whether two sightings are the same parked car in the first place.
     */
    const val AT_REST_SECONDS = VehicleVisits.PARKED_GAP_SECONDS

    /** Past this since the last sighting ended, the card says when the car was last actually seen. */
    const val FRESH_SECONDS = 5.0 * 60.0
}

/**
 * One vehicle, parked somewhere a camera can see, as the home screen shows it. Fields come from
 * the two ends of the stay: [thumbnailEventId], [label], [cameraName] and [zones] from the most
 * recent sighting (where the car is *now*), [firstSeenEpochSeconds] from the first (when it got
 * there), and [subLabel] from the best-scored sighting that put a name to it — the classifier
 * recognizes a car on some frames and not others.
 */
data class StationaryObject(
    /** The most recent sighting's event id: its thumbnail is the freshest crop of the car in place. */
    val thumbnailEventId: String,
    val cameraName: String,
    /** Frigate's object label, e.g. "car", "truck". */
    val label: String,
    /** The classifier's name for it ("sarahs_tesla"), or null for a car it doesn't know. */
    val subLabel: String?,
    /** Where it is, in the order it got there; the last is where it ended up. */
    val zones: List<String>,
    /** When the stay's first sighting started — bounded below by how far back the fetch reached (see [sinceIsKnown]). */
    val firstSeenEpochSeconds: Double,
    /** When the last sighting ended, or its start while it is still in progress. */
    val lastSeenEpochSeconds: Double,
    /**
     * A camera has it in sight as good as now: the sighting is still in progress, or it ended
     * within [StationaryObjects.FRESH_SECONDS]. When it is false the card says when the car was
     * last actually seen, rather than letting a silent minute pass for a confirmed one.
     */
    val seenRecently: Boolean,
    /** How many Frigate events this stay folds — a parked car collects one every few minutes. */
    val sightings: Int,
    /**
     * False when the stay's first sighting is the oldest detection the fetch reached, so the car
     * was already there before the app was looking and "since" would be a guess.
     */
    val sinceIsKnown: Boolean,
) {
    /** Frigate put a name to it: one of the household's cars, rather than any car. */
    val isRecognized: Boolean
        get() = !subLabel.isNullOrBlank() && !subLabel.equals(FaceLibrary.UNKNOWN_GUESS, ignoreCase = true)
}

/** How a [StationaryObject] reads on its card. Derived, like [MomentPresentation], never stored. */
data class StationaryObjectPresentation(
    /** "Sarah's Tesla", or just "Car" for one the classifier doesn't know. */
    val title: String,
    /** "Driveway" — where it is; the camera's name when it is in no zone. */
    val placeLabel: String,
    /** "since 10:04 AM" / "since 6:12 PM yesterday", or null when the fetch didn't reach its arrival. */
    val sinceLabel: String?,
    /** "last seen 4:51 PM" once the sighting is no longer fresh; null while Frigate is still seeing it. */
    val lastSeenLabel: String?,
)

/**
 * [today] is passed in rather than read from a clock for the same reason [MomentEvent.present]
 * does it: a strip rendered at 11:59 PM shouldn't relabel itself at midnight without a refresh.
 */
@OptIn(ExperimentalTime::class)
fun StationaryObject.present(today: LocalDate, timeZone: TimeZone = TimeZone.currentSystemDefault()): StationaryObjectPresentation {
    val title = subLabel?.takeIf { isRecognized }?.let { subLabelDisplayName(it) }
        ?: label.lowercase().replaceFirstChar { it.uppercase() }
    val place = zones.lastOrNull { it.isNotBlank() }
    return StationaryObjectPresentation(
        title = title,
        placeLabel = place?.let { zoneDisplayName(it).replaceFirstChar(Char::uppercase) } ?: cameraDisplayName(cameraName),
        sinceLabel = if (sinceIsKnown) "since ${dayQualifiedClockLabel(firstSeenEpochSeconds, today, timeZone)}" else null,
        lastSeenLabel = if (seenRecently) null else "last seen ${clockLabel(lastSeenEpochSeconds, timeZone)}",
    )
}

/** "6:12 PM" for today, "6:12 PM yesterday" for the night before, "6:12 PM Sep 14" for anything older. */
@OptIn(ExperimentalTime::class)
private fun dayQualifiedClockLabel(epochSeconds: Double, today: LocalDate, timeZone: TimeZone): String {
    val date = Instant.fromEpochSeconds(epochSeconds.toLong()).toLocalDateTime(timeZone).date
    val clock = clockLabel(epochSeconds, timeZone)
    return when (today.toEpochDays() - date.toEpochDays()) {
        0L -> clock
        1L -> "$clock yesterday"
        else -> "$clock ${date.shortLabel()}"
    }
}

/**
 * The vehicles sitting still in view at [nowEpochSeconds], newest arrival first — a car that has
 * just pulled in is the news, and the one that has been there since this morning is where it was.
 *
 * Walks oldest-first, folding each sighting into the stay it [MomentEvent.repeats] exactly as the
 * feed does, but keeping the stays a still sighting *starts*: a car parked since before the app
 * was looking has no arrival to anchor to, and it is precisely the car the strip exists to show.
 * Run this after [inZones], so the cars out on the street — which the zones reject — never appear
 * as parked in the yard.
 *
 * [oldestFetchedEpochSeconds] is how far back the detections behind this list actually reach:
 * a stay that starts there was already under way, so its card says where the car is without
 * claiming to know since when. Leave it out when the list is known to be complete.
 */
fun List<MomentEvent>.stationaryObjects(
    nowEpochSeconds: Double,
    oldestFetchedEpochSeconds: Double? = null,
): List<StationaryObject> {
    val stays = ArrayList<Stay>()
    for (event in sortedBy { it.startEpochSeconds }) {
        if (event.category != MomentCategory.VEHICLES) continue
        val index = stays.indexOfLast { event.repeats(it.latest) }
        if (index >= 0) {
            stays[index] = stays[index].folding(event)
        } else {
            stays += Stay(first = event, latest = event, named = event.takeIf { it.isRecognized })
        }
    }
    return stays
        .filter { it.isAtRest(nowEpochSeconds) }
        .map { it.toStationaryObject(nowEpochSeconds, oldestFetchedEpochSeconds) }
        .sortedByDescending { it.firstSeenEpochSeconds }
}

/** One vehicle's run of sightings at one spot: where it started, where it is now, and its best name. */
private data class Stay(
    val first: MomentEvent,
    val latest: MomentEvent,
    /** The best-scored sighting the classifier put a name to, or null while it hasn't. */
    val named: MomentEvent?,
    val sightings: Int = first.sightings,
) {
    /** This stay with one more sighting of the same vehicle at the same spot folded into it. */
    fun folding(event: MomentEvent): Stay = copy(
        latest = event,
        named = listOfNotNull(named, event.takeIf { it.isRecognized }).maxByOrNull { it.subLabelScore ?: 0.0 },
        sightings = sightings + event.sightings,
    )

    /** Parked, and seen recently enough to still be claimed: in progress, or ended within [StationaryObjects.AT_REST_SECONDS]. */
    fun isAtRest(nowEpochSeconds: Double): Boolean {
        if (!latest.isStill()) return false
        val end = latest.endEpochSeconds ?: return true
        return nowEpochSeconds - end <= StationaryObjects.AT_REST_SECONDS
    }

    fun toStationaryObject(nowEpochSeconds: Double, oldestFetchedEpochSeconds: Double?): StationaryObject = StationaryObject(
        thumbnailEventId = latest.id,
        cameraName = latest.cameraName,
        label = latest.label,
        subLabel = named?.subLabel,
        zones = latest.zones,
        firstSeenEpochSeconds = first.startEpochSeconds,
        lastSeenEpochSeconds = latest.endEpochSeconds ?: latest.startEpochSeconds,
        seenRecently = latest.endEpochSeconds?.let { nowEpochSeconds - it <= StationaryObjects.FRESH_SECONDS } ?: true,
        sightings = sightings,
        sinceIsKnown = oldestFetchedEpochSeconds == null ||
            first.startEpochSeconds > oldestFetchedEpochSeconds + FETCH_EDGE_MARGIN_SECONDS,
    )
}

/**
 * How far inside the fetched window a stay must start for its arrival to count as observed. A
 * page boundary lands mid-second, and a car's first sighting a minute inside it is one the app
 * genuinely watched arrive.
 */
private const val FETCH_EDGE_MARGIN_SECONDS = 60.0
