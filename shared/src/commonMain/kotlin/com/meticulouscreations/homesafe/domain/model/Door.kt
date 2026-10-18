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
) {
    /** Battery low enough to swap: the sensor says so, or reports a fifth or less. */
    val needsBattery: Boolean get() = batteryLow || (battery != null && battery <= LOW_BATTERY_PERCENT)

    /** Link quality this low drops messages; a Zigbee plug between it and the stick helps. */
    val weakSignal: Boolean get() = linkQuality != null && linkQuality < WEAK_LINK_QUALITY

    companion object {
        const val LOW_BATTERY_PERCENT = 20
        const val WEAK_LINK_QUALITY = 30
    }
}

/** A door opening or closing. [pushed] is "away" or "home" when the opening was pushed, null when it wasn't. */
data class DoorEvent(
    val doorId: String,
    val open: Boolean,
    val atMillis: Long,
    val pushed: String? = null,
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
) {
    companion object {
        val EMPTY = DoorsSnapshot()
    }
}
