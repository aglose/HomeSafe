package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/** What kind of thing a symbol is, which decides how its price is written (points, dollars, a yield). */
enum class InstrumentKind {
    INDEX,
    EQUITY,
    CRYPTO,
    COMMODITY,
    YIELD,
    CURRENCY,
    ;

    companion object {
        /**
         * Yahoo's `quoteType`/`instrumentType` ("EQUITY", "ETF", "MUTUALFUND", "CRYPTOCURRENCY",
         * "FUTURE", …) as a kind; funds are priced in dollars a share, like a stock. Null when
         * Yahoo didn't say or said something new.
         */
        fun fromYahoo(type: String?): InstrumentKind? = when (type?.uppercase()) {
            "EQUITY", "ETF", "MUTUALFUND", "MONEYMARKET" -> EQUITY
            "CRYPTOCURRENCY" -> CRYPTO
            "INDEX" -> INDEX
            "FUTURE" -> COMMODITY
            "CURRENCY" -> CURRENCY
            else -> null
        }
    }
}

/** A market symbol the app follows, as Yahoo Finance names it, with how to show it. */
@Immutable
data class MarketSymbol(
    val symbol: String,
    val name: String,
    val shortName: String,
    val kind: InstrumentKind,
    /** One or two sentences on what it is and why it matters, for its detail page. */
    val about: String = "",
    /** What it's priced in, as Yahoo writes it ("USD", "JPY", "GBp"); null for the catalog's own, all in dollars or points. */
    val currency: String? = null,
)

/**
 * A quote as of [marketTimeEpochSeconds], with today's intraday path in [intraday] (the spark
 * call's 5-minute closes) for the sparkline. [previousClose] is the last session's close, which
 * is what the day's change is measured from and where the 1D chart draws its dotted baseline.
 */
@Immutable
data class Quote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val dayHigh: Double?,
    val dayLow: Double?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    val volume: Double?,
    val marketTimeEpochSeconds: Long,
    /** Seconds east of UTC for the exchange's clock, from Yahoo; how its times are written. */
    val gmtOffsetSeconds: Int,
    val sessionStartEpochSeconds: Long?,
    val sessionEndEpochSeconds: Long?,
    val intraday: Series,
    /** The company's or fund's name ("Peloton Interactive, Inc."), when Yahoo gave one. */
    val name: String? = null,
    /** Yahoo's word for what it is ("EQUITY", "ETF", "MUTUALFUND", …); see [InstrumentKind.fromYahoo]. */
    val instrumentType: String? = null,
    /** The currency it trades in ("USD", "JPY", "GBp"), when Yahoo said. */
    val currency: String? = null,
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() = if (previousClose == 0.0) 0.0 else change / previousClose * 100.0

    /**
     * Whether [nowEpochSeconds] is inside the regular session. A quote that came without its
     * session times isn't known to be open, so it isn't (callers treat crypto, which trades round
     * the clock, as open on their own).
     */
    fun isSessionOpen(nowEpochSeconds: Long): Boolean {
        val start = sessionStartEpochSeconds ?: return false
        val end = sessionEndEpochSeconds ?: return false
        return nowEpochSeconds in start until end
    }
}

/**
 * The ranges a price chart offers, Robinhood's row: what to ask Yahoo for each. Yahoo quietly
 * thins `range=max` to monthly or quarterly bars (and the S&P's to 1985 on), so [MAX] is asked for
 * by dates instead (see `YahooFinanceApi.history`), which honours the weekly interval.
 */
enum class ChartRange(val label: String, val yahooRange: String, val yahooInterval: String) {
    DAY("1D", "1d", "5m"),
    WEEK("1W", "5d", "15m"),
    MONTH("1M", "1mo", "1h"),
    THREE_MONTHS("3M", "3mo", "1d"),
    YEAR_TO_DATE("YTD", "ytd", "1d"),
    YEAR("1Y", "1y", "1d"),
    FIVE_YEARS("5Y", "5y", "1wk"),
    MAX("ALL", "max", "1wk"),
}

/** A price history for one [range]: the points, and the close before it began (the baseline a 1D chart draws). */
@Immutable
data class PriceHistory(
    val symbol: String,
    val range: ChartRange,
    val series: Series,
    val baseline: Double?,
    val gmtOffsetSeconds: Int,
    /** The highest price traded in the range (the bars' highs, not just their closes), and when. */
    val highest: Double? = null,
    val highestEpochSeconds: Long? = null,
)

/**
 * Everything the Markets tab follows. The watchlist is the sheet's tickers plus any added in the
 * app (see [WatchedSymbol]), or [defaultWatchlist] when there are neither.
 */
object MarketCatalog {
    val SP500 = MarketSymbol(
        "^GSPC",
        "S&P 500",
        "S&P 500",
        InstrumentKind.INDEX,
        "The 500 largest US public companies, weighted by size: the broadest single read on American stocks and the benchmark most retirement money tracks.",
    )
    val DOW = MarketSymbol(
        "^DJI",
        "Dow Jones Industrial Average",
        "Dow",
        InstrumentKind.INDEX,
        "30 blue-chip companies, weighted by share price. The oldest US index, and the number the evening news quotes.",
    )
    val NASDAQ = MarketSymbol(
        "^IXIC",
        "Nasdaq Composite",
        "Nasdaq",
        InstrumentKind.INDEX,
        "Every stock on the Nasdaq exchange, heavy in technology. It moves further than the S&P in both directions.",
    )
    val RUSSELL = MarketSymbol(
        "^RUT",
        "Russell 2000",
        "Russell 2K",
        InstrumentKind.INDEX,
        "2,000 small US companies. They borrow more and sell mostly at home, so this index feels a slowing economy and higher rates first.",
    )
    val VIX = MarketSymbol(
        "^VIX",
        "CBOE Volatility Index",
        "VIX",
        InstrumentKind.INDEX,
        "The market's fear gauge: the volatility options traders expect from the S&P 500 over the next 30 days. Under 20 is calm; over 30 is fear.",
    )
    val TEN_YEAR = MarketSymbol(
        "^TNX",
        "10-Year Treasury Yield",
        "10Y Yield",
        InstrumentKind.YIELD,
        "What the US government pays to borrow for ten years. Mortgages and most long-term borrowing are priced off it.",
    )
    val GOLD = MarketSymbol(
        "GC=F",
        "Gold Futures",
        "Gold",
        InstrumentKind.COMMODITY,
        "The classic safe haven. Gold tends to climb when investors lose faith in currencies, governments or the stock market.",
    )
    val OIL = MarketSymbol(
        "CL=F",
        "Crude Oil (WTI)",
        "Oil",
        InstrumentKind.COMMODITY,
        "West Texas crude. Oil spikes have come before most US recessions since the 1970s.",
    )
    val BITCOIN = MarketSymbol(
        "BTC-USD",
        "Bitcoin",
        "Bitcoin",
        InstrumentKind.CRYPTO,
        "The largest cryptocurrency, trading around the clock: often the first thing to move when risk appetite changes.",
    )
    val DOLLAR = MarketSymbol(
        "DX-Y.NYB",
        "US Dollar Index",
        "Dollar",
        InstrumentKind.CURRENCY,
        "The dollar against six major currencies. A fast-rising dollar tightens credit around the world.",
    )

    val indices = listOf(SP500, DOW, NASDAQ, RUSSELL, VIX)
    val macro = listOf(TEN_YEAR, GOLD, OIL, BITCOIN, DOLLAR)

    /** The watchlist when neither the sheet nor the app names any tickers. */
    val defaultWatchlist = listOf("TSLA", "NVDA", "AAPL", "BTC-USD")

    private val known = (indices + macro).associateBy { it.symbol }

    /**
     * How to show [symbol]: the catalog's own entry, or one made up from the ticker. Its name,
     * kind and currency come from its [quote] (Yahoo sends them with the price), else the [name]
     * and [kind] saved when it was added, else a guess from the ticker.
     */
    fun lookup(symbol: String, quote: Quote? = null, name: String? = null, kind: InstrumentKind? = null): MarketSymbol = known[symbol] ?: MarketSymbol(
        symbol = symbol,
        name = quote?.name ?: name ?: symbol,
        shortName = symbol.removeSuffix("-USD"),
        kind = InstrumentKind.fromYahoo(quote?.instrumentType)
            ?: kind
            ?: if (symbol.endsWith("-USD")) InstrumentKind.CRYPTO else InstrumentKind.EQUITY,
        currency = quote?.currency,
    )
}
