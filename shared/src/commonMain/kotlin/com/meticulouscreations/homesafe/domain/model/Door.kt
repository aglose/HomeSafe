package com.meticulouscreations.homesafe.domain.model

/**
 * When a door opening pushes (the relay's `notify`, see docs/doors.md). [AWAY], the default,
 * pushes only while everyone's away; [ALWAYS] pushes while someone is home too, as an ordinary
 * alert that each phone's quiet hours still hold back; [NEVER] only keeps the history.
 */
enum class DoorNotify(val wire: String) {
    AWAY("away"),
    ALWAYS("always"),
    NEVER("never"),
    ;

    companion object {
        fun fromWire(value: String?): DoorNotify = entries.firstOrNull { it.wire == value } ?: AWAY
    }
}

/** One door sensor on the box's Zigbee network, as the relay last heard it. */
data class Door(
    /** The sensor's Zigbee IEEE address: what the relay knows it by, through renames. */
    val id: String,
    /** As it was named on the pairing page, e.g. "Front door". */
    val name: String,
    /** Open, closed, or null before the sensor's first reading. */
    val open: Boolean?,
    /** When it last opened or closed, in epoch millis; null before its first change. */
    val changedAtMillis: Long? = null,
    /** Battery percent, as the sensor last reported it. */
    val battery: Int? = null,
    val batteryLow: Boolean = false,
    /** Zigbee link quality, 0-255. */
    val linkQuality: Int? = null,
    /** False once Zigbee2MQTT has stopped hearing from it; null before it has said either way. */
    val online: Boolean? = null,
    val notify: DoorNotify = DoorNotify.AWAY,
    /** Vendor and model, e.g. "Third Reality 3RDS17BZ". */
    val model: String? = null,
    /** Where it is on the floor plan; null until someone places it. */
    val spot: DoorSpot? = null,
) {
    /** Battery low enough to swap: the sensor says so, or reports a fifth or less. */
    val needsBattery: Boolean get() = batteryLow || (battery != null && battery <= LOW_BATTERY_PERCENT)

    /** Still called by its address, as Zigbee2MQTT names a device it has just paired. */
    val unnamed: Boolean get() = name == id

    /** How well the stick hears it, from its last [linkQuality]; null before it has said. */
    val signal: DoorSignal? get() = linkQuality?.let(DoorSignal::of)

    companion object {
        const val LOW_BATTERY_PERCENT = 20
    }
}

/**
 * A door sensor's Zigbee link quality (0-255) told as a level, each from [atLeast] up. [WEAK]
 * drops messages; a Zigbee plug between the sensor and the stick helps.
 */
enum class DoorSignal(val atLeast: Int) {
    WEAK(0),
    FAIR(30),
    GOOD(80),
    STRONG(150),
    ;

    companion object {
        fun of(linkQuality: Int): DoorSignal = entries.last { linkQuality >= it.atLeast }
    }
}

/** A door opening or closing. [pushed] is "away" or "home" when the opening was pushed, null when it wasn't. */
data class DoorEvent(
    val doorId: String,
    val open: Boolean,
    val atMillis: Long,
    val pushed: String? = null,
)

/** How far a device that joined while pairing has got (the relay's `pairing.joined[].status`). */
enum class DoorJoinStatus(val wire: String) {
    /** On the network. A sensor that dropped off and rejoined goes no further: it's back. */
    JOINED("joined"),

    /** A new device, being asked what it is. */
    INTERVIEWING("interviewing"),

    /** A new device, set up. */
    PAIRED("paired"),

    /** A new device that couldn't be set up; pairing it again usually works. */
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): DoorJoinStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** A device that joined the Zigbee network while pairing was open. */
data class DoorJoin(
    /** Its IEEE address, the same as a [Door.id] once it's a door. */
    val id: String,
    /** Its name in Zigbee2MQTT when it joined: its address, for a new one. */
    val name: String,
    val status: DoorJoinStatus,
    val atMillis: Long,
)

/** Whether the Zigbee network is letting sensors join, and what has while it was. */
data class DoorPairing(
    val open: Boolean = false,
    /** When the window closes by itself, in epoch millis; null when it's closed or the relay didn't say. */
    val untilMillis: Long? = null,
    /** Oldest first. Kept after the window closes, until it's opened again. */
    val joined: List<DoorJoin> = emptyList(),
)

/** Everything the relay says about the doors. */
data class DoorsSnapshot(
    val doors: List<Door> = emptyList(),
    /** Newest first. */
    val events: List<DoorEvent> = emptyList(),
    /** The relay is connected to the Zigbee broker, and Zigbee2MQTT says it's running. */
    val listening: Boolean = false,
    /** False for a relay without door sensors at all (one from before them, which answers 404). */
    val supported: Boolean = true,
    /** Whether this is what the relay said, rather than the placeholder before it has answered. */
    val answered: Boolean = false,
    /**
     * The relay's count of door changes, for asking it to answer at the next one; null from a
     * relay that can't (it's then polled).
     */
    val version: Long? = null,
    /** Pairing sensors from the app; null from a relay that can't (one from before it). */
    val pairing: DoorPairing? = null,
) {
    companion object {
        val EMPTY = DoorsSnapshot()
    }
}
