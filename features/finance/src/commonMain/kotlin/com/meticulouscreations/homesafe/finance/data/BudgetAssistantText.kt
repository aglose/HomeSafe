package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.MAX_TOOL_RESULT_CHARS
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetCard
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.Purchase
import com.meticulouscreations.homesafe.finance.domain.RecurringCharge
import kotlin.math.abs
import kotlin.math.roundToLong

// The budget as the assistant reads it. A month's `toString()` spends most of a result's room on a
// purchase's id and flags, which the assistant has no use for: tools only read. So a purchase is
// one short line, the figures that sum up the month come ahead of the purchases, and when the
// purchases still don't fit, the text says which were left out rather than ending partway through.

/** What the budget tool was asked for: a month, or every day [from] one [to] another, and which purchases of it. */
internal data class BudgetAsk(
    val month: String? = null,
    val from: String? = null,
    val to: String? = null,
    val search: String? = null,
    val category: String? = null,
    val card: String? = null,
) {
    private val narrowed: Boolean get() = search != null || category != null || card != null

    /** A whole month, as the Budget page has it, rather than some of its purchases. */
    val wholeMonth: Boolean get() = from == null && !narrowed

    /** What is wrong with it, for Claude to ask again, or null. */
    val problem: String?
        get() = when {
            month != null && !MONTH.matches(month) -> "month is YYYY-MM."
            from != null && !DAY.matches(from) -> "from is YYYY-MM-DD."
            to != null && !DAY.matches(to) -> "to is YYYY-MM-DD."
            month != null && (from != null || to != null) -> "Give month, or from and to, not both."
            to != null && from == null -> "Give from as well as to."
            from != null && to != null && to < from -> "from is after to."
            else -> null
        }

    /** The cards [card] names, by their name, institution or last four digits. */
    private fun named(cards: List<BudgetCard>): Set<String> {
        val asked = card ?: return emptySet()
        return cards.filter { listOfNotNull(it.key, it.name, it.institution, it.mask).any { text -> text.contains(asked, ignoreCase = true) } }.mapTo(HashSet()) { it.key }
    }

    /** The purchases asked for out of [budgets]' months, newest first. */
    fun purchases(budgets: List<Budget>, cards: List<BudgetCard>): List<Purchase> {
        val keys = named(cards)
        return budgets.flatMap { it.purchases }.filter { purchase ->
            (from == null || purchase.date >= from) &&
                (to == null || purchase.date <= to) &&
                (search == null || purchase.mentions(search)) &&
                (category == null || purchase.category?.contains(category.trim().replace(' ', '_'), ignoreCase = true) == true) &&
                (card == null || purchase.cardKey in keys || purchase.cardKey.contains(card, ignoreCase = true))
        }.sortedByDescending { it.date }
    }

    private fun Purchase.mentions(text: String): Boolean = name.contains(text, ignoreCase = true) ||
        note?.contains(text, ignoreCase = true) == true ||
        amazon?.items?.any { it.title.contains(text, ignoreCase = true) } == true

    /** What was asked for, in words. */
    fun scope(latest: Budget): String = buildString {
        append(
            when {
                from != null -> "Purchases from $from to ${to ?: "today"}"
                else -> "Purchases in ${month ?: latest.month} only (give from and to to look at other months)"
            },
        )
        val filters = listOfNotNull(search?.let { "mentioning \"$it\"" }, category?.let { "in category \"$it\"" }, card?.let { "on card \"$it\"" })
        if (filters.isNotEmpty()) append(", ").append(filters.joinToString(", "))
        append('.')
    }

    companion object {
        private val MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")
        private val DAY = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")
    }
}

/** The months from [first] to [last] (`YYYY-MM`), the newest first. */
internal fun monthsBetween(first: String, last: String): List<String> {
    fun index(month: String) = month.take(4).toInt() * 12 + month.drop(5).toInt() - 1
    return (index(last) downTo index(first)).map { "${it / 12}-${(it % 12 + 1).toString().padStart(2, '0')}" }
}

/**
 * What the budget tool answers: [budgets] are the months that were read and [latest] the newest
 * known, for the cards; [unread] are the months that couldn't be. Never longer than [room], which
 * is what keeps the agent's cap on a tool's result from cutting it off with nothing said about what went.
 */
internal fun budgetText(ask: BudgetAsk, latest: Budget, budgets: List<Budget>, unread: List<String> = emptyList(), room: Int = MAX_TOOL_RESULT_CHARS): String {
    val head = buildString {
        if (ask.wholeMonth) {
            appendMonth(latest)
        } else {
            appendLine(ask.scope(latest))
            appendCoverage(latest)
            if (ask.from != null && ask.search == null && ask.category == null && ask.card == null) {
                appendLine("Each month's spending, whole months:")
                budgets.forEach { appendLine("- ${it.month}: ${money(it.spent)} (${it.purchases.size} purchases)") }
            }
        }
        if (unread.isNotEmpty()) appendLine("Couldn't read ${unread.joinToString()}: nothing from those months is here or in any total.")
        appendLine()
    }
    return head + purchasesText(ask.purchases(budgets, latest.cards), room - head.length)
}

/** The purchases, as many of the newest as [room] has space for, saying what any left out come to. */
private fun purchasesText(purchases: List<Purchase>, room: Int): String {
    if (purchases.isEmpty()) return "Purchases: none."
    val lines = ArrayList<String>(purchases.size)
    // Room for what is said about them, which can't be known until how many fit is.
    var left = room - PURCHASES_HEADER_ROOM
    for (purchase in purchases) {
        val line = purchaseLine(purchase)
        left -= line.length + 1
        if (left < 0) break
        lines += line
    }
    return buildString {
        appendLine("Purchases: ${purchases.size} coming to ${money(purchases.sumOf { it.amount })}, the newest first; a negative amount is a refund.")
        if (lines.size < purchases.size) {
            val omitted = purchases.drop(lines.size)
            appendLine(
                "Too many to list: only the newest ${lines.size} are below. Left out: ${omitted.size} coming to ${money(omitted.sumOf { it.amount })}, " +
                    "from ${omitted.last().date} to ${omitted.first().date}. Use from and to, search, category or card to see them.",
            )
        }
        appendLine("date | amount | merchant | category | card | whose | budget line | anything else")
        lines.forEach { appendLine(it) }
    }.trimEnd()
}

private fun purchaseLine(purchase: Purchase): String = buildString {
    append(purchase.date).append(" | ").append(money(purchase.amount)).append(" | ").append(purchase.name.oneLine())
    append(" | ").append(purchase.category ?: "-").append(" | ").append(purchase.cardKey)
    append(" | ").append(if (purchase.shares.isEmpty()) purchase.bucket.label() else purchase.shares.joinToString { "${it.bucket.label()} ${money(it.amount)}" })
    append(" | ").append(purchase.line ?: "-")
    if (purchase.pending) append(" | pending")
    purchase.note?.let { append(" | note: ").append(it.oneLine()) }
    val order = purchase.amazon
    when {
        order != null -> append(" | Amazon order of ").append(order.ordered).append(": ").append(
            order.items.joinToString("; ") { item ->
                buildString {
                    append(item.title.oneLine())
                    if (item.quantity != 1) append(" x").append(item.quantity)
                    item.price?.let { append(" at ").append(money(it)) }
                    item.need?.let { append(" (").append(it.wire).append(')') }
                }
            },
        )

        purchase.fromAmazon -> append(" | Amazon order not found")
    }
}

/** Everything about a whole month but its purchases. */
private fun StringBuilder.appendMonth(budget: Budget) {
    appendLine("Budget for ${budget.month}: ${if (budget.isCurrentMonth) "under way, day ${budget.day} of ${budget.daysInMonth}" else "over, ${budget.daysInMonth} days"}.")
    budget.syncedAtEpochSeconds?.let { appendLine("Cards last read at $it (Unix seconds)${if (budget.syncing) ", and being read again now" else ""}.") }
    appendCoverage(budget)

    appendLine("Spent ${money(budget.spent)}, of which ${money(budget.pending)} is pending.")
    budget.config.limits.total?.let { appendLine("The month's limit: ${money(it)}.") }
    budget.savingsLine?.let { appendLine("Spending past ${money(it)} comes out of savings: what take-home leaves after the bills not on a card.") }
    budget.config.sheet.takeHome?.let { appendLine("Take-home a month: ${money(it)}.") }
    budget.config.sheet.billsOffCard?.let { appendLine("Bills not on a card a month: ${money(it)}.") }
    if (budget.config.people.isNotEmpty()) appendLine("People: ${budget.config.people.joinToString()}.")

    section("Cards", budget.cards) { card ->
        val holders = card.holders.joinToString { "${it.mark} is ${it.person ?: "not said"} (${it.count})" }
        "${card.key}: ${card.institution} ${card.name}${card.mask?.let { " ending $it" }.orEmpty()}, ${card.role.label()}" +
            card.balance?.let { ", owes ${money(it)}" }.orEmpty() + if (holders.isEmpty()) "" else ", marks: $holders"
    }
    section("Whose", budget.buckets) { "${it.id.label()}: ${money(it.spent)}${it.limit?.let { limit -> " of ${money(limit)}" }.orEmpty()} (${it.count})" }
    section("Budget lines paid by card", budget.lines) { line ->
        "${line.name}: ${money(line.spent)}${line.planned?.let { " of ${money(it)} planned" }.orEmpty()}${line.usual?.let { ", usually ${money(it)}" }.orEmpty()} (${line.count})"
    }
    if (budget.outsideLines.count > 0) appendLine("In none of those lines: ${money(budget.outsideLines.spent)} (${budget.outsideLines.count}).")
    section("Bills in the sheet, a month", budget.config.sheet.bills.entries.toList()) { "${it.key}: ${money(it.value)}" }
    section("Plaid's categories", budget.categories) { "${it.name}: ${money(it.spent)} (${it.count})" }
    section("The biggest merchants, not all of them", budget.merchants) { "${it.name.oneLine()}: ${money(it.spent)} (${it.count})" }
    if (budget.daily.isNotEmpty()) appendLine("Spent each day from the 1st: ${budget.daily.joinToString { money(it) }}.")
    section("The months before", budget.history) { "${it.month}: ${money(it.spent)} over ${it.days} days" }
    budget.amazon?.let { amazon ->
        appendLine(
            "Amazon: ${amazon.charges} charges, ${money(amazon.essential)} essential, ${money(amazon.discretionary)} discretionary, " +
                "${money(amazon.unsorted)} not sorted, ${money(amazon.unmatched)} with no order found.",
        )
        if (amazon.failedMailboxes.isNotEmpty()) appendLine("Amazon's emails couldn't be read from: ${amazon.failedMailboxes.joinToString()}.")
    }
    budget.recurring?.let { recurring ->
        appendLine("Charged again and again, ${money(recurring.monthly)} a month for those still running:")
        recurring.running.forEach { appendLine("- ${it.recurringLine()}") }
        section("Stopped", recurring.stopped) { it.recurringLine() }
    }
    section("Merchants always put with someone", budget.config.rules) { "${it.merchantKey}: ${it.bucket.label()}" }
    section("Merchants always put in a line", budget.config.lineRules) { "${it.merchantKey}: ${it.line ?: "no line"}" }
}

/** What the purchases here leave out, so that nothing missing from them is taken to be nothing spent. */
private fun StringBuilder.appendCoverage(budget: Budget) {
    if (!budget.configured) appendLine("No bank connection is set up on the server, so there are no purchases to read.")
    if (budget.sandbox) appendLine("This is Plaid's sandbox: test institutions with made-up purchases.")
    if (!budget.ready) appendLine("Plaid is still fetching a card's first purchases, so this is incomplete.")
    appendLine("This is the purchases and refunds on the cards in the budget. It isn't payments to the cards, or the money in bank accounts beyond what checking pays again and again.")
    val leftOut = budget.cards.mapNotNull { card ->
        when {
            !card.readsPurchases -> "${card.key} (linked as a loan, so its purchases were never read)"
            card.role == CardRole.Unset -> "${card.key} (no role given yet)"
            card.role == CardRole.Ignore -> "${card.key} (set to be no part of the budget)"
            else -> null
        }
    }
    if (leftOut.isNotEmpty()) appendLine("Linked cards whose purchases aren't here: ${leftOut.joinToString()}.")
    val stale = budget.cards.filter { it.needsRelink }
    if (stale.isNotEmpty()) appendLine("Need signing in to again, so their newest purchases may be missing: ${stale.joinToString { it.key }}.")
}

private fun <T> StringBuilder.section(title: String, items: List<T>, describe: (T) -> String) {
    if (items.isEmpty()) return
    appendLine("$title:")
    items.forEach { appendLine("- ${describe(it)}") }
}

private fun RecurringCharge.recurringLine(): String = buildString {
    append(name.oneLine()).append(": ").append(rhythm.name.lowercase()).append(if (bill) " bill, last " else ", ").append(money(amount))
    was?.let { append(", was ").append(money(it)) }
    append(", ").append(money(monthly)).append(" a month, ").append(count).append(" times from ").append(first).append(" to ").append(last)
    next?.let { append(", next ").append(it) }
    append(if (fromChecking) ", from checking" else ", on $cardKey")
    line?.let { append(", in ").append(it) }
}

private fun BucketId.label(): String = when (this) {
    is BucketId.Person -> name
    BucketId.Family -> "family"
    BucketId.Unassigned -> "nobody yet"
}

private fun CardRole.label(): String = when (this) {
    is CardRole.Person -> "$name's own"
    CardRole.Family -> "the family's"
    CardRole.Split -> "carried by two, each purchase put to one"
    CardRole.Ignore -> "no part of the budget"
    CardRole.Unset -> "no role given yet"
}

private fun String.oneLine(): String = trim().replace(WHITESPACE, " ")

private fun money(amount: Double): String {
    val cents = (abs(amount) * 100).roundToLong()
    val sign = if (amount < 0 && cents != 0L) "-" else ""
    return "$sign${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

private val WHITESPACE = Regex("""\s+""")

private const val PURCHASES_HEADER_ROOM = 500
