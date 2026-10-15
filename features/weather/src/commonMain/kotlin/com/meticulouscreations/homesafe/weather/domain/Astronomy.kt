package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Where the sun is in the sky: [altitudeDeg] above the horizon (negative below it), [azimuthDeg] clockwise from north. */
@Immutable
data class SunPosition(val altitudeDeg: Double, val azimuthDeg: Double)

/** The eight names the moon's month is told in. */
enum class MoonPhaseName { NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT }

/**
 * The moon tonight: [cycle] runs 0 (new) through 0.5 (full) back to 1, and [illumination] is
 * how much of the disc is lit, 0–1.
 */
@Immutable
data class MoonPhase(val cycle: Double, val illumination: Double) {
    val waxing: Boolean get() = cycle < 0.5

    val name: MoonPhaseName
        get() = when {
            cycle < 0.03 || cycle > 0.97 -> MoonPhaseName.NEW
            cycle < 0.22 -> MoonPhaseName.WAXING_CRESCENT
            cycle < 0.28 -> MoonPhaseName.FIRST_QUARTER
            cycle < 0.47 -> MoonPhaseName.WAXING_GIBBOUS
            cycle < 0.53 -> MoonPhaseName.FULL
            cycle < 0.72 -> MoonPhaseName.WANING_GIBBOUS
            cycle < 0.78 -> MoonPhaseName.LAST_QUARTER
            else -> MoonPhaseName.WANING_CRESCENT
        }
}

/**
 * The light of one day at a place, as instants: the sun's rise and set, and the edges of the
 * hours photographers name. Any of them is null where it doesn't happen that day (a polar
 * summer has no dusk).
 */
@Immutable
data class DayLight(
    /** Civil dawn: the sun 6° under the horizon, when there is light to see by. */
    val dawn: Long? = null,
    val sunrise: Long? = null,
    /** The morning's golden hour is over: the sun has climbed past 6°. */
    val goldenMorningEnd: Long? = null,
    val solarNoon: Long? = null,
    /** The evening's golden hour begins: the sun is down to 6°. */
    val goldenEveningStart: Long? = null,
    val sunset: Long? = null,
    val dusk: Long? = null,
)

/**
 * Sun and moon from the clock and a pair of coordinates, with no network: the low-precision
 * formulas from Meeus's *Astronomical Algorithms* (the ones NOAA's solar calculator uses), good
 * to a fraction of a degree, which is far finer than a sky drawn on a phone needs.
 */
object Astronomy {
    private const val RAD = PI / 180.0
    private const val SYNODIC_DAYS = 29.530588853

    private fun julianCenturies(epochSeconds: Long): Double = (epochSeconds / 86_400.0 + 2_440_587.5 - 2_451_545.0) / 36_525.0

    private fun norm360(deg: Double): Double = deg - 360.0 * floor(deg / 360.0)

    fun sunPosition(epochSeconds: Long, latitude: Double, longitude: Double): SunPosition {
        val t = julianCenturies(epochSeconds)
        val meanLongitude = norm360(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val meanAnomaly = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = meanAnomaly * RAD
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = (125.04 - 1934.136 * t) * RAD
        val apparentLongitude = (meanLongitude + center - 0.00569 - 0.00478 * sin(omega)) * RAD
        val obliquity = (23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega)) * RAD
        val declination = asin(sin(obliquity) * sin(apparentLongitude))

        val y = tan(obliquity / 2).let { it * it }
        val l0 = meanLongitude * RAD
        val equationOfTimeMinutes = 4.0 / RAD * (
            y * sin(2 * l0) - 2 * eccentricity * sin(m) + 4 * eccentricity * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) - 1.25 * eccentricity * eccentricity * sin(2 * m)
            )
        val utcMinutes = (epochSeconds % 86_400 + 86_400) % 86_400 / 60.0
        val trueSolarMinutes = ((utcMinutes + equationOfTimeMinutes + 4.0 * longitude) % 1440.0 + 1440.0) % 1440.0
        val hourAngle = (trueSolarMinutes / 4.0 - 180.0) * RAD
        val lat = latitude * RAD
        val sinAltitude = (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val azimuth = atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(declination) * cos(lat)) / RAD + 180.0
        return SunPosition(asin(sinAltitude) / RAD, norm360(azimuth))
    }

    fun moonPhase(epochSeconds: Long): MoonPhase {
        val t = julianCenturies(epochSeconds)
        val d = (297.8501921 + 445_267.1114034 * t) * RAD
        val sunAnomaly = (357.5291092 + 35_999.0502909 * t) * RAD
        val moonAnomaly = (134.9633964 + 477_198.8675055 * t) * RAD
        // The moon's distance round the sky from the sun: 0 at new, 180 at full.
        val elongation = d / RAD + 6.289 * sin(moonAnomaly) - 2.100 * sin(sunAnomaly) + 1.274 * sin(2 * d - moonAnomaly) +
            0.658 * sin(2 * d) + 0.214 * sin(2 * moonAnomaly) + 0.110 * sin(d)
        val e = norm360(elongation)
        return MoonPhase(cycle = e / 360.0, illumination = (1 - cos(e * RAD)) / 2)
    }

    /** The next instant after [epochSeconds] the moon is full ([full]) or new, to within a few minutes. */
    fun nextMoon(epochSeconds: Long, full: Boolean): Long {
        val target = if (full) 0.5 else 0.0

        // How far short of the target the cycle is, in turns, in (-0.5, 0.5].
        fun gap(at: Long): Double {
            val g = target - moonPhase(at).cycle
            return g - floor(g + 0.5)
        }
        var guess = epochSeconds
        var remaining = gap(guess).let { if (it <= 0) it + 1 else it }
        // The mean month gets within a day; two refinements with the true longitude settle it.
        repeat(4) {
            guess += (remaining * SYNODIC_DAYS * 86_400).toLong()
            remaining = gap(guess)
        }
        return guess
    }

    /**
     * The light of the day that starts at [dayStartEpochSeconds] (the place's local midnight),
     * found by walking the sun's height across it in five-minute steps.
     */
    fun dayLight(dayStartEpochSeconds: Long, latitude: Double, longitude: Double): DayLight {
        val step = 300L
        val count = (86_400 / step).toInt()
        val altitudes = DoubleArray(count + 1) { sunPosition(dayStartEpochSeconds + it * step, latitude, longitude).altitudeDeg }

        fun crossing(threshold: Double, rising: Boolean): Long? {
            for (i in 0 until count) {
                val a = altitudes[i]
                val b = altitudes[i + 1]
                val crosses = if (rising) a < threshold && b >= threshold else a >= threshold && b < threshold
                if (crosses) return dayStartEpochSeconds + i * step + ((threshold - a) / (b - a) * step).toLong()
            }
            return null
        }
        val noon = altitudes.indices.maxByOrNull { altitudes[it] }?.let { dayStartEpochSeconds + it * step }
        return DayLight(
            dawn = crossing(CIVIL_TWILIGHT_DEG, rising = true),
            sunrise = crossing(HORIZON_DEG, rising = true),
            goldenMorningEnd = crossing(GOLDEN_HOUR_DEG, rising = true),
            solarNoon = noon,
            goldenEveningStart = crossing(GOLDEN_HOUR_DEG, rising = false),
            sunset = crossing(HORIZON_DEG, rising = false),
            dusk = crossing(CIVIL_TWILIGHT_DEG, rising = false),
        )
    }

    /** The sun's centre is this far under the true horizon when its top edge shows, once the air has bent its light. */
    const val HORIZON_DEG = -0.833
    const val CIVIL_TWILIGHT_DEG = -6.0
    const val GOLDEN_HOUR_DEG = 6.0
}
