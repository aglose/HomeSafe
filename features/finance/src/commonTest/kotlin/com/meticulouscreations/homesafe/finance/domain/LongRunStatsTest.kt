package com.meticulouscreations.homesafe.finance.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LongRunStats] over a whole history, and [Holdings] across the watchlist's positions: the
 * arithmetic the "All time" block and the holdings card show.
 */
class LongRunStatsTest {

    private val year = (365.25 * 86_400).toLong()
    private val now = 2_000_000_000L

    /** One bar a year for [values].size years, ending a week ago. */
    private fun yearly(vararg values: Double, highest: Double? = null, highestAt: Long? = null): PriceHistory {
        val n = values.size
        val times = LongArray(n) { now - 7 * 86_400 - (n - 1 - it) * year }
        return PriceHistory("^X", ChartRange.MAX, Series(times, values), null, 0, highest, highestAt)
    }

    private fun quote(price: Double, dayHigh: Double? = null) = Quote(
        symbol = "^X", price = price, previousClose = price, dayHigh = dayHigh, dayLow = null, fiftyTwoWeekHigh = null,
        fiftyTwoWeekLow = null, volume = null, marketTimeEpochSeconds = now, gmtOffsetSeconds = 0,
        sessionStartEpochSeconds = null, sessionEndEpochSeconds = null, intraday = Series.Empty,
    )

    private fun close(expected: Double, actual: Double?, what: String) =
        assertTrue(actual != null && abs(actual - expected) < 0.01, "$what: expected $expected, was $actual")

    @Test
    fun returnsOverOneFiveAndTenYearsAndSinceTheStart() {
        // Eleven years of bars: 100 at the start, doubling to 200 by now.
        val values = DoubleArray(11) { 100.0 + it * 10.0 }
        val stats = LongRunStats.of(yearly(*values), quote(200.0), now)!!
        close(100.0, stats.sinceStartPercent, "since the start")
        close(200.0 / 190.0 * 100 - 100, stats.oneYearPercent, "a year")
        close(200.0 / 150.0 * 100 - 100, stats.fiveYearPercent, "five years")
        close(100.0, stats.tenYearPercent, "ten years")
        // Doubling over ten years and a week is a little under 7.2% a year.
        assertTrue(stats.perYearPercent!! in 7.0..7.2, "per year: ${stats.perYearPercent}")
    }

    @Test
    fun aSpanTheHistoryDoesntCoverIsLeftBlank() {
        val stats = LongRunStats.of(yearly(100.0, 110.0, 120.0), quote(130.0), now)!!
        assertTrue(stats.oneYearPercent != null)
        assertNull(stats.fiveYearPercent)
        assertNull(stats.tenYearPercent)
    }

    @Test
    fun theRecordIsTheHighestTradeNotTheHighestCloseAndTodayCanBeatIt() {
        val history = yearly(100.0, 300.0, 250.0, highest = 320.0, highestAt = 12345L)
        val below = LongRunStats.of(history, quote(240.0), now)!!
        assertEquals(320.0, below.allTimeHigh)
        assertEquals(12345L, below.allTimeHighEpochSeconds)
        close(-25.0, below.fromHighPercent, "from the high")

        val record = LongRunStats.of(history, quote(330.0, dayHigh = 335.0), now)!!
        assertEquals(335.0, record.allTimeHigh)
        assertEquals(now, record.allTimeHighEpochSeconds)
    }

    @Test
    fun withoutHighsTheHighestCloseStandsIn() {
        val stats = LongRunStats.of(yearly(100.0, 300.0, 250.0), quote(250.0), now)!!
        assertEquals(300.0, stats.allTimeHigh)
    }

    @Test
    fun underAYearThereIsNoYearlyRate() {
        val times = longArrayOf(now - 100 * 86_400, now - 86_400)
        val history = PriceHistory("NEW", ChartRange.MAX, Series(times, doubleArrayOf(10.0, 12.0)), null, 0)
        assertNull(LongRunStats.of(history, null, now)!!.perYearPercent)
    }

    @Test
    fun holdingsAddUpOnlyWhatHasAQuoteAndGainOnlyWhereTheCostIsKnown() {
        val quotes = mapOf(
            "A" to quote(110.0).copy(symbol = "A", previousClose = 100.0),
            "B" to quote(50.0).copy(symbol = "B", previousClose = 50.0),
        )
        val entries = listOf(
            WatchEntry("A", inSheet = true, addedInApp = false, position = Position(10.0, costPerShare = 80.0)),
            WatchEntry("B", inSheet = false, addedInApp = true, position = Position(2.0)),
            WatchEntry("C", inSheet = false, addedInApp = true, position = Position(5.0, 1.0)),
            WatchEntry("D", inSheet = true, addedInApp = false, position = null),
        )
        val h = Holdings.of(entries, quotes).single()
        assertEquals("USD", h.currency)
        assertEquals(1_200.0, h.value)
        assertEquals(100.0, h.dayChange)
        assertEquals(300.0, h.totalGain)
        assertEquals(800.0, h.costBasis)
        assertEquals(2, h.count)
        assertTrue(Holdings.of(entries.map { it.copy(position = null) }, quotes).isEmpty())
    }

    @Test
    fun holdingsInDifferentCurrenciesAreNeverAddedTogether() {
        val quotes = mapOf(
            "AAPL" to quote(200.0).copy(symbol = "AAPL", currency = "USD"),
            "7203.T" to quote(3_000.0).copy(symbol = "7203.T", currency = "JPY"),
            "VTI" to quote(300.0).copy(symbol = "VTI", currency = null),
        )
        val entries = listOf("7203.T" to 100.0, "AAPL" to 2.0, "VTI" to 1.0).map { (s, n) -> WatchEntry(s, false, true, Position(n)) }
        val totals = Holdings.of(entries, quotes)
        assertEquals(listOf("USD", "JPY"), totals.map { it.currency }, "dollars first; a quote without a currency counts as dollars")
        assertEquals(700.0, totals[0].value)
        assertEquals(300_000.0, totals[1].value)
    }

    @Test
    fun aRecordFromTheHistoryIsNotTodaysButOneSetTodayIs() {
        val history = yearly(100.0, 300.0, 250.0, highest = 320.0, highestAt = 12345L)
        assertEquals(false, LongRunStats.of(history, quote(240.0), now)!!.allTimeHighToday)
        assertEquals(true, LongRunStats.of(history, quote(330.0, dayHigh = 335.0), now)!!.allTimeHighToday)
    }

    @Test
    fun anAddedSymbolKeepsItsSavedKindUntilItsQuoteSaysOtherwise() {
        assertEquals(InstrumentKind.INDEX, MarketCatalog.lookup("^FTSE", quote = null, name = "FTSE 100", kind = InstrumentKind.INDEX).kind)
        assertEquals(InstrumentKind.EQUITY, MarketCatalog.lookup("^FTSE").kind, "with nothing saved, a guess from the ticker")
        val fromQuote = quote(8_000.0).copy(symbol = "^FTSE", instrumentType = "INDEX", currency = "GBP")
        assertEquals(InstrumentKind.INDEX, MarketCatalog.lookup("^FTSE", fromQuote, kind = InstrumentKind.EQUITY).kind)
        assertEquals("GBP", MarketCatalog.lookup("^FTSE", fromQuote).currency)
    }
}
