package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.HOUR
import com.meticulouscreations.homesafe.weather.WeatherData.OFFSET
import com.meticulouscreations.homesafe.weather.WeatherData.at
import com.meticulouscreations.homesafe.weather.WeatherData.hour
import com.meticulouscreations.homesafe.weather.WeatherData.report
import com.meticulouscreations.homesafe.weather.WeatherData.slices
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_kind_clear_night
import homesafe.shared.generated.resources.weather_span_mix_around
import homesafe.shared.generated.resources.weather_span_rain_all_day
import homesafe.shared.generated.resources.weather_span_rain_around
import homesafe.shared.generated.resources.weather_span_rain_from_to
import homesafe.shared.generated.resources.weather_span_rain_on_off
import homesafe.shared.generated.resources.weather_span_snow_from_to
import homesafe.shared.generated.resources.weather_span_storm_on_off
import homesafe.shared.generated.resources.weather_story_cooler
import homesafe.shared.generated.resources.weather_story_high_low
import homesafe.shared.generated.resources.weather_story_mix_continuing
import homesafe.shared.generated.resources.weather_story_rain_continuing
import homesafe.shared.generated.resources.weather_story_rain_starting
import homesafe.shared.generated.resources.weather_story_rain_stopping
import homesafe.shared.generated.resources.weather_story_snow_starting
import homesafe.shared.generated.resources.weather_story_storm_starting
import homesafe.shared.generated.resources.weather_story_warmer
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherStoryTest {

    private val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)
    private val imperial = WeatherUnits()

    /** 3 PM on the 9th. */
    private val now = at(0, 15)

    private fun pm(hour: String) = UiText.of(Res.string.weather_time_pm, hour)

    private fun wetHour(day: Int, atHour: Int, mm: Double = 1.0, code: Int = 61, snowCm: Double = 0.0) =
        hour(at(day, atHour), precipitationMm = mm, weatherCode = code, snowfallCm = snowCm, probability = 90)

    private fun span(startHour: Int, endHour: Int, family: Precipitation = Precipitation.RAIN, mm: Double = 2.0, snowCm: Double = 0.0) =
        WetSpan(at(0, startHour), at(0, endHour), family, mm, snowCm, peakMmPerHour = 1.0)

    // ---- wetSpans -----------------------------------------------------------------------------

    @Test
    fun dryHoursMakeNoSpans() {
        assertTrue(WeatherStory.wetSpans(WeatherData.calmHours(0..0)).isEmpty())
        assertTrue(WeatherStory.wetSpans(emptyList()).isEmpty())
    }

    @Test
    fun aTraceOfRainIsNotASpan() {
        val hours = listOf(hour(at(0, 1), precipitationMm = 0.1, weatherCode = 61, probability = 20))
        assertTrue(WeatherStory.wetSpans(hours).isEmpty())
    }

    @Test
    fun consecutiveWetHoursAreOneSpanFromTheFirstHoursStartToTheLastHoursEnd() {
        val hours = listOf(wetHour(0, 3), wetHour(0, 4, mm = 2.0), wetHour(0, 5))
        val span = WeatherStory.wetSpans(hours).single()
        assertEquals(at(0, 3), span.startEpochSeconds)
        assertEquals(at(0, 6), span.endEpochSeconds)
        assertEquals(3, span.hours)
        assertEquals(4.0, span.totalMm, 1e-9)
        assertEquals(2.0, span.peakMmPerHour, 1e-9)
        assertEquals(Precipitation.RAIN, span.family)
    }

    @Test
    fun aSingleDryHourBetweenTwoWetOnesDoesNotBreakTheSpan() {
        val hours = listOf(wetHour(0, 3), wetHour(0, 4), hour(at(0, 5)), wetHour(0, 6))
        val span = WeatherStory.wetSpans(hours).single()
        assertEquals(at(0, 3), span.startEpochSeconds)
        assertEquals(at(0, 7), span.endEpochSeconds)
    }

    @Test
    fun twoDryHoursInARowSplitTheSpans() {
        val hours = listOf(wetHour(0, 3), hour(at(0, 4)), hour(at(0, 5)), wetHour(0, 6))
        val spans = WeatherStory.wetSpans(hours)
        assertEquals(2, spans.size)
        assertEquals(at(0, 4), spans[0].endEpochSeconds)
        assertEquals(at(0, 6), spans[1].startEpochSeconds)
    }

    @Test
    fun aSpanRunningToTheEndOfTheHoursIsClosed() {
        val hours = listOf(hour(at(0, 1)), wetHour(0, 2), wetHour(0, 3))
        val span = WeatherStory.wetSpans(hours).single()
        assertEquals(at(0, 4), span.endEpochSeconds)
    }

    @Test
    fun aSpanEndsAtItsLastWetHourNotAtTrailingDryOnes() {
        val hours = listOf(wetHour(0, 2), hour(at(0, 3)))
        assertEquals(at(0, 3), WeatherStory.wetSpans(hours).single().endEpochSeconds)
    }

    @Test
    fun snowfallMakesASnowSpan() {
        val hours = listOf(wetHour(0, 2, mm = 1.0, code = 73, snowCm = 1.0), wetHour(0, 3, mm = 1.0, code = 73, snowCm = 1.0))
        val span = WeatherStory.wetSpans(hours).single()
        assertEquals(Precipitation.SNOW, span.family)
        assertEquals(2.0, span.snowCm, 1e-9)
    }

    @Test
    fun rainWithSomeSnowInItIsAMix() {
        // 0.8 cm of snow out of 2 mm: not mostly snow, but enough to matter.
        val hours = listOf(wetHour(0, 2, mm = 1.0, code = 61, snowCm = 0.4), wetHour(0, 3, mm = 1.0, code = 61, snowCm = 0.4))
        assertEquals(Precipitation.MIX, WeatherStory.wetSpans(hours).single().family)
    }

    @Test
    fun freezingRainIsAMixWhateverTheSnowfall() {
        val hours = listOf(wetHour(0, 2, mm = 1.0, code = 66))
        assertEquals(Precipitation.MIX, WeatherStory.wetSpans(hours).single().family)
    }

    @Test
    fun aThunderstormHourMakesTheWholeSpanStormy() {
        val hours = listOf(wetHour(0, 2, code = 61), wetHour(0, 3, code = 95), wetHour(0, 4, code = 61))
        assertEquals(Precipitation.STORM, WeatherStory.wetSpans(hours).single().family)
    }

    @Test
    fun anHourWithBetterThanEvenOddsOfRainCountsEvenWithoutMeasurableWater() {
        val hours = listOf(hour(at(0, 2), precipitationMm = 0.0, weatherCode = 61, probability = 60))
        assertEquals(1, WeatherStory.wetSpans(hours).size)
    }

    // ---- dominantFamily -----------------------------------------------------------------------

    @Test
    fun aStormAnywhereMakesTheWholeDayStormy() {
        val spans = listOf(span(1, 2, Precipitation.RAIN, mm = 20.0), span(5, 6, Precipitation.STORM, mm = 1.0))
        assertEquals(Precipitation.STORM, WeatherStory.dominantFamily(spans))
    }

    @Test
    fun theFamilyWithTheMostWaterWins() {
        val spans = listOf(span(1, 2, Precipitation.RAIN, mm = 1.0), span(5, 8, Precipitation.SNOW, mm = 6.0, snowCm = 5.0))
        assertEquals(Precipitation.SNOW, WeatherStory.dominantFamily(spans))
    }

    @Test
    fun noSpansDefaultToRain() {
        assertEquals(Precipitation.RAIN, WeatherStory.dominantFamily(emptyList()))
    }

    // ---- spanPhrase ---------------------------------------------------------------------------

    private fun phrase(spans: List<WetSpan>, fromHour: Int = 6, toHour: Int = 24) =
        WeatherStory.spanPhrase(spans, at(0, fromHour), at(0, toHour), OFFSET)

    @Test
    fun noSpansHaveNoPhrase() {
        assertNull(phrase(emptyList()))
    }

    @Test
    fun aSingleHourIsRainAroundThatTime() {
        assertEquals(UiText.of(Res.string.weather_span_rain_around, pm("3")), phrase(listOf(span(15, 16))))
    }

    @Test
    fun aLongerSpanIsFromOneTimeToAnother() {
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, pm("2"), pm("6")), phrase(listOf(span(14, 18))))
    }

    @Test
    fun aSpanThatBeganBeforeTheWindowIsReadFromTheWindowsStart() {
        // Raining since noon, window opens at 3 PM.
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, pm("3"), pm("6")), phrase(listOf(span(12, 18)), fromHour = 15))
    }

    @Test
    fun aSpanFillingTheWholeWindowIsAllDay() {
        assertEquals(UiText.of(Res.string.weather_span_rain_all_day), phrase(listOf(span(5, 25)), fromHour = 6, toHour = 24))
    }

    @Test
    fun threeOrMoreSpansAreOnAndOff() {
        val spans = listOf(span(8, 9), span(12, 13), span(16, 17))
        assertEquals(UiText.of(Res.string.weather_span_rain_on_off), phrase(spans))
    }

    @Test
    fun twoSpansFarApartInALongDryDayAreOnAndOff() {
        val spans = listOf(span(7, 8), span(18, 19))
        assertEquals(UiText.of(Res.string.weather_span_rain_on_off), phrase(spans))
    }

    @Test
    fun twoSpansCloseTogetherAreOneStretch() {
        val spans = listOf(span(14, 15), span(17, 18))
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, pm("2"), pm("6")), phrase(spans))
    }

    @Test
    fun twoSpansThatAreMostOfTheWindowAreOneStretchNotOnAndOff() {
        val spans = listOf(span(6, 14), span(19, 23))
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, UiText.of(Res.string.weather_time_am, "6"), pm("11")), phrase(spans))
    }

    @Test
    fun theWordsFollowTheFamily() {
        assertEquals(UiText.of(Res.string.weather_span_snow_from_to, pm("2"), pm("6")), phrase(listOf(span(14, 18, Precipitation.SNOW, snowCm = 3.0))))
        assertEquals(UiText.of(Res.string.weather_span_mix_around, pm("3")), phrase(listOf(span(15, 16, Precipitation.MIX))))
        val storms = listOf(span(8, 9, Precipitation.STORM), span(12, 13, Precipitation.RAIN), span(16, 17, Precipitation.RAIN))
        assertEquals(UiText.of(Res.string.weather_span_storm_on_off), phrase(storms))
    }

    // ---- nearTerm -----------------------------------------------------------------------------

    private fun near(vararg mm: Double, snowCm: Double = 0.0, code: Int = 3, currentMm: Double = 0.0) = WeatherStory.nearTerm(
        report(now, minutely = slices(now, *mm, snowCm = snowCm), current = WeatherData.current(now, weatherCode = code, precipitationMm = currentMm)),
        now,
    )

    @Test
    fun withNoQuarterHourForecastItIsDry() {
        assertEquals(NearTerm.Dry, WeatherStory.nearTerm(report(now), now))
    }

    @Test
    fun aDryTwoHoursIsDry() {
        assertEquals(NearTerm.Dry, near(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun rainComingAndGoingIsStartingWithAnEnd() {
        // Dry for three quarters, then two wet ones, then dry.
        val result = near(0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)
        val starting = assertIs<NearTerm.Starting>(result)
        assertEquals(at(0, 15, 45), starting.startEpochSeconds)
        assertEquals(at(0, 16, 15), starting.endEpochSeconds)
        assertEquals(Precipitation.RAIN, starting.family)
        assertEquals(2.0, starting.peakMmPerHour, 1e-9)
    }

    @Test
    fun rainThatDoesNotStopWithinTheWindowIsStartingWithoutAnEnd() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5))
        assertEquals(at(0, 15, 30), starting.startEpochSeconds)
        assertNull(starting.endEpochSeconds)
    }

    @Test
    fun aSingleDryQuarterHourAtTheEdgeOfTheWindowIsNotKnownToBeTheEnd() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.0))
        assertNull(starting.endEpochSeconds)
    }

    @Test
    fun aSingleDryQuarterHourBetweenWetOnesIsNotAnEndEither() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.5, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5))
        assertNull(starting.endEpochSeconds)
    }

    @Test
    fun theEndIsTheFirstOfTwoDryQuartersInARow() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.5, 0.0, 0.5, 0.0, 0.0, 0.5, 0.5))
        assertEquals(at(0, 16, 15), starting.endEpochSeconds)
    }

    @Test
    fun twoDryQuartersAtTheVeryEndAreAnEnd() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0))
        assertEquals(at(0, 16, 45), starting.endEpochSeconds)
    }

    @Test
    fun aLoneFaintQuarterHourIsIgnored() {
        // 0.1 mm in a quarter hour is 0.4 mm/h and nothing follows it: a few drops, not rain.
        assertEquals(NearTerm.Dry, near(0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun twoFaintQuartersInARowAreRain() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.0, 0.1, 0.1, 0.0, 0.0, 0.0, 0.0))
        assertEquals(at(0, 15, 45), starting.startEpochSeconds)
    }

    @Test
    fun aLoneQuarterHourOfRealRainCounts() {
        // 0.3 mm in a quarter hour is 1.2 mm/h: a proper shower even if brief.
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.0, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(at(0, 15, 30), starting.startEpochSeconds)
        assertEquals(at(0, 15, 45), starting.endEpochSeconds)
    }

    @Test
    fun rainNowThatThenStopsForTwoQuartersIsStopping() {
        val stopping = assertIs<NearTerm.Stopping>(near(0.5, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(at(0, 15, 45), stopping.endEpochSeconds)
        assertEquals(Precipitation.RAIN, stopping.family)
    }

    @Test
    fun oneDryQuarterInTheMiddleOfRainIsALullNotAStop() {
        assertIs<NearTerm.Continuing>(near(0.5, 0.5, 0.5, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5))
    }

    @Test
    fun rainThroughoutTheWindowIsContinuing() {
        assertEquals(NearTerm.Continuing(Precipitation.RAIN), near(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5))
    }

    @Test
    fun aDryFinalQuarterIsNotEnoughToCallItStopped() {
        assertIs<NearTerm.Continuing>(near(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.0))
    }

    @Test
    fun theGaugeSayingItsRainingCountsWhenTheModelsFirstQuarterIsDry() {
        // Raining by the gauge, and the model sees nothing: it reads as about to stop.
        val result = near(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, code = 61, currentMm = 0.3)
        assertIs<NearTerm.Stopping>(result)
    }

    @Test
    fun mostlySnowingQuartersMakeItSnow() {
        val starting = assertIs<NearTerm.Starting>(near(0.0, 0.4, 0.4, 0.4, 0.4, 0.0, 0.0, 0.0, 0.0, snowCm = 0.5))
        assertEquals(Precipitation.SNOW, starting.family)
    }

    @Test
    fun aThunderstormNowMakesAnyRainStormy() {
        assertEquals(NearTerm.Continuing(Precipitation.STORM), near(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, code = 95))
    }

    @Test
    fun aThunderstormInTheNextTwoHoursMakesRainStormy() {
        val stormy = WeatherData.calmHours { if (it.epochSeconds == at(0, 16)) it.copy(weatherCode = 95) else it }
        val result = WeatherStory.nearTerm(report(now, hourly = stormy, minutely = slices(now, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)), now)
        assertEquals(Precipitation.STORM, assertIs<NearTerm.Starting>(result).family)
    }

    @Test
    fun quarterHoursAlreadyPastAreNotConsidered() {
        // Wet for the two quarters before the one in progress, dry from then on.
        val result = WeatherStory.nearTerm(report(now, minutely = slices(now - 1_800, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)), now)
        assertEquals(NearTerm.Dry, result)
    }

    // ---- nearTermPhrase -----------------------------------------------------------------------

    @Test
    fun startingRainSaysInAboutHowManyMinutes() {
        val near = NearTerm.Starting(now + 25 * 60, now + 50 * 60, Precipitation.RAIN, 2.0)
        assertEquals(UiText.of(Res.string.weather_story_rain_starting, 25), WeatherStory.nearTermPhrase(near, now))
    }

    @Test
    fun theMinutesAreRoundedToFive() {
        val near = NearTerm.Starting(now + 23 * 60, null, Precipitation.RAIN, 2.0)
        assertEquals(UiText.of(Res.string.weather_story_rain_starting, 25), WeatherStory.nearTermPhrase(near, now))
    }

    @Test
    fun startingSnowAndStormsHaveTheirOwnWords() {
        assertEquals(UiText.of(Res.string.weather_story_snow_starting, 15), WeatherStory.nearTermPhrase(NearTerm.Starting(now + 900, null, Precipitation.SNOW, 1.0), now))
        assertEquals(UiText.of(Res.string.weather_story_storm_starting, 15), WeatherStory.nearTermPhrase(NearTerm.Starting(now + 900, null, Precipitation.STORM, 1.0), now))
    }

    @Test
    fun stoppingRainSaysInAboutHowManyMinutes() {
        assertEquals(UiText.of(Res.string.weather_story_rain_stopping, 30), WeatherStory.nearTermPhrase(NearTerm.Stopping(now + 30 * 60, Precipitation.RAIN), now))
    }

    @Test
    fun continuingRainTakesNoNumber() {
        assertEquals(UiText.of(Res.string.weather_story_rain_continuing), WeatherStory.nearTermPhrase(NearTerm.Continuing(Precipitation.RAIN), now))
        assertEquals(UiText.of(Res.string.weather_story_mix_continuing), WeatherStory.nearTermPhrase(NearTerm.Continuing(Precipitation.MIX), now))
    }

    @Test
    fun dryHasNoPhrase() {
        assertNull(WeatherStory.nearTermPhrase(NearTerm.Dry, now))
    }

    // ---- headline -----------------------------------------------------------------------------

    private fun hoursWithRain(vararg wetHours: Int) =
        WeatherData.calmHours { h -> if (((h.epochSeconds - at(0, 0)) / HOUR) in wetHours.map { it.toLong() }) h.copy(precipitationMm = 1.0, weatherCode = 61, precipitationProbability = 90) else h }

    @Test
    fun theHeadlineIsTheNearTermPhraseWhenRainIsMinutesAway() {
        val r = report(now, hourly = hoursWithRain(20, 21), minutely = slices(now, 0.0, 0.0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5))
        assertEquals(UiText.of(Res.string.weather_story_rain_starting, 30), WeatherStory.headline(r, metric))
    }

    @Test
    fun theHeadlineSaysWhenRainComesLaterToday() {
        val r = report(now, hourly = hoursWithRain(20, 21))
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, pm("8"), pm("10")), WeatherStory.headline(r, metric))
    }

    @Test
    fun rainLaterBeatsTheComparisonWithYesterday() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 30.0) else it }
        val r = report(now, hourly = hoursWithRain(20, 21), daily = days)
        assertEquals(UiText.of(Res.string.weather_span_rain_from_to, pm("8"), pm("10")), WeatherStory.headline(r, metric))
    }

    @Test
    fun withNoRainTheHeadlineSaysHowMuchWarmerThanYesterday() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 27.0) else it }
        assertEquals(UiText.of(Res.string.weather_story_warmer, "7°"), WeatherStory.headline(report(now, daily = days), metric))
    }

    @Test
    fun theWarmerAmountIsInTheChosenUnit() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 27.0) else it }
        // 7 C is 12.6 F.
        assertEquals(UiText.of(Res.string.weather_story_warmer, "13°"), WeatherStory.headline(report(now, daily = days), imperial))
    }

    @Test
    fun withNoRainTheHeadlineSaysHowMuchCoolerThanYesterday() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 17.0) else it }
        assertEquals(UiText.of(Res.string.weather_story_cooler, "3°"), WeatherStory.headline(report(now, daily = days), metric))
    }

    @Test
    fun aChangeOfTwoDegreesIsNews() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 22.0) else it }
        assertEquals(UiText.of(Res.string.weather_story_warmer, "2°"), WeatherStory.headline(report(now, daily = days), metric))
    }

    @Test
    fun aSmallerChangeFallsThroughToTheHighAndLow() {
        val days = WeatherData.calmDays { if (it.epochSeconds == at(0, 0)) it.copy(highC = 21.0, lowC = 11.0) else it }
        assertEquals(UiText.of(Res.string.weather_story_high_low, "21°", "11°"), WeatherStory.headline(report(now, daily = days), metric))
    }

    @Test
    fun withoutYesterdayItIsTheHighAndLow() {
        val days = WeatherData.calmDays(0..2)
        assertEquals(UiText.of(Res.string.weather_story_high_low, "20°", "10°"), WeatherStory.headline(report(now, daily = days), metric))
    }

    @Test
    fun withNoDaysAtAllItIsTheSkyInAWord() {
        val night = report(now, daily = emptyList(), current = WeatherData.current(now, weatherCode = 0, isDay = false))
        assertEquals(UiText.of(Res.string.weather_kind_clear_night), WeatherStory.headline(night, metric))
    }

    @Test
    fun rainThatAlreadyEndedDoesNotMakeTheHeadline() {
        // It rained this morning; from 3 PM on it is dry.
        val r = report(now, hourly = hoursWithRain(6, 7))
        assertEquals(UiText.of(Res.string.weather_story_high_low, "20°", "10°"), WeatherStory.headline(r, metric))
    }

    @Test
    fun rainBeyondTheHeadlinesFourteenHourReachIsNotMentioned() {
        // 3 PM + 14 hours is 5 AM; rain at 7 AM tomorrow is further than that.
        val r = report(now, hourly = WeatherData.calmHours { if (it.epochSeconds == at(1, 7)) it.copy(precipitationMm = 2.0, weatherCode = 61) else it })
        assertEquals(UiText.of(Res.string.weather_story_high_low, "20°", "10°"), WeatherStory.headline(r, metric))
    }

    // ---- pressureTrend ------------------------------------------------------------------------

    private fun hoursWithPressure(at15: Double, at12: Double) = WeatherData.calmHours { h ->
        when (h.epochSeconds) {
            at(0, 15) -> h.copy(pressureHpa = at15)
            at(0, 12) -> h.copy(pressureHpa = at12)
            else -> h
        }
    }

    private fun trend(now: Double?, at15: Double = 1015.0, at12: Double) =
        WeatherStory.pressureTrend(report(this.now, hourly = hoursWithPressure(at15, at12), current = WeatherData.current(this.now, pressureHpa = now)), this.now)

    @Test
    fun aBarometerUpByTwoOverThreeHoursIsRising() {
        assertEquals(PressureTrend.RISING, trend(now = 1015.0, at12 = 1013.0))
    }

    @Test
    fun aBarometerDownByTwoOverThreeHoursIsFalling() {
        assertEquals(PressureTrend.FALLING, trend(now = 1015.0, at12 = 1017.0))
    }

    @Test
    fun aSmallChangeIsSteady() {
        assertEquals(PressureTrend.STEADY, trend(now = 1015.0, at12 = 1014.2))
    }

    @Test
    fun aChangeOfExactlyOnePointFiveCounts() {
        assertEquals(PressureTrend.RISING, trend(now = 1015.0, at12 = 1013.5))
        assertEquals(PressureTrend.FALLING, trend(now = 1015.0, at12 = 1016.5))
    }

    @Test
    fun withoutACurrentReadingTheHoursPressureIsUsed() {
        assertEquals(PressureTrend.RISING, trend(now = null, at15 = 1016.0, at12 = 1012.0))
    }

    @Test
    fun withNoPressureAtAllThereIsNoTrend() {
        assertNull(WeatherStory.pressureTrend(report(now), now))
    }

    @Test
    fun withoutThreeHoursOfHistoryThereIsNoTrend() {
        val short = WeatherData.calmHours(0..0).filter { it.epochSeconds >= at(0, 14) && it.epochSeconds <= at(0, 15) }
        assertNull(WeatherStory.pressureTrend(report(now, hourly = short, current = WeatherData.current(now, pressureHpa = 1015.0)), now))
    }

    // ---- comfort ------------------------------------------------------------------------------

    private fun comfort(
        feels: Double = 20.0,
        probability: Int? = 0,
        mm: Double = 0.0,
        wind: Double = 5.0,
        gust: Double = 8.0,
        uv: Double? = 0.0,
        code: Int = 3,
        day: Boolean = true,
    ) = WeatherStory.comfort(hour(at(0, 12), feelsLikeC = feels, probability = probability, precipitationMm = mm, windKmh = wind, gustKmh = gust, uvIndex = uv, weatherCode = code, isDay = day))

    @Test
    fun aMildCalmDryDayHourIsPerfect() {
        assertEquals(100, comfort())
    }

    @Test
    fun theDarkScoresNothing() {
        assertEquals(0, comfort(day = false))
    }

    @Test
    fun theColdCostsFourPointsADegree() {
        assertEquals(60, comfort(feels = 7.0))
    }

    @Test
    fun theHeatCostsFivePointsADegree() {
        assertEquals(50, comfort(feels = 34.0))
    }

    @Test
    fun theEdgesOfTheComfortableBandCostNothing() {
        assertEquals(100, comfort(feels = 17.0))
        assertEquals(100, comfort(feels = 24.0))
    }

    @Test
    fun chanceOfRainWindGustsAndSunAllCost() {
        assertEquals(72, comfort(probability = 50))
        assertEquals(70, comfort(mm = 1.0))
        assertEquals(68, comfort(wind = 38.0))
        assertEquals(80, comfort(gust = 60.0))
        assertEquals(86, comfort(uv = 9.0))
        assertEquals(75, comfort(code = 61))
    }

    @Test
    fun theScoreNeverGoesBelowZero() {
        assertEquals(0, comfort(feels = -20.0, probability = 100, mm = 10.0, wind = 90.0, gust = 120.0, code = 95))
    }

    // ---- outdoorWindow ------------------------------------------------------------------------

    @Test
    fun theBestWindowOnACalmDayIsTheFirstFiveHoursOfDaylightLeft() {
        val morning = at(0, 8)
        val window = assertNotNull(WeatherStory.outdoorWindow(report(morning), morning))
        assertEquals(at(0, 8), window.startEpochSeconds)
        assertEquals(at(0, 13), window.endEpochSeconds)
        assertEquals(100, window.score)
        assertEquals(20.0, window.temperatureC, 1e-9)
    }

    @Test
    fun aColdMorningPushesTheWindowToTheWarmerHours() {
        val hours = WeatherData.calmHours { h -> if (h.epochSeconds in at(0, 8)..at(0, 10)) h.copy(feelsLikeC = 5.0) else h }
        val window = assertNotNull(WeatherStory.outdoorWindow(report(at(0, 8), hourly = hours), at(0, 8)))
        assertEquals(at(0, 11), window.startEpochSeconds)
        assertEquals(at(0, 16), window.endEpochSeconds)
    }

    @Test
    fun aLongerFairStretchBeatsASlightlyBetterShortOne() {
        // Two perfect hours, a cold one, then five at 98 (and everything after too cold to compete).
        val hours = WeatherData.calmHours { h ->
            when {
                h.epochSeconds >= at(0, 16) -> h.copy(feelsLikeC = 5.0)
                h.epochSeconds == at(0, 10) -> h.copy(feelsLikeC = 5.0)
                h.epochSeconds in at(0, 11)..at(0, 15) -> h.copy(feelsLikeC = 16.5)
                else -> h
            }
        }
        val window = assertNotNull(WeatherStory.outdoorWindow(report(at(0, 8), hourly = hours), at(0, 8)))
        assertEquals(at(0, 11), window.startEpochSeconds)
        assertEquals(98, window.score)
    }

    @Test
    fun aShortExcellentStretchInsideALongFairOneIsFound() {
        // Two perfect hours (1 and 2 PM) with fair hours either side, and everything else cold.
        val hours = WeatherData.calmHours { h ->
            when {
                h.epochSeconds in at(0, 13)..at(0, 14) -> h
                h.epochSeconds in at(0, 9)..at(0, 17) && h.epochSeconds < at(1, 0) -> h.copy(feelsLikeC = 12.0)
                else -> h.copy(feelsLikeC = 5.0)
            }
        }
        val window = assertNotNull(WeatherStory.outdoorWindow(report(at(0, 8), hourly = hours), at(0, 8)))
        assertEquals(at(0, 13), window.startEpochSeconds)
        assertEquals(at(0, 15), window.endEpochSeconds)
        assertEquals(100, window.score)
    }

    @Test
    fun theBestWindowIsFoundWhenTodayIsNotAmongTheDays() {
        val window = assertNotNull(WeatherStory.outdoorWindow(report(at(0, 8), daily = emptyList()), at(0, 8)))
        assertEquals(at(0, 8), window.startEpochSeconds)
        assertEquals(100, window.score)
    }

    @Test
    fun stretchesAreAtMostFiveHours() {
        val window = assertNotNull(WeatherStory.outdoorWindow(report(at(0, 8)), at(0, 8)))
        assertTrue(window.endEpochSeconds - window.startEpochSeconds <= 5 * HOUR)
        assertTrue(window.endEpochSeconds - window.startEpochSeconds >= 2 * HOUR)
    }

    @Test
    fun aWetTwoDaysHaveNoWindowWorthRecommending() {
        val hours = WeatherData.calmHours { it.copy(precipitationMm = 2.0, weatherCode = 63, precipitationProbability = 95) }
        assertNull(WeatherStory.outdoorWindow(report(at(0, 8), hourly = hours), at(0, 8)))
    }

    @Test
    fun withLessThanTwoHoursOfForecastLeftThereIsNoWindow() {
        val hours = WeatherData.calmHours(-1..0).filter { it.epochSeconds <= at(0, 10) }
        assertNull(WeatherStory.outdoorWindow(report(at(0, 10), hourly = hours, daily = WeatherData.calmDays(-1..0)), at(0, 10)))
    }

    @Test
    fun atNightTheWindowIsTomorrowsDaylight() {
        val night = at(0, 22)
        val window = assertNotNull(WeatherStory.outdoorWindow(report(night), night))
        assertEquals(at(1, 7), window.startEpochSeconds)
    }

    // ---- tips ---------------------------------------------------------------------------------

    private fun tips(current: CurrentConditions = WeatherData.current(now), change: (HourForecast) -> HourForecast) =
        WeatherStory.tips(report(now, hourly = WeatherData.calmHours(change = change), current = current), now)

    @Test
    fun aCalmMildDayNeedsNoTips() {
        assertEquals(emptyList(), WeatherStory.tips(report(now), now))
    }

    @Test
    fun rainLaterTodayMeansAnUmbrella() {
        val result = tips { if (it.epochSeconds == at(0, 20)) it.copy(precipitationMm = 1.0, weatherCode = 61) else it }
        assertEquals(listOf(WeatherTip.UMBRELLA), result)
    }

    @Test
    fun rainThatAlreadyFellThisMorningNeedsNoUmbrella() {
        val result = tips { if (it.epochSeconds == at(0, 9)) it.copy(precipitationMm = 1.0, weatherCode = 61) else it }
        assertEquals(emptyList(), result)
    }

    @Test
    fun snowAloneNeedsNoUmbrella() {
        val result = tips { if (it.epochSeconds == at(0, 20)) it.copy(precipitationMm = 1.0, snowfallCm = 1.0, weatherCode = 73, temperatureC = 15.0, feelsLikeC = 15.0) else it }
        assertEquals(emptyList(), result)
    }

    @Test
    fun wetnessNearFreezingMeansIceAheadOfTheUmbrella() {
        // A cool day throughout (no layers needed), with one wet hour at freezing.
        val result = tips { h ->
            val cool = h.copy(temperatureC = 5.0, feelsLikeC = 5.0)
            if (h.epochSeconds == at(0, 20)) cool.copy(precipitationMm = 1.0, weatherCode = 61, temperatureC = 0.0) else cool
        }
        assertEquals(listOf(WeatherTip.ICE, WeatherTip.UMBRELLA), result)
    }

    @Test
    fun precipitationNowAtOrBelowFreezingMeansIce() {
        val result = tips(current = WeatherData.current(now, temperatureC = -1.0, weatherCode = 73)) { it }
        assertEquals(listOf(WeatherTip.ICE), result)
    }

    @Test
    fun strongGustsMeanAWindTip() {
        val result = tips { if (it.epochSeconds == at(0, 18)) it.copy(gustKmh = 60.0) else it }
        assertEquals(listOf(WeatherTip.WIND), result)
    }

    @Test
    fun strongSunMeansSunscreenOnlyInDaylight() {
        assertEquals(listOf(WeatherTip.SUNSCREEN), tips { if (it.epochSeconds == at(0, 16)) it.copy(uvIndex = 7.0) else it })
        assertEquals(emptyList(), tips { if (it.epochSeconds == at(0, 22)) it.copy(uvIndex = 7.0) else it })
    }

    @Test
    fun aColdFeelMeansACoat() {
        val result = tips { if (it.epochSeconds == at(0, 23)) it.copy(feelsLikeC = 3.0) else it }
        assertEquals(listOf(WeatherTip.COAT), result)
    }

    @Test
    fun aBigTemperatureSwingMeansLayers() {
        val result = tips { if (it.epochSeconds == at(0, 23)) it.copy(temperatureC = 8.0, feelsLikeC = 8.0) else it }
        assertEquals(listOf(WeatherTip.LAYERS), result)
    }

    @Test
    fun aCoatTakesTheLayersTipsPlace() {
        val result = tips { if (it.epochSeconds == at(0, 23)) it.copy(temperatureC = 2.0, feelsLikeC = 2.0) else it }
        assertEquals(listOf(WeatherTip.COAT), result)
    }

    @Test
    fun noMoreThanThreeTipsMostPressingFirst() {
        val result = tips { h ->
            when (h.epochSeconds) {
                at(0, 20) -> h.copy(precipitationMm = 1.0, weatherCode = 61, temperatureC = 0.0, feelsLikeC = 0.0, gustKmh = 70.0)
                at(0, 16) -> h.copy(uvIndex = 8.0)
                else -> h
            }
        }
        assertEquals(listOf(WeatherTip.ICE, WeatherTip.UMBRELLA, WeatherTip.WIND), result)
    }

    @Test
    fun withNoDaysThereAreNoTips() {
        assertEquals(emptyList(), WeatherStory.tips(report(now, daily = emptyList()), now))
    }

    // ---- feelsDifferent -----------------------------------------------------------------------

    @Test
    fun feelsLikeIsWorthAMentionFromTwoAndAHalfDegrees() {
        assertTrue(WeatherStory.feelsDifferent(WeatherData.current(now, temperatureC = 20.0, feelsLikeC = 17.5)))
        assertTrue(WeatherStory.feelsDifferent(WeatherData.current(now, temperatureC = 20.0, feelsLikeC = 22.5)))
        assertFalse(WeatherStory.feelsDifferent(WeatherData.current(now, temperatureC = 20.0, feelsLikeC = 17.6)))
        assertFalse(WeatherStory.feelsDifferent(WeatherData.current(now, temperatureC = 20.0, feelsLikeC = 20.0)))
    }
}
