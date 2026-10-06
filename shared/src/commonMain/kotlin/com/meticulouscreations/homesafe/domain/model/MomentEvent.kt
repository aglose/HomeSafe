package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.moments_date_short
import homesafe.shared.generated.resources.moments_date_today
import homesafe.shared.generated.resources.moments_date_yesterday
import homesafe.shared.generated.resources.moments_label_animal
import homesafe.shared.generated.resources.moments_label_bear
import homesafe.shared.generated.resources.moments_label_bicycle
import homesafe.shared.generated.resources.moments_label_bird
import homesafe.shared.generated.resources.moments_label_boat
import homesafe.shared.generated.resources.moments_label_bus
import homesafe.shared.generated.resources.moments_label_car
import homesafe.shared.generated.resources.moments_label_cat
import homesafe.shared.generated.resources.moments_label_cow
import homesafe.shared.generated.resources.moments_label_deer
import homesafe.shared.generated.resources.moments_label_dog
import homesafe.shared.generated.resources.moments_label_face
import homesafe.shared.generated.resources.moments_label_fox
import homesafe.shared.generated.resources.moments_label_horse
import homesafe.shared.generated.resources.moments_label_license_plate
import homesafe.shared.generated.resources.moments_label_motorcycle
import homesafe.shared.generated.resources.moments_label_package
import homesafe.shared.generated.resources.moments_label_person
import homesafe.shared.generated.resources.moments_label_rabbit
import homesafe.shared.generated.resources.moments_label_sheep
import homesafe.shared.generated.resources.moments_label_squirrel
import homesafe.shared.generated.resources.moments_label_train
import homesafe.shared.generated.resources.moments_label_truck
import homesafe.shared.generated.resources.moments_label_vehicle
import homesafe.shared.generated.resources.moments_month_apr
import homesafe.shared.generated.resources.moments_month_aug
import homesafe.shared.generated.resources.moments_month_dec
import homesafe.shared.generated.resources.moments_month_feb
import homesafe.shared.generated.resources.moments_month_jan
import homesafe.shared.generated.resources.moments_month_jul
import homesafe.shared.generated.resources.moments_month_jun
import homesafe.shared.generated.resources.moments_month_mar
import homesafe.shared.generated.resources.moments_month_may
import homesafe.shared.generated.resources.moments_month_nov
import homesafe.shared.generated.resources.moments_month_oct
import homesafe.shared.generated.resources.moments_month_sep
import homesafe.shared.generated.resources.moments_seen_last_seen
import homesafe.shared.generated.resources.moments_seen_still_there
import homesafe.shared.generated.resources.moments_title_detected
import homesafe.shared.generated.resources.moments_title_in_zone
import homesafe.shared.generated.resources.moments_title_on_zone
import homesafe.shared.generated.resources.moments_weekday_friday
import homesafe.shared.generated.resources.moments_weekday_monday
import homesafe.shared.generated.resources.moments_weekday_saturday
import homesafe.shared.generated.resources.moments_weekday_sunday
import homesafe.shared.generated.resources.moments_weekday_thursday
import homesafe.shared.generated.resources.moments_weekday_tuesday
import homesafe.shared.generated.resources.moments_weekday_wednesday
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class MomentCategory {
    ALL,
    PEOPLE,
    VEHICLES,
    ANIMALS,
}

/** A detection Frigate recorded (person, vehicle, animal, ...) — the facts, with no display strings. */
data class MomentEvent(
    val id: String,
    val cameraName: String,
    /** Frigate's object label, e.g. "person", "car", "dog". */
    val label: String,
    /** A refinement Frigate attached, e.g. a recognized face or plate; null for most events. */
    val subLabel: String?,
    val startEpochSeconds: Double,
    /** Null while the detection is still in progress. */
    val endEpochSeconds: Double?,
    val topScore: Double?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
    /**
     * Frigate zone keys the object passed through, in the order it entered them, and for a folded
     * visit with the most recent sighting's last; empty when none.
     * Frigate only tags a zone whose object filter accepts the label, so once the feed has run
     * [inZones] this holds the zones that *wanted* the object, not merely the ones it crossed.
     */
    val zones: List<String> = emptyList(),
    /**
     * Where the object went, as bottom-centre points in detect-frame fractions, oldest first.
     * Empty when Frigate reported no path (an API-created event, say).
     */
    val pathPoints: List<MaskPoint> = emptyList(),
    /**
     * When each of [pathPoints] was recorded, in epoch seconds: Frigate adds a point when the
     * object has travelled ~5% of the frame, so the last one is when it last went anywhere. The
     * same length as [pathPoints], or empty when the times aren't known (a row cached by an older
     * build, a path that is only the best frame's box). What [isParked] reads.
     */
    val pathEpochSeconds: List<Double> = emptyList(),
    /** The best frame's box; null for an API-created event. What [mergeVehicleVisits] matches sightings on. */
    val box: DetectionBox? = null,
    /** How sure the classifier was of [subLabel]; null when Frigate didn't say. */
    val subLabelScore: Double? = null,
    /**
     * How many Frigate events this stands for: 1 normally, more once [mergeVehicleVisits] folded a
     * vehicle's re-detections at one spot into it. The id, clip and thumbnail are the first sighting's.
     */
    val sightings: Int = 1,
) {
    val category: MomentCategory get() = categoryForLabel(label)

    /** What the UI calls the camera this was seen on — see [cameraDisplayName]. */
    val cameraDisplayName: String get() = cameraDisplayName(cameraName)
    val isInProgress: Boolean get() = endEpochSeconds == null

    /** Frigate put a name to it: a known car or a known face. Its "unknown" marker doesn't count. */
    val isRecognized: Boolean
        get() = !subLabel.isNullOrBlank() && !subLabel.equals(FaceLibrary.UNKNOWN_GUESS, ignoreCase = true)
    val durationSeconds: Double? get() = endEpochSeconds?.let { it - startEpochSeconds }
}

/**
 * Maps Frigate's COCO-style labels onto the feed's three filter chips. Labels outside these
 * sets (e.g. "package", "umbrella") still appear under "All events"; only the type filters they don't
 * belong to hide them.
 */
fun categoryForLabel(label: String): MomentCategory = when (label.lowercase()) {
    "person" -> MomentCategory.PEOPLE
    "car", "truck", "bus", "motorcycle", "bicycle", "boat", "train", "vehicle" -> MomentCategory.VEHICLES
    "dog", "cat", "bird", "horse", "sheep", "cow", "bear", "deer", "rabbit", "squirrel", "fox", "animal" -> MomentCategory.ANIMALS
    else -> MomentCategory.ALL
}

/** How a [MomentEvent] reads in the feed. Derived, not stored, so a label change never leaves stale copy behind. */
data class MomentPresentation(
    /** "Person on the front lawn" (see [detectionTitle]) */
    val title: UiText,
    /** "8:42 AM" */
    val timeLabel: UiText,
    /** "0:15", or null while still in progress. */
    val durationLabel: String?,
    /** "Today" / "Yesterday" / "Monday" — the group header the card sorts under. */
    val dateGroup: UiText,
    /** "Sep 2" — the group header's right-hand sub label. */
    val dateSubLabel: UiText,
    /**
     * The pill on the card: what Frigate's label is called ("Person", "Car"; the card shows it in
     * capitals). A recognised name is left to the title, which already leads with it; the pill
     * used to repeat it ("CAR · ANDREWS TESLA").
     */
    val badgeLabel: UiText,
    /**
     * "Front Yard · Sidewalk, Front lawn" — the camera, then every zone the object was in, in
     * the order it reached them; just the camera when it was in none.
     */
    val locationLabel: UiText,
    /**
     * "Seen 9 times · still there" / "Seen 3 times · last seen 2:24 PM" for a moment that stands
     * for several sightings of the same parked vehicle; null for a single sighting.
     */
    val sightingsLabel: UiText?,
    /**
     * "5 clips" / "6 sightings" on an entry that folds several detections together (see
     * [MomentVisit.present]), where it doubles as the control that lists them; null for one detection.
     */
    val clipCountLabel: UiText? = null,
)

/** "Car in the driveway · 8:42 PM": the moment in a line, as a prompt or a dialog names the one it's about. */
val MomentPresentation.summary: UiText
    get() = UiText.Joined(listOf(title, timeLabel), UiText.of(Res.string.common_dot_separator))

/** "8:42 PM · Front Yard · Driveway": the card's line under its title. */
val MomentPresentation.whenAndWhere: UiText
    get() = UiText.Joined(listOf(timeLabel, locationLabel), UiText.of(Res.string.common_dot_separator))

/**
 * [today] is passed in (not read from a clock) so grouping is deterministic in tests and so a
 * feed rendered at 11:59 PM doesn't relabel every card at midnight without a refresh.
 */
@OptIn(ExperimentalTime::class)
fun MomentEvent.present(today: LocalDate, timeZone: TimeZone = TimeZone.currentSystemDefault()): MomentPresentation {
    val date = Instant.fromEpochSeconds(startEpochSeconds.toLong()).toLocalDateTime(timeZone).date
    val timeLabel = clockLabel(startEpochSeconds, timeZone)

    val daysAgo = today.toEpochDays() - date.toEpochDays()
    val dateGroup = when {
        daysAgo <= 0 -> UiText.of(Res.string.moments_date_today)
        daysAgo == 1L -> UiText.of(Res.string.moments_date_yesterday)
        daysAgo < 7 -> UiText.of(date.dayOfWeek.displayName)
        else -> date.shortLabel()
    }
    val dateSubLabel = date.shortLabel()

    // The subject is the classifier's name for the object when it has one. A placeholder the
    // classifier or the face model files things under ("none", "unknown") is not a name, and the
    // card never offers one as if it were.
    val subject = subLabel?.takeIf { isFamiliar }?.let { subLabelDisplayName(it).asUiText() } ?: labelName(label)
    val places = zones.filter { it.isNotBlank() }
    val sightingsLabel = when {
        sightings <= 1 -> null
        endEpochSeconds == null -> UiText.plural(Res.plurals.moments_seen_still_there, sightings)
        else -> UiText.plural(Res.plurals.moments_seen_last_seen, sightings, sightings, clockLabel(endEpochSeconds, timeZone))
    }

    return MomentPresentation(
        title = detectionTitle(subject, places.lastOrNull()),
        timeLabel = timeLabel.asUiText(),
        durationLabel = durationSeconds?.let { formatMomentDuration(it) },
        dateGroup = dateGroup,
        dateSubLabel = dateSubLabel,
        badgeLabel = labelName(label),
        locationLabel = locationLabel(cameraDisplayName, places),
        sightingsLabel = sightingsLabel,
    )
}

/**
 * "Sarah's Tesla in the driveway", "Person on the front lawn", "Car detected": [subject] where it
 * ended up — the zone [place] it last entered, since where it ended up matters more than where
 * it came from — or, when it was in none, just that it was seen. Enclosed places take "in the",
 * surfaces "on the" (see [isEnclosedZone]); each is a whole sentence of its own to translate.
 */
fun detectionTitle(subject: UiText, place: String?): UiText = when {
    place == null -> UiText.of(Res.string.moments_title_detected, subject)
    isEnclosedZone(place) -> UiText.of(Res.string.moments_title_in_zone, subject, zoneDisplayName(place))
    else -> UiText.of(Res.string.moments_title_on_zone, subject, zoneDisplayName(place))
}

/**
 * What the app calls Frigate's object [label]: "Person" for `person`, "Car" for `car`. A label it
 * has no word for is shown as Frigate wrote it, tidied up ("Umbrella", "Shopping cart"), since
 * that is data the app can't translate.
 */
fun labelName(label: String): UiText =
    LABEL_NAMES[label.lowercase()]?.let { UiText.of(it) }
        ?: label.split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { it.lowercase() }.replaceFirstChar(Char::uppercase).asUiText()

private val LABEL_NAMES: Map<String, StringResource> = mapOf(
    "person" to Res.string.moments_label_person,
    "car" to Res.string.moments_label_car,
    "truck" to Res.string.moments_label_truck,
    "bus" to Res.string.moments_label_bus,
    "motorcycle" to Res.string.moments_label_motorcycle,
    "bicycle" to Res.string.moments_label_bicycle,
    "boat" to Res.string.moments_label_boat,
    "train" to Res.string.moments_label_train,
    "vehicle" to Res.string.moments_label_vehicle,
    "dog" to Res.string.moments_label_dog,
    "cat" to Res.string.moments_label_cat,
    "bird" to Res.string.moments_label_bird,
    "horse" to Res.string.moments_label_horse,
    "sheep" to Res.string.moments_label_sheep,
    "cow" to Res.string.moments_label_cow,
    "bear" to Res.string.moments_label_bear,
    "deer" to Res.string.moments_label_deer,
    "rabbit" to Res.string.moments_label_rabbit,
    "squirrel" to Res.string.moments_label_squirrel,
    "fox" to Res.string.moments_label_fox,
    "animal" to Res.string.moments_label_animal,
    "package" to Res.string.moments_label_package,
    "face" to Res.string.moments_label_face,
    "license_plate" to Res.string.moments_label_license_plate,
)

/**
 * "Front Yard · Sidewalk, Front lawn": the camera ([cameraDisplayName]), then [zones] — Frigate
 * zone keys, in the order given — capitalised; the camera alone when there are none.
 */
internal fun locationLabel(cameraDisplayName: String, zones: List<String>): UiText {
    val camera = cameraDisplayName.asUiText()
    if (zones.isEmpty()) return camera
    val places = zones.map { zoneDisplayName(it).replaceFirstChar(Char::uppercase).asUiText() }
    return UiText.Joined(listOf(camera, UiText.Joined(places, UiText.of(Res.string.common_list_separator))), UiText.of(Res.string.common_dot_separator))
}

/** "8:42 AM" — shared with the home screen's in-view strip, which times its cards the same way. */
@OptIn(ExperimentalTime::class)
internal fun clockLabel(epochSeconds: Double, timeZone: TimeZone): String {
    val local = Instant.fromEpochSeconds(epochSeconds.toLong()).toLocalDateTime(timeZone)
    val hour12 = (local.hour + 11) % 12 + 1
    val minute = local.minute.toString().padStart(2, '0')
    return "$hour12:$minute ${if (local.hour < 12) "AM" else "PM"}"
}

private val DayOfWeek.displayName: StringResource
    get() = when (this) {
        DayOfWeek.MONDAY -> Res.string.moments_weekday_monday
        DayOfWeek.TUESDAY -> Res.string.moments_weekday_tuesday
        DayOfWeek.WEDNESDAY -> Res.string.moments_weekday_wednesday
        DayOfWeek.THURSDAY -> Res.string.moments_weekday_thursday
        DayOfWeek.FRIDAY -> Res.string.moments_weekday_friday
        DayOfWeek.SATURDAY -> Res.string.moments_weekday_saturday
        DayOfWeek.SUNDAY -> Res.string.moments_weekday_sunday
    }

private val Month.shortName: StringResource
    get() = when (this) {
        Month.JANUARY -> Res.string.moments_month_jan
        Month.FEBRUARY -> Res.string.moments_month_feb
        Month.MARCH -> Res.string.moments_month_mar
        Month.APRIL -> Res.string.moments_month_apr
        Month.MAY -> Res.string.moments_month_may
        Month.JUNE -> Res.string.moments_month_jun
        Month.JULY -> Res.string.moments_month_jul
        Month.AUGUST -> Res.string.moments_month_aug
        Month.SEPTEMBER -> Res.string.moments_month_sep
        Month.OCTOBER -> Res.string.moments_month_oct
        Month.NOVEMBER -> Res.string.moments_month_nov
        Month.DECEMBER -> Res.string.moments_month_dec
    }

/** "Sep 2": how the feed names a day, in its headers and on the chip that opens it at one. */
fun LocalDate.shortLabel(): UiText = UiText.of(Res.string.moments_date_short, UiText.of(month.shortName), day)

/** A filesystem-safe on-device filename for this event's downloaded clip, e.g. "homesafe_front_door_1788401732.mp4". */
fun MomentEvent.downloadFileName(): String {
    val safeCameraName = cameraName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    return "homesafe_${safeCameraName}_${startEpochSeconds.toLong()}.mp4"
}

/** "0:15" / "12:04" / "1:02:34" */
private fun formatMomentDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}
