package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.DAY
import com.meticulouscreations.homesafe.weather.WeatherData.HOUR
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.WeatherData.report
import com.meticulouscreations.homesafe.weather.WeatherData.slices
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_alert_until
import homesafe.shared.generated.resources.weather_minutes_short
import homesafe.shared.generated.resources.weather_notice_art_range
import homesafe.shared.generated.resources.weather_notice_fact_aqi
import homesafe.shared.generated.resources.weather_notice_fact_uv
import homesafe.shared.generated.resources.weather_notice_heavy_at_times
import homesafe.shared.generated.resources.weather_notice_sample_body
import homesafe.shared.generated.resources.weather_now
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import homesafe.shared.generated.resources.weather_unit_kmh
import homesafe.shared.generated.resources.weather_unit_mm
import homesafe.shared.generated.resources.weather_weekday_long_fri
import homesafe.shared.generated.resources.weather_weekday_long_thu
import homesafe.shared.generated.resources.weather_weekday_thu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a notice carries beyond its two lines of text: the picture it wears, what its art shows,
 * the place, and when it happens and is over. The words themselves are `WeatherNoticePlannerTest`'s.
 */
class WeatherNoticePictureTest {

    private val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)

    private fun plan(now: Long, report: WeatherReport, placeName: String? = null, settings: WeatherNoticeSettings = WeatherNoticeSettings()) =
        WeatherNoticePlanner.plan(report, placeName, settings, metric, now)

    private fun pm(text: String) = UiText.of(Res.string.weather_time_pm, text)

    private fun am(text: String) = UiText.of(Res.string.weather_time_am, text)

    private fun warning(event: String, key: String = "urn:x") = WeatherAlert(id = "urn:x", key = key, event = event, severity = AlertSeverity.SEVERE)

    // ---- Which picture ------------------------------------------------------------------------

    @Test
    fun aWarningWearsThePictureOfItsVtecPhenomenonWhateverItIsCalled() {
        assertEquals(WeatherNoticeIcon.TORNADO, WeatherNoticeIcon.of(warning("Something Else Entirely", key = "KPQR.TO.W.0012")))
        assertEquals(WeatherNoticeIcon.STORM, WeatherNoticeIcon.of(warning("x", key = "KPQR.SV.W.0040")))
        assertEquals(WeatherNoticeIcon.FLOOD, WeatherNoticeIcon.of(warning("x", key = "KJAX.FF.W.0003")))
        assertEquals(WeatherNoticeIcon.FLOOD, WeatherNoticeIcon.of(warning("x", key = "KJAX.CF.W.0001")))
        assertEquals(WeatherNoticeIcon.HURRICANE, WeatherNoticeIcon.of(warning("x", key = "KMFL.HU.W.1009")))
        assertEquals(WeatherNoticeIcon.SNOW, WeatherNoticeIcon.of(warning("x", key = "KBUF.BZ.W.0001")))
        assertEquals(WeatherNoticeIcon.MIX, WeatherNoticeIcon.of(warning("x", key = "KBUF.IS.W.0001")))
        assertEquals(WeatherNoticeIcon.HEAT, WeatherNoticeIcon.of(warning("x", key = "KPSR.XH.W.0002")))
        assertEquals(WeatherNoticeIcon.COLD, WeatherNoticeIcon.of(warning("x", key = "KFGF.EC.W.0002")))
        assertEquals(WeatherNoticeIcon.WIND, WeatherNoticeIcon.of(warning("x", key = "KPQR.HW.W.0002")))
        assertEquals(WeatherNoticeIcon.FIRE, WeatherNoticeIcon.of(warning("x", key = "KLOX.FW.W.0002")))
    }

    @Test
    fun aWarningWithNoVtecIsReadFromItsName() {
        assertEquals(WeatherNoticeIcon.TORNADO, WeatherNoticeIcon.of(warning("Tornado Warning")))
        assertEquals(WeatherNoticeIcon.STORM, WeatherNoticeIcon.of(warning("Severe Thunderstorm Warning")))
        assertEquals(WeatherNoticeIcon.FLOOD, WeatherNoticeIcon.of(warning("Flash Flood Warning")), "a flash flood is a flood, though the word has \"ash\" in it")
        assertEquals(WeatherNoticeIcon.FLOOD, WeatherNoticeIcon.of(warning("Storm Surge Warning")))
        assertEquals(WeatherNoticeIcon.HURRICANE, WeatherNoticeIcon.of(warning("Tropical Storm Warning")))
        assertEquals(WeatherNoticeIcon.SNOW, WeatherNoticeIcon.of(warning("Winter Storm Warning")))
        assertEquals(WeatherNoticeIcon.MIX, WeatherNoticeIcon.of(warning("Ice Storm Warning")))
        assertEquals(WeatherNoticeIcon.COLD, WeatherNoticeIcon.of(warning("Wind Chill Warning")), "the chill, not the wind")
        assertEquals(WeatherNoticeIcon.WIND, WeatherNoticeIcon.of(warning("High Wind Warning")))
        assertEquals(WeatherNoticeIcon.FIRE, WeatherNoticeIcon.of(warning("Red Flag Warning")))
        assertEquals(WeatherNoticeIcon.AIR, WeatherNoticeIcon.of(warning("Air Quality Alert")))
        assertEquals(WeatherNoticeIcon.FOG, WeatherNoticeIcon.of(warning("Dense Fog Advisory")))
        assertEquals(WeatherNoticeIcon.WARNING, WeatherNoticeIcon.of(warning("Civil Emergency Message")))
    }

    @Test
    fun aMessageIdWithDotsInItIsNotTakenForAVtecKey() {
        assertEquals(WeatherNoticeIcon.WARNING, WeatherNoticeIcon.of(warning("Special Weather Statement", key = "urn:oid:2.49.0.1")))
    }

    // ---- A warning ----------------------------------------------------------------------------

    @Test
    fun aWarningOpensOutIntoAllTheAgencySaidWithItsHandWrappedLinesRejoined() {
        val now = at(0, 15)
        val alert = WeatherAlert(
            id = "urn:1",
            key = "KPQR.TO.W.0012",
            event = "Tornado Warning",
            headline = "Tornado Warning issued October 9 at 3:02PM PDT",
            description = "* WHAT...A tornado was\nspotted near Gresham.\n\n* WHERE...Multnomah County.",
            instruction = "TAKE COVER NOW! Move to a basement\nor an interior room.",
            severity = AlertSeverity.EXTREME,
            sender = "NWS Portland OR",
            endsEpochSeconds = at(0, 15, 45),
        )
        val notice = plan(now, report(now, alerts = listOf(alert)), placeName = "Portland").single()
        assertEquals("Tornado Warning issued October 9 at 3:02PM PDT".asUiText(), notice.body)
        assertEquals(
            (
                "Tornado Warning issued October 9 at 3:02PM PDT\n\n" +
                    "TAKE COVER NOW! Move to a basement or an interior room.\n\n" +
                    "* WHAT...A tornado was spotted near Gresham.\n\n* WHERE...Multnomah County."
                ).asUiText(),
            notice.detail,
        )
        assertEquals(WeatherNoticeIcon.TORNADO, notice.icon)
        assertEquals("Portland", notice.place)
        assertEquals("NWS Portland OR".asUiText(), notice.caption)
        assertEquals(UiText.of(Res.string.weather_alert_until, UiText.of(Res.string.weather_weekday_thu), pm("3:45")), notice.headline)
        assertEquals(at(0, 15, 45), notice.expiresEpochSeconds, "down when the warning lapses")
        assertEquals("KPQR.TO.W.0012", notice.alertKey)
        assertNull(notice.chart)
    }

    @Test
    fun aWarningThatSaysOneThingHasNothingMoreToOpenOutInto() {
        val now = at(0, 15)
        val notice = plan(now, report(now, alerts = listOf(warning("Tornado Warning").copy(headline = "Take cover")))).single()
        assertNull(notice.detail)
        assertNull(notice.headline)
        assertNull(notice.expiresEpochSeconds)
    }

    @Test
    fun aWarningWithNoWordsAtAllStillSaysWhatItIs() {
        val now = at(0, 15)
        assertEquals("Tornado Warning".asUiText(), plan(now, report(now, alerts = listOf(warning("Tornado Warning")))).single().body)
    }

    // ---- Rain on the way ----------------------------------------------------------------------

    @Test
    fun aShowerOnTheWayShowsTheNextTwoHoursAndIsOverWhenItStops() {
        val now = at(0, 15)
        val minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val notice = plan(now, report(now, minutely = minutely), placeName = "Portland").single()
        assertEquals(WeatherNoticeIcon.RAIN, notice.icon)
        assertEquals("Portland", notice.place)
        assertEquals(at(0, 15, 30), notice.eventEpochSeconds)
        assertEquals(at(0, 16), notice.expiresEpochSeconds)
        assertEquals(pm("3:30"), notice.headline)
        assertEquals(listOf(WeatherNoticeFact(WeatherNoticeIcon.RAIN, UiText.of(Res.string.weather_minutes_short, 30))), notice.facts)
        val chart = notice.chart as WeatherNoticeChart.Soon
        assertEquals(minutely.take(9), chart.slices, "the quarter-hour in progress and two hours after it")
        assertEquals(at(0, 15, 30), chart.startEpochSeconds)
        assertEquals(listOf(UiText.of(Res.string.weather_now), pm("3:30"), pm("4"), pm("4:30"), pm("5")), chart.ticks)
    }

    @Test
    fun aHeavyShowerWithNoEndInSightSaysSoAndStaysUpTwoHours() {
        val now = at(0, 15)
        val notice = plan(now, report(now, minutely = slices(now, 0.0, 0.0, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5))).single()
        assertEquals(listOf(WeatherNoticeFact(null, UiText.of(Res.string.weather_notice_heavy_at_times))), notice.facts)
        assertEquals(at(0, 15, 30) + 2 * HOUR, notice.expiresEpochSeconds)
        assertNull(notice.place)
    }

    @Test
    fun snowOnTheWayWearsSnowAndAStormABolt() {
        val now = at(0, 15)
        val snow = plan(now, report(now, minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, snowCm = 0.5))).single()
        assertEquals(WeatherNoticeIcon.SNOW, snow.icon)
        val storm = plan(now, report(now, minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0), current = WeatherData.current(now, weatherCode = 95))).single()
        assertEquals(WeatherNoticeIcon.STORM, storm.icon)
    }

    // ---- The day ahead ------------------------------------------------------------------------

    private val morning = at(0, 7, 30)
    private val evening = at(0, 19)

    @Test
    fun todaysOutlookWearsItsFirstNewsAndMarksEachOnThePicture() {
        val hourly = WeatherData.calmHours { h ->
            when (h.epochSeconds) {
                at(0, 14), at(0, 15), at(0, 16) -> h.copy(precipitationMm = 1.0, weatherCode = 61, precipitationProbability = 90)
                at(0, 12) -> h.copy(gustKmh = 80.0, uvIndex = 9.0)
                else -> h
            }
        }
        val notice = plan(morning, report(morning, hourly = hourly, air = AirQuality(usAqi = 160)), placeName = "Portland").single()
        assertEquals(WeatherNoticeIcon.RAIN, notice.icon)
        assertEquals(
            listOf(
                WeatherNoticeFact(WeatherNoticeIcon.RAIN, UiText.of(Res.string.weather_unit_mm, "3.0")),
                WeatherNoticeFact(WeatherNoticeIcon.WIND, UiText.of(Res.string.weather_unit_kmh, "80")),
                WeatherNoticeFact(WeatherNoticeIcon.AIR, UiText.of(Res.string.weather_notice_fact_aqi, 160)),
            ),
            notice.facts,
            "the first three: the picture has room for no more, and the text still says the sun",
        )
        assertEquals(UiText.of(Res.string.weather_weekday_long_thu), notice.caption)
        assertEquals(UiText.of(Res.string.weather_notice_art_range, "20°", "10°"), notice.headline)
        assertEquals(at(0, 0) + DAY, notice.expiresEpochSeconds, "down at the end of the day")
        assertNull(notice.eventEpochSeconds, "about the whole day: its time is when it was posted")
        val chart = notice.chart as WeatherNoticeChart.Day
        assertEquals(24, chart.hours.size)
        assertEquals(at(0, 0), chart.hours.first().epochSeconds)
        assertEquals(morning, chart.nowEpochSeconds)
        assertEquals(listOf(am("12"), am("6"), pm("12"), pm("6")), chart.ticks)
    }

    @Test
    fun aDayOfWindAloneWearsTheWind() {
        val hourly = WeatherData.calmHours { h -> if (h.epochSeconds == at(0, 12)) h.copy(gustKmh = 80.0) else h }
        assertEquals(WeatherNoticeIcon.WIND, plan(morning, report(morning, hourly = hourly)).single().icon)
    }

    @Test
    fun tomorrowsOutlookDrawsTomorrowWithNoPresentOnItAndIsOldNewsByItsOwnLateMorning() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(1, 0)) it.copy(highC = 8.0, lowC = -2.0) else it }
        val notice = plan(evening, report(evening, daily = days)).single()
        assertEquals(WeatherNoticeIcon.FREEZE, notice.icon)
        assertEquals(listOf(WeatherNoticeFact(WeatherNoticeIcon.FREEZE, "-2°".asUiText()), WeatherNoticeFact(WeatherNoticeIcon.COLDER, "12°".asUiText())), notice.facts)
        assertEquals(UiText.of(Res.string.weather_weekday_long_fri), notice.caption)
        assertEquals(UiText.of(Res.string.weather_notice_art_range, "8°", "-2°"), notice.headline)
        assertEquals(at(1, 11), notice.expiresEpochSeconds)
        val chart = notice.chart as WeatherNoticeChart.Day
        assertEquals(at(1, 0), chart.hours.first().epochSeconds)
        assertNull(chart.nowEpochSeconds)
    }

    @Test
    fun aDayWithTooFewHoursForecastHasNoChart() {
        val hourly = WeatherData.calmHours(days = -1..0).map { if (it.epochSeconds == at(0, 12)) it.copy(gustKmh = 80.0) else it }.filter { it.epochSeconds < at(0, 13) && it.epochSeconds >= at(0, 6) }
        val notice = plan(morning, report(morning, hourly = hourly)).single()
        assertNull(notice.chart)
        assertTrue(notice.facts.isNotEmpty(), "its marks are still there")
    }

    @Test
    fun strongSunIsItsOwnMarkOnADayWithOtherNews() {
        val hourly = WeatherData.calmHours { h -> if (h.epochSeconds == at(0, 12)) h.copy(gustKmh = 80.0, uvIndex = 9.0) else h }
        assertEquals(
            listOf(WeatherNoticeIcon.WIND to UiText.of(Res.string.weather_unit_kmh, "80"), WeatherNoticeIcon.SUN to UiText.of(Res.string.weather_notice_fact_uv, 9)),
            plan(morning, report(morning, hourly = hourly)).single().facts.map { it.icon to it.text },
        )
    }

    // ---- A sample, asked for from Settings ----------------------------------------------------

    @Test
    fun aSampleIsTodayAsTheForecastHasItNewsOrNot() {
        val now = at(0, 15)
        val notice = assertNotNull(WeatherNoticePlanner.sample(report(now), "Portland", metric, now))
        assertEquals(WeatherNoticePlanner.SAMPLE_KEY, notice.key)
        assertEquals(WeatherStory.headline(report(now), metric, now), notice.title, "the forecast in a sentence, as Today's hero has it")
        assertEquals(UiText.of(Res.string.weather_notice_sample_body), notice.body)
        assertEquals(WeatherNoticeIcon.SUN, notice.icon, "a calm dry day")
        assertEquals("Portland", notice.place)
        assertEquals(listOf(WeatherNoticeFact(WeatherNoticeIcon.WIND, UiText.of(Res.string.weather_unit_kmh, "8"))), notice.facts)
        assertEquals(UiText.of(Res.string.weather_notice_art_range, "20°", "10°"), notice.headline)
        val chart = notice.chart as WeatherNoticeChart.Day
        assertEquals(24, chart.hours.size)
        assertEquals(now, chart.nowEpochSeconds)
        assertEquals(now + 600, notice.expiresEpochSeconds, "for looking at now")
        assertFalse(notice.urgent)
    }

    @Test
    fun aSampleOnAWetDayWearsWhatFallsAndSaysHowMuch() {
        val now = at(0, 9)
        val hourly = WeatherData.calmHours { h ->
            if (h.epochSeconds in at(0, 14)..at(0, 16)) h.copy(precipitationMm = 1.0, snowfallCm = 1.0, weatherCode = 73, precipitationProbability = 90, uvIndex = 4.0, isDay = true) else h
        }
        val notice = assertNotNull(WeatherNoticePlanner.sample(report(now, hourly = hourly), null, metric, now))
        assertEquals(WeatherNoticeIcon.SNOW, notice.icon)
        assertEquals(listOf(WeatherNoticeIcon.SNOW, WeatherNoticeIcon.WIND, WeatherNoticeIcon.SUN), notice.facts.map { it.icon })
        assertNull(notice.place)
    }

    @Test
    fun thereIsNoSampleWithoutAForecastForToday() {
        val now = at(0, 15)
        assertNull(WeatherNoticePlanner.sample(report(now, daily = emptyList()), "Portland", metric, now))
    }
}
