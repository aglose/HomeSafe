package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.data.InMemoryWeatherDao
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.weather.domain.Place
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherLocatorTest {

    private class Harness(access: LocationAccess = LocationAccess.WHILE_IN_USE) {
        val fence = FakeGeofence(access)
        val repository = FakeWeatherRepository().apply { onName = { _, _ -> "Portland" to "OR" } }
        val identity = DeviceIdentityStore(InMemorySettingsDao())
        val preferences = WeatherPreferencesRepositoryImpl(InMemoryWeatherDao())
        val locator = WeatherLocator(fence, identity, repository, preferences)

        /** A locator with no fix in hand, over the same phone and stores: what the app does after a while (a fix is kept for half a minute). */
        fun anotherLocator() = WeatherLocator(fence, identity, repository, preferences)
    }

    @Test
    fun withoutAFixThereIsNoPlace() = runTest {
        val h = Harness().apply { fence.here = null }
        assertNull(h.locator.locate())
    }

    @Test
    fun whereTheMonitorCannotLocateThereIsNoPlaceAndNoLookup() = runTest {
        val h = Harness().apply { fence.isSupported = false }
        assertNull(h.locator.locate())
        assertEquals(emptyList(), h.repository.nameRequests)
    }

    @Test
    fun aFixIsTheCurrentPlaceNamedByTheWeatherService() = runTest {
        val h = Harness()
        val place = h.locator.locate()
        assertEquals(Place(Place.CURRENT_ID, "Portland", "OR", 45.5234, -122.6762), place)
        assertEquals(true, place?.isCurrentLocation)
    }

    @Test
    fun aFixNobodyCanNameIsStillAPlace() = runTest {
        val h = Harness().apply { repository.onName = { _, _ -> null } }
        assertEquals(Place(Place.CURRENT_ID, "", "", 45.5234, -122.6762), h.locator.locate())
    }

    @Test
    fun theFixIsRememberedForNextTime() = runTest {
        val h = Harness()
        val place = h.locator.locate()
        assertEquals(place, h.preferences.observe().first().lastKnown)
    }

    @Test
    fun aNearbyFixKeepsTheNameWithoutAskingAgain() = runTest {
        val h = Harness()
        h.locator.locate()
        h.fence.here = GeoPoint(45.5334, -122.6862)
        val moved = h.anotherLocator().locate()
        assertEquals(1, h.repository.nameRequests.size)
        assertEquals("Portland", moved?.name)
        assertEquals(45.5334, moved?.latitude)
        assertEquals(45.5334, h.preferences.observe().first().lastKnown?.latitude)
    }

    @Test
    fun aFixFarEnoughAwayIsNamedAfresh() = runTest {
        val h = Harness()
        h.locator.locate()
        h.fence.here = GeoPoint(47.6062, -122.3321)
        h.repository.onName = { _, _ -> "Seattle" to "WA" }
        assertEquals("Seattle", h.anotherLocator().locate()?.name)
        assertEquals(2, h.repository.nameRequests.size)
    }

    @Test
    fun aKnownPlaceWithNoNameIsNamedAgainNextTime() = runTest {
        val h = Harness().apply { repository.onName = { _, _ -> null } }
        h.locator.locate()
        h.repository.onName = { _, _ -> "Portland" to "OR" }
        assertEquals("Portland", h.anotherLocator().locate()?.name)
    }

    @Test
    fun aFixAFewSecondsOldAnswersTheNextAsker() = runTest {
        val h = Harness()
        val first = h.locator.locate()
        h.fence.here = GeoPoint(47.6062, -122.3321)
        assertEquals(first, h.locator.locate())
        assertEquals(1, h.fence.fixes)
    }

    @Test
    fun twoAskersAtOnceShareOneFix() = runTest {
        val h = Harness()
        val both = coroutineScope { listOf(async { h.locator.locate() }, async { h.locator.locate() }).awaitAll() }
        assertEquals(both[0], both[1])
        assertEquals(1, h.fence.fixes)
    }

    @Test
    fun aMissingFixIsNotKeptSoTheNextAskerTriesAgain() = runTest {
        val h = Harness().apply { fence.here = null }
        assertNull(h.locator.locate())
        h.fence.here = GeoPoint(45.5234, -122.6762)
        assertEquals(45.5234, h.locator.locate()?.latitude)
        assertEquals(2, h.fence.fixes)
    }

    @Test
    fun locationIsOnlyAskedForWhenNotAskedOrDenied() = runTest {
        listOf(LocationAccess.NOT_ASKED, LocationAccess.DENIED).forEach { access ->
            val h = Harness(access)
            h.locator.requestAccess()
            assertEquals(1, h.fence.accessRequests, "$access")
        }
        listOf(LocationAccess.WHILE_IN_USE, LocationAccess.ALWAYS, LocationAccess.UNAVAILABLE).forEach { access ->
            val h = Harness(access)
            h.locator.requestAccess()
            assertEquals(0, h.fence.accessRequests, "$access")
        }
    }

    @Test
    fun supportAndAccessComeFromTheMonitor() = runTest {
        val h = Harness(LocationAccess.DENIED)
        assertEquals(true, h.locator.isSupported)
        assertEquals(LocationAccess.DENIED, h.locator.access.value)
        h.fence.access.value = LocationAccess.ALWAYS
        assertEquals(LocationAccess.ALWAYS, h.locator.access.value)
        h.fence.isSupported = false
        assertEquals(false, h.locator.isSupported)
    }

    // ---- remembered ---------------------------------------------------------------------------

    @Test
    fun rememberedIsWhereThePhoneLastWas() = runTest {
        val h = Harness()
        val last = Place(Place.CURRENT_ID, "Salem", "OR", 44.94, -123.03)
        h.preferences.update { it.copy(lastKnown = last) }
        assertEquals(last, h.locator.remembered())
        assertEquals(0, h.repository.nameRequests.size)
    }

    @Test
    fun withNoLastKnownPlaceRememberedIsTheHouseholdsHomeAndNothingIsFetched() = runTest {
        val h = Harness()
        h.identity.saveCachedHome(HomeLocation(45.0, -122.0, 150.0))
        assertEquals(Place(Place.CURRENT_ID, "", "", 45.0, -122.0), h.locator.remembered())
        assertEquals(emptyList(), h.repository.nameRequests)
    }

    // ---- named --------------------------------------------------------------------------------

    @Test
    fun anUnnamedPlaceIsGivenTheNameTheServiceKnowsItBy() = runTest {
        val h = Harness()
        val unnamed = Place(Place.CURRENT_ID, "", "", 45.0, -122.0)
        assertEquals(Place(Place.CURRENT_ID, "Portland", "OR", 45.0, -122.0), h.locator.named(unnamed))
        assertEquals(listOf(45.0 to -122.0), h.repository.nameRequests)
    }

    @Test
    fun aPlaceThatHasANameIsLeftAloneWithoutAskingAnyone() = runTest {
        val h = Harness()
        val salem = Place(Place.CURRENT_ID, "Salem", "OR", 44.94, -123.03)
        assertEquals(salem, h.locator.named(salem))
        assertEquals(emptyList(), h.repository.nameRequests)
    }

    @Test
    fun aPlaceTheServiceCannotNameStaysUnnamed() = runTest {
        val h = Harness().apply { repository.onName = { _, _ -> null } }
        val unnamed = Place(Place.CURRENT_ID, "", "", 45.0, -122.0)
        assertEquals(unnamed, h.locator.named(unnamed))
    }

    @Test
    fun withNothingToGoOnNothingIsRemembered() = runTest {
        assertNull(Harness().locator.remembered())
    }
}
