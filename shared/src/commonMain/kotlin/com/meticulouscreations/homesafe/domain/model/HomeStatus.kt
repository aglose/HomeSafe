package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The two lines at the top of the home page, which say what is going on before anything is
 * scrolled or tapped: [headline] is the latest detection — "Person on the front lawn" while it is
 * news, "All quiet since 6:56 PM" once it isn't — and [details] the rest of the picture, "3 min ago
 * · 4 cameras on · Everyone home".
 *
 * Either is null while what it is built from hasn't loaded, so the page can hold the line's place
 * rather than print a placeholder that is then swapped out. Derived, like [MomentPresentation],
 * never stored.
 */
data class HomeStatus(
    val headline: String?,
    val details: String?,
)

/**
 * The home page's summary, out of what the app already has: [latestMoment] (the newest detection
 * on any camera, null when there is none — meaningful only once [momentsLoaded]), the server's
 * [cameras] (null until the cache has answered), and the relay's [presence] ([HouseholdPresence.EMPTY]
 * until it has).
 *
 * [nowEpochSeconds] and [today] are passed in rather than read from a clock for the same reason
 * [MomentEvent.present] does it, and because "3 min ago" has to be recomputed as time passes by
 * whoever holds the clock.
 */
fun homeStatus(
    latestMoment: MomentEvent?,
    momentsLoaded: Boolean,
    cameras: List<Camera>?,
    presence: HouseholdPresence,
    nowEpochSeconds: Double,
    today: LocalDate,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): HomeStatus {
    // A detection still in progress but begun long ago is a car Frigate is still tracking where it
    // stopped, not news; it is the quiet it has been since.
    val recent = latestMoment?.takeIf { nowEpochSeconds - it.lastActiveEpochSeconds < RECENT_ACTIVITY_SECONDS }
    val headline = when {
        !momentsLoaded -> null
        recent != null -> recent.summaryTitle()
        latestMoment == null -> "All quiet"
        else -> "All quiet since ${dayQualifiedClockLabel(latestMoment.lastActiveEpochSeconds, today, timeZone)}"
    }
    val parts = listOfNotNull(
        recent?.let { activityAgeLabel(it, nowEpochSeconds) },
        cameras?.let(::camerasOnLabel),
        presenceLabel(presence),
    )
    return HomeStatus(
        headline = headline,
        details = parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.replaceFirstChar(Char::uppercase),
    )
}

/**
 * "Everyone home" / "1 of 2 home" among the phones the relay counts; "You're away" when this phone
 * is, whatever the others say, since that is what its owner most wants confirmed; "House empty"
 * once the relay has declared it so. Null until the relay has answered, or when no phone counts.
 */
internal fun presenceLabel(presence: HouseholdPresence): String? {
    if (presence.everyoneAway) return "House empty"
    if (presence.thisDevice?.away == true) return "You're away"
    val counting = presence.countingDevices
    if (counting.isEmpty()) return null
    val home = counting.count { !it.away }
    return if (home == counting.size) "Everyone home" else "$home of ${counting.size} home"
}

/**
 * "4 cameras on" / "3 of 4 cameras on": what the server has switched on, which is what each card's
 * own pill reports once it is playing. Null for a server with no cameras, which the grid below
 * already says in so many words.
 */
internal fun camerasOnLabel(cameras: List<Camera>): String? {
    if (cameras.isEmpty()) return null
    val on = cameras.count { it.enabled }
    val noun = if (cameras.size == 1) "camera" else "cameras"
    return if (on == cameras.size) "${cameras.size} $noun on" else "$on of ${cameras.size} $noun on"
}

/** "Person on the front lawn" / "Sarah's Tesla in the driveway" / "Person at Backyard" (the camera, when it was in no zone). */
private fun MomentEvent.summaryTitle(): String {
    val subject = subLabel?.takeIf { isRecognized }?.let { subLabelDisplayName(it) }
        ?: label.lowercase().replaceFirstChar { it.uppercase() }
    val place = zones.lastOrNull { it.isNotBlank() }
    return if (place != null) "$subject ${zonePhrase(place)}" else "$subject at $cameraDisplayName"
}

/** "now" while Frigate is still tracking it, then "just now" and "3 min ago". */
private fun activityAgeLabel(moment: MomentEvent, nowEpochSeconds: Double): String {
    if (moment.isInProgress) return "now"
    val minutes = ((nowEpochSeconds - moment.lastActiveEpochSeconds) / 60).toLong()
    return if (minutes < 1) "just now" else "$minutes min ago"
}

/** When the detection was last known to be going on: its end, or its start while it is still in progress. */
private val MomentEvent.lastActiveEpochSeconds: Double get() = endEpochSeconds ?: startEpochSeconds

/** How long a detection stays the headline before the page settles back to "All quiet since …". */
internal const val RECENT_ACTIVITY_SECONDS = 30 * 60
