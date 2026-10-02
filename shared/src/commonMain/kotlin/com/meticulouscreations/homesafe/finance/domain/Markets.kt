package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_range_1d
import homesafe.shared.generated.resources.fin_data_range_1m
import homesafe.shared.generated.resources.fin_data_range_1w
import homesafe.shared.generated.resources.fin_data_range_1y
import homesafe.shared.generated.resources.fin_data_range_3m
import homesafe.shared.generated.resources.fin_data_range_5y
import homesafe.shared.generated.resources.fin_data_range_max
import homesafe.shared.generated.resources.fin_data_range_ytd
import homesafe.shared.generated.resources.fin_data_symbol_bitcoin
import homesafe.shared.generated.resources.fin_data_symbol_bitcoin_about
import homesafe.shared.generated.resources.fin_data_symbol_dollar_about
import homesafe.shared.generated.resources.fin_data_symbol_dollar_name
import homesafe.shared.generated.resources.fin_data_symbol_dollar_short
import homesafe.shared.generated.resources.fin_data_symbol_dow_about
import homesafe.shared.generated.resources.fin_data_symbol_dow_name
import homesafe.shared.generated.resources.fin_data_symbol_dow_short
import homesafe.shared.generated.resources.fin_data_symbol_gold_about
import homesafe.shared.generated.resources.fin_data_symbol_gold_name
import homesafe.shared.generated.resources.fin_data_symbol_gold_short
import homesafe.shared.generated.resources.fin_data_symbol_nasdaq_about
import homesafe.shared.generated.resources.fin_data_symbol_nasdaq_name
import homesafe.shared.generated.resources.fin_data_symbol_nasdaq_short
import homesafe.shared.generated.resources.fin_data_symbol_oil_about
import homesafe.shared.generated.resources.fin_data_symbol_oil_name
import homesafe.shared.generated.resources.fin_data_symbol_oil_short
import homesafe.shared.generated.resources.fin_data_symbol_russell_about
import homesafe.shared.generated.resources.fin_data_symbol_russell_name
import homesafe.shared.generated.resources.fin_data_symbol_russell_short
import homesafe.shared.generated.resources.fin_data_symbol_sp500
import homesafe.shared.generated.resources.fin_data_symbol_sp500_about
import homesafe.shared.generated.resources.fin_data_symbol_ten_year_about
import homesafe.shared.generated.resources.fin_data_symbol_ten_year_name
import homesafe.shared.generated.resources.fin_data_symbol_ten_year_short
import homesafe.shared.generated.resources.fin_data_symbol_vix_about
import homesafe.shared.generated.resources.fin_data_symbol_vix_name
import homesafe.shared.generated.resources.fin_data_symbol_vix_short
import org.jetbrains.compose.resources.StringResource

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

/**
 * A market symbol the app follows, as Yahoo Finance names it, with how to show it. The names are
 * the app's own words for the ones it knows, and the ticker itself for the rest.
 */
@Immutable
data class MarketSymbol(
    val symbol: String,
    val name: UiText,
    val shortName: UiText,
    val kind: InstrumentKind,
    /** One or two sentences on what it is and why it matters, for its detail page; null for a ticker the app only knows by name. */
    val about: StringResource? = null,
    /** What it's priced in, as Yahoo writes it ("USD", "JPY", "GBp"); null for the catalog's own, all in dollars or points. */
    val currency: String? = null,
) {
    constructor(symbol: String, name: StringResource, shortName: StringResource, kind: InstrumentKind, about: StringResource) :
        this(symbol, UiText.of(name), UiText.of(shortName), kind, about)
}

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
enum class ChartRange(val label: StringResource, val yahooRange: String, val yahooInterval: String) {
    DAY(Res.string.fin_data_range_1d, "1d", "5m"),
    WEEK(Res.string.fin_data_range_1w, "5d", "15m"),
    MONTH(Res.string.fin_data_range_1m, "1mo", "1h"),
    THREE_MONTHS(Res.string.fin_data_range_3m, "3mo", "1d"),
    YEAR_TO_DATE(Res.string.fin_data_range_ytd, "ytd", "1d"),
    YEAR(Res.string.fin_data_range_1y, "1y", "1d"),
    FIVE_YEARS(Res.string.fin_data_range_5y, "5y", "1wk"),
    MAX(Res.string.fin_data_range_max, "max", "1wk"),
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
    val SP500 = MarketSymbol("^GSPC", Res.string.fin_data_symbol_sp500, Res.string.fin_data_symbol_sp500, InstrumentKind.INDEX, Res.string.fin_data_symbol_sp500_about)
    val DOW = MarketSymbol("^DJI", Res.string.fin_data_symbol_dow_name, Res.string.fin_data_symbol_dow_short, InstrumentKind.INDEX, Res.string.fin_data_symbol_dow_about)
    val NASDAQ = MarketSymbol("^IXIC", Res.string.fin_data_symbol_nasdaq_name, Res.string.fin_data_symbol_nasdaq_short, InstrumentKind.INDEX, Res.string.fin_data_symbol_nasdaq_about)
    val RUSSELL = MarketSymbol("^RUT", Res.string.fin_data_symbol_russell_name, Res.string.fin_data_symbol_russell_short, InstrumentKind.INDEX, Res.string.fin_data_symbol_russell_about)
    val VIX = MarketSymbol("^VIX", Res.string.fin_data_symbol_vix_name, Res.string.fin_data_symbol_vix_short, InstrumentKind.INDEX, Res.string.fin_data_symbol_vix_about)
    val TEN_YEAR = MarketSymbol("^TNX", Res.string.fin_data_symbol_ten_year_name, Res.string.fin_data_symbol_ten_year_short, InstrumentKind.YIELD, Res.string.fin_data_symbol_ten_year_about)
    val GOLD = MarketSymbol("GC=F", Res.string.fin_data_symbol_gold_name, Res.string.fin_data_symbol_gold_short, InstrumentKind.COMMODITY, Res.string.fin_data_symbol_gold_about)
    val OIL = MarketSymbol("CL=F", Res.string.fin_data_symbol_oil_name, Res.string.fin_data_symbol_oil_short, InstrumentKind.COMMODITY, Res.string.fin_data_symbol_oil_about)
    val BITCOIN = MarketSymbol("BTC-USD", Res.string.fin_data_symbol_bitcoin, Res.string.fin_data_symbol_bitcoin, InstrumentKind.CRYPTO, Res.string.fin_data_symbol_bitcoin_about)
    val DOLLAR = MarketSymbol("DX-Y.NYB", Res.string.fin_data_symbol_dollar_name, Res.string.fin_data_symbol_dollar_short, InstrumentKind.CURRENCY, Res.string.fin_data_symbol_dollar_about)

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
        name = (quote?.name ?: name ?: symbol).asUiText(),
        shortName = symbol.removeSuffix("-USD").asUiText(),
        kind = InstrumentKind.fromYahoo(quote?.instrumentType)
            ?: kind
            ?: if (symbol.endsWith("-USD")) InstrumentKind.CRYPTO else InstrumentKind.EQUITY,
        currency = quote?.currency,
    )
}
