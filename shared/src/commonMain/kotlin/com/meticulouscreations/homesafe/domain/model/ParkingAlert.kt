package com.meticulouscreations.homesafe.domain.model

/**
 * A push from the relay saying a household car parked on the street must move, or has
 * (`parking_tell` in `relay/relay.py`, docs/parking.md), as the push's data spells it out. The
 * relay also sends a title and body in English; a phone that can read this data words the
 * notification itself, in its own language.
 */
data class ParkingAlert(
    /** The notification's identity: one per car, so each push about it replaces the last. */
    val id: String,
    /** The car's name, as the relay shows it, e.g. "Andrew's Tesla". Data, not copy. */
    val carName: String,
    val event: Event,
    /** The street, as the household wrote it down or OpenStreetMap names it. Data, not copy. */
    val street: String,
    /** Which side of [street], or null when the relay couldn't tell (or the car has moved). */
    val side: Side?,
    /** When the car must be gone by, in epoch seconds; null once it has moved. */
    val byEpochSeconds: Long?,
    /** What the sign says, the household's own words for it (e.g. "Street sweeping"); null when it gave none. Data, not copy. */
    val note: String?,
    /** A time limit's length; null for a no-parking window. */
    val limitMinutes: Int?,
    /** A no-parking window's start and end, in epoch seconds; null for a time limit. */
    val fromEpochSeconds: Long?,
    val untilEpochSeconds: Long?,
    /** More of the same car's news (it has moved): replace the notification without alerting. */
    val silent: Boolean,
) {
    enum class Event {
        /** It must be gone by [byEpochSeconds]. */
        DUE,

        /** [byEpochSeconds] has come and the car is still there. */
        NOW,

        /** The car has left the spot the last notification was about. */
        MOVED,
    }

    enum class Side { NORTH, EAST, SOUTH, WEST }

    companion object {
        const val KEY_PARKING = "parking"
        const val KEY_EVENT = "parking_event"
        const val KEY_CAR = "parking_car"
        const val KEY_CAR_NAME = "parking_car_name"
        const val KEY_STREET = "parking_street"
        const val KEY_SIDE = "parking_side"
        const val KEY_BY = "parking_by"
        const val KEY_NOTE = "parking_note"
        const val KEY_MINUTES = "parking_minutes"
        const val KEY_FROM = "parking_from"
        const val KEY_UNTIL = "parking_until"
        const val KEY_ID = "notif_id"

        /** Whether a push is about parking at all. */
        fun isParking(values: (String) -> String?): Boolean = values(KEY_PARKING) == "1"

        /** The alert in [values] (a push's data), or null when it isn't one this build can word: an event a newer relay knows about. */
        fun from(values: (String) -> String?): ParkingAlert? {
            if (!isParking(values)) return null
            val event = when (values(KEY_EVENT)) {
                "due" -> Event.DUE
                "now" -> Event.NOW
                "moved" -> Event.MOVED
                else -> return null
            }
            val car = values(KEY_CAR)?.takeIf { it.isNotBlank() } ?: return null
            val by = values(KEY_BY)?.toLongOrNull()
            if (event != Event.MOVED && by == null) return null
            val limit = values(KEY_MINUTES)?.toIntOrNull()?.takeIf { it > 0 }
            return ParkingAlert(
                id = values(KEY_ID)?.takeIf { it.isNotBlank() } ?: "parking-$car",
                carName = values(KEY_CAR_NAME)?.takeIf { it.isNotBlank() } ?: car,
                event = event,
                street = values(KEY_STREET).orEmpty(),
                side = Side.entries.firstOrNull { it.name.equals(values(KEY_SIDE), ignoreCase = true) },
                byEpochSeconds = by.takeIf { event != Event.MOVED },
                note = values(KEY_NOTE)?.takeIf { it.isNotBlank() },
                limitMinutes = limit,
                fromEpochSeconds = values(KEY_FROM)?.toLongOrNull()?.takeIf { limit == null },
                untilEpochSeconds = values(KEY_UNTIL)?.toLongOrNull()?.takeIf { limit == null },
                silent = values("silent") == "1",
            )
        }
    }
}
