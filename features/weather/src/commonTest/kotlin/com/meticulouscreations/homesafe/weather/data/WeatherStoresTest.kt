package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.data.WeatherDao
import com.meticulouscreations.homesafe.data.WeatherPreferencesEntity
import com.meticulouscreations.homesafe.data.WeatherReportEntity
import com.meticulouscreations.homesafe.weather.domain.MeasureSystem
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.TemperatureUnit
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import com.meticulouscreations.homesafe.weather.domain.WeatherPreferences
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weather app's three small stores, each over the same DAO the real ones share. */
class WeatherStoresTest {

    private val portland = Place(Place.idFor(45.5234, -122.6762), "Portland", "Oregon, United States", 45.5234, -122.6762)
    private val seattle = Place(Place.idFor(47.6062, -122.3321), "Seattle", "Washington, United States", 47.6062, -122.3321)
    private val tokyo = Place(Place.idFor(35.6762, 139.6503), "Tokyo", "Japan", 35.6762, 139.6503)
    private val oslo = Place(Place.idFor(59.9139, 10.7522), "Oslo", "Norway", 59.9139, 10.7522)

    // ---- Places -------------------------------------------------------------------------------

    private suspend fun WeatherPlacesRepositoryImpl.ids() = observe().first().map { it.name }

    @Test
    fun withNoCitiesThereIsAnEmptyList() = runTest {
        assertEquals(emptyList(), WeatherPlacesRepositoryImpl(InMemoryWeatherDao()).observe().first())
    }

    @Test
    fun citiesComeBackInTheOrderTheyWereAdded() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        places.add(portland)
        places.add(seattle)
        places.add(tokyo)
        assertEquals(listOf("Portland", "Seattle", "Tokyo"), places.ids())
    }

    @Test
    fun aCityComesBackWithAllItsDetail() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        places.add(seattle)
        assertEquals(listOf(seattle), places.observe().first())
    }

    @Test
    fun addingTheSameCityTwiceKeepsOne() = runTest {
        val dao = InMemoryWeatherDao()
        val places = WeatherPlacesRepositoryImpl(dao)
        places.add(portland)
        places.add(seattle)
        places.add(portland.copy(name = "Portland again"))
        assertEquals(listOf("Portland", "Seattle"), places.ids())
        assertEquals(listOf(0, 1), dao.places().map { it.position })
    }

    @Test
    fun removingACityLeavesTheRestInOrder() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.remove(seattle.id)
        assertEquals(listOf("Portland", "Tokyo"), places.ids())
    }

    @Test
    fun aCityAddedAfterARemovalGoesToTheEnd() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.remove(portland.id)
        places.add(oslo)
        assertEquals(listOf("Seattle", "Tokyo", "Oslo"), places.ids())
    }

    @Test
    fun removingACityForgetsItsSavedForecastToo() = runTest {
        val dao = InMemoryWeatherDao()
        val places = WeatherPlacesRepositoryImpl(dao)
        places.add(seattle)
        dao.upsertReport(WeatherReportEntity(seattle.id, "{}", 1, seattle.latitude, seattle.longitude))
        dao.upsertReport(WeatherReportEntity(portland.id, "{}", 1, portland.latitude, portland.longitude))
        places.remove(seattle.id)
        assertNull(dao.report(seattle.id))
        assertNotNull(dao.report(portland.id))
    }

    @Test
    fun removingACityThatIsNotThereChangesNothing() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        places.add(portland)
        places.remove("nowhere")
        assertEquals(listOf("Portland"), places.ids())
    }

    @Test
    fun removingACityEvenIfItsForecastCannotBeDeletedStillRemovesIt() = runTest {
        val real = InMemoryWeatherDao()
        val dao = object : WeatherDao by real {
            override suspend fun deleteReport(placeId: String) = throw IllegalStateException("locked")
        }
        val places = WeatherPlacesRepositoryImpl(dao)
        places.add(portland)
        places.remove(portland.id)
        assertEquals(emptyList(), places.observe().first())
    }

    @Test
    fun movingACityToTheFrontShiftsTheOthersDown() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(tokyo.id, 0)
        assertEquals(listOf("Tokyo", "Portland", "Seattle"), places.ids())
    }

    @Test
    fun movingACityDownOnePlaceSwapsItWithItsNeighbour() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(portland.id, 1)
        assertEquals(listOf("Seattle", "Portland", "Tokyo"), places.ids())
    }

    @Test
    fun movingPastTheEndsPutsACityAtTheEnd() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(portland.id, 99)
        assertEquals(listOf("Seattle", "Tokyo", "Portland"), places.ids())
    }

    @Test
    fun movingBeforeTheStartPutsACityAtTheFront() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(tokyo.id, -1)
        assertEquals(listOf("Tokyo", "Portland", "Seattle"), places.ids())
    }

    @Test
    fun movingToWhereItIsChangesNothing() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(seattle.id, 1)
        assertEquals(listOf("Portland", "Seattle", "Tokyo"), places.ids())
    }

    @Test
    fun movingACityThatIsNotThereChangesNothing() = runTest {
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        listOf(portland, seattle).forEach { places.add(it) }
        places.move("nowhere", 0)
        assertEquals(listOf("Portland", "Seattle"), places.ids())
    }

    @Test
    fun theOrderStaysAfterAMoveAndALaterAdd() = runTest {
        val dao = InMemoryWeatherDao()
        val places = WeatherPlacesRepositoryImpl(dao)
        listOf(portland, seattle, tokyo).forEach { places.add(it) }
        places.move(tokyo.id, 0)
        places.add(oslo)
        assertEquals(listOf("Tokyo", "Portland", "Seattle", "Oslo"), places.ids())
        assertEquals(listOf(0, 1, 2, 3), dao.places().map { it.position })
    }

    // ---- Preferences --------------------------------------------------------------------------

    private fun prefsEntity(
        temperatureUnit: String = "FAHRENHEIT",
        measures: String = "IMPERIAL",
        lastLatitude: Double? = null,
        lastLongitude: Double? = null,
    ) = WeatherPreferencesEntity(
        temperatureUnit = temperatureUnit,
        measures = measures,
        noticesEnabled = true,
        noticePrecipitationSoon = true,
        noticeDailyOutlook = true,
        noticeSevereAlerts = true,
        noticeExtremes = true,
        stillSky = false,
        lastLatitude = lastLatitude,
        lastLongitude = lastLongitude,
    )

    @Test
    fun withNothingSavedThePreferencesAreTheDefaults() = runTest {
        assertEquals(WeatherPreferences(), WeatherPreferencesRepositoryImpl(InMemoryWeatherDao()).observe().first())
    }

    @Test
    fun everyPreferenceRoundTrips() = runTest {
        val repository = WeatherPreferencesRepositoryImpl(InMemoryWeatherDao())
        val chosen = WeatherPreferences(
            units = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC),
            notices = WeatherNoticeSettings(enabled = true, precipitationSoon = false, dailyOutlook = true, severeAlerts = false, extremes = false),
            stillSky = true,
            selectedPlaceId = seattle.id,
            lastKnown = Place(Place.CURRENT_ID, "Portland", "OR", 45.5234, -122.6762),
        )
        repository.update { chosen }
        assertEquals(chosen, repository.observe().first())
    }

    @Test
    fun thePhonesPlaceHasNoNameUntilItIsGivenOne() = runTest {
        val repository = WeatherPreferencesRepositoryImpl(InMemoryWeatherDao())
        val unnamed = Place(Place.CURRENT_ID, "", "", 10.5, 20.5)
        repository.update { it.copy(lastKnown = unnamed) }
        assertEquals(unnamed, repository.observe().first().lastKnown)
    }

    @Test
    fun clearingTheLastKnownPlaceForgetsIt() = runTest {
        val repository = WeatherPreferencesRepositoryImpl(InMemoryWeatherDao())
        repository.update { it.copy(lastKnown = Place(Place.CURRENT_ID, "Portland", "OR", 45.5, -122.6)) }
        repository.update { it.copy(lastKnown = null) }
        assertNull(repository.observe().first().lastKnown)
    }

    @Test
    fun anUpdateChangesOnlyWhatItTouches() = runTest {
        val repository = WeatherPreferencesRepositoryImpl(InMemoryWeatherDao())
        repository.update { it.copy(stillSky = true) }
        repository.update { it.copy(units = it.units.copy(temperature = TemperatureUnit.CELSIUS)) }
        val saved = repository.observe().first()
        assertTrue(saved.stillSky)
        assertEquals(WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.IMPERIAL), saved.units)
    }

    @Test
    fun anUnknownSavedUnitNameFallsBackToItsDefaultAlone() = runTest {
        val dao = InMemoryWeatherDao()
        dao.upsertPreferences(
            WeatherPreferencesEntity(
                temperatureUnit = "KELVIN",
                measures = "METRIC",
                noticesEnabled = false,
                noticePrecipitationSoon = true,
                noticeDailyOutlook = true,
                noticeSevereAlerts = true,
                noticeExtremes = true,
                stillSky = true,
            ),
        )
        val saved = WeatherPreferencesRepositoryImpl(dao).observe().first()
        assertEquals(WeatherUnits(TemperatureUnit.FAHRENHEIT, MeasureSystem.METRIC), saved.units)
        assertEquals(false, saved.notices.enabled)
        assertTrue(saved.stillSky)
        val other = prefsEntity(temperatureUnit = "CELSIUS", measures = "NAUTICAL")
        dao.upsertPreferences(other)
        assertEquals(WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.IMPERIAL), WeatherPreferencesRepositoryImpl(dao).observe().first().units)
    }

    @Test
    fun aLastKnownPlaceNeedsBothCoordinates() = runTest {
        val dao = InMemoryWeatherDao()
        dao.upsertPreferences(prefsEntity(lastLatitude = 45.5, lastLongitude = null))
        assertNull(WeatherPreferencesRepositoryImpl(dao).observe().first().lastKnown)
    }

    @Test
    fun anUpdateThatChangesNothingWritesNothing() = runTest {
        var writes = 0
        val real = InMemoryWeatherDao()
        val dao = object : WeatherDao by real {
            override suspend fun upsertPreferences(entity: WeatherPreferencesEntity) {
                writes++
                real.upsertPreferences(entity)
            }
        }
        val repository = WeatherPreferencesRepositoryImpl(dao)
        repository.update { it }
        assertEquals(0, writes)
        repository.update { it.copy(stillSky = true) }
        assertEquals(1, writes)
        repository.update { it.copy(stillSky = true) }
        assertEquals(1, writes)
    }

    // ---- Notice ledger ------------------------------------------------------------------------

    private val day = 86_400L

    @Test
    fun aRecordedNoticeIsReportedWithWhenItWasSent() = runTest {
        val ledger = WeatherNoticeLedgerImpl(InMemoryWeatherDao())
        ledger.record("alert:1", 1_000)
        ledger.record("soon:5", 2_000)
        assertEquals(mapOf("alert:1" to 1_000L, "soon:5" to 2_000L), ledger.sent(3_000))
    }

    @Test
    fun withNothingRecordedNothingWasSent() = runTest {
        assertEquals(emptyMap(), WeatherNoticeLedgerImpl(InMemoryWeatherDao()).sent(1_000_000))
    }

    @Test
    fun recordingAKeyAgainUpdatesWhenItWasSent() = runTest {
        val ledger = WeatherNoticeLedgerImpl(InMemoryWeatherDao())
        ledger.record("alert:1", 1_000)
        ledger.record("alert:1", 5_000)
        assertEquals(mapOf("alert:1" to 5_000L), ledger.sent(6_000))
    }

    @Test
    fun noticesOlderThanFiveDaysAreForgotten() = runTest {
        val dao = InMemoryWeatherDao()
        val ledger = WeatherNoticeLedgerImpl(dao)
        val now = 100 * day
        ledger.record("old", now - 5 * day - 1)
        ledger.record("edge", now - 5 * day)
        ledger.record("recent", now - day)
        assertEquals(mapOf("edge" to now - 5 * day, "recent" to now - day), ledger.sent(now))
        // And they are gone from storage, not only hidden.
        assertEquals(setOf("edge", "recent"), dao.notices().map { it.key }.toSet())
    }

    @Test
    fun theSameNoticeBecomesNewAgainOnceItHasAgedOut() = runTest {
        val ledger = WeatherNoticeLedgerImpl(InMemoryWeatherDao())
        ledger.record("today:1", 0)
        assertTrue("today:1" in ledger.sent(4 * day))
        assertTrue("today:1" !in ledger.sent(6 * day))
    }
}
