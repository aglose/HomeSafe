package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.current
import com.meticulouscreations.homesafe.assistant.inputOf
import com.meticulouscreations.homesafe.assistant.listing
import com.meticulouscreations.homesafe.assistant.noInput
import com.meticulouscreations.homesafe.assistant.stringInput
import com.meticulouscreations.homesafe.assistant.stringProperty
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.WatchlistRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.assistant_tool_budget
import homesafe.shared.generated.resources.assistant_tool_finance
import homesafe.shared.generated.resources.assistant_tool_markets
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

// What the assistant can read from the finance app. The household's own figures (the sheet, the
// budget, the watchlist and what is held in it) are held back while the finance app is locked (no
// server to vouch, and nobody has unlocked it), exactly as the app holds them back, checked again
// after the read in case it locked meanwhile; the market prices themselves are public and never are.

@Inject
@ContributesIntoSet(AppScope::class)
class FinanceSheetTool(private val finance: FinanceRepository, private val access: FinanceAccess) : AssistantTool {
    override val name = "finance_sheet"
    override val description = "The household's finance sheet: monthly income and expenses line by line, net monthly, accounts " +
        "and their balances, investments, total assets, the emergency fund target, and the rest of what the Finance app shows."
    override val inputSchema = noInput
    override val activity = Res.string.assistant_tool_finance
    override val lock get() = access.locked

    override suspend fun run(input: JsonObject): String {
        if (access.locked.value) return LOCKED
        val sheet = finance.personalFinance().fold({ it.toString() }, { "Couldn't read the sheet: ${it.message}" })
        return if (access.locked.value) LOCKED else sheet
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class BudgetTool(private val budgets: BudgetRepository, private val access: FinanceAccess) : AssistantTool {
    override val name = "budget"
    override val description = "The monthly budget built from the household's synced bank cards: what has been spent and is " +
        "pending, each bucket against its limit, the cards, and the month's purchases with their buckets. Defaults to this month."
    override val inputSchema = inputOf("month" to stringProperty("The month to look at, as YYYY-MM. Leave out for the current month."))
    override val activity = Res.string.assistant_tool_budget
    override val lock get() = access.locked

    override suspend fun run(input: JsonObject): String {
        if (access.locked.value) return LOCKED
        val budget = budgets.budget(input.stringInput("month")).fold({ it.toString() }, { "Couldn't read the budget: ${it.message}" })
        return if (access.locked.value) LOCKED else budget
    }
}

@Inject
@ContributesIntoSet(AppScope::class)
class MarketsTool(private val finance: FinanceRepository, private val watchlist: WatchlistRepository, private val access: FinanceAccess) : AssistantTool {
    override val name = "market_quotes"
    override val description = "Current market prices: price, previous close, the day's change, day and 52-week range, volume and " +
        "market time (Unix seconds). Give ticker symbols, or leave them out for the household's watchlist (with any positions held)."
    override val inputSchema = inputOf("symbols" to stringProperty("Ticker symbols separated by commas, e.g. \"AAPL,VOO\". Leave out for the watchlist."))
    override val activity = Res.string.assistant_tool_markets
    override val lock get() = access.locked

    override suspend fun run(input: JsonObject): String {
        val asked = input.stringInput("symbols")?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
        // What the household watches and holds is theirs; prices for symbols asked for by name are anyone's.
        val watched = if (access.locked.value) emptyList() else watchlist.observe().current().orEmpty()
        if (asked == null && access.locked.value) return LOCKED
        val symbols = asked ?: watched.map { it.symbol }
        if (symbols.isEmpty()) return "The watchlist is empty; ask for symbols by name."
        val quotes = finance.quotes(symbols).getOrElse { return "Couldn't get quotes: ${it.message}" }
        val positions = if (access.locked.value) emptyMap() else watched.associate { it.symbol to it.position }
        if (asked == null && access.locked.value) return LOCKED
        return listing(quotes, limit = 50) { quote -> describe(quote) + (positions[quote.symbol]?.let { ", position $it" } ?: "") }
    }

    // A quote's intraday series is hundreds of points; the figures a person asks about are these.
    private fun describe(quote: Quote): String {
        val change = if (quote.previousClose != 0.0) (quote.price / quote.previousClose - 1) * 100 else 0.0
        return "${quote.symbol}: ${quote.price} (previous close ${quote.previousClose}, ${(change * 100).roundToLong() / 100.0}%), " +
            "day ${quote.dayLow}–${quote.dayHigh}, 52 weeks ${quote.fiftyTwoWeekLow}–${quote.fiftyTwoWeekHigh}, volume ${quote.volume}, at ${quote.marketTimeEpochSeconds}"
    }
}

private const val LOCKED = "The finance app is locked: the home server isn't answering and nobody has unlocked Finance on " +
    "this phone. Ask them to open Finance and unlock it, then ask again."
