package com.meticulouscreations.homesafe.weather.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.weather_alert_until
import homesafe.shared.generated.resources.weather_minutes_short
import homesafe.shared.generated.resources.weather_notice_air
import homesafe.shared.generated.resources.weather_notice_amount
import homesafe.shared.generated.resources.weather_notice_art_range
import homesafe.shared.generated.resources.weather_notice_cold
import homesafe.shared.generated.resources.weather_notice_colder_tomorrow
import homesafe.shared.generated.resources.weather_notice_fact_aqi
import homesafe.shared.generated.resources.weather_notice_fact_uv
import homesafe.shared.generated.resources.weather_notice_freeze
import homesafe.shared.generated.resources.weather_notice_gusts
import homesafe.shared.generated.resources.weather_notice_heat
import homesafe.shared.generated.resources.weather_notice_heavy_at_times
import homesafe.shared.generated.resources.weather_notice_high_tomorrow
import homesafe.shared.generated.resources.weather_notice_sample_body
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
import homesafe.shared.generated.resources.weather_now
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
 * What a weather notification is about, as the picture it wears: its icon in the status bar,
 * its colour, the art in the shade. One per thing worth telling apart at a glance.
 */
enum class WeatherNoticeIcon {
    RAIN,
    SNOW,
    MIX,
    STORM,
    FOG,
    WIND,
    SUN,
    HEAT,
    COLD,
    FREEZE,
    WARMER,
    COLDER,
    AIR,
    TORNADO,
    FLOOD,
    HURRICANE,
    FIRE,

    /** A government warning of a kind with no picture of its own. */
    WARNING,
    ;

    companion object {
        fun of(family: Precipitation): WeatherNoticeIcon = when (family) {
            Precipitation.SNOW -> SNOW
            Precipitation.MIX -> MIX
            Precipitation.STORM -> STORM
            else -> RAIN
        }

        /**
         * What [alert] warns of. Read from its VTEC phenomenon where it has one (the two letters
         * in [WeatherAlert.key], which mean the same whatever the message says), and otherwise
         * from the agency's own name for the event.
         */
        fun of(alert: WeatherAlert): WeatherNoticeIcon = phenomenon(alert.key)?.let(PHENOMENA::get) ?: named(alert.event)

        /** "TO" from `KJAX.TO.W.0012`; null for a key that isn't a VTEC event's. */
        private fun phenomenon(key: String): String? {
            val parts = key.split('.')
            return parts.getOrNull(1)?.takeIf { parts.size == 4 && it.length == 2 && parts[2].length == 1 && it.all(Char::isUpperCase) }
        }

        private fun named(event: String): WeatherNoticeIcon {
            val name = event.lowercase()
            return EVENT_WORDS.firstOrNull { (words, _) -> words.any(name::contains) }?.second ?: WARNING
        }

        /** The National Weather Service's phenomenon codes (directive 10-1703), by the picture each gets. */
        private val PHENOMENA: Map<String, WeatherNoticeIcon> = buildMap {
            fun all(icon: WeatherNoticeIcon, vararg codes: String) = codes.forEach { put(it, icon) }
            all(TORNADO, "TO")
            all(STORM, "SV")
            all(FLOOD, "FF", "FA", "FL", "CF", "LS", "HY", "SS", "SU", "TS", "RP", "BH")
            all(HURRICANE, "HU", "TR", "TY", "HI", "TI")
            all(SNOW, "WS", "BZ", "SN", "HS", "LE", "SQ", "WW", "SB", "BS", "LB")
            all(MIX, "IS", "ZR", "IP", "ZF", "UP", "ZY")
            all(FREEZE, "FZ", "FR", "HZ")
            all(COLD, "WC", "EC", "CW", "XC")
            all(HEAT, "HT", "EH", "XH")
            all(WIND, "HW", "WI", "EW", "LW", "BW", "GL", "SR", "HF", "SC", "DS", "DU", "MA")
            all(FIRE, "FW")
            all(FOG, "FG", "SM", "AF", "MF", "MS", "MH")
            all(AIR, "AS", "AQ")
        }

        /** For a warning with no VTEC (a statement, an air quality alert): words in its name, most telling first. */
        private val EVENT_WORDS: List<Pair<List<String>, WeatherNoticeIcon>> = listOf(
            listOf("tornado") to TORNADO,
            listOf("thunderstorm") to STORM,
            listOf("hurricane", "tropical", "typhoon") to HURRICANE,
            listOf("flood", "surge", "tsunami", "surf", "rip current") to FLOOD,
            listOf("blizzard", "snow", "winter") to SNOW,
            listOf("ice", "freezing rain", "sleet") to MIX,
            listOf("freeze", "frost") to FREEZE,
            listOf("wind chill", "cold") to COLD,
            listOf("heat") to HEAT,
            listOf("fire", "red flag") to FIRE,
            listOf("air quality", "air stagnation") to AIR,
            listOf("fog", "smoke", "ash") to FOG,
            listOf("wind", "gale", "dust") to WIND,
        )
    }
}

/** One number worth its own mark on a notification's picture: how much rain, how hard the gusts. [icon] null for a remark with no picture. */
@Immutable
data class WeatherNoticeFact(val icon: WeatherNoticeIcon?, val text: UiText)

/** The forecast behind a notification, for the chart on its picture. [ticks] are the words along its foot, left to right. */
@Immutable
sealed interface WeatherNoticeChart {
    val ticks: List<UiText>

    /** The quarter-hours from the one in progress: how hard it will fall in each, with the shower starting at [startEpochSeconds]. */
    data class Soon(val slices: List<PrecipSlice>, val startEpochSeconds: Long, override val ticks: List<UiText>) : WeatherNoticeChart

    /** One day by the hour: its temperature and what falls. [nowEpochSeconds] marks the present on today's; null on a day still to come. */
    data class Day(val hours: List<HourForecast>, val nowEpochSeconds: Long?, override val ticks: List<UiText>) : WeatherNoticeChart
}

/**
 * One notification the weather app wants to post. [key] names the thing it's about (this
 * alert, this morning, this shower), so the same thing is never announced twice: see
 * [WeatherNoticePlanner.plan]'s `sent`.
 *
 * [title] and [body] are the notification as it first shows. The rest is what makes it more
 * than two lines of text: which picture it wears ([icon]), the place it is for, when what it
 * tells of happens and when it stops being news, and what its art shows ([headline] and [caption]
 * beside the picture, [facts] as marks under them, [chart] across the foot).
 */
@Immutable
data class WeatherNotice(
    val key: String,
    val kind: WeatherNoticeKind,
    val title: UiText,
    val body: UiText,
    val icon: WeatherNoticeIcon,
    /** The place it is for, or null when that has no name to give. */
    val place: String? = null,
    /** Daylight when what it tells of happens: the picture's sky. */
    val isDay: Boolean = true,
    /** All there is to say, for the notification opened out; null when [body] already is. */
    val detail: UiText? = null,
    /** When what it tells of happens (a shower's first drops): the time the notification wears. Null for one about a whole day. */
    val eventEpochSeconds: Long? = null,
    /** When it stops being news and is taken down: the shower over, the day done, the warning lapsed. */
    val expiresEpochSeconds: Long? = null,
    val caption: UiText? = null,
    val headline: UiText? = null,
    val facts: List<WeatherNoticeFact> = emptyList(),
    val chart: WeatherNoticeChart? = null,
    /** For a government warning, its [WeatherAlert.key]: a tap opens the warning itself. */
    val alertKey: String? = null,
) {
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
        val quiet = isQuiet(hour)

        if (settings.severeAlerts) {
            report.alerts
                .filter { it.isUrgent && (it.endsEpochSeconds == null || it.endsEpochSeconds > nowEpochSeconds) }
                .forEach { alert -> notices += warning(alert, report) }
        }

        if (!quiet && settings.precipitationSoon) soon(report, placeName, nowEpochSeconds, sent)?.let { notices += it }

        if (!quiet && settings.dailyOutlook) {
            if (hour in MORNING_HOURS) today(report, settings, units, nowEpochSeconds)?.let { notices += it }
            if (hour in EVENING_HOURS) tomorrow(report, settings, units, nowEpochSeconds)?.let { notices += it }
        }
        return notices.filter { it.key !in sent }.map { it.copy(place = placeName) }
    }

    /**
     * Whether a shower's notification is held back at [localHour] on the place's clock, whatever
     * the forecast says: the rules of [plan] that don't need one to go by.
     */
    fun holdsBackSoon(localHour: Int, nowEpochSeconds: Long, sent: Map<String, Long>): Boolean =
        isQuiet(localHour) || saidSoonLately(nowEpochSeconds, sent)

    private fun isQuiet(hour: Int): Boolean = hour < QUIET_UNTIL_HOUR || hour >= QUIET_FROM_HOUR

    private fun saidSoonLately(now: Long, sent: Map<String, Long>): Boolean =
        sent.any { (key, at) -> key.startsWith(SOON_PREFIX) && now - at < SOON_COOLDOWN_SECONDS }

    /**
     * A notification to look at, asked for from Settings: today as the forecast has it, whether
     * or not there is news in it, with everything a real one carries (its picture, the day's
     * chart, the place, a tap that opens Today). Null without a forecast for today. It is no
     * part of [plan]: nothing remembers it was sent, and it is gone in ten minutes.
     */
    fun sample(report: WeatherReport, placeName: String?, units: WeatherUnits, nowEpochSeconds: Long): WeatherNotice? {
        val day = report.today(nowEpochSeconds) ?: return null
        val hours = report.hoursOf(day)
        val spans = WeatherStory.wetSpans(hours)
        val gust = hours.maxOfOrNull { it.gustKmh } ?: 0.0
        val uv = hours.filter { it.isDay }.maxOfOrNull { it.uvIndex ?: 0.0 } ?: 0.0
        val icon = when {
            spans.isNotEmpty() -> WeatherNoticeIcon.of(WeatherStory.dominantFamily(spans))
            report.current.kind == WeatherKind.FOG -> WeatherNoticeIcon.FOG
            gust >= WINDY_GUST_KMH -> WeatherNoticeIcon.WIND
            else -> WeatherNoticeIcon.SUN
        }
        return outlook(
            key = SAMPLE_KEY,
            kind = WeatherNoticeKind.TODAY,
            title = WeatherStory.headline(report, units, nowEpochSeconds),
            body = UiText.of(Res.string.weather_notice_sample_body),
            icon = icon,
            facts = listOfNotNull(
                spans.takeIf { it.isNotEmpty() }?.let { WeatherNoticeFact(icon, amount(it, units).second) },
                WeatherNoticeFact(WeatherNoticeIcon.WIND, WeatherFormat.speed(gust, units)),
                WeatherNoticeFact(WeatherNoticeIcon.SUN, UiText.of(Res.string.weather_notice_fact_uv, uv.toInt())).takeIf { uv >= 1.0 },
            ),
            report = report,
            day = day,
            units = units,
            expires = nowEpochSeconds + SAMPLE_SECONDS,
            now = nowEpochSeconds,
        ).copy(place = placeName, isDay = report.current.isDay)
    }

    private fun warning(alert: WeatherAlert, report: WeatherReport): WeatherNotice {
        val until = alert.endsEpochSeconds?.let { ends ->
            val offset = report.offsetAt(ends)
            UiText.of(Res.string.weather_alert_until, UiText.of(WeatherFormat.weekday(ends, offset)), WeatherFormat.clock(ends, offset))
        }
        // The agency's words as it wrote them, but for its line breaks: it wraps its paragraphs by hand.
        val said = listOf(alert.headline, alert.instruction, alert.description).map(::unwrapped).filter { it.isNotBlank() }
        return WeatherNotice(
            // By what the warning is, not by its message: an update to it is not a new warning.
            key = "alert:${alert.key}",
            kind = WeatherNoticeKind.SEVERE_ALERT,
            title = alert.event.asUiText(),
            body = excerpt(said.firstOrNull() ?: alert.event, ALERT_BODY_CHARS).asUiText(),
            icon = WeatherNoticeIcon.of(alert),
            // An excerpt where the agency ran long: what to do comes before the description, and a tap opens the rest.
            detail = said.takeIf { it.size > 1 }?.joinToString("\n\n")?.let { excerpt(it, ALERT_DETAIL_CHARS) }?.asUiText(),
            expiresEpochSeconds = alert.endsEpochSeconds,
            caption = alert.sender.takeIf { it.isNotBlank() }?.asUiText(),
            headline = until,
            alertKey = alert.key,
        )
    }

    /** [text] whole if it fits in [max] characters; otherwise cut back to the last whole word that does, and marked as cut. */
    private fun excerpt(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max - 1)
        return cut.substring(0, cut.indexOfLast(Char::isWhitespace).takeIf { it > max / 2 } ?: cut.length).trimEnd() + ELLIPSIS
    }

    /** [text] with the line breaks inside its paragraphs taken out, and the blank lines between them kept. */
    private fun unwrapped(text: String): String =
        text.trim().split(PARAGRAPH_BREAK).joinToString("\n\n") { paragraph -> paragraph.trim().replace(LINE_BREAK, " ") }

    private fun soon(report: WeatherReport, placeName: String?, now: Long, sent: Map<String, Long>): WeatherNotice? {
        val near = WeatherStory.nearTerm(report, now) as? NearTerm.Starting ?: return null
        val lead = near.startEpochSeconds - now
        if (lead > SOON_LEAD_SECONDS) return null
        // One shower, one notification: the next check will see the same start a little nearer.
        if (saidSoonLately(now, sent)) return null
        val title = WeatherStory.nearTermPhrase(near, now) ?: return null
        val at = WeatherFormat.clock(near.startEpochSeconds, report.offsetAt(near.startEpochSeconds))
        val lasting = near.endEpochSeconds?.let { end -> WeatherFormat.roundedMinutes(end - near.startEpochSeconds) }
        val where = when {
            placeName != null && lasting != null -> UiText.of(Res.string.weather_notice_soon_body_lasting, at, placeName, lasting)
            placeName != null -> UiText.of(Res.string.weather_notice_soon_body, at, placeName)
            lasting != null -> UiText.of(Res.string.weather_notice_soon_here_lasting, at, lasting)
            else -> UiText.of(Res.string.weather_notice_soon_here, at)
        }
        val heavy = near.peakMmPerHour >= HEAVY_MM_PER_HOUR
        val body = if (heavy) dotted(listOf(where, UiText.of(Res.string.weather_notice_heavy_at_times))) else where
        val icon = WeatherNoticeIcon.of(near.family)
        val slices = report.slicesFrom(now).take(SOON_CHART_SLICES)
        return WeatherNotice(
            key = "$SOON_PREFIX${near.startEpochSeconds / WeatherReport.HOUR_SECONDS}",
            kind = WeatherNoticeKind.PRECIPITATION_SOON,
            title = title,
            body = body,
            icon = icon,
            isDay = report.current.isDay,
            eventEpochSeconds = near.startEpochSeconds,
            // Until it has stopped; where the forecast doesn't see it stop, for as long as it sees at all.
            expiresEpochSeconds = near.endEpochSeconds ?: (near.startEpochSeconds + SOON_LINGER_SECONDS),
            headline = at,
            facts = listOfNotNull(
                lasting?.let { WeatherNoticeFact(icon, UiText.of(Res.string.weather_minutes_short, it)) },
                WeatherNoticeFact(null, UiText.of(Res.string.weather_notice_heavy_at_times)).takeIf { heavy },
            ),
            chart = WeatherNoticeChart.Soon(
                slices = slices,
                startEpochSeconds = near.startEpochSeconds,
                // "Now", then the clock at each half hour after it.
                ticks = slices.indices.filter { it % 2 == 0 }.map { i ->
                    if (i == 0) UiText.of(Res.string.weather_now) else slices[i].epochSeconds.let { WeatherFormat.hourOrClock(it, report.offsetAt(it)) }
                },
            ),
        )
    }

    /** One thing an outlook has to say: the title it would give the notification, its clause of the body, and its mark on the picture. */
    private class Item(val title: StringResource, val text: UiText, val icon: WeatherNoticeIcon, val fact: UiText)

    private fun wet(spans: List<WetSpan>, phrase: UiText, titles: Map<Precipitation, StringResource>, units: WeatherUnits): Item {
        val family = WeatherStory.dominantFamily(spans)
        val amount = amount(spans, units)
        return Item(titles[family] ?: titles.getValue(Precipitation.RAIN), dotted(listOf(phrase, amount.first)), WeatherNoticeIcon.of(family), amount.second)
    }

    private fun today(report: WeatherReport, settings: WeatherNoticeSettings, units: WeatherUnits, now: Long): WeatherNotice? {
        val day = report.today(now) ?: return null
        val end = day.epochSeconds + WeatherReport.DAY_SECONDS
        val hours = report.hoursFrom(now).filter { it.epochSeconds < end }
        val items = ArrayList<Item>()

        val spans = WeatherStory.wetSpans(hours).filter { it.totalMm >= NOTABLE_MM || it.snowCm >= NOTABLE_SNOW_CM }
        if (spans.isNotEmpty() && hours.isNotEmpty()) {
            WeatherStory.spanPhrase(spans, hours.first().epochSeconds, end, report::offsetAt)?.let { items += wet(spans, it, TODAY_TITLES, units) }
        }
        if (settings.extremes) {
            val gust = hours.maxOfOrNull { it.gustKmh } ?: 0.0
            if (gust >= WINDY_GUST_KMH) {
                val speed = WeatherFormat.speed(gust, units)
                items += Item(Res.string.weather_notice_title_wind, UiText.of(Res.string.weather_notice_gusts, speed), WeatherNoticeIcon.WIND, speed)
            }
            val hottest = hours.maxOfOrNull { it.feelsLikeC }
            if (hottest != null && hottest >= HOT_FEELS_C) {
                val degrees = WeatherFormat.degrees(hottest, units)
                items += Item(Res.string.weather_notice_title_heat, UiText.of(Res.string.weather_notice_heat, degrees), WeatherNoticeIcon.HEAT, degrees.asUiText())
            }
            val coldest = hours.minOfOrNull { it.feelsLikeC }
            if (coldest != null && coldest <= BITTER_FEELS_C) {
                val degrees = WeatherFormat.degrees(coldest, units)
                items += Item(Res.string.weather_notice_title_cold, UiText.of(Res.string.weather_notice_cold, degrees), WeatherNoticeIcon.COLD, degrees.asUiText())
            }
            report.air?.takeIf { it.usAqi >= UNHEALTHY_AQI }?.let { air ->
                items += Item(Res.string.weather_notice_title_air, UiText.of(Res.string.weather_notice_air, air.usAqi), WeatherNoticeIcon.AIR, UiText.of(Res.string.weather_notice_fact_aqi, air.usAqi))
            }
            // Strong sun is worth a mention on a day that has something else to say, and not a
            // notification of its own: in summer that would be one every clear morning.
            val uv = hours.filter { it.isDay }.maxOfOrNull { it.uvIndex ?: 0.0 } ?: 0.0
            if (uv >= STRONG_UV && items.isNotEmpty()) {
                items += Item(Res.string.weather_notice_title_uv, UiText.of(Res.string.weather_notice_uv, uv.toInt()), WeatherNoticeIcon.SUN, UiText.of(Res.string.weather_notice_fact_uv, uv.toInt()))
            }
        }
        if (items.isEmpty()) return null
        return outlook("today:${day.epochSeconds}", WeatherNoticeKind.TODAY, items, items.map { it.text }, report, day, units, expires = end, now = now)
    }

    private fun tomorrow(report: WeatherReport, settings: WeatherNoticeSettings, units: WeatherUnits, now: Long): WeatherNotice? {
        val today = report.today(now) ?: return null
        val next = report.tomorrow(now) ?: return null
        val hours = report.hoursOf(next)
        val items = ArrayList<Item>()

        val spans = WeatherStory.wetSpans(hours).filter { it.totalMm >= NOTABLE_MM || it.snowCm >= NOTABLE_SNOW_CM }
        if (spans.isNotEmpty()) {
            WeatherStory.spanPhrase(spans, next.epochSeconds, next.epochSeconds + WeatherReport.DAY_SECONDS, report::offsetAt)?.let { items += wet(spans, it, TOMORROW_TITLES, units) }
        }
        var swung = false
        if (settings.extremes) {
            // The first freeze after milder nights is the one that catches plants and pipes out.
            if (next.lowC <= 0.0 && today.lowC > 0.5) {
                val low = WeatherFormat.degrees(next.lowC, units)
                items += Item(Res.string.weather_notice_title_freeze, UiText.of(Res.string.weather_notice_freeze, low), WeatherNoticeIcon.FREEZE, low.asUiText())
            }
            val swing = next.highC - today.highC
            if (abs(swing) >= SWING_C) {
                swung = true
                val high = WeatherFormat.degrees(next.highC, units)
                val by = WeatherFormat.degreesBetween(swing, units)
                items += if (swing < 0) {
                    Item(Res.string.weather_notice_title_colder_tomorrow, UiText.of(Res.string.weather_notice_colder_tomorrow, high, by), WeatherNoticeIcon.COLDER, by.asUiText())
                } else {
                    Item(Res.string.weather_notice_title_warmer_tomorrow, UiText.of(Res.string.weather_notice_warmer_tomorrow, high, by), WeatherNoticeIcon.WARMER, by.asUiText())
                }
            }
        }
        if (items.isEmpty()) return null
        // A wet day with no swing still says how warm it gets: the first thing asked after "will it rain".
        val parts = items.map { it.text }.toMutableList()
        if (!swung) parts += UiText.of(Res.string.weather_notice_high_tomorrow, WeatherFormat.degrees(next.highC, units))
        // Old news once tomorrow's own morning has had its say.
        val expires = next.epochSeconds + (MORNING_HOURS.last + 1) * WeatherReport.HOUR_SECONDS
        return outlook("tomorrow:${next.epochSeconds}", WeatherNoticeKind.TOMORROW, items, parts, report, next, units, expires, now = null)
    }

    /** The notification for [day] that [items] add up to: the first of them names it, and all of them mark its picture. */
    private fun outlook(
        key: String,
        kind: WeatherNoticeKind,
        items: List<Item>,
        parts: List<UiText>,
        report: WeatherReport,
        day: DayForecast,
        units: WeatherUnits,
        expires: Long,
        now: Long?,
    ): WeatherNotice = outlook(
        key = key,
        kind = kind,
        title = UiText.of(items.first().title),
        body = dotted(parts),
        icon = items.first().icon,
        facts = items.take(MAX_FACTS).map { WeatherNoticeFact(it.icon, it.fact) },
        report = report,
        day = day,
        units = units,
        expires = expires,
        now = now,
    )

    /** A notification about [day] as a whole: its name and range beside the picture, and its hours as the chart. */
    private fun outlook(
        key: String,
        kind: WeatherNoticeKind,
        title: UiText,
        body: UiText,
        icon: WeatherNoticeIcon,
        facts: List<WeatherNoticeFact>,
        report: WeatherReport,
        day: DayForecast,
        units: WeatherUnits,
        expires: Long,
        now: Long?,
    ): WeatherNotice {
        val hours = report.hoursOf(day)
        return WeatherNotice(
            key = key,
            kind = kind,
            title = title,
            body = body,
            icon = icon,
            expiresEpochSeconds = expires,
            caption = UiText.of(WeatherFormat.weekdayLong(day.epochSeconds, report.utcOffsetSeconds)),
            headline = UiText.of(Res.string.weather_notice_art_range, WeatherFormat.degrees(day.highC, units), WeatherFormat.degrees(day.lowC, units)),
            facts = facts.take(MAX_FACTS),
            chart = WeatherNoticeChart.Day(
                hours = hours,
                nowEpochSeconds = now,
                // Midnight, six, noon and six: each word sits at the start of its quarter of the day.
                ticks = hours.filterIndexed { i, _ -> i % 6 == 0 }.map { WeatherFormat.hour(it.epochSeconds, report.offsetAt(it.epochSeconds)) },
            ).takeIf { hours.size >= MIN_CHART_HOURS },
        )
    }

    /** How much falls in [spans]: as a clause for the body ("About 0.4 in"), and as the number alone for the picture. */
    private fun amount(spans: List<WetSpan>, units: WeatherUnits): Pair<UiText, UiText> {
        val snow = spans.sumOf { it.snowCm }
        val water = spans.sumOf { it.totalMm }
        return if (WeatherStory.dominantFamily(spans) == Precipitation.SNOW && snow >= NOTABLE_SNOW_CM) {
            WeatherFormat.snow(snow, units).let { UiText.of(Res.string.weather_notice_snow_amount, it) to it }
        } else {
            WeatherFormat.precipitation(water, units).let { UiText.of(Res.string.weather_notice_amount, it) to it }
        }
    }

    private fun dotted(parts: List<UiText>): UiText = if (parts.size == 1) parts.single() else UiText.Joined(parts, UiText.of(Res.string.common_dot_separator))

    private val TODAY_TITLES = mapOf(
        Precipitation.RAIN to Res.string.weather_notice_title_rain_today,
        Precipitation.SNOW to Res.string.weather_notice_title_snow_today,
        Precipitation.MIX to Res.string.weather_notice_title_mix_today,
        Precipitation.STORM to Res.string.weather_notice_title_storm_today,
    )
    private val TOMORROW_TITLES = mapOf(
        Precipitation.RAIN to Res.string.weather_notice_title_rain_tomorrow,
        Precipitation.SNOW to Res.string.weather_notice_title_snow_tomorrow,
        Precipitation.MIX to Res.string.weather_notice_title_mix_tomorrow,
        Precipitation.STORM to Res.string.weather_notice_title_storm_tomorrow,
    )

    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val LINE_BREAK = Regex("\\s*\\n\\s*")

    private const val SOON_PREFIX = "soon:"

    /** The sample's key: one sample replaces the last. */
    const val SAMPLE_KEY = "sample"

    /** A sample is for looking at now. */
    private const val SAMPLE_SECONDS = 10 * 60L

    /** Two hours of quarter-hours, with the one in progress: what the picture's chart shows. */
    private const val SOON_CHART_SLICES = 9

    /** How long a shower's notification stays up when the forecast doesn't see the shower end. */
    private const val SOON_LINGER_SECONDS = 2 * 3600L

    /** As many marks as fit across the picture. */
    private const val MAX_FACTS = 3

    /** Fewer hours than this is no day to draw. */
    private const val MIN_CHART_HOURS = 12

    /** A shower further off than this is the forecast's business, not a notification's. */
    const val SOON_LEAD_SECONDS = 75 * 60L

    /** After announcing one shower, how long before another is worth a second buzz. */
    const val SOON_COOLDOWN_SECONDS = 3 * 3600L

    private const val QUIET_UNTIL_HOUR = 6
    private const val QUIET_FROM_HOUR = 22
    private val MORNING_HOURS = 6..10
    private val EVENING_HOURS = 18..21

    private const val ALERT_BODY_CHARS = 240

    /** Marks agency text that was cut to fit. Punctuation, the same in any language. */
    private const val ELLIPSIS = "…"

    /** What an opened-out notification has room for; the warning's own page, a tap away, has the rest. */
    private const val ALERT_DETAIL_CHARS = 700

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
