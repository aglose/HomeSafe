package com.meticulouscreations.homesafe.domain.model

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
     * just can't declare the house empty or hold away mode open.
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
)

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
) {
    /** The phones the relay actually counts — release installs (and, for now, any iPhone). */
    val countingDevices: List<PresenceDevice> get() = devices.filter { it.countsForAway }

    val thisDevice: PresenceDevice? get() = devices.firstOrNull { it.isThisDevice }

    companion object {
        val EMPTY = HouseholdPresence(devices = emptyList(), everyoneAway = false)
    }
}

/**
 * The Settings list of the household's devices, split the way a person reads it: [primary] is
 * the phones that decide away mode and are in use — this phone always among them, even as a
 * debug build, since its switch is right above — counted ones first. [others] is what folds away
 * behind "N other devices": debug installs, and anything not heard from in [STALE_AFTER_SECONDS],
 * which is what a reinstalled app leaves behind. Those are listed counted first, then most
 * recently seen first, so the long-dead ones sink to the bottom.
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
            val (primary, others) = devices.partition { it.isThisDevice || (it.countsForAway && !stale(it)) }
            return HouseholdDeviceList(
                primary = primary.sortedWith(compareBy({ !it.countsForAway }, { !it.isThisDevice })),
                others = others.sortedWith(compareBy({ !it.countsForAway }, { -(it.lastSeenEpochSeconds ?: 0.0) })),
            )
        }
    }
}

/**
 * "seen just now" / "seen 12 min ago" / "seen 3 h ago" / "seen yesterday" / "seen 5 days ago" —
 * how long since the relay heard from a device.
 */
fun formatLastSeen(lastSeenEpochSeconds: Double, nowEpochSeconds: Double): String {
    val seconds = (nowEpochSeconds - lastSeenEpochSeconds).toLong().coerceAtLeast(0)
    val days = seconds / 86_400
    return when {
        seconds < 120 -> "seen just now"
        seconds < 3_600 -> "seen ${seconds / 60} min ago"
        seconds < 86_400 -> "seen ${seconds / 3_600} h ago"
        days == 1L -> "seen yesterday"
        else -> "seen $days days ago"
    }
}
