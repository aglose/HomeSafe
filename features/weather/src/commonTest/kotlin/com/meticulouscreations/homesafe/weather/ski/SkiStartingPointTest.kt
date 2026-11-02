package com.meticulouscreations.homesafe.weather.ski

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.weather.data.FakeGeofence
import com.meticulouscreations.homesafe.weather.data.FakeWeatherRepository
import com.meticulouscreations.homesafe.weather.data.WeatherLocator
import com.meticulouscreations.homesafe.weather.data.WeatherPlacesRepositoryImpl
import com.meticulouscreations.homesafe.weather.data.WeatherPreferencesRepositoryImpl
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.ski.data.SkiStartingPointImpl
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkiStartingPointTest {
    private class Harness(canLocate: Boolean = true) {
        val fence = FakeGeofence().apply {
            isSupported = canLocate
            if (!canLocate) here = null
        }
        val identity = DeviceIdentityStore(InMemorySettingsDao())
        val places = WeatherPlacesRepositoryImpl(InMemoryWeatherDao())
        val locator = WeatherLocator(fence, identity, FakeWeatherRepository(), WeatherPreferencesRepositoryImpl(InMemoryWeatherDao()))
        val start = SkiStartingPointImpl(identity, locator, places)
    }

    private val city = Place("reno", "Reno", "Nevada", 39.5, -119.8)

    @Test
    fun aDeviceThatCanLocateStartsFromWhereItIs() = runTest {
        val h = Harness()
        h.identity.saveCachedHome(HomeLocation(37.0, -122.0, 100.0))

        assertTrue(h.start.canLocate)
        assertEquals(SkiPoint(45.5234, -122.6762), h.start.of(SkiOrigin.HERE))
        assertEquals(SkiPoint(37.0, -122.0), h.start.of(SkiOrigin.HOME))
    }

    @Test
    fun aMacStartsFromHomeWhicheverWasChosen() = runTest {
        val h = Harness(canLocate = false)
        h.identity.saveCachedHome(HomeLocation(37.0, -122.0, 100.0))
        h.places.add(city)

        assertFalse(h.start.canLocate)
        assertEquals(SkiPoint(37.0, -122.0), h.start.of(SkiOrigin.HERE))
        assertEquals(SkiPoint(37.0, -122.0), h.start.of(SkiOrigin.HOME))
    }

    @Test
    fun withNoHomeAMacGoesByAWeatherCityAndNamesIt() = runTest {
        val h = Harness(canLocate = false)
        h.places.add(city)
        h.places.add(Place("sf", "San Francisco", "California", 37.8, -122.4))

        assertEquals(SkiPoint(39.5, -119.8, "Reno"), h.start.of(SkiOrigin.HOME))
    }

    @Test
    fun withNothingAtAllThereIsNoStart() = runTest {
        assertNull(Harness(canLocate = false).start.of(SkiOrigin.HOME))
    }
}
