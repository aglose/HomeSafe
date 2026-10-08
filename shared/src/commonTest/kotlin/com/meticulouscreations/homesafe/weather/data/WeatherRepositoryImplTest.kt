package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.data.WeatherDao
import com.meticulouscreations.homesafe.data.WeatherReportEntity
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherRepositoryImplTest {

    private val start = WeatherPayloads.MIDNIGHT + 15 * 3_600L

    private val portland = Place(Place.CURRENT_ID, "Portland", "OR", 45.5234, -122.6762)
    private val seattle = Place(Place.idFor(47.6062, -122.3321), "Seattle", "Washington, United States", 47.6062, -122.3321)
    private val london = Place(Place.idFor(51.5, -0.12), "London", "United Kingdom", 51.5, -0.12)

    private class Harness(
        val web: FakeWeb = WeatherPayloads.everythingUp(),
        val dao: WeatherDao = InMemoryWeatherDao(),
        val clock: MutableClock,
    ) {
        val repository = WeatherRepositoryImpl(OpenMeteoApi(web.client), NwsApi(web.client), RadarApi(web.client), dao, clock)
        val forecasts: Int get() = web.count("/v1/forecast")
    }

    private fun harness(web: FakeWeb = WeatherPayloads.everythingUp(), dao: WeatherDao = InMemoryWeatherDao()) = Harness(web, dao, MutableClock(start))

    // ---- report -------------------------------------------------------------------------------

    @Test
    fun aReportIsTheForecastWithAirQualityAndAlertsAlongside() = runTest {
        val h = harness()
        val report = h.repository.report(portland).getOrThrow()
        assertEquals(15.0, report.current.temperatureC)
        assertEquals(42, report.air?.usAqi)
        assertEquals(listOf("urn:1"), report.alerts.map { it.id })
        assertEquals(start, report.fetchedAtEpochSeconds)
    }

    @Test
    fun aFreshEnoughReportIsNotFetchedAgain() = runTest {
        val h = harness()
        val first = h.repository.report(portland).getOrThrow()
        h.clock.seconds += 100
        val second = h.repository.report(portland).getOrThrow()
        assertEquals(1, h.forecasts)
        assertEquals(first, second)
    }

    @Test
    fun aReportOlderThanTheMaximumAgeIsFetchedAgain() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.clock.seconds += 601
        h.repository.report(portland)
        assertEquals(2, h.forecasts)
    }

    @Test
    fun theMaximumAgeIsTheCallers() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.clock.seconds += 61
        h.repository.report(portland, maxAgeSeconds = 60)
        assertEquals(2, h.forecasts)
        h.clock.seconds += 61
        h.repository.report(portland, maxAgeSeconds = 3_600)
        assertEquals(2, h.forecasts)
    }

    @Test
    fun aReportExactlyAtTheMaximumAgeIsStillFresh() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.clock.seconds += 600
        h.repository.report(portland)
        assertEquals(1, h.forecasts)
    }

    @Test
    fun theNewReportIsWhatTheNextCallGets() = runTest {
        val web = WeatherPayloads.everythingUp(temperatureC = 15.0)
        val h = harness(web)
        assertEquals(15.0, h.repository.report(portland).getOrThrow().current.temperatureC)
        web.route("/v1/forecast", WeatherPayloads.forecast(temperatureC = 20.0))
        h.clock.seconds += 700
        assertEquals(20.0, h.repository.report(portland).getOrThrow().current.temperatureC)
        assertEquals(20.0, h.repository.report(portland).getOrThrow().current.temperatureC)
        assertEquals(2, h.forecasts)
    }

    @Test
    fun eachPlaceHasItsOwnReport() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.repository.report(seattle)
        h.repository.report(portland)
        h.repository.report(seattle)
        assertEquals(2, h.forecasts)
    }

    @Test
    fun thePhoneMovingFarEnoughMakesTheOldReportStale() = runTest {
        val h = harness()
        h.repository.report(portland)
        // Same "current" place, but a few kilometres on.
        h.repository.report(portland.copy(latitude = portland.latitude + 0.05))
        assertEquals(2, h.forecasts)
    }

    @Test
    fun thePhoneMovingALittleKeepsTheReport() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.repository.report(portland.copy(latitude = portland.latitude + 0.01, longitude = portland.longitude - 0.01))
        assertEquals(1, h.forecasts)
    }

    @Test
    fun theForecastIsAskedForAtTheNewPlaceAfterTheMove() = runTest {
        val h = harness()
        h.repository.report(portland)
        h.repository.report(portland.copy(latitude = 47.6, longitude = -122.3))
        assertEquals("47.6", h.web.requestsTo("/v1/forecast").last().url.parameters["latitude"])
    }

    @Test
    fun anAirQualityFailureDoesNotFailTheReport() = runTest {
        val h = harness(WeatherPayloads.everythingUp().fail("/v1/air-quality", HttpStatusCode.InternalServerError))
        val report = h.repository.report(portland).getOrThrow()
        assertNull(report.air)
        assertEquals(1, report.alerts.size)
    }

    @Test
    fun anAlertsFailureDoesNotFailTheReport() = runTest {
        val h = harness(WeatherPayloads.everythingUp().fail("/alerts/active", HttpStatusCode.InternalServerError))
        val report = h.repository.report(portland).getOrThrow()
        assertEquals(emptyList(), report.alerts)
        assertEquals(42, report.air?.usAqi)
    }

    @Test
    fun alertsAreNotAskedForOutsideTheUs() = runTest {
        val h = harness()
        val report = h.repository.report(london).getOrThrow()
        assertEquals(0, h.web.count("api.weather.gov"))
        assertEquals(emptyList(), report.alerts)
        assertEquals(42, report.air?.usAqi)
    }

    @Test
    fun aForecastFailureFailsTheReportAndIsNotRemembered() = runTest {
        val web = WeatherPayloads.everythingUp().fail("/v1/forecast", HttpStatusCode.ServiceUnavailable)
        val h = harness(web)
        assertTrue(h.repository.report(portland).isFailure)
        assertNull(h.dao.report(portland.id))
        web.route("/v1/forecast", WeatherPayloads.forecast())
        assertTrue(h.repository.report(portland).isSuccess)
        assertEquals(2, h.forecasts)
    }

    @Test
    fun aFailedRefreshLeavesTheLastGoodReportToFallBackOn() = runTest {
        val web = WeatherPayloads.everythingUp()
        val h = harness(web)
        val good = h.repository.report(portland).getOrThrow()
        web.fail("/v1/forecast", HttpStatusCode.BadGateway)
        h.clock.seconds += 1_000
        assertTrue(h.repository.report(portland).isFailure)
        assertEquals(good, h.repository.lastReport(portland))
    }

    @Test
    fun aForecastThatFailsDoesNotWaitForTheSideServices() = runTest {
        val web = WeatherPayloads.everythingUp().fail("/v1/forecast", HttpStatusCode.BadGateway)
        val h = harness(web)
        assertTrue(h.repository.report(portland).isFailure)
    }

    @Test
    fun twoCallsAtOnceMakeOneFetch() = runTest {
        val h = harness()
        val reports = coroutineScope {
            listOf(async { h.repository.report(portland) }, async { h.repository.report(portland) }, async { h.repository.report(portland) }).awaitAll()
        }
        assertTrue(reports.all { it.isSuccess })
        assertEquals(1, h.forecasts)
    }

    @Test
    fun aStorageFailureDoesNotFailTheReport() = runTest {
        val broken = object : WeatherDao by InMemoryWeatherDao() {
            override suspend fun upsertReport(entity: WeatherReportEntity) = throw IllegalStateException("disk full")
        }
        val h = harness(dao = broken)
        assertTrue(h.repository.report(portland).isSuccess)
    }

    // ---- lastReport ---------------------------------------------------------------------------

    @Test
    fun theReportIsKeptOnTheDeviceWithWhereItWasFor() = runTest {
        val h = harness()
        h.repository.report(portland)
        val row = assertNotNull(h.dao.report(portland.id))
        assertEquals(portland.latitude, row.latitude)
        assertEquals(portland.longitude, row.longitude)
        assertEquals(start, row.fetchedAtEpochSeconds)
    }

    @Test
    fun lastReportIsTheOneJustFetched() = runTest {
        val h = harness()
        val report = h.repository.report(portland).getOrThrow()
        assertEquals(report, h.repository.lastReport(portland))
    }

    @Test
    fun lastReportSurvivesRestartingTheApp() = runTest {
        val dao = InMemoryWeatherDao()
        val report = harness(dao = dao).repository.report(portland).getOrThrow()
        // A new repository over the same storage: nothing in memory.
        val restarted = harness(WeatherPayloads.everythingUp(), dao)
        assertEquals(report, restarted.repository.lastReport(portland))
        assertEquals(0, restarted.forecasts)
    }

    @Test
    fun lastReportIsNullForAPlaceNeverFetched() = runTest {
        assertNull(harness().repository.lastReport(seattle))
    }

    @Test
    fun aSavedReportIsRejectedForSomewhereElse() = runTest {
        val dao = InMemoryWeatherDao()
        harness(dao = dao).repository.report(portland)
        val restarted = harness(dao = dao)
        // The phone's place is now in Seattle.
        assertNull(restarted.repository.lastReport(portland.copy(latitude = 47.6, longitude = -122.3)))
        // Only a little way along it is still good.
        assertNotNull(restarted.repository.lastReport(portland.copy(latitude = portland.latitude + 0.01)))
    }

    @Test
    fun aMovedPlaceDoesNotGetTheOldReportFromMemoryEither() = runTest {
        val h = harness()
        h.repository.report(portland)
        assertNull(h.repository.lastReport(portland.copy(latitude = 47.6, longitude = -122.3)))
    }

    @Test
    fun aSavedReportThatCannotBeReadIsNull() = runTest {
        val dao = InMemoryWeatherDao()
        dao.upsertReport(WeatherReportEntity(portland.id, "{ this is not a report", start, portland.latitude, portland.longitude))
        assertNull(harness(dao = dao).repository.lastReport(portland))
    }

    @Test
    fun lastReportToleratesAStorageFailure() = runTest {
        val broken = object : WeatherDao by InMemoryWeatherDao() {
            override suspend fun report(placeId: String): WeatherReportEntity? = throw IllegalStateException("database locked")
        }
        assertNull(harness(dao = broken).repository.lastReport(portland))
    }

    // ---- searchPlaces and nameOf ----------------------------------------------------------------

    @Test
    fun aSearchIsRememberedWhateverItsCaseAndSpacing() = runTest {
        val h = harness()
        val first = h.repository.searchPlaces("Seattle").getOrThrow()
        val second = h.repository.searchPlaces("  seattle ").getOrThrow()
        assertEquals(first, second)
        assertEquals("Seattle", first.single().name)
        assertEquals(1, h.web.count("/v1/search"))
    }

    @Test
    fun aDifferentSearchIsMadeAfresh() = runTest {
        val h = harness()
        h.repository.searchPlaces("Seattle")
        h.repository.searchPlaces("Tacoma")
        assertEquals(2, h.web.count("/v1/search"))
    }

    @Test
    fun aFailedSearchIsNotRemembered() = runTest {
        val web = WeatherPayloads.everythingUp().fail("/v1/search", HttpStatusCode.ServiceUnavailable)
        val h = harness(web)
        assertTrue(h.repository.searchPlaces("Seattle").isFailure)
        web.route("/v1/search", WeatherPayloads.SEARCH)
        assertTrue(h.repository.searchPlaces("Seattle").isSuccess)
        assertEquals(2, web.count("/v1/search"))
    }

    @Test
    fun theSearchIsAskedForInLowerCase() = runTest {
        val h = harness()
        h.repository.searchPlaces("SEATTLE")
        assertEquals("seattle", h.web.requests.single().url.parameters["name"])
    }

    @Test
    fun nameOfIsTheTownAndStateInTheUs() = runTest {
        assertEquals("Portland" to "OR", harness().repository.nameOf(45.5234, -122.6762))
    }

    @Test
    fun nameOfOutsideTheUsIsNullWithoutAskingAnyone() = runTest {
        val h = harness()
        assertNull(h.repository.nameOf(51.5, -0.12))
        assertEquals(0, h.web.requests.size)
    }

    @Test
    fun nameOfIsRememberedForPlacesInTheSameSpot() = runTest {
        val h = harness()
        h.repository.nameOf(45.5234, -122.6762)
        h.repository.nameOf(45.5240, -122.6770)
        assertEquals(1, h.web.count("/points/"))
    }

    @Test
    fun nameOfElsewhereIsAskedFor() = runTest {
        val h = harness()
        h.repository.nameOf(45.5234, -122.6762)
        h.repository.nameOf(47.6062, -122.3321)
        assertEquals(2, h.web.count("/points/"))
    }

    @Test
    fun aMissingNameIsNotRememberedSoTheServiceIsTriedAgain() = runTest {
        val web = WeatherPayloads.everythingUp().fail("/points/", HttpStatusCode.InternalServerError)
        val h = harness(web)
        assertNull(h.repository.nameOf(45.5, -122.6))
        web.route("/points/", WeatherPayloads.POINT)
        assertEquals("Portland" to "OR", h.repository.nameOf(45.5, -122.6))
        assertEquals(2, web.count("/points/"))
        // And once it has one, it keeps it.
        h.repository.nameOf(45.5, -122.6)
        assertEquals(2, web.count("/points/"))
    }

    // ---- radar --------------------------------------------------------------------------------

    @Test
    fun aRadarLoopIsKeptForTwoMinutes() = runTest {
        val h = harness()
        val first = h.repository.radar(45.5, -122.6).getOrThrow()
        h.clock.seconds += 119
        assertEquals(first, h.repository.radar(45.5, -122.6).getOrThrow())
        assertEquals(1, h.web.count("mrms/lcref.json"))
        h.clock.seconds += 2
        h.repository.radar(45.5, -122.6)
        assertEquals(2, h.web.count("mrms/lcref.json"))
    }

    @Test
    fun theUsLoopServesAnyPlaceInTheUs() = runTest {
        val h = harness()
        h.repository.radar(45.5, -122.6)
        h.repository.radar(33.4, -112.0)
        assertEquals(1, h.web.count("mrms/lcref.json"))
    }

    @Test
    fun movingFromTheUsToElsewhereMakesAnotherLoop() = runTest {
        val h = harness()
        assertEquals(RadarSource.US_MRMS, h.repository.radar(45.5, -122.6).getOrThrow().source)
        assertEquals(RadarSource.RAINVIEWER, h.repository.radar(51.5, -0.12).getOrThrow().source)
        assertEquals(RadarSource.US_MRMS, h.repository.radar(45.5, -122.6).getOrThrow().source)
        assertEquals(2, h.web.count("mrms/lcref.json"))
    }

    @Test
    fun aFailedRadarLoadIsNotRemembered() = runTest {
        val web = WeatherPayloads.everythingUp().fail("weather-maps.json", HttpStatusCode.ServiceUnavailable)
        val h = harness(web)
        assertTrue(h.repository.radar(51.5, -0.12).isFailure)
        web.route("weather-maps.json", WeatherPayloads.RAINVIEWER_INDEX)
        assertTrue(h.repository.radar(51.5, -0.12).isSuccess)
        assertEquals(2, web.count("weather-maps.json"))
    }
}
