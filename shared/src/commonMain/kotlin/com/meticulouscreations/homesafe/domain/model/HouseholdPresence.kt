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
)

/**
 * Who's home, as the relay on the Frigate box sees it. The relay is the source of truth because
 * both phones must agree: when [everyoneAway] flips on, every person seen by any camera is worth
 * a loud alert on both, whatever the zone rules say.
 */
data class HouseholdPresence(
    val devices: List<PresenceDevice>,
    /** At least one *counting* device registered, and all of those away. */
    val everyoneAway: Boolean,
) {
    /** The phones the relay actually counts — release installs (and, for now, any iPhone). */
    val countingDevices: List<PresenceDevice> get() = devices.filter { it.countsForAway }

    val thisDevice: PresenceDevice? get() = devices.firstOrNull { it.isThisDevice }

    companion object {
        val EMPTY = HouseholdPresence(devices = emptyList(), everyoneAway = false)
    }
}
