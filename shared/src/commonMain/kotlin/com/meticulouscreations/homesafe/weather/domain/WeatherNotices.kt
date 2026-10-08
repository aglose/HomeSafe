package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.weather_notice_air
import homesafe.shared.generated.resources.weather_notice_amount
import homesafe.shared.generated.resources.weather_notice_cold
import homesafe.shared.generated.resources.weather_notice_colder_tomorrow
import homesafe.shared.generated.resources.weather_notice_freeze
import homesafe.shared.generated.resources.weather_notice_gusts
import homesafe.shared.generated.resources.weather_notice_heat
import homesafe.shared.generated.resources.weather_notice_heavy_at_times
import homesafe.shared.generated.resources.weather_notice_high_tomorrow
import homesafe.shared.generated.resources.weather_notice_snow_amount
import homesafe.shared.generated.resources.weather_notice_soon_body
import homesafe.shared.generated.resources.weather_notice_soon_body_lasting
import homesafe.shared.generated.resources.weather_notice_soon_here
import homesafe.shared.generated.resources.weather_notice_soon_here_lasting
import homesafe.shared.generated.resources.weather_notice_title_air
import homesafe.shared.generated.resources.weather_notice_title_cold
import homesafe.shared.generated.resources.weather_notice_title_colder_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_freeze
import homesafe.shared.generated.resources.weather_notice_title_heat
import homesafe.shared.generated.resources.weather_notice_title_mix_today
import homesafe.shared.generated.resources.weather_notice_title_mix_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_rain_today
import homesafe.shared.generated.resources.weather_notice_title_rain_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_snow_today
import homesafe.shared.generated.resources.weather_notice_title_snow_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_storm_today
import homesafe.shared.generated.resources.weather_notice_title_storm_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_uv
import homesafe.shared.generated.resources.weather_notice_title_warmer_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_wind
import homesafe.shared.generated.resources.weather_notice_uv
import homesafe.shared.generated.resources.weather_notice_warmer_tomorrow
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs

enum class WeatherNoticeKind {
    /** A government warning. Sent whatever the hour. */
    SEVERE_ALERT,

    /** Rain, snow or a storm arriving within the hour or so. */
    PRECIPITATION_SOON,

    /** What today holds, sent once in the morning. */
    TODAY,

    /** What tomorrow holds, sent once in the evening. */
    TOMORROW,
}

/**
 * One notification the weather app wants to post. [key] names the thing it's about (this
 * alert, this morning, this shower), so the same thing is never announced twice: see
 * [WeatherNoticePlanner.plan]'s `sent`.
 */
@Immutable
data class WeatherNotice(val key: String, val kind: WeatherNoticeKind, val title: UiText, val body: UiText) {
    val urgent: Boolean get() = kind == WeatherNoticeKind.SEVERE_ALERT
}

/**
 * Decides which notifications a forecast deserves. The aim is the handful that change what
 * someone does with their day, each said once: rain about to start, a warning, and one look
 * ahead morning and evening when there's something in it. Nothing in the night but warnings.
 *
 * A pure function of the forecast, the clock and what was already sent, so the same rules run in
 * the background check and in a test.
 */
object WeatherNoticePlanner {

    /**
     * The notices due at [nowEpochSeconds] for [report], the forecast for [placeName] (null when
     * the place has no name to give: the phone's position, somewhere nobody could name). [sent]
     * holds the keys already posted and when; anything in it is left out, and a shower
     * announced within [SOON_COOLDOWN_SECONDS] silences the next one.
     */
    fun plan(
        report: WeatherReport,
        placeName: String?,
        settings: WeatherNoticeSettings,
        units: WeatherUnits,
        nowEpochSeconds: Long,
        sent: Map<String, Long> = emptyMap(),
    ): List<WeatherNotice> {
        if (!settings.enabled) return emptyList()
        val notices = ArrayList<WeatherNotice>()
        val hour = report.localHour(nowEpochSeconds)
        val quiet = hour < QUIET_UNTIL_HOUR || hour >= QUIET_FROM_HOUR

        if (settings.severeAlerts) {
            report.alerts
                .filter { it.isUrgent && (it.endsEpochSeconds == null || it.endsEpochSeconds > nowEpochSeconds) }
                .forEach { alert ->
                    notices += WeatherNotice(
                        // By what the warning is, not by its message: an update to it is not a new warning.
                        key = "alert:${alert.key}",
                        kind = WeatherNoticeKind.SEVERE_ALERT,
                        title = alert.event.asUiText(),
                        body = alert.headline.ifBlank { alert.description }.take(ALERT_BODY_CHARS).asUiText(),
                    )
                }
        }

        if (!quiet && settings.precipitationSoon) soon(report, placeName, nowEpochSeconds, sent)?.let { notices += it }

        if (!quiet && settings.dailyOutlook) {
            if (hour in MORNING_HOURS) today(report, settings, units, nowEpochSeconds)?.let { notices += it }
            if (hour in EVENING_HOURS) tomorrow(report, settings, units, nowEpochSeconds)?.let { notices += it }
        }
        return notices.filter { it.key !in sent }
    }

    private fun soon(report: WeatherReport, placeName: String?, now: Long, sent: Map<String, Long>): WeatherNotice? {
        val near = WeatherStory.nearTerm(report, now) as? NearTerm.Starting ?: return null
        val lead = near.startEpochSeconds - now
        if (lead > SOON_LEAD_SECONDS) return null
        // One shower, one notification: the next check will see the same start a little nearer.
        if (sent.any { (key, at) -> key.startsWith(SOON_PREFIX) && now - at < SOON_COOLDOWN_SECONDS }) return null
        val title = WeatherStory.nearTermPhrase(near, now) ?: return null
        val at = WeatherFormat.clock(near.startEpochSeconds, report.offsetAt(near.startEpochSeconds))
        val lasting = near.endEpochSeconds?.let { end -> WeatherFormat.roundedMinutes(end - near.startEpochSeconds) }
        val where = when {
            placeName != null && lasting != null -> UiText.of(Res.string.weather_notice_soon_body_lasting, at, placeName, lasting)
            placeName != null -> UiText.of(Res.string.weather_notice_soon_body, at, placeName)
            lasting != null -> UiText.of(Res.string.weather_notice_soon_here_lasting, at, lasting)
            else -> UiText.of(Res.string.weather_notice_soon_here, at)
        }
        val body = if (near.peakMmPerHour >= HEAVY_MM_PER_HOUR) dotted(listOf(where, UiText.of(Res.string.weather_notice_heavy_at_times))) else where
        return WeatherNotice("$SOON_PREFIX${near.startEpochSeconds / WeatherReport.HOUR_SECONDS}", WeatherNoticeKind.PRECIPITATION_SOON, title, body)
    }

    private fun today(report: WeatherReport, settings: WeatherNoticeSettings, units: WeatherUnits, now: Long): WeatherNotice? {
        val day = report.today(now) ?: return null
        val end = day.epochSeconds + WeatherReport.DAY_SECONDS
        val hours = report.hoursFrom(now).filter { it.epochSeconds < end }
        val items = ArrayList<Pair<StringResource, UiText>>()

        val spans = WeatherStory.wetSpans(hours).filter { it.totalMm >= NOTABLE_MM || it.snowCm >= NOTABLE_SNOW_CM }
        if (spans.isNotEmpty() && hours.isNotEmpty()) {
            val family = WeatherStory.dominantFamily(spans)
            val phrase = WeatherStory.spanPhrase(spans, hours.first().epochSeconds, end, report::offsetAt)
            if (phrase != null) {
                items += when (family) {
                    Precipitation.SNOW -> Res.string.weather_notice_title_snow_today
                    Precipitation.MIX -> Res.string.weather_notice_title_mix_today
                    Precipitation.STORM -> Res.string.weather_notice_title_storm_today
                    else -> Res.string.weather_notice_title_rain_today
                } to dotted(listOf(phrase, amount(spans, units)))
            }
        }
        if (settings.extremes) {
            val gust = hours.maxOfOrNull { it.gustKmh } ?: 0.0
            if (gust >= WINDY_GUST_KMH) items += Res.string.weather_notice_title_wind to UiText.of(Res.string.weather_notice_gusts, WeatherFormat.speed(gust, units))
            val hottest = hours.maxOfOrNull { it.feelsLikeC }
            if (hottest != null && hottest >= HOT_FEELS_C) items += Res.string.weather_notice_title_heat to UiText.of(Res.string.weather_notice_heat, WeatherFormat.degrees(hottest, units))
            val coldest = hours.minOfOrNull { it.feelsLikeC }
            if (coldest != null && coldest <= BITTER_FEELS_C) items += Res.string.weather_notice_title_cold to UiText.of(Res.string.weather_notice_cold, WeatherFormat.degrees(coldest, units))
            report.air?.takeIf { it.usAqi >= UNHEALTHY_AQI }?.let { air ->
                items += Res.string.weather_notice_title_air to UiText.of(Res.string.weather_notice_air, air.usAqi)
            }
            // Strong sun is worth a mention on a day that has something else to say, and not a
            // notification of its own: in summer that would be one every clear morning.
            val uv = hours.filter { it.isDay }.maxOfOrNull { it.uvIndex ?: 0.0 } ?: 0.0
            if (uv >= STRONG_UV && items.isNotEmpty()) items += Res.string.weather_notice_title_uv to UiText.of(Res.string.weather_notice_uv, uv.toInt())
        }
        if (items.isEmpty()) return null
        return WeatherNotice("today:${day.epochSeconds}", WeatherNoticeKind.TODAY, UiText.of(items.first().first), dotted(items.map { it.second }))
    }

    private fun tomorrow(report: WeatherReport, settings: WeatherNoticeSettings, units: WeatherUnits, now: Long): WeatherNotice? {
        val today = report.today(now) ?: return null
        val next = report.tomorrow(now) ?: return null
        val hours = report.hoursOf(next)
        val items = ArrayList<Pair<StringResource, UiText>>()

        val spans = WeatherStory.wetSpans(hours).filter { it.totalMm >= NOTABLE_MM || it.snowCm >= NOTABLE_SNOW_CM }
        if (spans.isNotEmpty()) {
            val family = WeatherStory.dominantFamily(spans)
            val phrase = WeatherStory.spanPhrase(spans, next.epochSeconds, next.epochSeconds + WeatherReport.DAY_SECONDS, report::offsetAt)
            if (phrase != null) {
                items += when (family) {
                    Precipitation.SNOW -> Res.string.weather_notice_title_snow_tomorrow
                    Precipitation.MIX -> Res.string.weather_notice_title_mix_tomorrow
                    Precipitation.STORM -> Res.string.weather_notice_title_storm_tomorrow
                    else -> Res.string.weather_notice_title_rain_tomorrow
                } to dotted(listOf(phrase, amount(spans, units)))
            }
        }
        if (settings.extremes) {
            // The first freeze after milder nights is the one that catches plants and pipes out.
            if (next.lowC <= 0.0 && today.lowC > 0.5) {
                items += Res.string.weather_notice_title_freeze to UiText.of(Res.string.weather_notice_freeze, WeatherFormat.degrees(next.lowC, units))
            }
            val swing = next.highC - today.highC
            if (abs(swing) >= SWING_C) {
                val high = WeatherFormat.degrees(next.highC, units)
                val by = WeatherFormat.degreesBetween(swing, units)
                items += if (swing < 0) {
                    Res.string.weather_notice_title_colder_tomorrow to UiText.of(Res.string.weather_notice_colder_tomorrow, high, by)
                } else {
                    Res.string.weather_notice_title_warmer_tomorrow to UiText.of(Res.string.weather_notice_warmer_tomorrow, high, by)
                }
            }
        }
        if (items.isEmpty()) return null
        // A wet day with no swing still says how warm it gets: the first thing asked after "will it rain".
        val parts = items.map { it.second }.toMutableList()
        if (items.none { it.first == Res.string.weather_notice_title_colder_tomorrow || it.first == Res.string.weather_notice_title_warmer_tomorrow }) {
            parts += UiText.of(Res.string.weather_notice_high_tomorrow, WeatherFormat.degrees(next.highC, units))
        }
        return WeatherNotice("tomorrow:${next.epochSeconds}", WeatherNoticeKind.TOMORROW, UiText.of(items.first().first), dotted(parts))
    }

    private fun amount(spans: List<WetSpan>, units: WeatherUnits): UiText {
        val snow = spans.sumOf { it.snowCm }
        val water = spans.sumOf { it.totalMm }
        return if (WeatherStory.dominantFamily(spans) == Precipitation.SNOW && snow >= NOTABLE_SNOW_CM) {
            UiText.of(Res.string.weather_notice_snow_amount, WeatherFormat.snow(snow, units))
        } else {
            UiText.of(Res.string.weather_notice_amount, WeatherFormat.precipitation(water, units))
        }
    }

    private fun dotted(parts: List<UiText>): UiText = if (parts.size == 1) parts.single() else UiText.Joined(parts, UiText.of(Res.string.common_dot_separator))

    private const val SOON_PREFIX = "soon:"

    /** A shower further off than this is the forecast's business, not a notification's. */
    const val SOON_LEAD_SECONDS = 75 * 60L

    /** After announcing one shower, how long before another is worth a second buzz. */
    const val SOON_COOLDOWN_SECONDS = 3 * 3600L

    private const val QUIET_UNTIL_HOUR = 6
    private const val QUIET_FROM_HOUR = 22
    private val MORNING_HOURS = 6..10
    private val EVENING_HOURS = 18..21

    private const val ALERT_BODY_CHARS = 240

    /** Less than this over a spell is a sprinkle. */
    private const val NOTABLE_MM = 1.0
    private const val NOTABLE_SNOW_CM = 1.0
    private const val HEAVY_MM_PER_HOUR = 7.6

    /** 40 mph: branches down, bins over. */
    private const val WINDY_GUST_KMH = 64.0

    /** 100 °F as felt. */
    private const val HOT_FEELS_C = 37.8

    /** 0 °F as felt. */
    private const val BITTER_FEELS_C = -17.8
    private const val STRONG_UV = 8.0
    private const val UNHEALTHY_AQI = 151

    /** A day-to-day change in the high big enough to dress differently for: 8 °C, about 14 °F. */
    private const val SWING_C = 8.0
}
