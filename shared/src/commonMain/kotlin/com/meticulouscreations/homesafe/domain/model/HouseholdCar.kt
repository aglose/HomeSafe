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
