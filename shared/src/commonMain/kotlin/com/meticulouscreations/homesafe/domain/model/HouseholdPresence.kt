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
