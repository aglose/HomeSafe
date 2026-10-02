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
 * call is cached in memory for a while, so moving between the finance tabs never waits twice.
 */
interface FinanceRepository {
    suspend fun quotes(symbols: List<String>, maxAgeMillis: Long = 15_000): Result<List<Quote>>

    suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory>

    /** A FRED series from [startDate] on; cached for hours, since most of them move monthly. */
    suspend fun fredSeries(seriesId: String, startDate: String): Result<Series>

    /** [indicator]'s history with its transform applied (e.g. a price index as year-over-year inflation). */
    suspend fun indicator(indicator: Indicator): Result<IndicatorReading>

    suspend fun personalFinance(refresh: Boolean = false): Result<PersonalFinance>
}
