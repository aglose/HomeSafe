package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_notice_art_range
import homesafe.shared.generated.resources.weather_outlook_all_day
import homesafe.shared.generated.resources.weather_outlook_around
import homesafe.shared.generated.resources.weather_outlook_feels
import homesafe.shared.generated.resources.weather_outlook_from_to
import homesafe.shared.generated.resources.weather_outlook_gusts
import homesafe.shared.generated.resources.weather_outlook_in_hours
import homesafe.shared.generated.resources.weather_outlook_in_hours_minutes
import homesafe.shared.generated.resources.weather_outlook_in_minutes
import homesafe.shared.generated.resources.weather_outlook_now
import homesafe.shared.generated.resources.weather_outlook_on_off
import homesafe.shared.generated.resources.weather_outlook_rest_of_today
import homesafe.shared.generated.resources.weather_outlook_until
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

/** The worst a day has in store: what falls first, then what the air is doing. */
enum class OutlookKind { DRY, RAIN, SNOW, MIX, STORM, WIND, HEAT, COLD }

/**
 * A day for someone planning around it: what it holds ([kind]), when or how bad ([timing]), how
 * soon it starts when that is worth saying ([note]), and the numbers to mark it with.
 */
@Immutable
data class DayOutlook(val kind: OutlookKind, val timing: UiText, val note: UiText?, val marks: List<UiText>)

/** Today and tomorrow, side by side. [tomorrow] is null where the forecast stops at today. */
@Immutable
data class Outlooks(val today: DayOutlook, val tomorrow: DayOutlook?) {
    /** Whether either day has something to say. Two dry days leave the screen as it was. */
    val hasNews: Boolean get() = today.kind != OutlookKind.DRY || (tomorrow != null && tomorrow.kind != OutlookKind.DRY)
}

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
     * when it stays dry. [offsetAt] reads the place's clock at a moment ([WeatherReport.offsetAt]).
     */
    fun spanPhrase(spans: List<WetSpan>, fromEpochSeconds: Long, toEpochSeconds: Long, utcOffsetSeconds: Int): UiText? =
        spanPhrase(spans, fromEpochSeconds, toEpochSeconds) { utcOffsetSeconds }

    fun spanPhrase(spans: List<WetSpan>, fromEpochSeconds: Long, toEpochSeconds: Long, offsetAt: (Long) -> Int): UiText? {
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
                UiText.of(pick(family, AROUND), WeatherFormat.hour(first.startEpochSeconds, offsetAt(first.startEpochSeconds)))

            else -> UiText.of(
                pick(family, FROM_TO),
                max(first.startEpochSeconds, fromEpochSeconds).let { WeatherFormat.hour(it, offsetAt(it)) },
                WeatherFormat.hour(last.endEpochSeconds, offsetAt(last.endEpochSeconds)),
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
        // An end is two dry quarter-hours in a row, as a stop is; a dry one at the very edge of what
        // the forecast shows isn't known to be an end.
        val end = (start + 1 until slices.lastIndex).firstOrNull { i -> !slices[i].isWet && !slices[i + 1].isWet }
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
            spanPhrase(wetSpans(ahead), from, to, report::offsetAt)?.let { return it }
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
        val horizon = report.daily.getOrNull(today + 1)?.takeIf { today >= 0 }?.let { it.epochSeconds + WeatherReport.DAY_SECONDS }
            ?: (nowEpochSeconds + 36 * WeatherReport.HOUR_SECONDS)
        val hours = report.hoursFrom(nowEpochSeconds).filter { it.epochSeconds < horizon }
        if (hours.size < MIN_WINDOW_HOURS) return null
        val scores = hours.map { comfort(it) }
        var best: OutdoorWindow? = null
        var bestRank = 0.0
        // Every stretch of two to five hours in a row that are all at least fair, from every start.
        for (start in hours.indices) {
            var sum = 0
            for (end in start until minOf(start + MAX_WINDOW_HOURS, hours.size)) {
                val consecutive = hours[end].epochSeconds - hours[start].epochSeconds == (end - start) * WeatherReport.HOUR_SECONDS
                if (scores[end] < FAIR_SCORE || !consecutive) break
                sum += scores[end]
                val length = end - start + 1
                if (length < MIN_WINDOW_HOURS) continue
                val average = sum.toDouble() / length
                // The better hours win; between stretches as good as each other, the longer one does.
                val rank = average + (length - MIN_WINDOW_HOURS) * LENGTH_BONUS
                if (best == null || rank > bestRank) {
                    bestRank = rank
                    best = OutdoorWindow(
                        startEpochSeconds = hours[start].epochSeconds,
                        endEpochSeconds = hours[end].epochSeconds + WeatherReport.HOUR_SECONDS,
                        score = average.toInt(),
                        temperatureC = hours.subList(start, end + 1).map { it.feelsLikeC }.average(),
                    )
                }
            }
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

    /**
     * What today (from [nowEpochSeconds] on) and tomorrow hold, or null without a forecast for
     * today. Rain counts by the notifications' own measure, so the screen and the shade agree.
     */
    fun outlooks(report: WeatherReport, units: WeatherUnits, nowEpochSeconds: Long): Outlooks? {
        val today = report.today(nowEpochSeconds) ?: return null
        return Outlooks(outlook(report, today, units, nowEpochSeconds), report.tomorrow(nowEpochSeconds)?.let { outlook(report, it, units, null) })
    }

    /** What [day] holds; [nowEpochSeconds] is the present when it is today, and only what is still ahead counts. */
    private fun outlook(report: WeatherReport, day: DayForecast, units: WeatherUnits, nowEpochSeconds: Long?): DayOutlook {
        val end = day.epochSeconds + WeatherReport.DAY_SECONDS
        val hours = if (nowEpochSeconds == null) report.hoursOf(day) else report.hoursFrom(nowEpochSeconds).filter { it.epochSeconds < end }
        val from = hours.firstOrNull()?.epochSeconds ?: day.epochSeconds
        // The quarter-hours can run past midnight, and rain that starts then is tomorrow's.
        val near = nowEpochSeconds?.let { now -> nearTerm(report, now).takeUnless { it is NearTerm.Starting && it.startEpochSeconds >= end } } ?: NearTerm.Dry
        val notable = wetSpans(hours).filter { it.totalMm >= WeatherNoticePlanner.NOTABLE_MM || it.snowCm >= WeatherNoticePlanner.NOTABLE_SNOW_CM }
        // An hour counts as wet from its start, however late in it the rain comes: the quarter-hours know when.
        val spans = notable.mapIndexed { i, span ->
            if (i == 0 && near is NearTerm.Starting && near.startEpochSeconds < span.endEpochSeconds) span.copy(startEpochSeconds = near.startEpochSeconds) else span
        }
        val range = UiText.of(Res.string.weather_notice_art_range, WeatherFormat.degrees(day.highC, units), WeatherFormat.degrees(day.lowC, units))
        if (spans.isNotEmpty() || near != NearTerm.Dry) {
            val family = if (spans.isNotEmpty()) dominantFamily(spans) else familyOf(near)
            val starts = (near as? NearTerm.Starting)?.startEpochSeconds ?: spans.firstOrNull()?.startEpochSeconds
            return DayOutlook(
                kind = when (family) {
                    Precipitation.SNOW -> OutlookKind.SNOW
                    Precipitation.MIX -> OutlookKind.MIX
                    Precipitation.STORM -> OutlookKind.STORM
                    else -> OutlookKind.RAIN
                },
                timing = when {
                    // Unless more follows after it stops: the tile's amount counts that too.
                    near is NearTerm.Stopping && spans.none { it.startEpochSeconds >= near.endEpochSeconds } -> UiText.of(Res.string.weather_outlook_until, WeatherFormat.hourOrClock(near.endEpochSeconds, report.offsetAt(near.endEpochSeconds)))

                    spans.isNotEmpty() -> spanTiming(spans, from, end, nowEpochSeconds != null, report::offsetAt)

                    near is NearTerm.Starting -> UiText.of(Res.string.weather_outlook_around, WeatherFormat.hourOrClock(near.startEpochSeconds, report.offsetAt(near.startEpochSeconds)))

                    else -> UiText.of(Res.string.weather_outlook_now)
                },
                note = if (nowEpochSeconds != null && starts != null && starts > nowEpochSeconds) startsIn(starts - nowEpochSeconds) else null,
                marks = listOfNotNull(
                    spans.takeIf { it.isNotEmpty() }?.let { WeatherNoticePlanner.amount(it, units).second },
                    day.precipitationProbability?.let { WeatherFormat.percent(it).asUiText() },
                ),
            )
        }
        val gust = hours.maxOfOrNull { it.gustKmh } ?: 0.0
        val hottest = hours.maxOfOrNull { it.feelsLikeC }
        val coldest = hours.minOfOrNull { it.feelsLikeC }
        return when {
            gust >= WeatherNoticePlanner.WINDY_GUST_KMH -> DayOutlook(OutlookKind.WIND, UiText.of(Res.string.weather_outlook_gusts, WeatherFormat.speed(gust, units)), null, listOf(range))
            hottest != null && hottest >= WeatherNoticePlanner.HOT_FEELS_C -> DayOutlook(OutlookKind.HEAT, UiText.of(Res.string.weather_outlook_feels, WeatherFormat.degrees(hottest, units)), null, listOf(range))
            coldest != null && coldest <= WeatherNoticePlanner.BITTER_FEELS_C -> DayOutlook(OutlookKind.COLD, UiText.of(Res.string.weather_outlook_feels, WeatherFormat.degrees(coldest, units)), null, listOf(range))
            else -> DayOutlook(OutlookKind.DRY, UiText.of(if (nowEpochSeconds != null) Res.string.weather_outlook_rest_of_today else Res.string.weather_outlook_all_day), null, listOf(range))
        }
    }

    /**
     * When [spans] fall within the stretch from [from] to [to], for a tile that has already said
     * what falls: "3 PM to 6 PM", "All day", "On and off". [spanPhrase]'s reading, without the word
     * for the weather; a stretch that is [today] is the rest of today rather than a whole day.
     */
    private fun spanTiming(spans: List<WetSpan>, from: Long, to: Long, today: Boolean, offsetAt: (Long) -> Int): UiText {
        val first = spans.first()
        val last = spans.last()
        val wetHours = spans.sumOf { it.hours }
        val windowHours = max(1, ((to - from) / WeatherReport.HOUR_SECONDS).toInt())
        return when {
            spans.size == 1 && first.startEpochSeconds <= from && first.endEpochSeconds >= to ->
                UiText.of(if (today) Res.string.weather_outlook_rest_of_today else Res.string.weather_outlook_all_day)

            spans.size >= 3 || (spans.size == 2 && wetHours * 2 < windowHours && last.startEpochSeconds - first.endEpochSeconds > 4 * WeatherReport.HOUR_SECONDS) ->
                UiText.of(Res.string.weather_outlook_on_off)

            spans.size == 1 && first.startEpochSeconds <= from ->
                UiText.of(Res.string.weather_outlook_until, WeatherFormat.hour(first.endEpochSeconds, offsetAt(first.endEpochSeconds)))

            spans.size == 1 && first.hours <= 1 ->
                UiText.of(Res.string.weather_outlook_around, WeatherFormat.hourOrClock(first.startEpochSeconds, offsetAt(first.startEpochSeconds)))

            else -> UiText.of(
                Res.string.weather_outlook_from_to,
                max(first.startEpochSeconds, from).let { WeatherFormat.hourOrClock(it, offsetAt(it)) },
                WeatherFormat.hour(last.endEpochSeconds, offsetAt(last.endEpochSeconds)),
            )
        }
    }

    /** "In about 1 hr 20 min"; from three hours on, to the nearest hour. */
    private fun startsIn(seconds: Long): UiText {
        val minutes = WeatherFormat.roundedMinutes(seconds)
        return when {
            minutes < 60 -> UiText.of(Res.string.weather_outlook_in_minutes, minutes)
            minutes >= 180 || minutes % 60 == 0 -> UiText.of(Res.string.weather_outlook_in_hours, (minutes + 30) / 60)
            else -> UiText.of(Res.string.weather_outlook_in_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    private fun familyOf(near: NearTerm): Precipitation = when (near) {
        is NearTerm.Starting -> near.family
        is NearTerm.Stopping -> near.family
        is NearTerm.Continuing -> near.family
        NearTerm.Dry -> Precipitation.RAIN
    }

    /** The first day past tomorrow worth planning around, or null when the forecast holds none. */
    fun nextWetDay(report: WeatherReport, nowEpochSeconds: Long): DayForecast? = report.daysFromToday(nowEpochSeconds).drop(2).firstOrNull { it.isWet }

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
    private const val MIN_WINDOW_HOURS = 2
    private const val MAX_WINDOW_HOURS = 5

    /** What an extra hour is worth against a point of average comfort: enough to break a tie, not to dilute a fine pair of hours. */
    private const val LENGTH_BONUS = 1.5
}
