package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An app's camera button and the chip that undoes it: to the cameras in one tap, and back to the app in one more. */
class ShellCameraHopTest {

    @Test
    fun theHopClosesTheAppAndBringsUpTheCameraListWithTheWayBackKept() {
        val nav = ShellNavigation()
        // Home had been left on a page of its own.
        nav.homeBackStack.add("a camera's page")
        nav.selectTab(TopLevelRoute.Settings)
        nav.openApp(InnerApp.FINANCE, Offset(40f, 300f), TopLevelRoute.Settings)

        assertNotNull(nav.hopToCameras).invoke()

        assertFalse(nav.appOpen)
        assertEquals(TopLevelRoute.Home, nav.selectedTab)
        assertEquals(1, nav.homeBackStack.size)
        assertEquals(InnerApp.FINANCE, nav.returnApp)
    }

    @Test
    fun theWayBackOpensTheAppToResumeAndIsThenSpent() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertFalse(nav.appResumes)
        assertNotNull(nav.hopToCameras).invoke()

        nav.returnToApp(Offset(195f, 700f))

        assertTrue(nav.isOpen(InnerApp.FINANCE))
        assertTrue(nav.appResumes)
        assertEquals(Offset(195f, 700f), nav.appOrigin)
        assertNull(nav.returnApp)
    }

    @Test
    fun theWayBackOpensTheAppOverTheTabItsChipWasOn() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertNotNull(nav.hopToCameras).invoke()

        // A native tab bar had moved on to Settings without telling the shell.
        nav.returnToApp(Offset.Zero, TopLevelRoute.Settings)

        assertTrue(nav.isOpen(InnerApp.FINANCE))
        assertEquals(TopLevelRoute.Settings, nav.appHost)
    }

    @Test
    fun openingAnAppAnotherWayForgetsTheWayBackAndStartsAfresh() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertNotNull(nav.hopToCameras).invoke()

        nav.openApp(InnerApp.WEATHER, Offset.Zero, TopLevelRoute.Home)
        assertNull(nav.returnApp)
        assertFalse(nav.appResumes)

        // And an app come back to, then closed and opened from the drawer, starts afresh too.
        nav.closeApp()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertNotNull(nav.hopToCameras).invoke()
        nav.returnToApp(Offset.Zero)
        nav.closeApp()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertFalse(nav.appResumes)
    }

    @Test
    fun wideningIntoTheSidebarForgetsTheWayBackForGood() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertNotNull(nav.hopToCameras).invoke()

        nav.useSidebar(true)
        nav.useSidebar(false)

        assertNull(nav.returnApp)
    }

    @Test
    fun aHostPuttingTheAppsInItsOwnSidebarForgetsTheWayBackToo() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home)
        assertNotNull(nav.hopToCameras).invoke()

        // An iPad's native sidebar sets this itself, without going through useSidebar.
        nav.appsInSidebar = true
        nav.appsInSidebar = false

        assertNull(nav.returnApp)
    }

    @Test
    fun thereIsNoHopBeforeSignInOrBesideASidebar() {
        assertNull(ShellNavigation().apply { camerasReachable = false }.hopToCameras)
        assertNull(ShellNavigation().apply { useSidebar(true) }.hopToCameras)
    }

    @Test
    fun withNoAppUpTheHopDoesNothing() {
        val nav = ShellNavigation()
        assertNotNull(nav.hopToCameras).invoke()
        assertNull(nav.returnApp)
        nav.returnToApp(Offset.Zero)
        assertFalse(nav.appOpen)
    }
}
