package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_heart_zone_1
import homesafe.shared.generated.resources.fitness_heart_zone_2
import homesafe.shared.generated.resources.fitness_heart_zone_3
import homesafe.shared.generated.resources.fitness_heart_zone_4
import homesafe.shared.generated.resources.fitness_heart_zone_5
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

/** One of the five heart-rate zones, easiest first. Under the first of them the heart is simply at rest, which is no zone. */
enum class HeartZone(val number: Int, val label: StringResource) {
    VERY_LIGHT(1, Res.string.fitness_heart_zone_1),
    LIGHT(2, Res.string.fitness_heart_zone_2),
    MODERATE(3, Res.string.fitness_heart_zone_3),
    HARD(4, Res.string.fitness_heart_zone_4),
    MAXIMUM(5, Res.string.fitness_heart_zone_5),
}

/**
 * What the zones are worked out from, as the lifter gave it: a maximum heart rate they know
 * ([maxBpm]), or failing that their [age] to estimate one from, and a resting heart rate
 * ([restingBpm]) if they want the zones drawn on the reserve between the two. All three may be
 * missing; with neither a maximum nor an age there are no zones, only a number.
 */
@Immutable
data class HeartProfile(val maxBpm: Int? = null, val age: Int? = null, val restingBpm: Int? = null)

/**
 * The zones in beats a minute: where each begins ([floors], zone 1's first) under a maximum of
 * [maxBpm]. [estimated] when that maximum came from an age and not from the lifter; [restingBpm]
 * when the zones were drawn on the reserve above it.
 */
@Immutable
data class ZoneBounds(val maxBpm: Int, val floors: List<Int>, val estimated: Boolean = false, val restingBpm: Int? = null) {
    /** The zone [bpm] falls in; null under zone 1. Anything over the maximum is still zone 5. */
    fun zoneOf(bpm: Int): HeartZone? = HeartZone.entries.lastOrNull { bpm >= floors[it.ordinal] }

    /** The beats [zone] covers, up to the one under the next zone's first (the maximum, for zone 5). */
    fun range(zone: HeartZone): IntRange = floors[zone.ordinal]..(floors.getOrNull(zone.ordinal + 1)?.minus(1) ?: maxBpm)
}

/**
 * Heart-rate zones from a [HeartProfile]. Everything that decides where a zone begins is here.
 *
 * **The zones.** Five bands of ten percent, starting at half of the maximum heart rate:
 * 50–60, 60–70, 70–80, 80–90 and 90–100% ([FLOORS]). This is the five-zone convention that
 * heart-rate monitors settled on (Polar's "sport zones"; most watches and gym equipment draw the
 * same lines), which is the reason to use it: the number on the phone means what it means on the
 * treadmill. It is a convention, not physiology. The American College of Sports Medicine's
 * intensity classes are close to it but not the same (light from 57% of maximum, moderate from
 * 64%, vigorous from 77%, near-maximal from 96%; Garber et al. 2011, Med Sci Sports Exerc
 * 43(7):1334–59), and a person's real thresholds are only found by testing them.
 *
 * **The maximum.** The lifter's own, if they know it. Otherwise estimated from age as
 * `208 − 0.7 × age` (Tanaka, Monahan & Seals 2001, J Am Coll Cardiol 37(1):153–6, a
 * meta-analysis of 351 studies checked against a laboratory sample), which fits adults better
 * than the older `220 − age`. Either formula is a population average with a standard deviation of
 * about ten beats, so an estimated maximum, and every zone drawn under it, can be a zone out for
 * a given person.
 *
 * **Heart-rate reserve.** With a resting heart rate the same percentages are taken of the reserve
 * between resting and maximum and added back on: `rest + share × (max − rest)` (Karvonen, Kentala
 * & Mustala 1957, Ann Med Exp Biol Fenn 35(3):307–15). That lifts every zone, the low ones most,
 * and suits someone whose resting rate is far from average.
 */
object HeartZones {
    /** Where each zone begins, as a share of the maximum (or of the reserve), zone 1's first. */
    val FLOORS = listOf(0.50, 0.60, 0.70, 0.80, 0.90)

    /** A maximum heart rate anyone might really have. */
    val MAX_RANGE = 120..230
    val AGE_RANGE = 10..100
    val RESTING_RANGE = 30..110

    /** A reading a heart could really give; outside it the sensor is guessing (or off the wrist, where it says 0). */
    val PLAUSIBLE = 30..240

    /** A resting rate has to leave this much room under the maximum to be a reserve worth dividing. */
    private const val LEAST_RESERVE = 40

    /** Tanaka's estimate of the maximum heart rate at [age]. */
    fun estimatedMax(age: Int): Int = (208 - 0.7 * age).roundToInt()

    /**
     * The zones for [profile]; null when it gives neither a believable maximum nor an age. A
     * maximum that was entered wins over an age; a resting rate that isn't believable, or leaves
     * no room under the maximum, is ignored.
     */
    fun bounds(profile: HeartProfile): ZoneBounds? {
        val entered = profile.maxBpm?.takeIf { it in MAX_RANGE }
        val max = entered ?: profile.age?.takeIf { it in AGE_RANGE }?.let(::estimatedMax) ?: return null
        val resting = profile.restingBpm?.takeIf { it in RESTING_RANGE && max - it >= LEAST_RESERVE }
        val floors = FLOORS.map { share -> if (resting != null) (resting + share * (max - resting)).roundToInt() else (share * max).roundToInt() }
        return ZoneBounds(max, floors, estimated = entered == null, restingBpm = resting)
    }
}

/**
 * One notification of a Bluetooth heart-rate sensor's Heart Rate Measurement characteristic
 * (0x2A37), read. [bpm] is as the sensor sent it, 0 included. [contact] is whether the sensor
 * says it is on skin, null when it doesn't report that.
 */
@Immutable
data class HeartRateMeasurement(val bpm: Int, val contact: Boolean? = null) {
    /** The heart rate, when there is one to believe: the sensor is on skin (or doesn't say) and the number is a heart's. */
    val reading: Int? get() = bpm.takeIf { contact != false && it in HeartZones.PLAUSIBLE }

    companion object {
        private const val FLAG_WIDE = 0x01
        private const val FLAG_CONTACT = 0x02
        private const val FLAG_CONTACT_SUPPORTED = 0x04

        /**
         * Reads the characteristic's bytes (Bluetooth Heart Rate Service 1.0, §3.1). The first is
         * flags: bit 0 says whether the heart rate that follows is one byte or two (little
         * endian); bit 2 says the sensor reports skin contact and bit 1 is that report. Energy
         * expended and RR intervals may follow and are not read. Null when there are too few
         * bytes to hold what the flags promise.
         */
        fun parse(bytes: ByteArray): HeartRateMeasurement? {
            if (bytes.size < 2) return null
            val flags = bytes[0].toInt() and 0xFF
            val wide = flags and FLAG_WIDE != 0
            if (wide && bytes.size < 3) return null
            val low = bytes[1].toInt() and 0xFF
            val bpm = if (wide) low or ((bytes[2].toInt() and 0xFF) shl 8) else low
            val contact = if (flags and FLAG_CONTACT_SUPPORTED != 0) flags and FLAG_CONTACT != 0 else null
            return HeartRateMeasurement(bpm, contact)
        }
    }
}

/**
 * Which zone the heart has settled in. A reading that sits on a line between two zones crosses
 * it every other beat, so a new zone only counts once the readings have stayed in it for
 * [HOLD_MILLIS]; until then the one before stands. [zone] is null under zone 1. Nothing is
 * [settled] until the first reading, which is taken as it comes.
 */
@Immutable
data class ZoneTracker(
    val zone: HeartZone? = null,
    val settled: Boolean = false,
    private val candidate: HeartZone? = null,
    private val candidateSinceMillis: Long? = null,
) {
    /** The tracker after a reading in [reading] (null under zone 1) at [atMillis]. */
    fun next(reading: HeartZone?, atMillis: Long): ZoneTracker = when {
        !settled -> ZoneTracker(reading, settled = true)
        reading == zone -> if (candidateSinceMillis == null) this else copy(candidate = null, candidateSinceMillis = null)
        candidateSinceMillis == null || reading != candidate -> copy(candidate = reading, candidateSinceMillis = atMillis)
        atMillis - candidateSinceMillis >= HOLD_MILLIS -> ZoneTracker(reading, settled = true)
        else -> this
    }

    companion object {
        /** How long the readings must stay in a new zone before it is the zone: a few beats' worth of notifications. */
        const val HOLD_MILLIS = 4_000L
    }
}

/**
 * A workout's heart, added up: how long was spent under zone 1 ([belowMillis]) and in each zone
 * ([zoneMillis], zone 1's first), the highest reading ([peakBpm]), and the readings weighted by
 * how long each stood ([bpmMillis]), which is what the average is taken from. Only time the
 * sensor was really reporting is in it: see [plus].
 */
@Immutable
data class HeartSummary(
    val belowMillis: Long = 0,
    val zoneMillis: List<Long> = List(HeartZone.entries.size) { 0L },
    val bpmMillis: Long = 0,
    val peakBpm: Int = 0,
) {
    val totalMillis: Long get() = belowMillis + zoneMillis.sum()

    val averageBpm: Int? get() = if (totalMillis > 0) (bpmMillis.toDouble() / totalMillis).roundToInt() else null

    val isEmpty: Boolean get() = totalMillis == 0L

    fun millisIn(zone: HeartZone): Long = zoneMillis[zone.ordinal]

    /**
     * The summary with a reading of [bpm], in [zone], that has stood for [millis] since the one
     * before it. A gap longer than [MAX_GAP_MILLIS] is the sensor having been away (out of
     * range, the app off screen), and nobody knows what the heart did in it: it adds nothing.
     */
    fun plus(bpm: Int, zone: HeartZone?, millis: Long): HeartSummary {
        if (millis <= 0 || millis > MAX_GAP_MILLIS) return if (bpm > peakBpm) copy(peakBpm = bpm) else this
        return HeartSummary(
            belowMillis = if (zone == null) belowMillis + millis else belowMillis,
            zoneMillis = if (zone == null) zoneMillis else zoneMillis.mapIndexed { index, held -> if (index == zone.ordinal) held + millis else held },
            bpmMillis = bpmMillis + bpm * millis,
            peakBpm = maxOf(peakBpm, bpm),
        )
    }

    companion object {
        /** Sensors notify about once a second; a reading stands for the time since the last one only up to this. */
        const val MAX_GAP_MILLIS = 5_000L
    }
}

/** The heart coming back down between sets: the highest it read since the set ended ([peakBpm]) and what it reads now. */
@Immutable
data class HeartRecovery(val peakBpm: Int, val bpm: Int) {
    val drop: Int get() = (peakBpm - bpm).coerceAtLeast(0)
}
