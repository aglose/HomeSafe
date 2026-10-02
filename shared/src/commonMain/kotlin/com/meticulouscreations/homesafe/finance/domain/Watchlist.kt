package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow

/** Shares held in a symbol, and what they cost on average when that's been given. */
@Immutable
data class Position(val shares: Double, val costPerShare: Double? = null) {
    fun value(price: Double): Double = shares * price

    /** What the holding gained or lost today, from the last close to [quote]'s price. */
    fun dayChange(quote: Quote): Double = shares * quote.change

    /** What it has gained or lost since it was bought, when its cost is known. */
    fun totalGain(price: Double): Double? = costPerShare?.let { shares * (price - it) }

    fun totalGainPercent(price: Double): Double? = costPerShare?.takeIf { it > 0 }?.let { (price - it) / it * 100 }
}

/**
 * A symbol followed from the app rather than the sheet, kept on this device: something added to
 * the watchlist, or a position entered against one of the sheet's own tickers. [name] and [kind]
 * are what the search said when it was added, for showing it before its first quote lands.
 */
@Immutable
data class WatchedSymbol(
    val symbol: String,
    val name: String,
    val kind: InstrumentKind,
    val addedAtEpochSeconds: Long,
    val position: Position? = null,
)

/** A search hit: a ticker Yahoo knows, with what it is ("ETF") and where it trades ("NASDAQ"). */
@Immutable
data class SymbolMatch(val symbol: String, val name: String, val kind: InstrumentKind, val typeLabel: String, val exchange: String?)

/** One watchlist row: the ticker, whether the budget sheet names it, and any shares held. */
@Immutable
data class WatchEntry(val symbol: String, val inSheet: Boolean, val addedInApp: Boolean, val position: Position?) {
    /** One of [MarketCatalog.defaultWatchlist], shown because nothing else is followed yet. */
    val isSuggestion: Boolean get() = !inSheet && !addedInApp
}

/** The positions held across the watchlist, valued at the latest quotes. Only symbols with a quote count. */
@Immutable
data class Holdings(
    val value: Double,
    val dayChange: Double,
    /** Gain since purchase over the positions whose cost is known, and what those cost. */
    val totalGain: Double?,
    val costBasis: Double?,
    val count: Int,
) {
    val dayChangePercent: Double? get() = (value - dayChange).takeIf { it > 0 }?.let { dayChange / it * 100 }
    val totalGainPercent: Double? get() = if (totalGain != null && costBasis != null && costBasis > 0) totalGain / costBasis * 100 else null

    companion object {
        fun of(entries: List<WatchEntry>, quotes: Map<String, Quote>): Holdings? {
            var value = 0.0
            var day = 0.0
            var gain = 0.0
            var cost = 0.0
            var withCost = 0
            var count = 0
            entries.forEach { e ->
                val p = e.position ?: return@forEach
                val q = quotes[e.symbol] ?: return@forEach
                count++
                value += p.value(q.price)
                day += p.dayChange(q)
                p.costPerShare?.let { c ->
                    withCost++
                    cost += p.shares * c
                    gain += p.shares * (q.price - c)
                }
            }
            if (count == 0) return null
            return Holdings(value, day, gain.takeIf { withCost > 0 }, cost.takeIf { withCost > 0 }, count)
        }
    }
}

/** The symbols the app follows on its own (see [WatchedSymbol]), stored on this device. */
interface WatchlistRepository {
    /** Oldest first. */
    fun observe(): Flow<List<WatchedSymbol>>

    /** Adds [symbol], or replaces what's kept for it. */
    suspend fun save(symbol: WatchedSymbol)

    suspend fun remove(symbol: String)
}
