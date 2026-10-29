package com.meticulouscreations.homesafe.domain.model

/** What the relay's `/parking` answers: the household's Teslas, and the signs along the watched streets. */
data class ParkingSnapshot(val cars: List<HouseholdCar>, val streets: List<ParkingStreet>)

/**
 * A stretch of one of the household's watched streets and the signs along its curbs, as the
 * relay reads them from its `parking.json` (`GET /parking`'s `streets`, docs/parking.md): what
 * the cars' map draws, so the household can check the signs in the app against the street's.
 */
data class ParkingStreet(
    /** The street, as the household wrote it down. Data, not copy. */
    val street: String,
    /** Down the middle of the street, in the order the relay runs it. */
    val line: List<Point>,
    /** Curb to curb, in metres. */
    val widthMetres: Double,
    /** The curbs that have signs: one, both, or none. */
    val curbs: List<ParkingCurb>,
) {
    data class Point(val latitude: Double, val longitude: Double)

    companion object {
        /** Curb to curb where the relay doesn't say, as it takes it (`PARKING_WIDTH_METRES`). */
        const val WIDTH_METRES = 12.0
    }
}

/** One curb of a [ParkingStreet] and its signs. */
data class ParkingCurb(
    /** Which side of the street it is; null for a word this build doesn't know. */
    val side: ParkingAlert.Side?,
    /** Whether it is on the left of [ParkingStreet.line], going the way the line runs. */
    val left: Boolean,
    val rules: List<ParkingRule>,
)

/** One sign: when a car can't stay at the curb, or how long it may. */
data class ParkingRule(
    /** How long a car may stay while it holds; null for no parking at all. */
    val limitMinutes: Int?,
    /** The days it holds on, 0 for Monday through 6 for Sunday; empty for every day. */
    val days: List<Int>,
    /** Which of those days in the month, 1 to 5, or -1 for the last; empty for every week. */
    val weeks: List<Int>,
    /** The months it holds in, 1 to 12; empty for all year. */
    val months: List<Int>,
    /** From when to when on those days, in minutes after midnight; the whole day is 0 to 1440, and an end before the start runs past midnight. */
    val startMinutes: Int,
    val endMinutes: Int,
    /** The household's own words for it (e.g. "Street sweeping"); null when it gave none. Data, not copy. */
    val note: String?,
    /** The permit that lifts it, as the sign names it (e.g. "B"). Data, not copy. */
    val permit: String?,
    /** The household holds [permit]: the sign doesn't apply to its cars. */
    val lifted: Boolean,
    /** When it next holds, in epoch seconds (under way when [nextFromEpochSeconds] has passed); null for not within the next few weeks. */
    val nextFromEpochSeconds: Long?,
    val nextUntilEpochSeconds: Long?,
) {
    /** Whether it holds at [nowEpochSeconds]. */
    fun on(nowEpochSeconds: Long): Boolean {
        val from = nextFromEpochSeconds ?: return false
        val until = nextUntilEpochSeconds ?: return false
        return nowEpochSeconds in from until until
    }
}
