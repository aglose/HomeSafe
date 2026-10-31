package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.AssistantTool
import com.meticulouscreations.homesafe.assistant.current
import com.meticulouscreations.homesafe.assistant.inputOf
import com.meticulouscreations.homesafe.assistant.listing
import com.meticulouscreations.homesafe.assistant.noInput
import com.meticulouscreations.homesafe.assistant.stringInput
import com.meticulouscreations.homesafe.assistant.stringProperty
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.Budget
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
    override val description = "The monthly budget built from the household's synced bank cards. With nothing asked, this month: what " +
        "has been spent and is pending, each bucket against its limit, each budget line paid by card (groceries and the like) against " +
        "what the sheet plans for it, the cards, what is charged again and again, and the month's purchases with their buckets and " +
        "lines. month looks at another month. from and to read the purchases between two days, across months. search, category and " +
        "card keep to the purchases that match, with what they come to. Plaid's categories can be wrong (an airline filed as " +
        "GENERAL_MERCHANDISE), so to find a kind of spending, search for its merchants' names over the months in question as well as " +
        "looking at categories. The answer says what it leaves out: bank accounts, cards outside the budget, and any purchases too many to list."
    override val inputSchema = inputOf(
        "month" to stringProperty("The month to look at, as YYYY-MM. Leave out for the current month."),
        "from" to stringProperty("The first day to read purchases from, as YYYY-MM-DD, instead of a month. At most $MAX_MONTHS_READ months back at a time."),
        "to" to stringProperty("The last day to read purchases to, as YYYY-MM-DD, with from. Leave out for today."),
        "search" to stringProperty("Only purchases whose merchant, note or Amazon items mention this, e.g. \"american\"."),
        "category" to stringProperty("Only purchases in this Plaid category, e.g. \"TRAVEL\"."),
        "card" to stringProperty("Only purchases on this card, by its name, institution or last four digits."),
    )
    override val activity = Res.string.assistant_tool_budget
    override val lock get() = access.locked

    override suspend fun run(input: JsonObject): String {
        if (access.locked.value) return LOCKED
        val ask = BudgetAsk(
            month = input.stringInput("month"),
            from = input.stringInput("from"),
            to = input.stringInput("to"),
            search = input.stringInput("search"),
            category = input.stringInput("category"),
            card = input.stringInput("card"),
        )
        ask.problem?.let { return it }
        val budget = if (ask.from == null) {
            budgets.budget(ask.month).fold({ budgetText(ask, it, listOf(it)) }, { "Couldn't read the budget: ${it.message}" })
        } else {
            range(ask, ask.from)
        }
        return if (access.locked.value) LOCKED else budget
    }

    /**
     * Every month [from] touches up to `to` or the month under way, whichever is first, one at a
     * time. The server refusing whoever is asking isn't a month that couldn't be read: it ends the
     * read there, so no answer still on its way puts back what that refusal took off the device.
     */
    private suspend fun range(ask: BudgetAsk, from: String): String {
        val latest = budgets.budget(null).getOrElse { return "Couldn't read the budget: ${it.message}" }
        val last = minOf(ask.to?.take(7) ?: latest.month, latest.month)
        val months = monthsBetween(from.take(7), last)
        if (months.size > MAX_MONTHS_READ) return "That is ${months.size} months; ask for at most $MAX_MONTHS_READ at a time."
        val read = ArrayList<Pair<String, Result<Budget>>>(months.size)
        for (month in months) {
            val budget = if (month == latest.month) Result.success(latest) else budgets.budget(month)
            val problem = (budget.exceptionOrNull() as? BankSyncException)?.problem
            if (problem == BankProblem.SIGNED_OUT || problem == BankProblem.NOT_ALLOWED) return "Couldn't read the budget: ${budget.exceptionOrNull()?.message}"
            read += month to budget
        }
        return budgetText(ask, latest, read.mapNotNull { (_, budget) -> budget.getOrNull() }, unread = read.filter { (_, budget) -> budget.isFailure }.map { it.first })
    }

    private companion object {
        // As far back as the relay asks Plaid to keep (`PLAID_TXN_DAYS` in `relay/relay.py`).
        const val MAX_MONTHS_READ = 24
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
