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
        assertFalse(nav.financeOpen)

        FinanceDeepLinks.open(FinanceDeepLink(FinanceTab.BUDGET))
        runCurrent()
        assertTrue(nav.financeOpen)
        assertEquals(FinanceTab.BUDGET, nav.financeTab)
        assertEquals(TopLevelRoute.Home, nav.financeHost)
        assertFalse(nav.drawerOpen)
        // Acted on: a later look finds nothing waiting.
        assertNull(FinanceDeepLinks.pending.value)

        // The app turned to the tab; it isn't asked to again.
        nav.onFinanceTabShown()
        assertNull(nav.financeTab)
        assertTrue(nav.financeOpen)
        taps.cancel()
    }

    @Test
    fun openedFromTheDrawerItAsksForNoTabAndClosingForgetsOneThatWasAsked() {
        val nav = ShellNavigation()
        nav.openFinance(Offset(40f, 300f), TopLevelRoute.Moments)
        assertNull(nav.financeTab)
        nav.openFinance(Offset.Zero, TopLevelRoute.Moments, FinanceTab.BUDGET)
        nav.closeFinance()
        assertNull(nav.financeTab)
        assertFalse(nav.financeOpen)
    }
}
