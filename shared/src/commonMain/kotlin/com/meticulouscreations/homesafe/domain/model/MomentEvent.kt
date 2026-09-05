package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
    /** Frigate zone keys the object passed through, in the order it entered them; empty when none. */
    val zones: List<String> = emptyList(),
) {
    val category: MomentCategory get() = categoryForLabel(label)
    /** What the UI calls the camera this was seen on — see [cameraDisplayName]. */
    val cameraDisplayName: String get() = cameraDisplayName(cameraName)
    val isInProgress: Boolean get() = endEpochSeconds == null
    val durationSeconds: Double? get() = endEpochSeconds?.let { it - startEpochSeconds }
}

/**
 * Maps Frigate's COCO-style labels onto the feed's three filter chips. Labels outside these
 * sets (e.g. "package", "umbrella") still appear under "All Events"; only the chips they don't
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
    val title: String,
    /** "8:42 AM" */
    val timeLabel: String,
    /** "0:15", or null while still in progress. */
    val durationLabel: String?,
    /** "Today" / "Yesterday" / "Monday" — the group header the card sorts under. */
    val dateGroup: String,
    /** "Sep 2" — the group header's right-hand sub label. */
    val dateSubLabel: String,
    /** The pill on the card: the label, with any sub-label appended ("PERSON · ANDREW"). */
    val badgeLabel: String,
)

/**
 * [today] is passed in (not read from a clock) so grouping is deterministic in tests and so a
 * feed rendered at 11:59 PM doesn't relabel every card at midnight without a refresh.
 */
@OptIn(ExperimentalTime::class)
fun MomentEvent.present(today: LocalDate, timeZone: TimeZone = TimeZone.currentSystemDefault()): MomentPresentation {
    val local = Instant.fromEpochSeconds(startEpochSeconds.toLong()).toLocalDateTime(timeZone)
    val date = local.date
    val hour12 = (local.hour + 11) % 12 + 1
    val minute = local.minute.toString().padStart(2, '0')
    val timeLabel = "$hour12:$minute ${if (local.hour < 12) "AM" else "PM"}"

    val daysAgo = today.toEpochDays() - date.toEpochDays()
    val dateGroup = when {
        daysAgo <= 0 -> "Today"
        daysAgo == 1L -> "Yesterday"
        daysAgo < 7 -> date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        else -> "${date.month.shortName()} ${date.day}"
    }
    val dateSubLabel = "${date.month.shortName()} ${date.day}"

    // "Sarah's Tesla in the driveway", "Person on the front lawn", "Car detected": the subject is
    // the classifier's name for the object when it has one, and the place is the last zone the
    // object entered — where it ended up matters more than where it came from.
    val noun = label.lowercase().replaceFirstChar { it.uppercase() }
    val subject = subLabel?.takeIf { it.isNotBlank() }?.let { subLabelDisplayName(it) } ?: noun
    val place = zones.lastOrNull { it.isNotBlank() }
    val title = if (place != null) "$subject ${zonePhrase(place)}" else "$subject detected"
    val badge = subLabel?.takeIf { it.isNotBlank() }?.let { "$label · ${subLabelDisplayName(it)}" } ?: label

    return MomentPresentation(
        title = title,
        timeLabel = timeLabel,
        durationLabel = durationSeconds?.let { formatMomentDuration(it) },
        dateGroup = dateGroup,
        dateSubLabel = dateSubLabel,
        badgeLabel = badge,
    )
}

private fun Month.shortName(): String = name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

/** A filesystem-safe on-device filename for this event's downloaded clip, e.g. "homesafe_front_door_1788401732.mp4". */
fun MomentEvent.downloadFileName(): String {
    val safeCameraName = cameraName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    return "homesafe_${safeCameraName}_${startEpochSeconds.toLong()}.mp4"
}

/** "0:15" / "12:04" / "1:02:34" */
private fun formatMomentDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}
