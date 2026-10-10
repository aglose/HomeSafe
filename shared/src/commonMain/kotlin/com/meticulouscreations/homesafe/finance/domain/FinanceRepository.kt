package com.meticulouscreations.homesafe.finance.domain

import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText

/** Why the budget sheet couldn't be read, each with its own fix on the setup card. */
enum class SheetProblem {
    /** The relay has no sheet id configured (`FINANCE_SHEET_ID`). */
    NOT_CONFIGURED,

    /** The relay can't find a Google service-account key to read with. */
    NO_KEY,

    /** Google says the service account may not read the sheet: it needs sharing with it. */
    NOT_SHARED,

    /** The Google Sheets API isn't switched on for the service account's Cloud project. */
    API_DISABLED,

    NOT_FOUND,

    /** Signed in, but as an account the relay won't show the household's money to (not a Frigate admin). */
    NOT_ALLOWED,

    /** The relay is older than this feature. */
    RELAY_OUTDATED,

    /** No server session (signed out, or not connected). */
    SIGNED_OUT,

    OTHER,
}

/**
 * The sheet couldn't be read, for [problem]. [text] is what to tell the person (the relay's own
 * explanation when it gave one, passed through as written); [technical], the status behind it.
 */
class SheetUnavailableException(
    val problem: SheetProblem,
    text: UiText,
    technical: String? = null,
    /** The account the sheet must be shared with, when the relay knows it. */
    val serviceAccount: String? = null,
    /** Where to switch on the Sheets API, when Google said so. */
    val activationUrl: String? = null,
) : LocalizedException(text, technical)

/**
 * Market quotes and histories, FRED's economic series, and the household's budget sheet. Every
 * call is cached in memory for a while, so moving between the finance tabs never waits twice, and
 * what each last answered is kept on the device for when nothing does: an economic series and the
 * sheet are answered from there when they can't be fetched (each says how old it is), while a
 * quote or a chart that couldn't be fetched stays a failure, with the kept one offered beside it
 * ([savedQuotes], [savedHistory]) so a stale price is never handed over as a live one.
 */
interface FinanceRepository {
    suspend fun quotes(symbols: List<String>, maxAgeMillis: Long = 15_000): Result<List<Quote>>

    /** The quotes last fetched for [symbols], however old, for those there are any for. Reads the device only. */
    suspend fun savedQuotes(symbols: List<String>): List<Quote> = emptyList()

    suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory>

    /** The history last fetched for [symbol] over [range], however old, or null. Reads the device only. */
    suspend fun savedHistory(symbol: String, range: ChartRange): PriceHistory? = null

    /** Tickers whose symbol or name matches [query], for adding to the watchlist. */
    suspend fun searchSymbols(query: String): Result<List<SymbolMatch>>

    /** A FRED series from [startDate] on; cached for hours, since most of them move monthly. */
    suspend fun fredSeries(seriesId: String, startDate: String): Result<Series>

    /** [indicator]'s history with its transform applied (e.g. a price index as year-over-year inflation). */
    suspend fun indicator(indicator: Indicator): Result<IndicatorReading>

    /**
     * The budget sheet. With no server to ask, the sheet as it was last read, if `FinanceAccess`
     * allows it to be shown; [PersonalFinance.fetchedAtEpochSeconds] says when that was.
     */
    suspend fun personalFinance(refresh: Boolean = false): Result<PersonalFinance>
}
