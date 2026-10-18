package com.meticulouscreations.homesafe.domain.model

/**
 * A push from the relay saying a door opened (`door_announce` in `relay/relay.py`), as the push's
 * data spells it out. The relay also sends a title and body in English; a phone that can read
 * this data words the notification itself, in its own language.
 */
data class DoorAlert(
    /** The notification's identity: one per door, so the next opening replaces it. */
    val id: String,
    val doorId: String,
    /** The door's name, as it was given on the pairing page. Data, not copy. */
    val doorName: String,
    /** Everyone was away: the loud channel. */
    val away: Boolean,
    /** Opened again soon after the last push: update the notification without a sound. */
    val silent: Boolean,
) {
    companion object {
        const val KEY_DOOR = "door"
        const val KEY_NAME = "door_name"
        const val KEY_EVENT = "door_event"
        const val KEY_ID = "notif_id"

        /** Whether a push is about a door at all. */
        fun isDoor(values: (String) -> String?): Boolean = !values(KEY_DOOR).isNullOrBlank()

        /** The alert in [values] (a push's data), or null when it isn't one this build can word: an event other than an opening. */
        fun from(values: (String) -> String?): DoorAlert? {
            val doorId = values(KEY_DOOR)?.takeIf { it.isNotBlank() } ?: return null
            if (values(KEY_EVENT) != "opened") return null
            return DoorAlert(
                id = values(KEY_ID)?.takeIf { it.isNotBlank() } ?: "door-$doorId",
                doorId = doorId,
                doorName = values(KEY_NAME)?.takeIf { it.isNotBlank() } ?: doorId,
                away = values("away") == "1",
                silent = values("silent") == "1",
            )
        }
    }
}
