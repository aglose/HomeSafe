package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.ui.geometry.Offset
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.navigation.FinanceDeepLink
import com.meticulouscreations.homesafe.navigation.FinanceDeepLinks
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A budget notification's tap, as the shell acts on it: the finance app comes up over whatever was there, on the tab asked for. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellFinanceNavigationTest {

    @Test
    fun aTapOnABudgetNotificationOpensFinanceOnTheBudgetTab() = runTest {
        val nav = ShellNavigation()
        nav.drawerOpen = true
        val taps = launch { nav.openFinanceFromNotifications() }
        runCurrent()
        assertFalse(nav.isOpen(InnerApp.FINANCE))

        FinanceDeepLinks.open(FinanceDeepLink(FinanceTab.BUDGET))
        runCurrent()
        assertTrue(nav.isOpen(InnerApp.FINANCE))
        assertEquals(FinanceTab.BUDGET, nav.financeTab)
        assertEquals(TopLevelRoute.Home, nav.appHost)
        assertFalse(nav.drawerOpen)
        // Acted on: a later look finds nothing waiting.
        assertNull(FinanceDeepLinks.pending.value)

        // The app turned to the tab; it isn't asked to again.
        nav.onFinanceTabShown()
        assertNull(nav.financeTab)
        assertTrue(nav.isOpen(InnerApp.FINANCE))
        taps.cancel()
    }

    @Test
    fun openedFromTheDrawerItAsksForNoTabAndClosingForgetsOneThatWasAsked() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset(40f, 300f), TopLevelRoute.Moments)
        assertNull(nav.financeTab)
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Moments, FinanceTab.BUDGET)
        nav.closeApp()
        assertNull(nav.financeTab)
        assertFalse(nav.isOpen(InnerApp.FINANCE))
    }

    @Test
    fun aTabAskedOfFinanceIsNotCarriedIntoAnotherApp() {
        val nav = ShellNavigation()
        nav.openApp(InnerApp.FINANCE, Offset.Zero, TopLevelRoute.Home, FinanceTab.BUDGET)
        nav.openApp(InnerApp.WEATHER, Offset.Zero, TopLevelRoute.Home)
        assertNull(nav.financeTab)
        assertTrue(nav.isOpen(InnerApp.WEATHER))
    }
}
