package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.presence_last_seen_days
import homesafe.shared.generated.resources.presence_last_seen_hours
import homesafe.shared.generated.resources.presence_last_seen_just_now
import homesafe.shared.generated.resources.presence_last_seen_minutes
import homesafe.shared.generated.resources.presence_last_seen_yesterday

/** One phone in the household and what its owner last said about being home. */
data class PresenceDevice(
    /** As the phone registered itself, e.g. "Google Pixel 10 Pro XL"; blank until it has. */
    val name: String,
    val platform: String,
    val away: Boolean,
    /** When [away] was last set, or null if the owner never touched the switch. */
    val updatedEpochSeconds: Double? = null,
    /** True for the entry that is this very phone, so the Settings switch knows which row it drives. */
    val isThisDevice: Boolean = false,
    /**
     * Whether the relay lets this phone's switch decide [HouseholdPresence.everyoneAway]. False for
     * debug installs (emulators, a test build beside the real app) — they still get pushes, they
     * just can't declare the house empty or hold away mode open. While there is a presence
     * authority, true for that one phone alone (see [decides]).
     */
    val countsForAway: Boolean = true,
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
    /** "release" or "debug", as the install registered itself; null from an older relay. */
    val build: String? = null,
    /**
     * This phone is the household's presence authority ([HouseholdPresence.authorityDeviceId]): its
     * switch alone says whether the house is empty. Every phone still gets its notifications.
     */
    val decides: Boolean = false,
) {
    /**
     * A test build — an Android debug install, an emulator — rather than a phone someone carries.
     * iPhones are never one, while there is no iOS release channel: the relay's own rule for who
     * votes when nobody decides alone. An older relay doesn't say the build, so there it is simply
     * whatever doesn't count.
     */
    val isTestInstall: Boolean
        get() = build?.let { !it.equals("release", ignoreCase = true) && !platform.equals("ios", ignoreCase = true) } ?: !countsForAway
}

/** Where home is, for the geofence every phone draws. Household state, kept by the relay. */
data class HomeLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

/** What flipped a phone's presence; the relay logs it and nothing else depends on it. */
enum class PresenceSource(val wire: String) {
    /** The "I'm away" switch. Immediate in both directions. */
    MANUAL("manual"),

    /** Crossed the home geofence. An exit is armed with a dwell; an entry is immediate. */
    GEOFENCE("geofence"),

    /** Reached Frigate over the LAN — you can't do that from the road. Home, immediately. */
    LAN("lan"),
}

/**
 * Who's home, as the relay on the Frigate box sees it. The relay is the source of truth because
 * both phones must agree: when [everyoneAway] flips on, every person seen by any camera is worth
 * a loud alert on both, whatever the zone rules say.
 */
data class HouseholdPresence(
    val devices: List<PresenceDevice>,
    /** At least one *counting* device registered, and all of those away. */
    val everyoneAway: Boolean,
    /** The household's home, or null until someone has set it from a phone standing in it. */
    val home: HomeLocation? = null,
    /**
     * The one install whose switch alone decides [everyoneAway] — the presence authority — or null
     * while every counting phone votes (and from an older relay). See `docs/away-mode.md`.
     */
    val authorityDeviceId: String? = null,
) {
    /** The phones the relay actually counts — the authority alone, or else release installs (and, for now, any iPhone). */
    val countingDevices: List<PresenceDevice> get() = devices.filter { it.countsForAway }

    val thisDevice: PresenceDevice? get() = devices.firstOrNull { it.isThisDevice }

    /** The presence authority's entry, if there is one and the relay listed it. */
    val decidingDevice: PresenceDevice? get() = devices.firstOrNull { it.decides }

    companion object {
        val EMPTY = HouseholdPresence(devices = emptyList(), everyoneAway = false)
    }
}

/**
 * The Settings list of the household's devices, split the way a person reads it: [primary] is
 * the household's phones in use — this phone always among them, even as a debug build, since its
 * switch is right above — the one that decides alone first, then the rest that aren't test
 * installs. [others] is what folds away behind "N other devices": test installs, and anything not
 * heard from in [STALE_AFTER_SECONDS], which is what a reinstalled app leaves behind. Those are
 * listed counted first, then most recently seen first, so the long-dead ones sink to the bottom.
 */
data class HouseholdDeviceList(
    val primary: List<PresenceDevice>,
    val others: List<PresenceDevice>,
) {
    /**
     * Folded-away devices that still count for away mode — an old install of a real phone. Each
     * still says "home" (or "away") for nobody, so the fold says how many there are.
     */
    val countedOthers: Int get() = others.count { it.countsForAway }

    companion object {
        /** A week: a phone that's in use reads presence every minute it's open. */
        const val STALE_AFTER_SECONDS = 7 * 24 * 60 * 60.0

        fun of(devices: List<PresenceDevice>, nowEpochSeconds: Double): HouseholdDeviceList {
            fun stale(device: PresenceDevice): Boolean =
                device.lastSeenEpochSeconds?.let { nowEpochSeconds - it > STALE_AFTER_SECONDS } ?: false
            // A presence authority leaves every other phone uncounted, but a phone someone carries
            // is still the household's; only test installs and stale rows fold away.
            val (primary, others) = devices.partition { it.isThisDevice || it.decides || (!it.isTestInstall && !stale(it)) }
            return HouseholdDeviceList(
                primary = primary.sortedWith(compareBy({ !it.decides }, { it.isTestInstall }, { !it.isThisDevice })),
                others = others.sortedWith(compareBy({ !it.countsForAway }, { -(it.lastSeenEpochSeconds ?: 0.0) })),
            )
        }
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
