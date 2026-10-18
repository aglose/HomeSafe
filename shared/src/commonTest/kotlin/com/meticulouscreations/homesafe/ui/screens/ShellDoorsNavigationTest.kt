package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.navigation.DoorDeepLink
import com.meticulouscreations.homesafe.navigation.DoorDeepLinks
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A door notification's tap, as the shell acts on it: the Doors page on Home, above the camera list, whatever was up. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellDoorsNavigationTest {

    @Test
    fun aTapOnADoorNotificationOpensTheDoorsPageOnHome() = runTest {
        val nav = ShellNavigation()
        nav.selectTab(TopLevelRoute.Settings)
        nav.drawerOpen = true
        val taps = launch { nav.openDoorsFromNotifications() }
        runCurrent()
        val list = nav.homeBackStack.single()

        DoorDeepLinks.open(DoorDeepLink("back_door"))
        runCurrent()
        assertEquals(TopLevelRoute.Home, nav.selectedTab)
        assertEquals(listOf(list, DoorsHomeRoute), nav.homeBackStack.toList())
        assertFalse(nav.drawerOpen)
        // Acted on: a later look finds nothing waiting.
        assertNull(DoorDeepLinks.pending.value)
        taps.cancel()
    }

    @Test
    fun itLandsAboveTheCameraListWhateverHomeHadOpen() {
        val nav = ShellNavigation()
        val list = nav.homeBackStack.single()
        // A camera's page, say: whatever stood above the list goes.
        nav.homeBackStack.add(SomePageOnHome)
        nav.openDoors()
        assertEquals(listOf(list, DoorsHomeRoute), nav.homeBackStack.toList())
    }

    @Test
    fun aSecondTapWithThePageUpLeavesItAsItIs() {
        val nav = ShellNavigation()
        val list = nav.homeBackStack.single()
        nav.openDoors()
        nav.openDoors()
        assertEquals(listOf(list, DoorsHomeRoute), nav.homeBackStack.toList())
    }

    @Test
    fun itClosesAnAppThatWasUpOverTheShell() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.WEATHER, Offset.Zero, TopLevelRoute.Moments)
        nav.openDoors()
        assertFalse(nav.isOpen(InnerApp.WEATHER))
        assertEquals(TopLevelRoute.Home, nav.selectedTab)
        assertEquals(DoorsHomeRoute, nav.homeBackStack.last())
    }
}

private data object SomePageOnHome
