package com.meticulouscreations.homesafe.navigation

import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [FinanceDeepLink]: the URI behind a budget notification's tap, and the hand-off that holds it until the shell exists. */
class FinanceDeepLinkTest {

    @Test
    fun aLinkReadsBackFromItsOwnUri() {
        assertEquals("homesafe://finance?tab=budget", FinanceDeepLink(FinanceTab.BUDGET).toUri())
        FinanceTab.entries.forEach { tab -> assertEquals(FinanceDeepLink(tab), FinanceDeepLink.fromUri(FinanceDeepLink(tab).toUri())) }
    }

    @Test
    fun aTabThisBuildDoesNotKnowOpensTheWallet() {
        assertEquals(FinanceDeepLink(FinanceTab.WALLET), FinanceDeepLink.fromUri("homesafe://finance?tab=crypto"))
        assertEquals(FinanceDeepLink(FinanceTab.WALLET), FinanceDeepLink.fromUri("homesafe://finance"))
    }

    @Test
    fun anythingElseIsNotAFinanceLink() {
        assertNull(FinanceDeepLink.fromUri("homesafe://moment?camera=front_door&start_time=1791374400"))
        assertNull(FinanceDeepLink.fromUri("https://finance?tab=budget"))
        assertNull(FinanceDeepLink.fromUri("not a uri"))
        // And a finance link is not a moment.
        assertNull(MomentDeepLink.fromUri(FinanceDeepLink(FinanceTab.BUDGET).toUri()))
    }

    @Test
    fun aTapWaitsToBeActedOnAndANewerOneReplacesIt() {
        val budget = FinanceDeepLink(FinanceTab.BUDGET)
        val markets = FinanceDeepLink(FinanceTab.MARKETS)
        FinanceDeepLinks.open(budget)
        FinanceDeepLinks.open(markets)
        // The first was replaced before anyone looked: clearing it leaves the newer one waiting.
        FinanceDeepLinks.consume(budget)
        assertEquals(markets, FinanceDeepLinks.pending.value)
        FinanceDeepLinks.consume(markets)
        assertNull(FinanceDeepLinks.pending.value)
    }
}
