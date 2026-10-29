package com.meticulouscreations.homesafe.domain.model

/**
 * One of the household's Teslas as the relay last knew it (`GET /parking`, docs/parking.md):
 * where Tesla last saw it, wherever that was, and, when that is at the curb of one of the
 * household's watched streets, the spot and when it must move from there.
 */
data class HouseholdCar(
    /** The relay's name for it, e.g. `andrews_tesla`. */
    val id: String,
    /** The car's name, as the relay shows it, e.g. "Andrew's Tesla". Data, not copy. */
    val name: String,
    /** Where it was last seen; null for a car Tesla has never said where it is. */
    val lastSeen: CarFix?,
    /** Its spot at a watched street's curb; null when it isn't parked at one. */
    val parked: CurbSpot?,
    /** Tesla said it is asleep the last time it was asked: it hasn't moved since [lastSeen]. */
    val asleep: Boolean,
    /** What the car itself last streamed beyond where it is; null for a car that hasn't streamed. */
    val live: CarLive? = null,
) {
    /** Where to put it on a map: the curb spot when it has one, else where it was last seen. */
    val latitude: Double? get() = parked?.latitude ?: lastSeen?.latitude
    val longitude: Double? get() = parked?.longitude ?: lastSeen?.longitude
}

/** A position Tesla gave for a car. */
data class CarFix(
    val latitude: Double,
    val longitude: Double,
    /** Which way it faced, in degrees clockwise from north; null when the car didn't say. */
    val headingDegrees: Float?,
    /** When Tesla had the fix, in epoch seconds. */
    val atEpochSeconds: Long,
    /** It was being driven. */
    val moving: Boolean,
    /** Within the household's home, by the radius set for away mode; null when there is no home set. */
    val home: Boolean?,
)

/** A car at the curb of one of the household's watched streets. */
data class CurbSpot(
    val latitude: Double,
    val longitude: Double,
    /** The street, as the household wrote it down or OpenStreetMap names it. Data, not copy. */
    val street: String,
    /** Which side of [street], or null when the relay couldn't tell. */
    val side: ParkingAlert.Side?,
    /** Since when it has been parked there, in epoch seconds. */
    val sinceEpochSeconds: Long?,
    /** When it must be gone by; null when no sign applies for the next while. */
    val moveByEpochSeconds: Long?,
    /** What the sign says, the household's own words for it (e.g. "Street sweeping"); null when it gave none. Data, not copy. */
    val note: String?,
    /** A time limit's length; null for a no-parking window (or no deadline). */
    val limitMinutes: Int?,
    /** The window the deadline falls in, in epoch seconds: a no-parking window's start and end. */
    val fromEpochSeconds: Long?,
    val untilEpochSeconds: Long?,
)

/**
 * What a car last streamed to the relay (Tesla's Fleet Telemetry, docs/tesla.md): its battery,
 * charging, locks, the trip it is on and the cabin's temperature. Each is null when the car hasn't
 * said. All but [connected] are the last word, so they stand while it sleeps.
 */
data class CarLive(
    /** Streaming when the relay answered: awake and heard from within the last few minutes. */
    val connected: Boolean,
    /** When the relay last heard from the car, in epoch seconds; null when it never has. */
    val heardEpochSeconds: Long?,
    val batteryPercent: Int?,
    /** The range the car estimates, in miles. */
    val rangeMiles: Double?,
    val charging: Charging?,
    /** How long until it reaches its charge limit, in minutes; null when not charging or unknown. */
    val minutesToFull: Int?,
    val locked: Boolean?,
    /** Sentry Mode is watching (armed or alerted). */
    val sentry: Boolean?,
    /** Where the car is navigating to, as the car names it. Data, not copy. */
    val destination: String?,
    val minutesToArrival: Int?,
    val insideCelsius: Double?,
) {
    enum class Charging { CHARGING, COMPLETE, PLUGGED_IN }

    /**
     * Still streaming at [nowEpochSeconds]: connected when the relay answered, and heard from within
     * the relay's own window since. A page showing what an unreachable relay last said stops
     * calling the car live once that has passed.
     */
    fun streaming(nowEpochSeconds: Long): Boolean {
        val heard = heardEpochSeconds ?: return false
        return connected && nowEpochSeconds - heard <= FRESH_SECONDS
    }

    companion object {
        /** The relay's TESLA_STREAM_FRESH_SECONDS. */
        const val FRESH_SECONDS = 300L
    }
}
