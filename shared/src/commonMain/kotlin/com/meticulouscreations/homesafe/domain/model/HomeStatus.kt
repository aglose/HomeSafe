package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.home_cameras_on
import homesafe.shared.generated.resources.home_cameras_some_on
import homesafe.shared.generated.resources.home_presence_everyone_home
import homesafe.shared.generated.resources.home_presence_house_empty
import homesafe.shared.generated.resources.home_presence_some_home
import homesafe.shared.generated.resources.home_presence_you_are_away
import homesafe.shared.generated.resources.home_status_all_quiet
import homesafe.shared.generated.resources.home_status_all_quiet_since
import homesafe.shared.generated.resources.home_status_just_now
import homesafe.shared.generated.resources.home_status_minutes_ago
import homesafe.shared.generated.resources.home_status_now
import homesafe.shared.generated.resources.home_status_subject_at_camera
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
    val headline: UiText?,
    val details: UiText?,
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
        latestMoment == null -> UiText.of(Res.string.home_status_all_quiet)
        else -> UiText.of(Res.string.home_status_all_quiet_since, dayQualifiedClockLabel(latestMoment.lastActiveEpochSeconds, today, timeZone))
    }
    val parts = listOfNotNull(
        recent?.let { activityAgeLabel(it, nowEpochSeconds) },
        cameras?.let(::camerasOnLabel),
        presenceLabel(presence),
    )
    return HomeStatus(
        headline = headline,
        details = parts.takeIf { it.isNotEmpty() }?.let { UiText.Joined(it, separator = UiText.of(Res.string.common_dot_separator)) },
    )
}

/**
 * "Everyone home" / "1 of 2 home" among the phones the relay counts; "You're away" when this phone
 * is, whatever the others say, since that is what its owner most wants confirmed; "House empty"
 * once the relay has declared it so. Null until the relay has answered, or when no phone counts.
 */
internal fun presenceLabel(presence: HouseholdPresence): UiText? {
    if (presence.everyoneAway) return UiText.of(Res.string.home_presence_house_empty)
    if (presence.thisDevice?.away == true) return UiText.of(Res.string.home_presence_you_are_away)
    val counting = presence.countingDevices
    if (counting.isEmpty()) return null
    val home = counting.count { !it.away }
    return if (home == counting.size) {
        UiText.of(Res.string.home_presence_everyone_home)
    } else {
        UiText.of(Res.string.home_presence_some_home, home, counting.size)
    }
}

/**
 * "4 cameras on" / "3 of 4 cameras on": what the server has switched on, which is what each card's
 * own pill reports once it is playing. Null for a server with no cameras, which the grid below
 * already says in so many words.
 */
internal fun camerasOnLabel(cameras: List<Camera>): UiText? {
    if (cameras.isEmpty()) return null
    val on = cameras.count { it.enabled }
    return if (on == cameras.size) {
        UiText.plural(Res.plurals.home_cameras_on, cameras.size)
    } else {
        UiText.plural(Res.plurals.home_cameras_some_on, cameras.size, on, cameras.size)
    }
}

/**
 * "Person on the front lawn" / "Sarah's Tesla in the driveway" (worded as the feed words them, see
 * [detectionTitle]) / "Person at Backyard" (the camera, when it was in no zone).
 */
private fun MomentEvent.summaryTitle(): UiText {
    val subject = subLabel?.takeIf { isRecognized }?.let { subLabelDisplayName(it).asUiText() } ?: labelName(label)
    val place = zones.lastOrNull { it.isNotBlank() }
    return if (place != null) {
        detectionTitle(subject, place)
    } else {
        UiText.of(Res.string.home_status_subject_at_camera, subject, cameraDisplayName)
    }
}

/**
 * "Now" while Frigate is still tracking it, then "Just now" and "3 min ago". Capitalised in the
 * strings themselves: when there is an age, it always leads the line.
 */
private fun activityAgeLabel(moment: MomentEvent, nowEpochSeconds: Double): UiText {
    if (moment.isInProgress) return UiText.of(Res.string.home_status_now)
    val minutes = ((nowEpochSeconds - moment.lastActiveEpochSeconds) / 60).toInt()
    return if (minutes < 1) UiText.of(Res.string.home_status_just_now) else UiText.plural(Res.plurals.home_status_minutes_ago, minutes)
}

/** When the detection was last known to be going on: its end, or its start while it is still in progress. */
private val MomentEvent.lastActiveEpochSeconds: Double get() = endEpochSeconds ?: startEpochSeconds

/** How long a detection stays the headline before the page settles back to "All quiet since …". */
internal const val RECENT_ACTIVITY_SECONDS = 30 * 60
