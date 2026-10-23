package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.presence_last_seen_days
import homesafe.shared.generated.resources.presence_last_seen_hours
import homesafe.shared.generated.resources.presence_last_seen_just_now
import homesafe.shared.generated.resources.presence_last_seen_minutes
import homesafe.shared.generated.resources.presence_last_seen_yesterday
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of the household's two tracked phones — its Android phone and its iPhone, each a release
 * build on real hardware — and what it last said about its owner being home. The relay lists no
 * other install: debug builds, emulators and old installs aren't tracked (see `docs/away-mode.md`).
 */
data class PresenceDevice(
    /** As the phone registered itself, e.g. "Google Pixel 10 Pro XL"; blank until it has. */
    val name: String,
    val platform: String,
    val away: Boolean,
    /** When [away] was last set, or null if it never was. */
    val updatedEpochSeconds: Double? = null,
    /** True for the entry that is this very phone, so the Settings switch knows which row it drives. */
    val isThisDevice: Boolean = false,
    /**
     * The phone has left the home geofence but its dwell hasn't run out yet: not [away], but will
     * be unless something says "home" first. See `docs/away-mode.md`.
     */
    val pendingAway: Boolean = false,
    /** The relay's key for this install, which is what removing it names; null from a relay that predates it. */
    val id: String? = null,
    /**
     * When the relay last heard from this install — a registration, a presence change, or the
     * app reading presence, which it does about once a minute while open. Null from an older relay.
     */
    val lastSeenEpochSeconds: Double? = null,
)

/** Why the relay doesn't track this install, so the Away mode page can say. */
enum class NotTrackedReason {
    /** A debug build: an emulator, the .debug app beside the real one, an Xcode Debug install. */
    DEBUG_BUILD,

    /** A release build on an emulator or simulator. */
    EMULATOR,

    /** Another phone of this platform is the tracked one. Removing it hands its place over. */
    ANOTHER_PHONE,

    /** Not registered yet, or for some reason this build doesn't know. */
    UNKNOWN,
}

/** Where home is, for the geofence every phone draws. Household state, kept by the relay. */
data class HomeLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
) {
    /** How far ([latitude], [longitude]) is from the middle of home, in metres, along the ground. */
    fun distanceMeters(latitude: Double, longitude: Double): Double {
        val dLat = (latitude - this.latitude) * DEGREES_TO_RADIANS
        val dLng = (longitude - this.longitude) * DEGREES_TO_RADIANS
        val h = sin(dLat / 2).pow(2) + cos(this.latitude * DEGREES_TO_RADIANS) * cos(latitude * DEGREES_TO_RADIANS) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val DEGREES_TO_RADIANS = PI / 180
    }
}

/** What flipped a phone's presence; the relay logs it and nothing else depends on it. */
enum class PresenceSource(val wire: String) {
    /** The "I'm away" switch. Immediate in both directions. */
    MANUAL("manual"),

    /** Crossed the home geofence. An exit is armed with a dwell; an entry is immediate. */
    GEOFENCE("geofence"),

    /** Reached Frigate over the LAN — you can't do that from the road. Home, immediately. */
    LAN("lan"),

    /**
     * A location fix taken while the app runs, against home: what notices a phone that is
     * already out (or in) when nothing has crossed the fence. Away is armed with the dwell.
     */
    LOCATION("location"),
}

/**
 * Who's home, as the relay on the Frigate box sees it. The relay is the source of truth because
 * both phones must agree: when [everyoneAway] flips on, every person seen by any camera is worth
 * a loud alert on both, whatever the zone rules say.
 */
data class HouseholdPresence(
    /** The tracked phones, at most one Android phone and one iPhone, Android first. */
    val devices: List<PresenceDevice>,
    /** At least one phone tracked, and all of them away. */
    val everyoneAway: Boolean,
    /** The household's home, or null until someone has set it from a phone standing in it. */
    val home: HomeLocation? = null,
    /** Why this install isn't among [devices], or null when it is (or the relay didn't say). */
    val thisDeviceNotTracked: NotTrackedReason? = null,
) {
    val thisDevice: PresenceDevice? get() = devices.firstOrNull { it.isThisDevice }

    companion object {
        val EMPTY = HouseholdPresence(devices = emptyList(), everyoneAway = false)
    }
}

/**
 * "seen just now" / "seen 12 min ago" / "seen 3 h ago" / "seen yesterday" / "seen 5 days ago" —
 * how long since the relay heard from a device.
 */
fun formatLastSeen(lastSeenEpochSeconds: Double, nowEpochSeconds: Double): UiText {
    val seconds = (nowEpochSeconds - lastSeenEpochSeconds).toLong().coerceAtLeast(0)
    val days = seconds / 86_400
    return when {
        seconds < 120 -> UiText.of(Res.string.presence_last_seen_just_now)
        seconds < 3_600 -> UiText.plural(Res.plurals.presence_last_seen_minutes, (seconds / 60).toInt())
        seconds < 86_400 -> UiText.plural(Res.plurals.presence_last_seen_hours, (seconds / 3_600).toInt())
        days == 1L -> UiText.of(Res.string.presence_last_seen_yesterday)
        else -> UiText.plural(Res.plurals.presence_last_seen_days, days.toInt())
    }
}
