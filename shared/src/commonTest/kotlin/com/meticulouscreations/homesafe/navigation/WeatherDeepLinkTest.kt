package com.meticulouscreations.homesafe.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherDeepLinkTest {

    @Test
    fun aLinkSurvivesItsUri() {
        val links = listOf(
            WeatherDeepLink(),
            WeatherDeepLink(WeatherDeepLink.View.RADAR, placeId = "current"),
            WeatherDeepLink(WeatherDeepLink.View.FORECAST, placeId = "4552,-12268"),
            // A warning's key is a VTEC event's, or a message id with colons and slashes in it.
            WeatherDeepLink(placeId = "current", alertKey = "KPQR.TO.W.0012"),
            WeatherDeepLink(alertKey = "https://api.weather.gov/alerts/urn:oid:2.49.0.1.840.0.abc&d=1"),
        )
        links.forEach { link -> assertEquals(link, WeatherDeepLink.fromUri(link.toUri()), link.toUri()) }
    }

    @Test
    fun aLinkSurvivesAnIosUserInfoAndIsMarkedAsTheWeathers() {
        val link = WeatherDeepLink(WeatherDeepLink.View.FORECAST, placeId = "current", alertKey = "KPQR.TO.W.0012")
        val info = link.toMap()
        assertEquals("1", info[WeatherDeepLink.KEY])
        assertEquals(link, WeatherDeepLink.from(info::get))
    }

    @Test
    fun thePlainLinkOlderNotificationsCarryOpensToday() {
        assertEquals(WeatherDeepLink(), WeatherDeepLink.fromUri("homesafe://weather"))
    }

    @Test
    fun aViewThisBuildDoesNotKnowOpensToday() {
        assertEquals(WeatherDeepLink(placeId = "current"), WeatherDeepLink.fromUri("homesafe://weather?view=almanac&place=current"))
    }

    @Test
    fun anotherAppsLinkIsNotTheWeathers() {
        assertNull(WeatherDeepLink.fromUri("homesafe://finance?tab=budget"))
        assertNull(WeatherDeepLink.fromUri("homesafe://moment?camera=front&start_time=1"))
        assertNull(WeatherDeepLink.fromUri("https://weather"))
        assertNull(WeatherDeepLink.fromUri(null))
    }

    @Test
    fun aNewerTapReplacesOneStillWaitingAndConsumingTheOlderLeavesIt() {
        val first = WeatherDeepLink(alertKey = "a")
        val second = WeatherDeepLink(WeatherDeepLink.View.RADAR)
        WeatherDeepLinks.open(first)
        WeatherDeepLinks.open(second)
        WeatherDeepLinks.consume(first)
        assertEquals(second, WeatherDeepLinks.pending.value)
        WeatherDeepLinks.consume(second)
        assertNull(WeatherDeepLinks.pending.value)
    }
}
