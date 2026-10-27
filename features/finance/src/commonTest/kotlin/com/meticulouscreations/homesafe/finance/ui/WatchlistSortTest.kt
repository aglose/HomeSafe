package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.WatchEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** The orders the watchlist can be put in. */
class WatchlistSortTest {

    private fun entry(symbol: String, position: Position? = null) = WatchEntry(symbol, inSheet = true, addedInApp = false, position = position)

    private fun quote(symbol: String, price: Double, previousClose: Double) = Quote(
        symbol = symbol,
        price = price,
        previousClose = previousClose,
        dayHigh = null,
        dayLow = null,
        fiftyTwoWeekHigh = null,
        fiftyTwoWeekLow = null,
        volume = null,
        marketTimeEpochSeconds = 0,
        gmtOffsetSeconds = 0,
        sessionStartEpochSeconds = null,
        sessionEndEpochSeconds = null,
        intraday = Series(LongArray(0), DoubleArray(0)),
    )

    private val entries = listOf(
        entry("VTI", Position(shares = 10.0)),
        entry("AAPL", Position(shares = 2.0)),
        entry("ETH"),
        entry("NEW", Position(shares = 5.0)),
    )

    private val quotes = mapOf(
        // +1%, worth 1,010.
        "VTI" to quote("VTI", 101.0, 100.0),
        // -5%, worth 380.
        "AAPL" to quote("AAPL", 190.0, 200.0),
        // +3%, nothing held.
        "ETH" to quote("ETH", 103.0, 100.0),
    )

    private fun List<WatchEntry>.symbols() = map { it.symbol }

    @Test
    fun theListsOwnOrderIsLeftAsItIs() {
        assertEquals(listOf("VTI", "AAPL", "ETH", "NEW"), sortWatchEntries(entries, quotes, WatchSort.LIST).symbols())
    }

    @Test
    fun biggestMovesComeFirstEitherWayWithNoQuoteLast() {
        assertEquals(listOf("AAPL", "ETH", "VTI", "NEW"), sortWatchEntries(entries, quotes, WatchSort.MOVE).symbols())
    }

    @Test
    fun mostHeldComesFirstAndWhatIsNotHeldOrNotQuotedKeepsItsOrderAtTheEnd() {
        assertEquals(listOf("VTI", "AAPL", "NEW", "ETH"), sortWatchEntries(entries.reversed(), quotes, WatchSort.HELD).symbols())
    }
}
