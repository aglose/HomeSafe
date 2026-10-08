package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.weather.domain.AirQuality
import com.meticulouscreations.homesafe.weather.domain.AlertSeverity
import com.meticulouscreations.homesafe.weather.domain.CurrentConditions
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.PrecipSlice
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_error_forecast_answered
import homesafe.shared.generated.resources.weather_error_no_forecast
import homesafe.shared.generated.resources.weather_error_radar
import homesafe.shared.generated.resources.weather_error_search_answered
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The weather clients against the shapes the public services really send: nulls inside arrays
 * where a model has nothing, a `results` key that is simply absent when a search finds nothing,
 * a sunrise of 0 in the polar summer, an index file for the radar that may be down.
 */
class WeatherApisTest {

    private val t = 1_759_993_200L

    private fun epoch(iso: String) = Instant.parse(iso).epochSeconds

    // ---- OpenMeteoApi.forecast ----------------------------------------------------------------

    private val forecastBody = """
        {
          "latitude": 45.52, "longitude": -122.68, "generationtime_ms": 0.91, "utc_offset_seconds": -25200,
          "timezone": "America/Los_Angeles", "timezone_abbreviation": "GMT-7", "elevation": 15.0,
          "current_units": {"time": "unixtime", "temperature_2m": "°C"},
          "current": {
            "time": ${t + 1800}, "interval": 900, "temperature_2m": 14.3, "apparent_temperature": 12.9,
            "relative_humidity_2m": 71.0, "dew_point_2m": 9.1, "is_day": 0, "weather_code": 61, "precipitation": 0.2,
            "cloud_cover": 100, "pressure_msl": 1012.4, "visibility": 24140.0, "wind_speed_10m": 11.2,
            "wind_direction_10m": 212, "wind_gusts_10m": 24.1, "uv_index": 0.0
          },
          "minutely_15": {
            "time": [${t + 1800}, ${t + 2700}, ${t + 3600}, ${t + 4500}],
            "precipitation": [0.0, 0.1, null, 0.4],
            "snowfall": [0.0, 0.0, 0.0, null]
          },
          "hourly": {
            "time": [$t, ${t + 3600}, ${t + 7200}, ${t + 10800}],
            "temperature_2m": [14.0, 13.5, null, 12.0],
            "apparent_temperature": [12.5, null, null, 10.0],
            "relative_humidity_2m": [71.0, 73.4, 75.0, 80.0],
            "dew_point_2m": [9.0, null, 8.0, 7.0],
            "precipitation_probability": [30, null, 80, 90],
            "precipitation": [0.0, 0.3, 1.2, 2.0],
            "snowfall": [0.0, 0.0, 0.0, 0.0],
            "weather_code": [3, null, 61, 63],
            "cloud_cover": [100, 90.6, 50, 20],
            "pressure_msl": [1012.0, 1011.5, 1011.0, 1010.0],
            "visibility": [24140.0, null, 20000.0, 10000.0],
            "wind_speed_10m": [11.2, 12.0, 13.0, 14.0],
            "wind_direction_10m": [212, 215.6, 220, 230],
            "wind_gusts_10m": [24.1, null, 30.0, 35.0],
            "uv_index": [0.0, null, 1.0, 2.0],
            "is_day": [0, null, 1, 1]
          },
          "daily": {
            "time": [${t - 86400}, $t, ${t + 86400}],
            "weather_code": [1, 80, 3],
            "temperature_2m_max": [18.0, 17.0, null],
            "temperature_2m_min": [9.0, 10.0, 8.0],
            "apparent_temperature_max": [17.5, null, 15.0],
            "apparent_temperature_min": [8.0, 9.5, 7.0],
            "sunrise": [${t - 86400 + 26400}, 0, ${t + 86400 + 26400}],
            "sunset": [${t - 86400 + 66000}, 0, ${t + 86400 + 66000}],
            "moonrise": [null, 0, ${t + 86400 + 70000}],
            "moonset": [${t - 86400 + 30000}, null, 0],
            "uv_index_max": [5.5, 3.0, 4.0],
            "precipitation_sum": [0.0, 4.5, 1.0],
            "rain_sum": [0.0, 3.0, 0.0],
            "showers_sum": [0.0, 1.5, null],
            "snowfall_sum": [0.0, 0.0, 0.0],
            "precipitation_hours": [0.0, 6.0, 1.0],
            "precipitation_probability_max": [5, 85.5, 20],
            "wind_speed_10m_max": [21.0, 30.0, 18.0],
            "wind_gusts_10m_max": [34.0, 45.0, 28.0],
            "wind_direction_10m_dominant": [250, 190.4, 240]
          }
        }
    """.trimIndent()

    private suspend fun forecast(web: FakeWeb) = OpenMeteoApi(web.client).forecast(45.5234, -122.6762, nowEpochSeconds = t + 2000)

    @Test
    fun forecastParsesTheCurrentConditions() = runTest {
        val report = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow()
        assertEquals(t + 2000, report.fetchedAtEpochSeconds)
        assertEquals(-25_200, report.utcOffsetSeconds)
        assertEquals("America/Los_Angeles", report.timeZoneId)
        assertEquals(
            CurrentConditions(
                epochSeconds = t + 1800,
                temperatureC = 14.3,
                feelsLikeC = 12.9,
                humidityPercent = 71,
                dewPointC = 9.1,
                precipitationMm = 0.2,
                weatherCode = 61,
                cloudCoverPercent = 100,
                pressureHpa = 1012.4,
                windKmh = 11.2,
                windDirectionDeg = 212,
                gustKmh = 24.1,
                isDay = false,
                visibilityM = 24_140.0,
                uvIndex = 0.0,
            ),
            report.current,
        )
    }

    @Test
    fun forecastReadsNullsInsideTheQuarterHourArraysAsZero() = runTest {
        val report = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow()
        assertEquals(
            // The amount stamped at the end of a quarter hour is that quarter hour's: each slice takes the next entry.
            listOf(
                PrecipSlice(t + 1800, 0.1, 0.0),
                PrecipSlice(t + 2700, 0.0, 0.0),
                PrecipSlice(t + 3600, 0.4, 0.0),
                PrecipSlice(t + 4500, 0.0, 0.0),
            ),
            report.minutely,
        )
    }

    @Test
    fun forecastStopsTheHoursAtTheFirstOneWithNoTemperature() = runTest {
        val report = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow()
        // The fourth hour has a temperature again, but follows the one without: past the model's reach.
        assertEquals(listOf(t, t + 3600), report.hourly.map { it.epochSeconds })
    }

    @Test
    fun forecastFillsGapsInAnHourFromWhatIsKnown() = runTest {
        val hours = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow().hourly
        assertEquals(
            HourForecast(
                epochSeconds = t,
                temperatureC = 14.0,
                feelsLikeC = 12.5,
                humidityPercent = 71,
                dewPointC = 9.0,
                // What falls (and the chance of it) is stamped at the end of its hour: this hour takes the next entry.
                precipitationProbability = null,
                precipitationMm = 0.3,
                snowfallCm = 0.0,
                weatherCode = 3,
                cloudCoverPercent = 100,
                visibilityM = 24_140.0,
                windKmh = 11.2,
                windDirectionDeg = 212,
                gustKmh = 24.1,
                uvIndex = 0.0,
                isDay = false,
                pressureHpa = 1012.0,
            ),
            hours[0],
        )
        // Nearly every optional value of the second hour is null in the payload.
        assertEquals(
            HourForecast(
                epochSeconds = t + 3600,
                temperatureC = 13.5,
                // No apparent temperature: the temperature itself.
                feelsLikeC = 13.5,
                humidityPercent = 73,
                dewPointC = null,
                precipitationProbability = 80,
                precipitationMm = 1.2,
                snowfallCm = 0.0,
                // No code: overcast, the dullest guess.
                weatherCode = 3,
                cloudCoverPercent = 91,
                visibilityM = null,
                windKmh = 12.0,
                windDirectionDeg = 216,
                gustKmh = 0.0,
                uvIndex = null,
                // No is_day: assumed day.
                isDay = true,
                pressureHpa = 1011.5,
            ),
            hours[1],
        )
    }

    @Test
    fun forecastGivesTheLastHourNothingFallingBecauseNothingIsStampedAfterIt() = runTest {
        val body = """
            {"utc_offset_seconds": 0, "current": {"time": 5, "temperature_2m": 10.0},
             "hourly": {"time": [0, 3600], "temperature_2m": [10.0, 11.0], "precipitation": [0.0, 2.0], "snowfall": [0.0, 1.0], "precipitation_probability": [10, 90]},
             "minutely_15": {"time": [0, 900], "precipitation": [0.0, 0.5], "snowfall": [0.0, 0.2]}}
        """.trimIndent()
        val report = forecast(FakeWeb().route("/v1/forecast", body)).getOrThrow()
        assertEquals(listOf(2.0, 0.0), report.hourly.map { it.precipitationMm })
        assertEquals(listOf(1.0, 0.0), report.hourly.map { it.snowfallCm })
        assertEquals(listOf(90, null), report.hourly.map { it.precipitationProbability })
        assertEquals(listOf(0.5, 0.0), report.minutely.map { it.precipitationMm })
        assertEquals(listOf(0.2, 0.0), report.minutely.map { it.snowfallCm })
        // Temperature is the state at the start of the hour and is not shifted.
        assertEquals(listOf(10.0, 11.0), report.hourly.map { it.temperatureC })
    }

    @Test
    fun forecastParsesTheDaysAndDropsOneWithNoHigh() = runTest {
        val days = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow().daily
        assertEquals(listOf(t - 86_400, t), days.map { it.epochSeconds })
        val yesterday = days[0]
        assertEquals(18.0, yesterday.highC)
        assertEquals(9.0, yesterday.lowC)
        assertEquals(17.5, yesterday.feelsHighC)
        assertEquals(t - 86_400 + 26_400, yesterday.sunriseEpochSeconds)
        assertEquals(t - 86_400 + 66_000, yesterday.sunsetEpochSeconds)
        assertNull(yesterday.moonriseEpochSeconds)
        assertEquals(t - 86_400 + 30_000, yesterday.moonsetEpochSeconds)
        assertEquals(5.5, yesterday.uvIndexMax)
        assertEquals(250, yesterday.windDirectionDeg)
    }

    @Test
    fun forecastReadsASunriseOfZeroAsNoSunrise() = runTest {
        val today = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow().daily[1]
        assertNull(today.sunriseEpochSeconds)
        assertNull(today.sunsetEpochSeconds)
        assertNull(today.moonriseEpochSeconds)
        assertNull(today.moonsetEpochSeconds)
        assertNull(today.daylightSeconds)
    }

    @Test
    fun forecastFoldsShowersIntoRain() = runTest {
        val today = forecast(FakeWeb().route("/v1/forecast", forecastBody)).getOrThrow().daily[1]
        assertEquals(4.5, today.rainMm, 1e-9)
        assertEquals(4.5, today.precipitationMm, 1e-9)
        assertEquals(6.0, today.precipitationHours)
        assertEquals(86, today.precipitationProbability)
        assertEquals(190, today.windDirectionDeg)
        assertNull(today.feelsHighC)
        assertEquals(80, today.weatherCode)
    }

    @Test
    fun forecastAsksForMetricUnixtimeAtTheCoordinates() = runTest {
        val web = FakeWeb().route("/v1/forecast", forecastBody)
        forecast(web).getOrThrow()
        val request = web.requests.single()
        assertEquals("api.open-meteo.com", request.url.host)
        assertEquals("/v1/forecast", request.url.encodedPath)
        val query = request.url.parameters
        assertEquals("45.5234", query["latitude"])
        assertEquals("-122.6762", query["longitude"])
        assertEquals("auto", query["timezone"])
        assertEquals("unixtime", query["timeformat"])
        assertEquals("1", query["past_days"])
        assertEquals("10", query["forecast_days"])
        assertEquals("1", query["past_minutely_15"])
        assertEquals("13", query["forecast_minutely_15"])
        assertEquals("precipitation,snowfall", query["minutely_15"])
        // Nothing asks for imperial units: the screen converts.
        assertNull(query["temperature_unit"])
        assertNull(query["wind_speed_unit"])
        assertNull(query["precipitation_unit"])
        assertTrue("temperature_2m" in query["current"].orEmpty())
        assertTrue("snowfall" in query["hourly"].orEmpty())
        assertTrue("sunrise" in query["daily"].orEmpty())
    }

    @Test
    fun forecastFailsWithTheStatusWhenTheServiceErrors() = runTest {
        val result = forecast(FakeWeb().fail("/v1/forecast", HttpStatusCode.ServiceUnavailable))
        val error = assertIs<WeatherServiceException>(result.exceptionOrNull())
        assertEquals(503, error.status)
        assertEquals(UiText.of(Res.string.weather_error_forecast_answered, 503), error.text)
        assertEquals("HTTP 503", error.message)
    }

    @Test
    fun forecastFailsWhenTheServiceRefusesTheRequest() = runTest {
        val result = forecast(FakeWeb().fail("/v1/forecast", HttpStatusCode.TooManyRequests))
        assertEquals(429, assertIs<WeatherServiceException>(result.exceptionOrNull()).status)
    }

    @Test
    fun forecastFailsWhenThereIsNoCurrentBlock() = runTest {
        val body = """{"utc_offset_seconds": -25200, "hourly": {"time": [1], "temperature_2m": [10.0]}}"""
        val error = assertIs<WeatherServiceException>(forecast(FakeWeb().route("/v1/forecast", body)).exceptionOrNull())
        assertEquals(UiText.of(Res.string.weather_error_no_forecast), error.text)
        assertEquals(200, error.status)
    }

    @Test
    fun forecastFailsWhenTheCurrentTemperatureIsMissing() = runTest {
        val body = """{"current": {"time": 5, "weather_code": 3}}"""
        val error = assertIs<WeatherServiceException>(forecast(FakeWeb().route("/v1/forecast", body)).exceptionOrNull())
        assertEquals(UiText.of(Res.string.weather_error_no_forecast), error.text)
    }

    @Test
    fun forecastSurvivesMissingHourlyAndDailyBlocks() = runTest {
        val body = """{"utc_offset_seconds": 3600, "current": {"time": 5, "temperature_2m": 10.0}}"""
        val report = forecast(FakeWeb().route("/v1/forecast", body)).getOrThrow()
        assertTrue(report.hourly.isEmpty() && report.daily.isEmpty() && report.minutely.isEmpty())
        // And the current block's own gaps get sensible values.
        assertEquals(10.0, report.current.feelsLikeC)
        assertEquals(3, report.current.weatherCode)
        assertTrue(report.current.isDay)
        assertEquals(0, report.current.humidityPercent)
    }

    @Test
    fun forecastFailsOnAnAnswerThatIsNotJson() = runTest {
        assertTrue(forecast(FakeWeb().route("/v1/forecast", "<html>Bad gateway</html>")).isFailure)
    }

    @Test
    fun forecastFailsWhenTheNetworkDoes() = runTest {
        val web = FakeWeb().route("/v1/forecast") { FakeWeb.Reply(failure = IllegalStateException("no route to host")) }
        assertTrue(forecast(web).isFailure)
    }

    // ---- OpenMeteoApi.airQuality --------------------------------------------------------------

    @Test
    fun airQualityParsesTheIndexAndItsParts() = runTest {
        val body = """{"current": {"time": 1, "us_aqi": 42.4, "pm2_5": 8.4, "pm10": 14.0, "ozone": 61.0, "nitrogen_dioxide": 7.5}}"""
        val web = FakeWeb().route("/v1/air-quality", body)
        val air = OpenMeteoApi(web.client).airQuality(45.5, -122.6).getOrThrow()
        assertEquals(AirQuality(usAqi = 42, pm25 = 8.4, pm10 = 14.0, ozone = 61.0, nitrogenDioxide = 7.5), air)
        assertEquals("air-quality-api.open-meteo.com", web.requests.single().url.host)
    }

    @Test
    fun airQualityIsNullWhenTheIndexIsNull() = runTest {
        val body = """{"current": {"time": 1, "us_aqi": null, "pm2_5": 8.4}}"""
        val air = OpenMeteoApi(FakeWeb().route("/v1/air-quality", body).client).airQuality(45.5, -122.6)
        assertTrue(air.isSuccess)
        assertNull(air.getOrNull())
    }

    @Test
    fun airQualityIsNullWithoutACurrentBlock() = runTest {
        val air = OpenMeteoApi(FakeWeb().route("/v1/air-quality", "{}").client).airQuality(45.5, -122.6)
        assertNull(air.getOrThrow())
    }

    @Test
    fun airQualityRoundsTheIndex() = runTest {
        val body = """{"current": {"us_aqi": 99.5}}"""
        assertEquals(100, OpenMeteoApi(FakeWeb().route("/v1/air-quality", body).client).airQuality(1.0, 1.0).getOrThrow()?.usAqi)
    }

    @Test
    fun airQualityFailsOnAServerError() = runTest {
        val air = OpenMeteoApi(FakeWeb().fail("/v1/air-quality", HttpStatusCode.InternalServerError).client).airQuality(45.5, -122.6)
        assertEquals(500, assertIs<WeatherServiceException>(air.exceptionOrNull()).status)
    }

    // ---- OpenMeteoApi.search ------------------------------------------------------------------

    private val searchBody = """
        {"results": [
          {"id": 5746545, "name": "Portland", "latitude": 45.52345, "longitude": -122.67621, "country": "United States", "admin1": "Oregon"},
          {"id": 4975802, "name": "Portland", "latitude": 43.66147, "longitude": -70.25533, "country": "United States", "admin1": "Maine"},
          {"id": 1880252, "name": "Singapore", "latitude": 1.28967, "longitude": 103.85007, "country": "Singapore", "admin1": "Singapore"},
          {"id": 2643743, "name": "London", "latitude": 51.50853, "longitude": -0.12574, "country": "United Kingdom"},
          {"id": 1, "name": "Nowhere", "latitude": 10.0, "longitude": 20.0},
          {"id": 9, "name": "Portland (duplicate)", "latitude": 45.5230, "longitude": -122.6770, "country": "United States", "admin1": "Oregon"}
        ]}
    """.trimIndent()

    @Test
    fun searchFormatsTheRegionFromStateAndCountry() = runTest {
        val web = FakeWeb().route("/v1/search", searchBody)
        val places = OpenMeteoApi(web.client).search("Portland").getOrThrow()
        assertEquals("Portland", places[0].name)
        assertEquals("Oregon, United States", places[0].region)
        assertEquals("Maine, United States", places[1].region)
    }

    @Test
    fun searchLeavesOutAStateThatRepeatsTheName() = runTest {
        val places = OpenMeteoApi(FakeWeb().route("/v1/search", searchBody).client).search("Portland").getOrThrow()
        assertEquals("Singapore", places.single { it.name == "Singapore" }.region)
    }

    @Test
    fun searchHandlesAMissingStateOrCountry() = runTest {
        val places = OpenMeteoApi(FakeWeb().route("/v1/search", searchBody).client).search("Portland").getOrThrow()
        assertEquals("United Kingdom", places.single { it.name == "London" }.region)
        assertEquals("", places.single { it.name == "Nowhere" }.region)
    }

    @Test
    fun searchGivesEachPlaceAnIdFromItsCoordinates() = runTest {
        val places = OpenMeteoApi(FakeWeb().route("/v1/search", searchBody).client).search("Portland").getOrThrow()
        assertEquals("4552,-12268", places[0].id)
        assertEquals(45.52345, places[0].latitude)
        assertEquals(-122.67621, places[0].longitude)
    }

    @Test
    fun searchKeepsOnlyTheFirstOfTwoResultsAtTheSameSpot() = runTest {
        val places = OpenMeteoApi(FakeWeb().route("/v1/search", searchBody).client).search("Portland").getOrThrow()
        assertEquals(5, places.size)
        assertTrue(places.none { it.name == "Portland (duplicate)" })
    }

    @Test
    fun searchWithNoResultsKeyIsAnEmptyList() = runTest {
        val places = OpenMeteoApi(FakeWeb().route("/v1/search", """{"generationtime_ms": 0.4}""").client).search("Zzzzzz")
        assertEquals(emptyList(), places.getOrThrow())
    }

    @Test
    fun searchBelowTwoLettersMakesNoRequest() = runTest {
        val web = FakeWeb().route("/v1/search", searchBody)
        val api = OpenMeteoApi(web.client)
        assertEquals(emptyList(), api.search("").getOrThrow())
        assertEquals(emptyList(), api.search("p").getOrThrow())
        assertEquals(emptyList(), api.search("  p ").getOrThrow())
        assertTrue(web.requests.isEmpty())
    }

    @Test
    fun searchTrimsTheQueryAndSendsTheLanguage() = runTest {
        val web = FakeWeb().route("/v1/search", searchBody)
        OpenMeteoApi(web.client).search("  Portland ", language = "fr").getOrThrow()
        val query = web.requests.single().url.parameters
        assertEquals("Portland", query["name"])
        assertEquals("12", query["count"])
        assertEquals("fr", query["language"])
        assertEquals("json", query["format"])
        assertEquals("geocoding-api.open-meteo.com", web.requests.single().url.host)
    }

    @Test
    fun searchFailsWithTheStatusOnAnError() = runTest {
        val result = OpenMeteoApi(FakeWeb().fail("/v1/search", HttpStatusCode.BadGateway).client).search("Portland")
        val error = assertIs<WeatherServiceException>(result.exceptionOrNull())
        assertEquals(502, error.status)
        assertEquals(UiText.of(Res.string.weather_error_search_answered, 502), error.text)
    }

    // ---- NwsApi.alerts ------------------------------------------------------------------------

    private val alertsBody = """
        {"features": [
          {"properties": {"id": "m1", "event": "Wind Advisory", "headline": "Wind Advisory until 4 AM", "description": "  South winds 20 to 30 mph.  ",
             "instruction": "Secure outdoor objects.\n", "severity": "Moderate", "senderName": "NWS Portland OR",
             "onset": "2025-10-09T14:00:00-07:00", "effective": "2025-10-09T13:00:00-07:00",
             "ends": "2025-10-10T04:00:00-07:00", "expires": "2025-10-09T20:00:00-07:00"}},
          {"properties": {"id": "x1", "event": "Tornado Warning", "severity": "Extreme"}},
          {"properties": {"id": "s1", "event": "Flood Warning", "severity": "Severe", "effective": "2025-10-09T10:00:00-07:00", "expires": "2025-10-09T18:00:00-07:00"}},
          {"properties": {"id": "u1", "event": "Special Statement", "severity": "Mystery"}},
          {"properties": {"id": "n1", "event": "Minor Thing", "severity": "Minor"}},
          {"properties": {"event": "No Id", "severity": "Severe"}},
          {"properties": {"id": "b1", "event": "   ", "severity": "Severe"}},
          {"properties": null},
          {}
        ]}
    """.trimIndent()

    private fun alertsApi(web: FakeWeb) = NwsApi(web.client)

    @Test
    fun alertsAreParsedWithTheAgenciesWords() = runTest {
        val alerts = alertsApi(FakeWeb().route("/alerts/active", alertsBody)).alerts(45.5, -122.6).getOrThrow()
        val wind = alerts.single { it.id == "m1" }
        assertEquals("Wind Advisory", wind.event)
        assertEquals("Wind Advisory until 4 AM", wind.headline)
        assertEquals("South winds 20 to 30 mph.", wind.description)
        assertEquals("Secure outdoor objects.", wind.instruction)
        assertEquals(AlertSeverity.MODERATE, wind.severity)
        assertEquals("NWS Portland OR", wind.sender)
        assertEquals(epoch("2025-10-09T21:00:00Z"), wind.onsetEpochSeconds)
        assertEquals(epoch("2025-10-10T11:00:00Z"), wind.endsEpochSeconds)
    }

    @Test
    fun alertsFallBackToEffectiveAndExpiresForTheirTimes() = runTest {
        val flood = alertsApi(FakeWeb().route("/alerts/active", alertsBody)).alerts(45.5, -122.6).getOrThrow().single { it.id == "s1" }
        assertEquals(epoch("2025-10-09T17:00:00Z"), flood.onsetEpochSeconds)
        assertEquals(epoch("2025-10-10T01:00:00Z"), flood.endsEpochSeconds)
    }

    @Test
    fun anAlertWithNoTimesHasNone() = runTest {
        val tornado = alertsApi(FakeWeb().route("/alerts/active", alertsBody)).alerts(45.5, -122.6).getOrThrow().single { it.id == "x1" }
        assertNull(tornado.onsetEpochSeconds)
        assertNull(tornado.endsEpochSeconds)
        assertEquals("", tornado.headline)
    }

    @Test
    fun alertsAreSortedWorstFirst() = runTest {
        val alerts = alertsApi(FakeWeb().route("/alerts/active", alertsBody)).alerts(45.5, -122.6).getOrThrow()
        assertEquals(listOf("x1", "s1", "m1", "n1", "u1"), alerts.map { it.id })
        assertEquals(
            listOf(AlertSeverity.EXTREME, AlertSeverity.SEVERE, AlertSeverity.MODERATE, AlertSeverity.MINOR, AlertSeverity.UNKNOWN),
            alerts.map { it.severity },
        )
    }

    @Test
    fun alertsWithoutAnIdOrAnEventAreDropped() = runTest {
        val alerts = alertsApi(FakeWeb().route("/alerts/active", alertsBody)).alerts(45.5, -122.6).getOrThrow()
        assertEquals(5, alerts.size)
        assertTrue(alerts.none { it.event.isBlank() })
    }

    @Test
    fun noFeaturesIsNoAlerts() = runTest {
        assertEquals(emptyList(), alertsApi(FakeWeb().route("/alerts/active", """{"features": []}""")).alerts(45.5, -122.6).getOrThrow())
        assertEquals(emptyList(), alertsApi(FakeWeb().route("/alerts/active", "{}")).alerts(45.5, -122.6).getOrThrow())
    }

    @Test
    fun anAlertsAnswerOfNotFoundOrBadRequestMeansNothingToSay() = runTest {
        // Outside the service's coverage.
        assertEquals(emptyList(), alertsApi(FakeWeb().fail("/alerts/active", HttpStatusCode.NotFound)).alerts(51.5, -0.1).getOrThrow())
        assertEquals(emptyList(), alertsApi(FakeWeb().fail("/alerts/active", HttpStatusCode.BadRequest)).alerts(51.5, -0.1).getOrThrow())
    }

    @Test
    fun alertsFailOnAServerError() = runTest {
        val result = alertsApi(FakeWeb().fail("/alerts/active", HttpStatusCode.InternalServerError)).alerts(45.5, -122.6)
        assertEquals(500, assertIs<WeatherServiceException>(result.exceptionOrNull()).status)
    }

    @Test
    fun alertsAskForTheActualAlertsAtAPointWithFourDecimals() = runTest {
        val web = FakeWeb().route("/alerts/active", alertsBody)
        alertsApi(web).alerts(45.52345678, -122.67618)
        val request = web.requests.single()
        assertEquals("api.weather.gov", request.url.host)
        assertEquals("45.5235,-122.6762", request.url.parameters["point"])
        assertEquals("actual", request.url.parameters["status"])
        assertEquals("alert,update", request.url.parameters["message_type"])
        assertEquals("application/geo+json", request.headers[HttpHeaders.Accept])
    }

    @Test
    fun theAlertPointKeepsFourDecimalsEvenWhenTheyAreZeros() = runTest {
        val web = FakeWeb().route("/alerts/active", "{}")
        alertsApi(web).alerts(45.5, -122.6)
        assertEquals("45.5000,-122.6000", web.requests.single().url.parameters["point"])
    }

    // ---- Alert keys ---------------------------------------------------------------------------

    private fun vtecAlert(id: String, vararg vtec: String) =
        """{"properties": {"id": "$id", "event": "Coastal Flood Warning", "severity": "Severe", "parameters": {"VTEC": [${vtec.joinToString { "\"$it\"" }}]}}}"""

    @Test
    fun anEventKeyIsTheOfficeWhatItsForHowSeriousAndItsNumber() {
        assertEquals("KJAX.CF.W.0001", eventKey("/O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z/"))
        assertEquals("KPQR.WI.Y.0012", eventKey("/O.NEW.KPQR.WI.Y.0012.251009T2100Z-251010T1100Z/"))
    }

    @Test
    fun theEventKeyIsTheSameThroughAWarningsUpdates() {
        val issued = eventKey("/O.NEW.KJAX.CF.W.0001.261007T1800Z-261008T2100Z/")
        val updated = eventKey("/O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z/")
        val extended = eventKey("/O.EXT.KJAX.CF.W.0001.261007T1800Z-261009T0300Z/")
        assertEquals(issued, updated)
        assertEquals(issued, extended)
    }

    @Test
    fun differentWarningsHaveDifferentKeys() {
        assertTrue(eventKey("/O.NEW.KJAX.CF.W.0001.261007T1800Z-261008T2100Z/") != eventKey("/O.NEW.KJAX.CF.W.0002.261007T1800Z-261008T2100Z/"))
        assertTrue(eventKey("/O.NEW.KJAX.CF.W.0001.261007T1800Z-261008T2100Z/") != eventKey("/O.NEW.KJAX.CF.Y.0001.261007T1800Z-261008T2100Z/"))
    }

    @Test
    fun anEventKeyNeedsTheSlashesOptionalButSixPartsAtLeast() {
        assertEquals("KJAX.CF.W.0001", eventKey("O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z"))
        assertNull(eventKey(""))
        assertNull(eventKey("/not a vtec string/"))
        assertNull(eventKey("/O.CON.KJAX.CF.W/"))
        assertNull(eventKey("/O.CON.KJAX..W.0001.000000T0000Z-261008T2100Z/"))
    }

    @Test
    fun anAlertTakesItsKeyFromItsVtecString() = runTest {
        val body = """{"features": [${vtecAlert("urn:msg:1", "/O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z/")}]}"""
        val alert = alertsApi(FakeWeb().route("/alerts/active", body)).alerts(30.3, -81.6).getOrThrow().single()
        assertEquals("urn:msg:1", alert.id)
        assertEquals("KJAX.CF.W.0001", alert.key)
    }

    @Test
    fun anUpdateToAWarningIsANewMessageWithTheSameKey() = runTest {
        val first = """{"features": [${vtecAlert("urn:msg:1", "/O.NEW.KJAX.CF.W.0001.261007T1800Z-261008T2100Z/")}]}"""
        val second = """{"features": [${vtecAlert("urn:msg:2", "/O.CON.KJAX.CF.W.0001.000000T0000Z-261008T2100Z/")}]}"""
        val a = alertsApi(FakeWeb().route("/alerts/active", first)).alerts(30.3, -81.6).getOrThrow().single()
        val b = alertsApi(FakeWeb().route("/alerts/active", second)).alerts(30.3, -81.6).getOrThrow().single()
        assertTrue(a.id != b.id)
        assertEquals(a.key, b.key)
    }

    @Test
    fun anAlertWithNoVtecIsKeyedByItsId() = runTest {
        val none = """{"properties": {"id": "urn:msg:7", "event": "Special Statement", "severity": "Severe"}}"""
        val empty = vtecAlert("urn:msg:8")
        val bad = vtecAlert("urn:msg:9", "garbage")
        val alerts = alertsApi(FakeWeb().route("/alerts/active", """{"features": [$none, $empty, $bad]}""")).alerts(30.3, -81.6).getOrThrow()
        assertEquals(listOf("urn:msg:7", "urn:msg:8", "urn:msg:9"), alerts.map { it.key }.sorted())
        assertTrue(alerts.all { it.key == it.id })
    }

    @Test
    fun theFirstUsableVtecStringNamesTheAlert() = runTest {
        val body = """{"features": [${vtecAlert("urn:msg:1", "garbage", "/O.NEW.KJAX.CF.W.0003.261007T1800Z-261008T2100Z/")}]}"""
        assertEquals("KJAX.CF.W.0003", alertsApi(FakeWeb().route("/alerts/active", body)).alerts(30.3, -81.6).getOrThrow().single().key)
    }

    // ---- NwsApi.nameOf and covers -------------------------------------------------------------

    @Test
    fun nameOfReadsTheNearestTownAndState() = runTest {
        val body = """{"properties": {"relativeLocation": {"properties": {"city": "Portland", "state": "OR"}}}}"""
        val web = FakeWeb().route("/points/", body)
        assertEquals("Portland" to "OR", NwsApi(web.client).nameOf(45.5234, -122.6762))
        assertEquals("/points/45.5234,-122.6762", web.requests.single().url.encodedPath.replace("%2C", ","))
    }

    @Test
    fun nameOfWithNoStateGivesAnEmptyOne() = runTest {
        val body = """{"properties": {"relativeLocation": {"properties": {"city": "Ketchikan"}}}}"""
        assertEquals("Ketchikan" to "", NwsApi(FakeWeb().route("/points/", body).client).nameOf(55.3, -131.6))
    }

    @Test
    fun nameOfIsNullOutsideTheUsOrWhenTheServiceWontSay() = runTest {
        assertNull(NwsApi(FakeWeb().fail("/points/", HttpStatusCode.NotFound).client).nameOf(51.5, -0.1))
        assertNull(NwsApi(FakeWeb().fail("/points/", HttpStatusCode.InternalServerError).client).nameOf(45.5, -122.6))
        assertNull(NwsApi(FakeWeb().route("/points/", "{}").client).nameOf(45.5, -122.6))
        assertNull(NwsApi(FakeWeb().route("/points/", """{"properties": {"relativeLocation": {"properties": {"city": "  "}}}}""").client).nameOf(45.5, -122.6))
    }

    @Test
    fun nameOfIsNullWhenTheNetworkFails() = runTest {
        val web = FakeWeb().route("/points/") { FakeWeb.Reply(failure = IllegalStateException("offline")) }
        assertNull(NwsApi(web.client).nameOf(45.5, -122.6))
    }

    @Test
    fun theNwsCoversTheFiftyStatesAndPuertoRico() {
        assertTrue(NwsApi.covers(45.5, -122.6))
        assertTrue(NwsApi.covers(61.2, -149.9))
        assertTrue(NwsApi.covers(21.3, -157.8))
        assertTrue(NwsApi.covers(18.2, -66.5))
        assertFalse(NwsApi.covers(51.5, -0.1))
        assertFalse(NwsApi.covers(35.7, 139.7))
        assertFalse(NwsApi.covers(-33.9, 151.2))
        assertFalse(NwsApi.covers(13.4, 144.8))
    }

    // ---- RadarApi.timeline --------------------------------------------------------------------

    private val lcrefIndex = """{"meta": {"end_valid": "2025-10-09T22:07:00Z"}}"""
    private val hrrrIndex = """{"model_init_utc": "2025-10-09T20:00:00Z"}"""
    private val rainViewerIndex = """
        {"version": "2.0", "generated": 1759999900, "host": "https://tilecache.rainviewer.com",
         "radar": {"past": [
            {"time": 1759999200, "path": "/v2/radar/1759999200"},
            {"time": 1759999800, "path": "/v2/radar/1759999800"}
         ], "nowcast": []}}
    """.trimIndent()

    private fun usWeb() = FakeWeb()
        .route("mrms/lcref.json", lcrefIndex)
        .route("hrrr/refd_0000.json", hrrrIndex)
        .route("weather-maps.json", rainViewerIndex)

    private val portland = 45.5 to -122.6
    private val london = 51.5 to -0.1

    @Test
    fun aUsPointGetsThirteenObservedFramesTenMinutesApartEndingOnAnEvenMinute() = runTest {
        val timeline = RadarApi(usWeb().client).timeline(portland.first, portland.second).getOrThrow()
        assertEquals(RadarSource.US_MRMS, timeline.source)
        val observed = timeline.frames.filter { !it.forecast }
        assertEquals(13, observed.size)
        // 22:07 is an odd minute; the newest frame is the even minute before it.
        assertEquals(epoch("2025-10-09T22:06:00Z"), observed.last().epochSeconds)
        assertEquals(epoch("2025-10-09T20:06:00Z"), observed.first().epochSeconds)
        observed.zipWithNext().forEach { (a, b) -> assertEquals(600, b.epochSeconds - a.epochSeconds) }
        assertEquals(0L, observed.last().epochSeconds / 60 % 2)
        assertEquals(12, timeline.latestObserved)
    }

    @Test
    fun observedFrameUrlsAreStampedInUtc() = runTest {
        val frames = RadarApi(usWeb().client).timeline(portland.first, portland.second).getOrThrow().frames.filter { !it.forecast }
        assertEquals("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/mrms::lcref-202510092206/{z}/{x}/{y}.png", frames.last().tileUrl)
        assertEquals("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/mrms::lcref-202510092006/{z}/{x}/{y}.png", frames.first().tileUrl)
        assertEquals(RadarApi.US_MAX_ZOOM, frames.first().maxZoom)
        assertEquals("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/mrms::lcref-202510092206/7/20/45.png", frames.last().url(7, 20, 45))
    }

    @Test
    fun forecastFramesFollowTheNewestObservedFrame() = runTest {
        val timeline = RadarApi(usWeb().client).timeline(portland.first, portland.second).getOrThrow()
        val forecast = timeline.frames.filter { it.forecast }
        val newest = epoch("2025-10-09T22:06:00Z")
        assertEquals(RadarApi.FUTURE_FRAMES, forecast.size)
        assertTrue(forecast.all { it.epochSeconds > newest })
        // The run began at 20:00; 22:06 is 126 minutes in, so the first frame after it is minute 135.
        assertEquals(epoch("2025-10-09T22:15:00Z"), forecast.first().epochSeconds)
        assertEquals(
            "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/hrrr::REFD-F0135-0/{z}/{x}/{y}.png?run=${epoch("2025-10-09T20:00:00Z")}",
            forecast.first().tileUrl,
        )
        // And the loop is in order, observed first.
        assertEquals(timeline.frames.map { it.epochSeconds }.sorted(), timeline.frames.map { it.epochSeconds })
        assertEquals(timeline.frames.sortedBy { it.forecast }, timeline.frames)
    }

    @Test
    fun aMissingForecastIndexStillGivesTheObservedFrames() = runTest {
        val web = usWeb().fail("hrrr/refd_0000.json", HttpStatusCode.NotFound)
        val timeline = RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow()
        assertEquals(RadarSource.US_MRMS, timeline.source)
        assertEquals(13, timeline.frames.size)
        assertTrue(timeline.frames.none { it.forecast })
    }

    @Test
    fun aBrokenForecastIndexStillGivesTheObservedFrames() = runTest {
        val web = usWeb().route("hrrr/refd_0000.json", "not json at all")
        val timeline = RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow()
        assertEquals(13, timeline.frames.size)
    }

    @Test
    fun aForecastIndexWithoutAnInitTimeAddsNoForecastFrames() = runTest {
        val web = usWeb().route("hrrr/refd_0000.json", "{}")
        assertEquals(13, RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow().frames.size)
    }

    @Test
    fun whenTheUsServiceIsDownRainViewerStandsIn() = runTest {
        val web = usWeb().fail("mrms/lcref.json", HttpStatusCode.InternalServerError)
        val timeline = RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow()
        assertEquals(RadarSource.RAINVIEWER, timeline.source)
        assertEquals(2, timeline.frames.size)
    }

    @Test
    fun whenTheUsIndexHasNoEndTimeRainViewerStandsIn() = runTest {
        val web = usWeb().route("mrms/lcref.json", """{"meta": {}}""")
        assertEquals(RadarSource.RAINVIEWER, RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow().source)
    }

    @Test
    fun whenTheUsNetworkFailsRainViewerStandsIn() = runTest {
        val web = usWeb().route("mrms/lcref.json") { FakeWeb.Reply(failure = IllegalStateException("reset")) }
        assertEquals(RadarSource.RAINVIEWER, RadarApi(web.client).timeline(portland.first, portland.second).getOrThrow().source)
    }

    @Test
    fun aPointOutsideTheUsGoesStraightToRainViewer() = runTest {
        val web = usWeb()
        val timeline = RadarApi(web.client).timeline(london.first, london.second).getOrThrow()
        assertEquals(RadarSource.RAINVIEWER, timeline.source)
        assertEquals(0, web.count("mesonet.agron.iastate.edu"))
        assertEquals(1, web.count("api.rainviewer.com"))
    }

    @Test
    fun rainViewerFramesAreTheObservedPastWithTheUnsmoothedTileAddress() = runTest {
        val timeline = RadarApi(usWeb().client).timeline(london.first, london.second).getOrThrow()
        assertEquals(listOf(1_759_999_200L, 1_759_999_800L), timeline.frames.map { it.epochSeconds })
        assertTrue(timeline.frames.none { it.forecast })
        assertEquals("https://tilecache.rainviewer.com/v2/radar/1759999800/256/{z}/{x}/{y}/2/0_0.png", timeline.frames.last().tileUrl)
        assertEquals(RadarApi.WORLD_MAX_ZOOM, timeline.frames.last().maxZoom)
        assertEquals(1, timeline.latestObserved)
    }

    @Test
    fun rainViewerFailingIsAFailureWithTheRadarMessage() = runTest {
        val result = RadarApi(FakeWeb().fail("weather-maps.json", HttpStatusCode.BadGateway).client).timeline(london.first, london.second)
        val error = assertIs<WeatherServiceException>(result.exceptionOrNull())
        assertEquals(UiText.of(Res.string.weather_error_radar), error.text)
        assertEquals(502, error.status)
    }

    @Test
    fun rainViewerWithNoFramesIsAFailure() = runTest {
        val web = FakeWeb().route("weather-maps.json", """{"host": "https://tilecache.rainviewer.com", "radar": {"past": []}}""")
        assertTrue(RadarApi(web.client).timeline(london.first, london.second).isFailure)
    }

    @Test
    fun aUsPointWithBothServicesDownFails() = runTest {
        val web = usWeb().fail("mrms/lcref.json", HttpStatusCode.ServiceUnavailable).fail("weather-maps.json", HttpStatusCode.ServiceUnavailable)
        assertTrue(RadarApi(web.client).timeline(portland.first, portland.second).isFailure)
    }

    @Test
    fun theContiguousUsIsABoxAroundTheLowerFortyEight() {
        assertTrue(RadarApi.inContiguousUs(45.5, -122.6))
        assertTrue(RadarApi.inContiguousUs(25.76, -80.19))
        assertTrue(RadarApi.inContiguousUs(24.55, -81.8))
        assertFalse(RadarApi.inContiguousUs(61.2, -149.9))
        assertFalse(RadarApi.inContiguousUs(21.3, -157.8))
        assertFalse(RadarApi.inContiguousUs(51.5, -0.1))
        assertFalse(RadarApi.inContiguousUs(35.7, 139.7))
    }

    // ---- forecastFrames, stamp and parseInstant -----------------------------------------------

    @Test
    fun forecastFramesStartAtTheFirstQuarterHourAfterTheNewestObservedFrame() {
        val init = epoch("2025-10-09T20:00:00Z")
        val frames = RadarApi.forecastFrames(init, newestObserved = epoch("2025-10-09T22:06:00Z"))
        assertEquals((0 until 8).map { init + (135 + it * 15) * 60L }, frames.map { it.epochSeconds })
        assertTrue(frames.all { it.forecast && it.maxZoom == RadarApi.US_MAX_ZOOM })
        assertEquals("hrrr::REFD-F0135-0", frames.first().tileUrl.substringAfter("1.0.0/").substringBefore("/{z}"))
        assertEquals("hrrr::REFD-F0240-0", frames.last().tileUrl.substringAfter("1.0.0/").substringBefore("/{z}"))
    }

    @Test
    fun forecastTileAddressesNameTheRunSoACachedPictureNeverOutlivesIt() {
        val first = RadarApi.forecastFrames(epoch("2025-10-09T20:00:00Z"), newestObserved = epoch("2025-10-09T22:06:00Z"))
        val later = RadarApi.forecastFrames(epoch("2025-10-09T21:00:00Z"), newestObserved = epoch("2025-10-09T22:06:00Z"))
        assertTrue(first.all { it.tileUrl.endsWith("?run=${epoch("2025-10-09T20:00:00Z")}") })
        assertTrue(later.all { it.tileUrl.endsWith("?run=${epoch("2025-10-09T21:00:00Z")}") })
        // The same lead time from two runs is two addresses.
        val a = first.first { it.tileUrl.contains("F0135") }
        val b = later.firstOrNull { it.tileUrl.contains("F0075") }
        assertNotNull(b)
        assertTrue(first.map { it.tileUrl }.intersect(later.map { it.tileUrl }.toSet()).isEmpty())
        assertEquals("https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/hrrr::REFD-F0135-0/3/1/2.png?run=${epoch("2025-10-09T20:00:00Z")}", a.url(3, 1, 2))
    }

    @Test
    fun aFrameAtExactlyTheNewestObservedTimeIsNotRepeated() {
        val init = epoch("2025-10-09T20:00:00Z")
        val frames = RadarApi.forecastFrames(init, newestObserved = init + 7_200)
        assertTrue(frames.all { it.epochSeconds > init + 7_200 })
        assertEquals(init + 135 * 60L, frames.first().epochSeconds)
    }

    @Test
    fun aRunNewerThanTheObservedFramesStartsAtItsFirstQuarterHour() {
        val init = epoch("2025-10-09T20:00:00Z")
        assertEquals(init + 900, RadarApi.forecastFrames(init, newestObserved = init - 100).first().epochSeconds)
    }

    @Test
    fun forecastFramesStopAtEighteenHours() {
        val init = epoch("2025-10-09T00:00:00Z")
        // 17 hours into the run: minutes 1035 to 1080 are left, four frames.
        val frames = RadarApi.forecastFrames(init, newestObserved = init + 17 * 3_600)
        assertEquals(listOf(1035, 1050, 1065, 1080), frames.map { ((it.epochSeconds - init) / 60).toInt() })
        // A run older than that has nothing left to show.
        assertTrue(RadarApi.forecastFrames(init, newestObserved = init + 19 * 3_600).isEmpty())
    }

    @Test
    fun stampIsYearMonthDayHourMinuteInUtc() {
        assertEquals("202610080438", RadarApi.stamp(epoch("2026-10-08T04:38:00Z")))
        assertEquals("202501010000", RadarApi.stamp(epoch("2025-01-01T00:00:00Z")))
        assertEquals("202512312359", RadarApi.stamp(epoch("2025-12-31T23:59:00Z")))
    }

    @Test
    fun stampDropsTheSeconds() {
        assertEquals("202510092206", RadarApi.stamp(epoch("2025-10-09T22:06:59Z")))
    }

    @Test
    fun stampIsNotTheLocalTime() {
        // Midnight in Portland is seven in the morning in UTC.
        assertEquals("202510090700", RadarApi.stamp(t))
    }

    @Test
    fun parseInstantReadsUtcAndOffsetTimes() {
        assertEquals(epoch("2025-10-09T21:00:00Z"), RadarApi.parseInstant("2025-10-09T21:00:00Z"))
        assertEquals(epoch("2025-10-09T21:00:00Z"), RadarApi.parseInstant("2025-10-09T14:00:00-07:00"))
        assertEquals(epoch("2025-10-09T21:00:00Z"), RadarApi.parseInstant("2025-10-09T23:00:00+02:00"))
    }

    @Test
    fun parseInstantGivesNullForNothingOrNonsense() {
        assertNull(RadarApi.parseInstant(null))
        assertNull(RadarApi.parseInstant(""))
        assertNull(RadarApi.parseInstant("soon"))
        assertNotNull(RadarApi.parseInstant("2025-10-09T21:00:00Z"))
    }
}
