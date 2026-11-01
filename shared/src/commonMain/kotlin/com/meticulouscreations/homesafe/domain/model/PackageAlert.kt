package com.meticulouscreations.homesafe.domain.model

/**
 * A push from the relay about a parcel (`package_notify` in `relay/relay.py`), as its data spells
 * it out. The relay also sends a title and body in English; a phone that can read this data words
 * the notification itself, in its own language.
 */
data class PackageAlert(
    /** Empty for the morning's list, which is about no one parcel. */
    val packageId: String,
    val event: Event,
    /** What the parcel is called, as data (its nickname, or what the shop's email said). Null when it is unknown, or a surprise kept from this phone. */
    val name: String?,
    /** A surprise that isn't this phone's owner's: to be worded as one. */
    val masked: Boolean,
    /** What the door camera made of a delivery: "confirmed", "seen", "not_seen", or null before it has said. */
    val verdict: String?,
    /** The morning's list: how many come today. */
    val count: Int,
    /** The morning's list: nobody is home. */
    val away: Boolean,
) {
    enum class Event { SHIPPED, OUT_FOR_DELIVERY, DELIVERED, PORCH, RAIN, MISSING, CAMERA_ONLY, ATTEMPTED, PROBLEM, SIGNATURE, LATE, RETURN_CLOSING, MORNING }

    companion object {
        const val KEY_PACKAGE = "package"
        const val KEY_EVENT = "package_event"

        /** Whether a push is about a parcel at all. */
        fun isPackage(values: (String) -> String?): Boolean = values(KEY_EVENT) != null

        /** The alert in [values] (a push's data), or null for an event this build can't word. */
        fun from(values: (String) -> String?): PackageAlert? {
            val event = when (values(KEY_EVENT)) {
                "shipped" -> Event.SHIPPED
                "out_for_delivery" -> Event.OUT_FOR_DELIVERY
                "delivered" -> Event.DELIVERED
                "porch" -> Event.PORCH
                "rain" -> Event.RAIN
                "missing" -> Event.MISSING
                "camera_only" -> Event.CAMERA_ONLY
                "problem_attempted" -> Event.ATTEMPTED
                "problem_exception" -> Event.PROBLEM
                "signature" -> Event.SIGNATURE
                "late" -> Event.LATE
                "return_closing" -> Event.RETURN_CLOSING
                "morning" -> Event.MORNING
                else -> return null
            }
            return PackageAlert(
                packageId = values(KEY_PACKAGE).orEmpty(),
                event = event,
                name = values("package_name")?.takeIf { it.isNotBlank() },
                masked = values("package_masked") == "1",
                verdict = values("package_verdict")?.takeIf { it.isNotBlank() },
                count = values("package_count")?.toIntOrNull() ?: 1,
                away = values("package_away") == "1",
            )
        }
    }
}
