package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An iPad's shell, where the apps are tabs in the sidebar and Settings stands its list beside the page it opens. */
class ShellSidebarNavigationTest {

    @Test
    fun withTheAppsInASidebarOpeningOneGoesToItsTabInsteadOfGrowingItOverTheShell() {
        val asked = mutableListOf<InnerApp>()
        val nav = ShellNavigation(onAppSelected = { asked += it })
        nav.appsInSidebar = true
        nav.openApp(InnerApp.FINANCE, Offset(40f, 300f), TopLevelRoute.Moments, FinanceTab.BUDGET)
        assertEquals(listOf(InnerApp.FINANCE), asked)
        assertFalse(nav.appOpen)
        assertNull(nav.appHost)
        // The tab reads what it was asked to show from the shell, as the overlay does.
        assertEquals(FinanceTab.BUDGET, nav.financeTab)
        // Nothing covers the shell, so its nav stays.
        assertTrue(nav.showsBottomNav(TopLevelRoute.Moments))
    }

    @Test
    fun withTheAppsInASidebarThereIsNoDrawerToReach() {
        val nav = ShellNavigation()
        assertNotNull(nav.openDrawer)
        assertNotNull(nav.drawerPullOn(TopLevelRoute.Home))
        nav.appsInSidebar = true
        assertNull(nav.openDrawer)
        assertNull(nav.drawerPullOn(TopLevelRoute.Home))
    }

    @Test
    fun withoutASidebarTheAppStillOpensOverTheShell() {
        val asked = mutableListOf<InnerApp>()
        val nav = ShellNavigation(onAppSelected = { asked += it })
        nav.openApp(InnerApp.WEATHER, Offset.Zero, TopLevelRoute.Home)
        assertTrue(nav.isOpen(InnerApp.WEATHER))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun settingsInTwoPanesKeepsTheTopBarOverItsListWhileAPageIsOpen() {
        val nav = ShellNavigation()
        nav.settingsBackStack.add(NotificationsRoute)
        assertFalse(nav.showsTopBar(TopLevelRoute.Settings))
        nav.twoPanes = true
        assertTrue(nav.showsTopBar(TopLevelRoute.Settings))
        // Home's pages bring their own header either way.
        nav.homeBackStack.add(DoorsHomeRoute)
        assertFalse(nav.showsTopBar(TopLevelRoute.Home))
    }
}
