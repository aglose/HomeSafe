package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_span_mix_all_day
import homesafe.shared.generated.resources.weather_span_mix_around
import homesafe.shared.generated.resources.weather_span_mix_from_to
import homesafe.shared.generated.resources.weather_span_mix_on_off
import homesafe.shared.generated.resources.weather_span_rain_all_day
import homesafe.shared.generated.resources.weather_span_rain_around
import homesafe.shared.generated.resources.weather_span_rain_from_to
import homesafe.shared.generated.resources.weather_span_rain_on_off
import homesafe.shared.generated.resources.weather_span_snow_all_day
import homesafe.shared.generated.resources.weather_span_snow_around
import homesafe.shared.generated.resources.weather_span_snow_from_to
import homesafe.shared.generated.resources.weather_span_snow_on_off
import homesafe.shared.generated.resources.weather_span_storm_all_day
import homesafe.shared.generated.resources.weather_span_storm_around
import homesafe.shared.generated.resources.weather_span_storm_from_to
import homesafe.shared.generated.resources.weather_span_storm_on_off
import homesafe.shared.generated.resources.weather_story_cooler
import homesafe.shared.generated.resources.weather_story_high_low
import homesafe.shared.generated.resources.weather_story_mix_continuing
import homesafe.shared.generated.resources.weather_story_mix_starting
import homesafe.shared.generated.resources.weather_story_mix_stopping
import homesafe.shared.generated.resources.weather_story_rain_continuing
import homesafe.shared.generated.resources.weather_story_rain_starting
import homesafe.shared.generated.resources.weather_story_rain_stopping
import homesafe.shared.generated.resources.weather_story_snow_continuing
import homesafe.shared.generated.resources.weather_story_snow_starting
import homesafe.shared.generated.resources.weather_story_snow_stopping
import homesafe.shared.generated.resources.weather_story_storm_continuing
import homesafe.shared.generated.resources.weather_story_storm_starting
import homesafe.shared.generated.resources.weather_story_storm_stopping
import homesafe.shared.generated.resources.weather_story_warmer
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.max

/** A run of wet hours: when it starts and ends, what falls, and how much. */
@Immutable
data class WetSpan(
    val startEpochSeconds: Long,
    /** The end of its last wet hour. */
    val endEpochSeconds: Long,
    val family: Precipitation,
    val totalMm: Double,
    val snowCm: Double,
    val peakMmPerHour: Double,
) {
    val hours: Int get() = ((endEpochSeconds - startEpochSeconds) / WeatherReport.HOUR_SECONDS).toInt()
}

/** What the quarter-hour forecast says about the next couple of hours. */
@Immutable
sealed interface NearTerm {
    /** Dry now, wet from [startEpochSeconds]. */
    data class Starting(val startEpochSeconds: Long, val endEpochSeconds: Long?, val family: Precipitation, val peakMmPerHour: Double) : NearTerm

    /** Wet now, dry from [endEpochSeconds]. */
    data class Stopping(val endEpochSeconds: Long, val family: Precipitation) : NearTerm

    /** Wet now and for as far as the forecast sees. */
    data class Continuing(val family: Precipitation) : NearTerm

    data object Dry : NearTerm
}

/** The stretch of daylight that will feel best outside, and how good that is, 0–100. */
@Immutable
data class OutdoorWindow(val startEpochSeconds: Long, val endEpochSeconds: Long, val score: Int, val temperatureC: Double)

enum class PressureTrend { RISING, STEADY, FALLING }

/** Small pieces of advice for the day ahead, each shown as a chip. */
enum class WeatherTip { UMBRELLA, SUNSCREEN, LAYERS, COAT, ICE, WIND }

/**
 * Reads a forecast the way a person would tell it: when the rain comes, how today compares with
 * yesterday, when to go outside. Every sentence is built here from the numbers, as resources, so
 * the screen, the drawer and a notification all say the same thing in the reader's language.
 */
object WeatherStory {
    /** Runs of wet hours in [hours], with a single dry hour between two wet ones not counted as a break. */
    fun wetSpans(hours: List<HourForecast>): List<WetSpan> {
        val spans = ArrayList<WetSpan>()
        var start = -1
        var lastWet = -1
        fun close() {
            if (start < 0) return
            val run = hours.subList(start, lastWet + 1)
            val snow = run.sumOf { it.snowfallCm }
            val total = run.sumOf { it.precipitationMm }
            val family = when {
                run.any { it.kind.isStorm } -> Precipitation.STORM
                snow >= 0.5 && snow * 0.7 >= total * 0.5 -> Precipitation.SNOW
                run.any { it.kind.precipitation == Precipitation.MIX } || (snow >= 0.5 && total >= 1.0) -> Precipitation.MIX
                else -> Precipitation.RAIN
            }
            spans += WetSpan(
                startEpochSeconds = run.first().epochSeconds,
                endEpochSeconds = run.last().epochSeconds + WeatherReport.HOUR_SECONDS,
                family = family,
                totalMm = total,
                snowCm = snow,
                peakMmPerHour = run.maxOf { it.precipitationMm },
            )
            start = -1
        }
        hours.forEachIndexed { i, hour ->
            if (hour.isWet) {
                if (start < 0) start = i
                lastWet = i
            } else if (start >= 0 && i - lastWet > 1) {
                close()
            }
        }
        close()
        return spans
    }

    /**
     * [spans] within the stretch from [fromEpochSeconds] to [toEpochSeconds] in one phrase:
     * "Rain around 3 PM", "Snow from 2 PM to 6 PM", "Rain all day", "On-and-off storms". Null
     * when it stays dry.
     */
    fun spanPhrase(spans: List<WetSpan>, fromEpochSeconds: Long, toEpochSeconds: Long, utcOffsetSeconds: Int): UiText? {
        if (spans.isEmpty()) return null
        val family = dominantFamily(spans)
        val first = spans.first()
        val last = spans.last()
        val wetHours = spans.sumOf { it.hours }
        val windowHours = max(1, ((toEpochSeconds - fromEpochSeconds) / WeatherReport.HOUR_SECONDS).toInt())
        return when {
            spans.size == 1 && first.startEpochSeconds <= fromEpochSeconds && first.endEpochSeconds >= toEpochSeconds ->
                UiText.of(pick(family, ALL_DAY))

            spans.size >= 3 || (spans.size == 2 && wetHours * 2 < windowHours && last.startEpochSeconds - first.endEpochSeconds > 4 * WeatherReport.HOUR_SECONDS) ->
                UiText.of(pick(family, ON_OFF))

            first.startEpochSeconds == last.startEpochSeconds && first.hours <= 1 ->
                UiText.of(pick(family, AROUND), WeatherFormat.hour(first.startEpochSeconds, utcOffsetSeconds))

            else -> UiText.of(
                pick(family, FROM_TO),
                WeatherFormat.hour(max(first.startEpochSeconds, fromEpochSeconds), utcOffsetSeconds),
                WeatherFormat.hour(last.endEpochSeconds, utcOffsetSeconds),
            )
        }
    }

    /** What most of the water in [spans] falls as; a storm anywhere makes the whole thing stormy. */
    fun dominantFamily(spans: List<WetSpan>): Precipitation = when {
        spans.any { it.family == Precipitation.STORM } -> Precipitation.STORM
        else -> spans.maxByOrNull { it.totalMm + it.snowCm }?.family ?: Precipitation.RAIN
    }

    /** The next couple of hours, from the quarter-hour forecast. */
    fun nearTerm(report: WeatherReport, nowEpochSeconds: Long): NearTerm {
        val slices = report.slicesFrom(nowEpochSeconds).take(NEAR_TERM_SLICES)
        if (slices.isEmpty()) return NearTerm.Dry
        val family = when {
            report.current.kind.isStorm || report.hoursFrom(nowEpochSeconds).take(2).any { it.kind.isStorm } -> Precipitation.STORM
            slices.count { it.isWet && it.isSnow } * 2 > slices.count { it.isWet } -> Precipitation.SNOW
            else -> Precipitation.RAIN
        }
        val wetNow = slices.first().isWet || (report.current.precipitationMm >= PrecipSlice.WET_SLICE_MM && report.current.kind.isPrecipitation)
        if (wetNow) {
            // Two dry quarter-hours in a row is a stop; one is a lull.
            val stop = (1 until slices.size).firstOrNull { i -> !slices[i].isWet && (i == slices.lastIndex || !slices[i + 1].isWet) }
            return if (stop == null || stop == slices.lastIndex) NearTerm.Continuing(family) else NearTerm.Stopping(slices[stop].epochSeconds, family)
        }
        val start = (1 until slices.size).firstOrNull { i ->
            slices[i].isWet && (slices[i].ratePerHourMm >= 1.0 || slices.getOrNull(i + 1)?.isWet == true)
        } ?: return NearTerm.Dry
        val end = (start + 1 until slices.size).firstOrNull { i -> !slices[i].isWet && slices.getOrNull(i + 1)?.isWet != true }
        return NearTerm.Starting(
            startEpochSeconds = slices[start].epochSeconds,
            endEpochSeconds = end?.let { slices[it].epochSeconds },
            family = family,
            peakMmPerHour = slices.drop(start).maxOf { it.ratePerHourMm },
        )
    }

    /** [nearTerm] as a sentence ("Rain starting in about 25 min"), or null when it's dry. */
    fun nearTermPhrase(near: NearTerm, nowEpochSeconds: Long): UiText? = when (near) {
        is NearTerm.Starting -> UiText.of(pick(near.family, STARTING), WeatherFormat.roundedMinutes(near.startEpochSeconds - nowEpochSeconds))
        is NearTerm.Stopping -> UiText.of(pick(near.family, STOPPING), WeatherFormat.roundedMinutes(near.endEpochSeconds - nowEpochSeconds))
        is NearTerm.Continuing -> UiText.of(pick(near.family, CONTINUING))
        NearTerm.Dry -> null
    }

    /**
     * The one sentence under the temperature: the rain if any is near or due today, otherwise how
     * the day compares with yesterday, otherwise its high and low.
     */
    fun headline(report: WeatherReport, units: WeatherUnits, nowEpochSeconds: Long = report.current.epochSeconds): UiText {
        nearTermPhrase(nearTerm(report, nowEpochSeconds), nowEpochSeconds)?.let { return it }

        val ahead = report.hoursFrom(nowEpochSeconds).take(HEADLINE_HOURS)
        if (ahead.isNotEmpty()) {
            val from = ahead.first().epochSeconds
            val to = ahead.last().epochSeconds + WeatherReport.HOUR_SECONDS
            spanPhrase(wetSpans(ahead), from, to, report.utcOffsetSeconds)?.let { return it }
        }

        val today = report.today(nowEpochSeconds)
        val yesterday = report.yesterday(nowEpochSeconds)
        if (today != null && yesterday != null) {
            val delta = today.highC - yesterday.highC
            if (abs(delta) >= NOTABLE_C) {
                return UiText.of(if (delta > 0) Res.string.weather_story_warmer else Res.string.weather_story_cooler, WeatherFormat.degreesBetween(delta, units))
            }
        }
        return if (today != null) {
            UiText.of(Res.string.weather_story_high_low, WeatherFormat.degrees(today.highC, units), WeatherFormat.degrees(today.lowC, units))
        } else {
            UiText.of(report.current.kind.label(report.current.isDay))
        }
    }

    /** How the barometer has moved over the last three hours, or null without enough history. */
    fun pressureTrend(report: WeatherReport, nowEpochSeconds: Long = report.current.epochSeconds): PressureTrend? {
        val i = report.hourly.indexOfLast { it.epochSeconds <= nowEpochSeconds }
        val now = report.current.pressureHpa ?: report.hourly.getOrNull(i)?.pressureHpa ?: return null
        val before = report.hourly.getOrNull(i - 3)?.pressureHpa ?: return null
        return when {
            now - before >= 1.5 -> PressureTrend.RISING
            now - before <= -1.5 -> PressureTrend.FALLING
            else -> PressureTrend.STEADY
        }
    }

    /**
     * The best couple of hours to be outside between now and the end of tomorrow: dry, close to
     * a comfortable temperature, little wind, not under a punishing sun, and in daylight. Null
     * when no stretch is worth recommending.
     */
    fun outdoorWindow(report: WeatherReport, nowEpochSeconds: Long = report.current.epochSeconds): OutdoorWindow? {
        val today = report.dayIndexAt(nowEpochSeconds)
        val horizon = report.daily.getOrNull(today + 1)?.let { it.epochSeconds + WeatherReport.DAY_SECONDS } ?: (nowEpochSeconds + WeatherReport.DAY_SECONDS)
        val hours = report.hoursFrom(nowEpochSeconds).filter { it.epochSeconds < horizon }
        if (hours.size < 2) return null
        val scores = hours.map { comfort(it) }
        var best: OutdoorWindow? = null
        var i = 0
        while (i < hours.size) {
            if (scores[i] < FAIR_SCORE) {
                i++
                continue
            }
            // Grow the stretch while the hours stay within a few points of where it began.
            var j = i
            var sum = 0
            while (j < hours.size && scores[j] >= FAIR_SCORE && scores[j] >= scores[i] - 12 && j - i < 5 && hours[j].epochSeconds - hours[i].epochSeconds == (j - i) * WeatherReport.HOUR_SECONDS) {
                sum += scores[j]
                j++
            }
            val length = j - i
            if (length >= 2) {
                val average = sum / length
                // A longer stretch is worth a little more than a short one that scores the same.
                if (best == null || average + length > best.score + ((best.endEpochSeconds - best.startEpochSeconds) / WeatherReport.HOUR_SECONDS).toInt()) {
                    best = OutdoorWindow(
                        startEpochSeconds = hours[i].epochSeconds,
                        endEpochSeconds = hours[j - 1].epochSeconds + WeatherReport.HOUR_SECONDS,
                        score = average,
                        temperatureC = hours.subList(i, j).map { it.feelsLikeC }.average(),
                    )
                }
            }
            i = max(j, i + 1)
        }
        return best
    }

    /** How pleasant [hour] is to be out in, 0–100; 0 outright for the dark. */
    fun comfort(hour: HourForecast): Int {
        if (!hour.isDay) return 0
        var score = 100.0
        // Comfortable is roughly 17–24 °C as felt; each degree beyond costs more on the cold side.
        val feels = hour.feelsLikeC
        score -= when {
            feels < 17 -> (17 - feels) * 4.0
            feels > 24 -> (feels - 24) * 5.0
            else -> 0.0
        }
        score -= (hour.precipitationProbability ?: 0) * 0.55
        score -= hour.precipitationMm * 30
        score -= max(0.0, hour.windKmh - 18) * 1.6
        score -= max(0.0, hour.gustKmh - 40) * 1.0
        score -= max(0.0, (hour.uvIndex ?: 0.0) - 7) * 7
        if (hour.kind.isPrecipitation) score -= 25
        return score.toInt().coerceIn(0, 100)
    }

    /** What to carry or wear for the rest of today, most pressing first, at most three. */
    fun tips(report: WeatherReport, nowEpochSeconds: Long = report.current.epochSeconds): List<WeatherTip> {
        val today = report.today(nowEpochSeconds) ?: return emptyList()
        val rest = report.hoursFrom(nowEpochSeconds).filter { it.epochSeconds < today.epochSeconds + WeatherReport.DAY_SECONDS }
        if (rest.isEmpty()) return emptyList()
        val tips = ArrayList<WeatherTip>()
        val wet = rest.filter { it.isWet }
        if (wet.any { it.temperatureC <= 0.5 } || (report.current.temperatureC <= 0.0 && report.current.kind.isPrecipitation)) tips += WeatherTip.ICE
        if (wet.any { !it.kind.isSnow }) tips += WeatherTip.UMBRELLA
        if (rest.maxOf { it.gustKmh } >= 55) tips += WeatherTip.WIND
        if (rest.any { it.isDay && (it.uvIndex ?: 0.0) >= 6 }) tips += WeatherTip.SUNSCREEN
        if (rest.minOf { it.feelsLikeC } <= 4) tips += WeatherTip.COAT
        if (rest.maxOf { it.temperatureC } - rest.minOf { it.temperatureC } >= 11 && WeatherTip.COAT !in tips) tips += WeatherTip.LAYERS
        return tips.take(3)
    }

    /** The feels-like temperature is worth its own line only when it's some way off the real one. */
    fun feelsDifferent(current: CurrentConditions): Boolean = abs(current.feelsLikeC - current.temperatureC) >= 2.5

    private fun pick(family: Precipitation, set: PhraseSet): StringResource = when (family) {
        Precipitation.SNOW -> set.snow
        Precipitation.MIX -> set.mix
        Precipitation.STORM -> set.storm
        else -> set.rain
    }

    private class PhraseSet(val rain: StringResource, val snow: StringResource, val mix: StringResource, val storm: StringResource)

    private val AROUND = PhraseSet(Res.string.weather_span_rain_around, Res.string.weather_span_snow_around, Res.string.weather_span_mix_around, Res.string.weather_span_storm_around)
    private val FROM_TO = PhraseSet(Res.string.weather_span_rain_from_to, Res.string.weather_span_snow_from_to, Res.string.weather_span_mix_from_to, Res.string.weather_span_storm_from_to)
    private val ALL_DAY = PhraseSet(Res.string.weather_span_rain_all_day, Res.string.weather_span_snow_all_day, Res.string.weather_span_mix_all_day, Res.string.weather_span_storm_all_day)
    private val ON_OFF = PhraseSet(Res.string.weather_span_rain_on_off, Res.string.weather_span_snow_on_off, Res.string.weather_span_mix_on_off, Res.string.weather_span_storm_on_off)
    private val STARTING = PhraseSet(Res.string.weather_story_rain_starting, Res.string.weather_story_snow_starting, Res.string.weather_story_mix_starting, Res.string.weather_story_storm_starting)
    private val STOPPING = PhraseSet(Res.string.weather_story_rain_stopping, Res.string.weather_story_snow_stopping, Res.string.weather_story_mix_stopping, Res.string.weather_story_storm_stopping)
    private val CONTINUING = PhraseSet(Res.string.weather_story_rain_continuing, Res.string.weather_story_snow_continuing, Res.string.weather_story_mix_continuing, Res.string.weather_story_storm_continuing)

    /** Two hours of quarter-hours. */
    private const val NEAR_TERM_SLICES = 9

    /** How far ahead the headline looks for rain once the next two hours are dry. */
    private const val HEADLINE_HOURS = 14

    /** A change from yesterday smaller than this (2 °C, about 4 °F) isn't news. */
    private const val NOTABLE_C = 2.0

    private const val FAIR_SCORE = 55
}
