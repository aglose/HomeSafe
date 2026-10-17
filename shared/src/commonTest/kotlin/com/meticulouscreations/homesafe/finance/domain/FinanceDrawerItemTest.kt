package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceDrawerItemTest {

    @Test
    fun tickingOneOnPutsItInTheCardsOrder() {
        assertEquals(
            listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.GOLD, FinanceDrawerItem.NET_WORTH),
            FinanceDrawerItem.toggled(FinanceDrawerItem.DEFAULT, FinanceDrawerItem.GOLD),
        )
    }

    @Test
    fun tickingOneOffTakesItOff() {
        assertEquals(listOf(FinanceDrawerItem.NET_WORTH), FinanceDrawerItem.toggled(FinanceDrawerItem.DEFAULT, FinanceDrawerItem.SP500))
    }

    @Test
    fun aFullCardTakesNoMoreUntilOneComesOff() {
        val four = listOf(FinanceDrawerItem.SP500, FinanceDrawerItem.DOW, FinanceDrawerItem.NASDAQ, FinanceDrawerItem.CASH)

        assertEquals(four, FinanceDrawerItem.toggled(four, FinanceDrawerItem.GOLD))
        assertEquals(four - FinanceDrawerItem.DOW, FinanceDrawerItem.toggled(four, FinanceDrawerItem.DOW))
    }

    @Test
    fun whatIsStoredReadsBackTheSame() {
        val items = listOf(FinanceDrawerItem.TEN_YEAR, FinanceDrawerItem.BITCOIN, FinanceDrawerItem.DEBT)

        assertEquals(items, FinanceDrawerItem.parse(FinanceDrawerItem.store(items)))
        assertEquals(FinanceDrawerItem.DEFAULT, FinanceDrawerItem.parse(FinanceDrawerItem.DEFAULT_STORED))
        assertEquals(FinanceDrawerItem.DEFAULT, FinanceDrawerItem.parse(null))
        assertEquals(emptyList(), FinanceDrawerItem.parse(""))
    }
}
