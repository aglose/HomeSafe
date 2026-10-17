package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.finance.domain.FinanceDrawerItem
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceDrawerItemsTest {

    /** The card can only show a market the view model polls, and every one it polls can be put on the card. */
    @Test
    fun theCardsMarketsAreTheCatalogsIndicesAndMacroMarkets() {
        assertEquals(
            (MarketCatalog.indices + MarketCatalog.macro).map { it.symbol }.toSet(),
            FinanceDrawerItem.entries.mapNotNull { it.symbol }.toSet(),
        )
    }
}
