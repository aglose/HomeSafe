package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Folds the feed's detections into what a person would call one thing happening.
 *
 * Why (seen on the Moments tab, 2026-09-22): someone pottering in the backyard for a minute came
 * out as five identical "Person detected · Backyard" cards between 6:55 and 6:56 PM, because
 * Frigate ends a person's event whenever it loses them behind a chair and starts a new one when
 * they step out again. And the household's own cars, which the classifier names, drowned the
 * evening: Andrew's Tesla had eight cards between 5:51 and 6:30 PM for pulling in, moving and
 * heading out again. [mergeVehicleVisits] already folds one vehicle's re-detections at one spot;
 * this folds what it leaves.
 *
 * - A **visit** is detections of one kind (Frigate's label) on one camera, each starting within
 *   [VISIT_GAP_SECONDS] of the last one ending. Short, so a second visit ten minutes later is a
 *   card of its own.
 * - A **routine** stretch is a household car's comings and goings: every sighting of one named car
 *   ([isHouseholdCar]), on any camera, within [ROUTINE_GAP_SECONDS] of the one before. Long,
 *   because the point is to get the family's cars out of the way, not to account for them.
 */
object MomentVisits {
    /** A detection starting this soon after the last one of its kind on its camera ended continues that visit. */
    const val VISIT_GAP_SECONDS = 3.0 * 60.0

    /** A household car seen again this soon after its last sighting is part of the same routine stretch. */
    const val ROUTINE_GAP_SECONDS = 60.0 * 60.0

    /**
     * Sub-labels that are not a name: Frigate's reserved classifier category (shown as "Not ours"
     * in the labelling screen), the key that name would slug to, and Frigate's unknown-face marker.
     */
    internal val NOT_A_NAME = setOf(ClassifierDataset.NONE_CATEGORY, "not_ours", FaceLibrary.UNKNOWN_GUESS)
}

/** Frigate put a name to it — a registered face, a classified car, a known plate — and not one of the placeholders in [MomentVisits.NOT_A_NAME]. */
val MomentEvent.isFamiliar: Boolean
    get() = !subLabel.isNullOrBlank() && subLabel.lowercase() !in MomentVisits.NOT_A_NAME

/** One of the household's cars: a vehicle the classifier put a name to. */
val MomentEvent.isHouseholdCar: Boolean get() = category == MomentCategory.VEHICLES && isFamiliar

enum class VisitKind {
    /** One detection, drawn as the ordinary card. */
    SINGLE,

    /** Several detections of one kind on one camera close together: one card that can open onto its clips. */
    VISIT,

    /** A household car's comings and goings: a quiet row that can open onto its sightings. */
    ROUTINE,
}

/**
 * One entry in the feed: a detection, or several folded together. [events] are oldest first.
 * Never empty.
 */
data class MomentVisit(val kind: VisitKind, val events: List<MomentEvent>) {
    init {
        require(events.isNotEmpty()) { "a visit needs at least one detection" }
    }

    /** Stable while the visit grows newer clips: the first detection's id. What the feed keys the entry on. */
    val key: String get() = events.first().id

    /**
     * What a tap plays and whose thumbnail stands for the entry: the first detection with a clip,
     * since how a visit started — someone arriving, a car pulling in — is what the card is about.
     */
    val lead: MomentEvent get() = events.firstOrNull { it.hasClip } ?: events.first()

    val startEpochSeconds: Double get() = events.first().startEpochSeconds

    /** When the last detection ended; null while any of them is still in progress. */
    val endEpochSeconds: Double?
        get() = if (events.any { it.isInProgress }) null else events.maxOf { it.endEpochSeconds ?: it.startEpochSeconds }

    /** When the newest detection started: where the entry sorts in a newest-first feed. */
    val latestStartEpochSeconds: Double get() = events.maxOf { it.startEpochSeconds }

    /** Any detection in it was put a name to. Frigate names a face a few seconds in, so one named clip vouches for the visit. */
    val isFamiliar: Boolean get() = events.any { it.isFamiliar }

    /** The best-scored name among its detections, when any has one. */
    val subLabel: String? get() = events.filter { it.isFamiliar }.maxByOrNull { it.subLabelScore ?: 0.0 }?.subLabel
}

/**
 * The feed's detections as visits, newest first by each visit's latest detection. Walks
 * oldest-first: a household car joins the most recent stretch of the same car still within
 * [MomentVisits.ROUTINE_GAP_SECONDS]; anything else joins the most recent visit on its camera with
 * its label that it follows within [MomentVisits.VISIT_GAP_SECONDS] — unless both carry different
 * names, because Andrew and Sarah crossing the yard a minute apart are two people. Whatever joins
 * nothing starts an entry of its own.
 *
 * Run it after the category and camera filters, so the gap is judged between the detections the
 * reader actually sees, and before the "unfamiliar only" one ([MomentVisit.isFamiliar]), so that
 * a stranger's visit isn't split around the clip in which Frigate happened to recognise them.
 */
fun List<MomentEvent>.groupIntoVisits(): List<MomentVisit> {
    val routines = ArrayList<MutableList<MomentEvent>>()
    val visits = ArrayList<MutableList<MomentEvent>>()
    for (event in sortedBy { it.startEpochSeconds }) {
        if (event.isHouseholdCar) {
            val car = event.subLabel?.lowercase()
            val open = routines.lastOrNull { run ->
                run.last().subLabel?.lowercase() == car && event.follows(run, MomentVisits.ROUTINE_GAP_SECONDS)
            }
            if (open != null) open += event else routines += mutableListOf(event)
            continue
        }
        val open = visits.lastOrNull { run ->
            val first = run.first()
            first.cameraName == event.cameraName &&
                first.label.equals(event.label, ignoreCase = true) &&
                event.follows(run, MomentVisits.VISIT_GAP_SECONDS) &&
                event.namedAlike(run)
        }
        if (open != null) open += event else visits += mutableListOf(event)
    }
    return (
        routines.map { MomentVisit(if (it.size > 1) VisitKind.ROUTINE else VisitKind.SINGLE, it) } +
            visits.map { MomentVisit(if (it.size > 1) VisitKind.VISIT else VisitKind.SINGLE, it) }
        ).sortedByDescending { it.latestStartEpochSeconds }
}

/** Starts within [gapSeconds] of the latest end in [run]; a detection in [run] still in progress counts as ongoing. */
private fun MomentEvent.follows(run: List<MomentEvent>, gapSeconds: Double): Boolean {
    if (run.any { it.isInProgress }) return true
    val end = run.maxOf { it.endEpochSeconds ?: it.startEpochSeconds }
    return startEpochSeconds - end <= gapSeconds
}

/** An unnamed detection fits any visit; a named one only a visit with no name yet or the same one. */
private fun MomentEvent.namedAlike(run: List<MomentEvent>): Boolean {
    if (!isFamiliar) return true
    return run.none { it.isFamiliar && !it.subLabel.equals(subLabel, ignoreCase = true) }
}

/**
 * How a folded entry reads. A [VisitKind.SINGLE] reads exactly as its detection does. A visit is
 * titled like its best-named detection, placed where it ended up, and timed as a range
 * ("6:55–6:56 PM") with a count of its clips. A routine stretch says what the car did rather than
 * where: "Andrew's Tesla came and went 6×".
 */
fun MomentVisit.present(today: LocalDate, timeZone: TimeZone = TimeZone.currentSystemDefault()): MomentPresentation {
    val leadPresentation = lead.present(today, timeZone)
    if (kind == VisitKind.SINGLE) return leadPresentation

    val name = subLabel
    val range = clockRangeLabel(startEpochSeconds, endEpochSeconds, timeZone)
    return when (kind) {
        VisitKind.ROUTINE -> {
            val cameras = events.map { it.cameraDisplayName }.distinct().joinToString(", ")
            leadPresentation.copy(
                title = "${subLabelDisplayName(name ?: lead.label)} came and went ${events.size}×",
                timeLabel = range,
                durationLabel = null,
                locationLabel = cameras,
                sightingsLabel = null,
                clipCountLabel = "${events.size} sightings",
            )
        }

        else -> {
            val subject = name?.let { subLabelDisplayName(it) } ?: lead.label.lowercase().replaceFirstChar { it.uppercase() }
            val zones = events.flatMap { it.zones }.filter { it.isNotBlank() }
            val place = zones.lastOrNull()
            val places = zones.distinct().joinToString(", ") { zoneDisplayName(it).replaceFirstChar(Char::uppercase) }
            leadPresentation.copy(
                title = if (place != null) "$subject ${zonePhrase(place)}" else "$subject detected",
                timeLabel = range,
                locationLabel = if (places.isEmpty()) lead.cameraDisplayName else "${lead.cameraDisplayName} · $places",
                sightingsLabel = null,
                clipCountLabel = "${events.size} clips",
            )
        }
    }
}

/**
 * "6:55–6:56 PM", "11:58 AM–12:04 PM", "6:55 PM" when both ends fall in the same minute, and
 * "Since 6:55 PM" while the last detection is still going.
 */
internal fun clockRangeLabel(startEpochSeconds: Double, endEpochSeconds: Double?, timeZone: TimeZone): String {
    val start = clockLabel(startEpochSeconds, timeZone)
    if (endEpochSeconds == null) return "Since $start"
    val end = clockLabel(endEpochSeconds, timeZone)
    if (start == end) return start
    val startMeridiem = start.takeLast(2)
    return if (startMeridiem == end.takeLast(2)) "${start.dropLast(3)}–$end" else "$start–$end"
}
