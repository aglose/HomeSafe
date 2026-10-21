package com.meticulouscreations.homesafe.domain.model

/**
 * A push from the relay saying Google stopped, or started again, streaming a Nest camera
 * (`nest_outage_push` in `relay/relay.py`), as the push's data spells it out. The relay also
 * sends a title and body in English; a phone that can read this data words the notification
 * itself, in its own language.
 */
data class NestOutageAlert(
    /** The notification's identity: one per camera, so the camera's return replaces its outage. */
    val id: String,
    /** The camera's go2rtc stream. */
    val camera: String,
    /** The camera's name, as the relay shows it. Data, not copy. */
    val cameraName: String,
    /** Null when the camera is streaming again; otherwise why it isn't. */
    val reason: Reason?,
    /** When the outage began, in epoch seconds, when the relay knows. */
    val sinceEpochSeconds: Long?,
) {
    enum class Reason {
        /** Google says the camera isn't available for streaming. */
        REFUSED,

        /** No video, and Google didn't say why. */
        UNCONFIRMED,
    }

    companion object {
        const val KEY_SYSTEM = "system"
        const val KEY_CAMERA = "camera"
        const val KEY_NAME = "nest_camera_name"
        const val KEY_EVENT = "nest_event"
        const val KEY_REASON = "nest_reason"
        const val KEY_SINCE = "nest_since"
        const val KEY_ID = "notif_id"

        /** Whether a push is about a Nest camera's stream at all. */
        fun isNestOutage(values: (String) -> String?): Boolean = values(KEY_SYSTEM) == "nest"

        /** The alert in [values] (a push's data), or null when it isn't one this build can word: an event or a reason a newer relay knows about. */
        fun from(values: (String) -> String?): NestOutageAlert? {
            if (!isNestOutage(values)) return null
            val camera = values(KEY_CAMERA)?.takeIf { it.isNotBlank() } ?: return null
            val reason = when (values(KEY_EVENT)) {
                "streaming" -> null

                "unavailable" -> when (values(KEY_REASON)) {
                    "refused" -> Reason.REFUSED
                    "unconfirmed" -> Reason.UNCONFIRMED
                    else -> return null
                }

                else -> return null
            }
            return NestOutageAlert(
                id = values(KEY_ID)?.takeIf { it.isNotBlank() } ?: "nest-$camera",
                camera = camera,
                cameraName = values(KEY_NAME)?.takeIf { it.isNotBlank() } ?: camera,
                reason = reason,
                sinceEpochSeconds = values(KEY_SINCE)?.toLongOrNull()?.takeIf { reason != null },
            )
        }
    }
}
