package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLink
import com.meticulouscreations.homesafe.navigation.WeatherDeepLinks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A weather notification's tap, as the shell acts on it: the weather app comes up over whatever was there, asked for what the notification was about. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellWeatherNavigationTest {

    @Test
    fun aTapOnAWarningOpensTheWeatherAppAskingForThatWarning() = runTest {
        val nav = ShellNavigation()
        nav.drawerOpen = true
        val taps = launch { nav.openWeatherFromNotifications() }
        runCurrent()
        assertFalse(nav.isOpen(InnerApp.WEATHER))

        val link = WeatherDeepLink(placeId = "current", alertKey = "KPQR.TO.W.0012")
        WeatherDeepLinks.open(link)
        runCurrent()
        assertTrue(nav.isOpen(InnerApp.WEATHER))
        assertEquals(link, nav.weatherLink)
        assertFalse(nav.drawerOpen)
        // Acted on: a later look finds nothing waiting.
        assertNull(WeatherDeepLinks.pending.value)

        // The app turned to it; it isn't asked to again.
        nav.onWeatherLinkShown()
        assertNull(nav.weatherLink)
        assertTrue(nav.isOpen(InnerApp.WEATHER))
        taps.cancel()
    }

    @Test
    fun aSecondTapWhileTheAppIsUpAsksItForTheNewThing() = runTest {
        val nav = ShellNavigation()
        val taps = launch { nav.openWeatherFromNotifications() }
        WeatherDeepLinks.open(WeatherDeepLink())
        runCurrent()
        nav.onWeatherLinkShown()

        val radar = WeatherDeepLink(WeatherDeepLink.View.RADAR)
        WeatherDeepLinks.open(radar)
        runCurrent()
        assertEquals(radar, nav.weatherLink)
        taps.cancel()
    }

    @Test
    fun openedFromTheDrawerItAsksForNothingAndClosingForgetsWhatWasAsked() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.WEATHER, Offset(40f, 300f), TopLevelRoute.Moments)
        assertNull(nav.weatherLink)
        nav.openApp(InnerApp.WEATHER, Offset.Zero, TopLevelRoute.Moments, weatherLink = WeatherDeepLink(WeatherDeepLink.View.RADAR))
        nav.closeApp()
        assertNull(nav.weatherLink)
    }

    @Test
    fun whatWasAskedOfTheWeatherIsNotCarriedIntoAnotherApp() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home, weatherLink = WeatherDeepLink(WeatherDeepLink.View.RADAR))
        assertNull(nav.weatherLink)
    }
}
