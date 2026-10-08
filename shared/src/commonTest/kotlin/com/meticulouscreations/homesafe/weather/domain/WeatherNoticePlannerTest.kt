package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.HOUR
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.WeatherData.report
import com.meticulouscreations.homesafe.weather.WeatherData.slices
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
import homesafe.shared.generated.resources.weather_notice_title_rain_today
import homesafe.shared.generated.resources.weather_notice_title_rain_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_snow_today
import homesafe.shared.generated.resources.weather_notice_title_uv
import homesafe.shared.generated.resources.weather_notice_title_warmer_tomorrow
import homesafe.shared.generated.resources.weather_notice_title_wind
import homesafe.shared.generated.resources.weather_notice_uv
import homesafe.shared.generated.resources.weather_notice_warmer_tomorrow
import homesafe.shared.generated.resources.weather_span_rain_from_to
import homesafe.shared.generated.resources.weather_span_snow_from_to
import homesafe.shared.generated.resources.weather_story_rain_starting
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import homesafe.shared.generated.resources.weather_unit_cm
import homesafe.shared.generated.resources.weather_unit_kmh
import homesafe.shared.generated.resources.weather_unit_mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherNoticePlannerTest {

    private val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)
    private val all = WeatherNoticeSettings()
    private val dot = UiText.of(Res.string.common_dot_separator)

    private fun plan(
        now: Long,
        report: WeatherReport,
        settings: WeatherNoticeSettings = all,
        sent: Map<String, Long> = emptyMap(),
        placeName: String? = null,
    ) = WeatherNoticePlanner.plan(report, placeName, settings, metric, now, sent)

    private fun joined(vararg parts: UiText) = UiText.Joined(parts.toList(), dot)

    private fun pm(text: String) = UiText.of(Res.string.weather_time_pm, text)

    private fun am(text: String) = UiText.of(Res.string.weather_time_am, text)

    private fun alert(
        id: String = "a1",
        severity: AlertSeverity = AlertSeverity.SEVERE,
        endsEpochSeconds: Long? = null,
        headline: String = "Tornado warning until 5 PM",
        description: String = "A tornado has been spotted.",
    ) = WeatherAlert(id, event = "Tornado Warning", headline = headline, description = description, severity = severity, endsEpochSeconds = endsEpochSeconds)

    // ---- Severe alerts ------------------------------------------------------------------------

    @Test
    fun aSevereAlertIsAUrgentNoticeWithTheAgenciesOwnWords() {
        val now = at(0, 15)
        val notice = plan(now, report(now, alerts = listOf(alert()))).single()
        assertEquals("alert:a1", notice.key)
        assertEquals(WeatherNoticeKind.SEVERE_ALERT, notice.kind)
        assertEquals("Tornado Warning".asUiText(), notice.title)
        assertEquals("Tornado warning until 5 PM".asUiText(), notice.body)
        assertTrue(notice.urgent)
    }

    @Test
    fun anExtremeAlertIsUrgentToo() {
        val now = at(0, 15)
        assertEquals(1, plan(now, report(now, alerts = listOf(alert(severity = AlertSeverity.EXTREME)))).size)
    }

    @Test
    fun aSevereAlertIsSentEvenInTheQuietOfNight() {
        val now = at(0, 3)
        val notice = plan(now, report(now, alerts = listOf(alert()))).single()
        assertEquals(WeatherNoticeKind.SEVERE_ALERT, notice.kind)
    }

    @Test
    fun aSevereAlertIsSentOnlyOnce() {
        val now = at(0, 15)
        val r = report(now, alerts = listOf(alert()))
        assertTrue(plan(now, r, sent = mapOf("alert:a1" to now - 600)).isEmpty())
    }

    @Test
    fun aDifferentAlertIsStillNew() {
        val now = at(0, 15)
        val r = report(now, alerts = listOf(alert(id = "a2")))
        assertEquals("alert:a2", plan(now, r, sent = mapOf("alert:a1" to now - 600)).single().key)
    }

    @Test
    fun anAlertThatHasExpiredIsSkipped() {
        val now = at(0, 15)
        assertTrue(plan(now, report(now, alerts = listOf(alert(endsEpochSeconds = now - 1)))).isEmpty())
        assertTrue(plan(now, report(now, alerts = listOf(alert(endsEpochSeconds = now)))).isEmpty())
    }

    @Test
    fun anAlertStillInForceIsSent() {
        val now = at(0, 15)
        assertEquals(1, plan(now, report(now, alerts = listOf(alert(endsEpochSeconds = now + 1)))).size)
    }

    @Test
    fun aLesserAlertDoesNotInterrupt() {
        val now = at(0, 15)
        assertTrue(plan(now, report(now, alerts = listOf(alert(severity = AlertSeverity.MODERATE)))).isEmpty())
        assertTrue(plan(now, report(now, alerts = listOf(alert(severity = AlertSeverity.MINOR)))).isEmpty())
        assertTrue(plan(now, report(now, alerts = listOf(alert(severity = AlertSeverity.UNKNOWN)))).isEmpty())
    }

    @Test
    fun theDescriptionStandsInForAMissingHeadlineAndIsCutShort() {
        val now = at(0, 15)
        val long = "x".repeat(400)
        val notice = plan(now, report(now, alerts = listOf(alert(headline = "", description = long)))).single()
        assertEquals("x".repeat(240).asUiText(), notice.body)
    }

    @Test
    fun aReissuedWarningIsNotAnnouncedAgain() {
        val now = at(0, 15)
        val first = alert(id = "urn:msg:1").copy(key = "KJAX.CF.W.0001")
        val update = alert(id = "urn:msg:2").copy(key = "KJAX.CF.W.0001")
        val sent = plan(now, report(now, alerts = listOf(first))).single()
        assertEquals("alert:KJAX.CF.W.0001", sent.key)
        // The update is a new message of the same warning.
        assertTrue(plan(now, report(now, alerts = listOf(update)), sent = mapOf(sent.key to now - 600)).isEmpty())
    }

    @Test
    fun aDifferentWarningIsAnnouncedEvenAtTheSameOffice() {
        val now = at(0, 15)
        val other = alert(id = "urn:msg:3").copy(key = "KJAX.CF.W.0002")
        val notice = plan(now, report(now, alerts = listOf(other)), sent = mapOf("alert:KJAX.CF.W.0001" to now - 600)).single()
        assertEquals("alert:KJAX.CF.W.0002", notice.key)
    }

    @Test
    fun severalAlertsEachGetANotice() {
        val now = at(0, 15)
        val r = report(now, alerts = listOf(alert(id = "a1"), alert(id = "a2")))
        assertEquals(listOf("alert:a1", "alert:a2"), plan(now, r).map { it.key })
    }

    // ---- Rain starting soon -------------------------------------------------------------------

    private val soonNow = at(0, 15)

    /** Dry for two quarters, then [mm] for each quarter after. */
    private fun soonReport(vararg mm: Double) = report(soonNow, minutely = slices(soonNow, 0.0, 0.0, *mm))

    private val rainForHalfAnHour = doubleArrayOf(0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun soonKey() = "soon:${at(0, 15, 30) / HOUR}"

    @Test
    fun rainStartingInHalfAnHourIsANoticeNamingThePlace() {
        val notice = plan(soonNow, soonReport(*rainForHalfAnHour), placeName = "Portland").single()
        assertEquals(WeatherNoticeKind.PRECIPITATION_SOON, notice.kind)
        assertEquals(soonKey(), notice.key)
        assertEquals(UiText.of(Res.string.weather_story_rain_starting, 30), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_soon_body_lasting, pm("3:30"), "Portland", 30), notice.body)
        assertEquals(false, notice.urgent)
    }

    @Test
    fun withoutAPlaceNameTheBodySaysHere() {
        val notice = plan(soonNow, soonReport(*rainForHalfAnHour)).single()
        assertEquals(UiText.of(Res.string.weather_notice_soon_here_lasting, pm("3:30"), 30), notice.body)
    }

    @Test
    fun rainWithNoEndInSightLeavesOutHowLongItLasts() {
        val noEnd = soonReport(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5)
        assertEquals(UiText.of(Res.string.weather_notice_soon_body, pm("3:30"), "Portland"), plan(soonNow, noEnd, placeName = "Portland").single().body)
        assertEquals(UiText.of(Res.string.weather_notice_soon_here, pm("3:30")), plan(soonNow, noEnd).single().body)
    }

    @Test
    fun heavyRainAddsHeavyAtTimes() {
        val heavy = soonReport(2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val notice = plan(soonNow, heavy).single()
        assertEquals(joined(UiText.of(Res.string.weather_notice_soon_here_lasting, pm("3:30"), 30), UiText.of(Res.string.weather_notice_heavy_at_times)), notice.body)
    }

    @Test
    fun rainFurtherOffThanTheLeadTimeWaits() {
        // Starts 90 minutes out; the lead is 75.
        val late = soonReport(0.0, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0)
        assertTrue(plan(soonNow, late).isEmpty())
    }

    @Test
    fun rainAtExactlyTheLeadTimeIsStillSent() {
        // Starts at 3:30 with the clock at 2:15 PM: 75 minutes.
        val now = at(0, 14, 15)
        val r = report(now, minutely = slices(now, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0))
        assertEquals(1, plan(now, r).size)
    }

    @Test
    fun noSoonNoticeWhileItIsAlreadyRaining() {
        val raining = report(soonNow, minutely = slices(soonNow, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(plan(soonNow, raining).isEmpty())
    }

    @Test
    fun noSoonNoticeForADryTwoHours() {
        assertTrue(plan(soonNow, soonReport(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).isEmpty())
    }

    @Test
    fun aShowerAnnouncedAnHourAgoSilencesTheNextWhateverItsKey() {
        val sent = mapOf("soon:1" to soonNow - 3_600)
        assertTrue(plan(soonNow, soonReport(*rainForHalfAnHour), sent = sent).isEmpty())
    }

    @Test
    fun theCooldownIsThreeHours() {
        val r = soonReport(*rainForHalfAnHour)
        assertTrue(plan(soonNow, r, sent = mapOf("soon:1" to soonNow - 3 * 3_600 + 1)).isEmpty())
        assertEquals(1, plan(soonNow, r, sent = mapOf("soon:1" to soonNow - 3 * 3_600)).size)
        assertEquals(1, plan(soonNow, r, sent = mapOf("soon:1" to soonNow - 5 * 3_600)).size)
    }

    @Test
    fun otherKindsOfNoticeDoNotStartTheSoonCooldown() {
        val r = soonReport(*rainForHalfAnHour)
        assertEquals(1, plan(soonNow, r, sent = mapOf("alert:a1" to soonNow - 60, "today:1" to soonNow - 60)).size)
    }

    @Test
    fun theSameShowerIsNotAnnouncedTwice() {
        val r = soonReport(*rainForHalfAnHour)
        assertTrue(plan(soonNow, r, sent = mapOf(soonKey() to soonNow - 4 * 3_600)).isEmpty())
    }

    @Test
    fun noSoonNoticeInTheQuietHours() {
        listOf(23, 2, 5).forEach { h ->
            val now = at(0, h)
            val r = report(now, minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
            assertTrue(plan(now, r).isEmpty(), "at $h:00")
        }
    }

    @Test
    fun theQuietHoursEndAtSixAndStartAtTen() {
        fun soonAt(h: Int): List<WeatherNotice> {
            val now = at(0, h)
            return plan(now, report(now, minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)))
        }
        assertEquals(1, soonAt(6).size)
        assertEquals(1, soonAt(21).size)
        assertTrue(soonAt(22).isEmpty())
        assertTrue(soonAt(5).isEmpty())
    }

    // ---- This morning's look at today ---------------------------------------------------------

    private val morning = at(0, 7, 30)

    /** Day 0's hours with [change] applied. */
    private fun todayHours(change: (HourForecast) -> HourForecast) = WeatherData.calmHours(change = change)

    private fun hoursAt(vararg hours: Int, change: (HourForecast) -> HourForecast): (HourForecast) -> HourForecast =
        { h -> if (hours.any { h.epochSeconds == at(0, it) }) change(h) else h }

    private fun wetAfternoon() = todayHours(hoursAt(14, 15, 16) { it.copy(precipitationMm = 1.0, weatherCode = 61, precipitationProbability = 90) })

    @Test
    fun aWetDayAheadIsWorthAMorningNotice() {
        val notice = plan(morning, report(morning, hourly = wetAfternoon())).single()
        assertEquals(WeatherNoticeKind.TODAY, notice.kind)
        assertEquals("today:${at(0, 0)}", notice.key)
        assertEquals(UiText.of(Res.string.weather_notice_title_rain_today), notice.title)
        assertEquals(
            joined(
                UiText.of(Res.string.weather_span_rain_from_to, pm("2"), pm("5")),
                UiText.of(Res.string.weather_notice_amount, UiText.of(Res.string.weather_unit_mm, "3.0")),
            ),
            notice.body,
        )
    }

    @Test
    fun aQuietDryMorningSaysNothing() {
        assertTrue(plan(morning, report(morning)).isEmpty())
    }

    @Test
    fun aSprinkleIsNotNotable() {
        val sprinkle = todayHours(hoursAt(14) { it.copy(precipitationMm = 0.6, weatherCode = 61, precipitationProbability = 90) })
        assertTrue(plan(morning, report(morning, hourly = sprinkle)).isEmpty())
    }

    @Test
    fun theMorningNoticeComesOnlyBetweenSixAndTenInTheMorning() {
        listOf(6, 10).forEach { h ->
            val now = at(0, h, 15)
            assertEquals(WeatherNoticeKind.TODAY, plan(now, report(now, hourly = wetAfternoon())).single().kind, "at $h:15")
        }
        listOf(5, 11, 14).forEach { h ->
            val now = at(0, h, 15)
            assertTrue(plan(now, report(now, hourly = wetAfternoon())).none { it.kind == WeatherNoticeKind.TODAY }, "at $h:15")
        }
    }

    @Test
    fun theMorningNoticeIsSentOnlyOnce() {
        val r = report(morning, hourly = wetAfternoon())
        assertTrue(plan(morning, r, sent = mapOf("today:${at(0, 0)}" to morning - 1_800)).isEmpty())
    }

    @Test
    fun aSnowyDayAheadIsToldInSnow() {
        val snowy = todayHours(hoursAt(14, 15) { it.copy(precipitationMm = 1.0, snowfallCm = 1.5, weatherCode = 73, precipitationProbability = 90) })
        val notice = plan(morning, report(morning, hourly = snowy)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_snow_today), notice.title)
        assertEquals(
            joined(
                UiText.of(Res.string.weather_span_snow_from_to, pm("2"), pm("4")),
                UiText.of(Res.string.weather_notice_snow_amount, UiText.of(Res.string.weather_unit_cm, "3.0")),
            ),
            notice.body,
        )
    }

    @Test
    fun strongGustsAreAnExtreme() {
        val windy = todayHours(hoursAt(14) { it.copy(gustKmh = 70.0) })
        val notice = plan(morning, report(morning, hourly = windy)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_wind), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_gusts, UiText.of(Res.string.weather_unit_kmh, "70")), notice.body)
    }

    @Test
    fun gustsJustUnderFortyMilesAnHourAreNot() {
        val breezy = todayHours(hoursAt(14) { it.copy(gustKmh = 63.0) })
        assertTrue(plan(morning, report(morning, hourly = breezy)).isEmpty())
    }

    @Test
    fun aHundredDegreesAsFeltIsAnExtreme() {
        val hot = todayHours(hoursAt(15) { it.copy(feelsLikeC = 38.0) })
        val notice = plan(morning, report(morning, hourly = hot)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_heat), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_heat, "38°"), notice.body)
    }

    @Test
    fun zeroDegreesFahrenheitAsFeltIsAnExtreme() {
        val bitter = todayHours(hoursAt(8) { it.copy(feelsLikeC = -18.0) })
        val notice = plan(morning, report(morning, hourly = bitter)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_cold), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_cold, "-18°"), notice.body)
    }

    @Test
    fun strongSunAloneIsNotWorthANotice() {
        val sunny = todayHours(hoursAt(13) { it.copy(uvIndex = 9.2) })
        assertTrue(plan(morning, report(morning, hourly = sunny)).isEmpty())
    }

    @Test
    fun strongSunIsMentionedOnADayThatHasSomethingElseToSay() {
        val windyAndSunny = todayHours { h ->
            when (h.epochSeconds) {
                at(0, 14) -> h.copy(gustKmh = 70.0)
                at(0, 13) -> h.copy(uvIndex = 9.2)
                else -> h
            }
        }
        val notice = plan(morning, report(morning, hourly = windyAndSunny)).single()
        // The title is the first thing, not the sun.
        assertEquals(UiText.of(Res.string.weather_notice_title_wind), notice.title)
        assertEquals(
            joined(UiText.of(Res.string.weather_notice_gusts, UiText.of(Res.string.weather_unit_kmh, "70")), UiText.of(Res.string.weather_notice_uv, 9)),
            notice.body,
        )
    }

    @Test
    fun strongSunAtNightDoesNotCountEvenOnABusyDay() {
        val night = todayHours { h ->
            when (h.epochSeconds) {
                at(0, 14) -> h.copy(gustKmh = 70.0)
                at(0, 22) -> h.copy(uvIndex = 9.2)
                else -> h
            }
        }
        val notice = plan(morning, report(morning, hourly = night)).single()
        assertEquals(UiText.of(Res.string.weather_notice_gusts, UiText.of(Res.string.weather_unit_kmh, "70")), notice.body)
    }

    @Test
    fun strongSunDoesNotMakeADayNotableWithExtremesOff() {
        val sunny = todayHours { h ->
            when (h.epochSeconds) {
                at(0, 14) -> h.copy(precipitationMm = 0.6, weatherCode = 61, precipitationProbability = 90)
                at(0, 13) -> h.copy(uvIndex = 9.2)
                else -> h
            }
        }
        assertTrue(plan(morning, report(morning, hourly = sunny), settings = all.copy(extremes = false)).isEmpty())
    }

    @Test
    fun unhealthyAirIsAnExtreme() {
        val notice = plan(morning, report(morning, air = AirQuality(usAqi = 160))).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_air), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_air, 160), notice.body)
        assertTrue(plan(morning, report(morning, air = AirQuality(usAqi = 150))).isEmpty())
    }

    @Test
    fun theFirstItemNamesTheNoticeAndEveryItemIsInTheBody() {
        val both = todayHours { h ->
            when (h.epochSeconds) {
                at(0, 14), at(0, 15), at(0, 16) -> h.copy(precipitationMm = 1.0, weatherCode = 61, precipitationProbability = 90)
                at(0, 13) -> h.copy(gustKmh = 70.0)
                else -> h
            }
        }
        val notice = plan(morning, report(morning, hourly = both)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_rain_today), notice.title)
        assertEquals(
            // Each item is its own run of parts, and the notice joins the items.
            joined(
                joined(
                    UiText.of(Res.string.weather_span_rain_from_to, pm("2"), pm("5")),
                    UiText.of(Res.string.weather_notice_amount, UiText.of(Res.string.weather_unit_mm, "3.0")),
                ),
                UiText.of(Res.string.weather_notice_gusts, UiText.of(Res.string.weather_unit_kmh, "70")),
            ),
            notice.body,
        )
    }

    @Test
    fun extremesOffLeavesOnlyTheRain() {
        val both = todayHours { h ->
            when (h.epochSeconds) {
                at(0, 14) -> h.copy(precipitationMm = 3.0, weatherCode = 61, precipitationProbability = 90, gustKmh = 70.0)
                else -> h
            }
        }
        val notice = plan(morning, report(morning, hourly = both), settings = all.copy(extremes = false)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_rain_today), notice.title)
        assertEquals(2, (notice.body as UiText.Joined).parts.size)
    }

    @Test
    fun extremesOffLeavesNothingWhenOnlyAnExtremeIsAhead() {
        val windy = todayHours(hoursAt(14) { it.copy(gustKmh = 70.0) })
        assertTrue(plan(morning, report(morning, hourly = windy), settings = all.copy(extremes = false)).isEmpty())
    }

    @Test
    fun extremesAfterNowButOnTomorrowAreNotTodays() {
        val windyTomorrow = WeatherData.calmHours { h -> if (h.epochSeconds == at(1, 10)) h.copy(gustKmh = 90.0) else h }
        assertTrue(plan(morning, report(morning, hourly = windyTomorrow)).isEmpty())
    }

    @Test
    fun hoursAlreadyPastDoNotCountForTodayAfterAll() {
        val earlierWet = todayHours(hoursAt(6) { it.copy(precipitationMm = 5.0, weatherCode = 63, precipitationProbability = 95) })
        val now = at(0, 9)
        assertTrue(plan(now, report(now, hourly = earlierWet)).isEmpty())
    }

    // ---- This evening's look at tomorrow ------------------------------------------------------

    private val evening = at(0, 19)

    private fun tomorrowDays(change: (DayForecast) -> DayForecast) = WeatherData.calmDays { if (it.epochSeconds == at(1, 0)) change(it) else it }

    private fun tomorrowRain() = WeatherData.calmHours { h ->
        if (h.epochSeconds in at(1, 9)..at(1, 11)) h.copy(precipitationMm = 1.0, weatherCode = 61, precipitationProbability = 90) else h
    }

    @Test
    fun aWetTomorrowIsWorthAnEveningNoticeThatAlsoSaysTheHigh() {
        val notice = plan(evening, report(evening, hourly = tomorrowRain())).single()
        assertEquals(WeatherNoticeKind.TOMORROW, notice.kind)
        assertEquals("tomorrow:${at(1, 0)}", notice.key)
        assertEquals(UiText.of(Res.string.weather_notice_title_rain_tomorrow), notice.title)
        assertEquals(
            joined(
                joined(
                    UiText.of(Res.string.weather_span_rain_from_to, am("9"), pm("12")),
                    UiText.of(Res.string.weather_notice_amount, UiText.of(Res.string.weather_unit_mm, "3.0")),
                ),
                UiText.of(Res.string.weather_notice_high_tomorrow, "20°"),
            ),
            notice.body,
        )
    }

    @Test
    fun aMildDryTomorrowSaysNothing() {
        assertTrue(plan(evening, report(evening)).isEmpty())
    }

    @Test
    fun theFirstFreezeAfterMilderNightsIsNoticed() {
        val days = tomorrowDays { it.copy(lowC = -1.0) }
        val notice = plan(evening, report(evening, daily = days)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_freeze), notice.title)
        assertEquals(joined(UiText.of(Res.string.weather_notice_freeze, "-1°"), UiText.of(Res.string.weather_notice_high_tomorrow, "20°")), notice.body)
    }

    @Test
    fun aFreezeAfterAFreezingNightIsNoNews() {
        val days = WeatherData.calmDays { d ->
            when (d.epochSeconds) {
                at(0, 0) -> d.copy(lowC = 0.0)
                at(1, 0) -> d.copy(lowC = -2.0)
                else -> d
            }
        }
        assertTrue(plan(evening, report(evening, daily = days)).isEmpty())
    }

    @Test
    fun aMuchColderTomorrowIsNoticedWithItsHighAndHowMuchColder() {
        val days = tomorrowDays { it.copy(highC = 10.0) }
        val notice = plan(evening, report(evening, daily = days)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_colder_tomorrow), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_colder_tomorrow, "10°", "10°"), notice.body)
    }

    @Test
    fun aMuchWarmerTomorrowIsNoticed() {
        val days = tomorrowDays { it.copy(highC = 30.0) }
        val notice = plan(evening, report(evening, daily = days)).single()
        assertEquals(UiText.of(Res.string.weather_notice_title_warmer_tomorrow), notice.title)
        assertEquals(UiText.of(Res.string.weather_notice_warmer_tomorrow, "30°", "10°"), notice.body)
    }

    @Test
    fun aSwingOfJustUnderEightDegreesIsNot() {
        assertTrue(plan(evening, report(evening, daily = tomorrowDays { it.copy(highC = 27.9) })).isEmpty())
    }

    @Test
    fun aWetDayWithASwingDoesNotRepeatTheHigh() {
        val r = report(evening, hourly = tomorrowRain(), daily = tomorrowDays { it.copy(highC = 30.0) })
        val body = plan(evening, r).single().body as UiText.Joined
        // The rain (itself a phrase and an amount) and the swing: no separate line for the high.
        assertEquals(2, body.parts.size)
        assertTrue(body.parts.none { it == UiText.of(Res.string.weather_notice_high_tomorrow, "30°") })
        assertEquals(UiText.of(Res.string.weather_notice_warmer_tomorrow, "30°", "10°"), body.parts.last())
    }

    @Test
    fun extremesOffLeavesOnlyTomorrowsRain() {
        val r = report(evening, hourly = tomorrowRain(), daily = tomorrowDays { it.copy(highC = 30.0, lowC = -3.0) })
        val body = plan(evening, r, settings = all.copy(extremes = false)).single().body as UiText.Joined
        assertEquals(
            listOf(
                joined(
                    UiText.of(Res.string.weather_span_rain_from_to, am("9"), pm("12")),
                    UiText.of(Res.string.weather_notice_amount, UiText.of(Res.string.weather_unit_mm, "3.0")),
                ),
                UiText.of(Res.string.weather_notice_high_tomorrow, "30°"),
            ),
            body.parts,
        )
    }

    @Test
    fun extremesOffLeavesNothingWhenOnlyAFreezeIsAhead() {
        assertTrue(plan(evening, report(evening, daily = tomorrowDays { it.copy(lowC = -5.0) }), settings = all.copy(extremes = false)).isEmpty())
    }

    @Test
    fun theEveningNoticeComesOnlyBetweenSixAndTenAtNight() {
        listOf(18, 21).forEach { h ->
            val now = at(0, h, 5)
            assertEquals(WeatherNoticeKind.TOMORROW, plan(now, report(now, hourly = tomorrowRain())).single().kind, "at $h:05")
        }
        listOf(17, 22, 23).forEach { h ->
            val now = at(0, h, 5)
            assertTrue(plan(now, report(now, hourly = tomorrowRain())).isEmpty(), "at $h:05")
        }
    }

    @Test
    fun theEveningNoticeIsSentOnlyOnce() {
        val r = report(evening, hourly = tomorrowRain())
        assertTrue(plan(evening, r, sent = mapOf("tomorrow:${at(1, 0)}" to evening - 1_800)).isEmpty())
    }

    @Test
    fun tomorrowsNoticeNeedsTomorrowsForecast() {
        val r = report(evening, daily = WeatherData.calmDays(-1..0))
        assertTrue(plan(evening, r).isEmpty())
    }

    // ---- The switches and the quiet hours -----------------------------------------------------

    @Test
    fun theMasterSwitchOffSilencesEverything() {
        val r = report(morning, hourly = wetAfternoon(), alerts = listOf(alert()), minutely = slices(morning, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertTrue(plan(morning, r, settings = all.copy(enabled = false)).isEmpty())
    }

    @Test
    fun theSevereAlertsSwitchOnlyAffectsAlerts() {
        val r = report(morning, hourly = wetAfternoon(), alerts = listOf(alert()))
        val kinds = plan(morning, r, settings = all.copy(severeAlerts = false)).map { it.kind }
        assertEquals(listOf(WeatherNoticeKind.TODAY), kinds)
    }

    @Test
    fun thePrecipitationSoonSwitchOnlyAffectsTheSoonNotice() {
        val r = report(soonNow, alerts = listOf(alert()), minutely = slices(soonNow, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(listOf(WeatherNoticeKind.SEVERE_ALERT, WeatherNoticeKind.PRECIPITATION_SOON), plan(soonNow, r).map { it.kind })
        assertEquals(listOf(WeatherNoticeKind.SEVERE_ALERT), plan(soonNow, r, settings = all.copy(precipitationSoon = false)).map { it.kind })
    }

    @Test
    fun theDailyOutlookSwitchSilencesTheMorningAndEveningNoticesAlike() {
        val off = all.copy(dailyOutlook = false)
        assertTrue(plan(morning, report(morning, hourly = wetAfternoon()), settings = off).isEmpty())
        assertTrue(plan(evening, report(evening, hourly = tomorrowRain()), settings = off).isEmpty())
    }

    @Test
    fun theDailyOutlookSwitchLeavesTheSoonNoticeAlone() {
        val r = report(soonNow, minutely = slices(soonNow, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(1, plan(soonNow, r, settings = all.copy(dailyOutlook = false)).size)
    }

    @Test
    fun inTheQuietHoursNothingButAlertsIsSent() {
        listOf(23, 1, 5).forEach { h ->
            val now = at(0, h, 30)
            val r = report(
                now,
                hourly = wetAfternoon(),
                daily = tomorrowDays { it.copy(highC = 30.0, lowC = -3.0) },
                alerts = listOf(alert()),
                minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0),
            )
            assertEquals(listOf(WeatherNoticeKind.SEVERE_ALERT), plan(now, r).map { it.kind }, "at $h:30")
        }
    }

    @Test
    fun noticesComeAlertFirstThenSoonThenTheOutlook() {
        val now = at(0, 8)
        val r = report(now, hourly = wetAfternoon(), alerts = listOf(alert()), minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(
            listOf(WeatherNoticeKind.SEVERE_ALERT, WeatherNoticeKind.PRECIPITATION_SOON, WeatherNoticeKind.TODAY),
            plan(now, r).map { it.kind },
        )
    }
}
