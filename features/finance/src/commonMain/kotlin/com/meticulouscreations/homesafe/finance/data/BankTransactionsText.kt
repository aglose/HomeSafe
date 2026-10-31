package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.assistant.MAX_TOOL_RESULT_CHARS
import com.meticulouscreations.homesafe.finance.domain.BankTransaction
import com.meticulouscreations.homesafe.finance.domain.BankTransactionQuery
import com.meticulouscreations.homesafe.finance.domain.BankTransactions
import com.meticulouscreations.homesafe.finance.domain.LinkedAccount
import com.meticulouscreations.homesafe.finance.domain.MoneyTotal

/** Every card's and bank account's transactions as the assistant reads them: see `BudgetAssistantText.kt`, whose thinking this follows. */
internal fun bankTransactionsText(query: BankTransactionQuery, answer: BankTransactions, room: Int = MAX_TOOL_RESULT_CHARS): String {
    val head = buildString {
        val filters = listOfNotNull(
            query.search?.let { "mentioning \"$it\"" },
            query.category?.let { "in category \"$it\"" },
            query.account?.let { "on account \"$it\"" },
            query.direction?.let { "with money going ${it.wire}" },
        )
        append("Transactions from ${answer.start} to ${answer.end}")
        if (filters.isNotEmpty()) append(", ").append(filters.joinToString(", "))
        appendLine(": ${answer.count}${if (answer.totals.isEmpty()) "" else ", " + totalsText(answer.totals)}.")
        appendLine(
            "A positive amount is money going out, a negative one money coming in. These totals are of everything that moved: a card being paid off " +
                "and money moved between the household's own accounts are on both sides, so they aren't what was spent or earned as they stand.",
        )
        appendLine("Accounts:")
        answer.accounts.forEach { appendLine("- ${accountLine(it)}") }
        appendLine()
    }
    // However many accounts there are, what the transactions come to and what was left out of them still gets said.
    val summary = if (head.length <= room / 2) head else head.take(room / 2) + "\n…(the rest of this list of accounts is too long to show)\n\n"
    return summary + listed(answer, room - summary.length)
}

private fun totalsText(totals: List<MoneyTotal>): String = totals.joinToString("; ") { total ->
    "${total.currency?.let { "$it " } ?: if (totals.size > 1) "no currency given: " else ""}${money(total.moneyOut)} going out and ${money(total.moneyIn)} coming in"
}

/** The transactions, as many of the newest as [room] has space for, saying what any left out come to. */
private fun listed(answer: BankTransactions, room: Int): String {
    if (answer.count == 0) return "Transactions: none."
    // Amounts say their currency once there is more than one, or it isn't dollars.
    val sayCurrency = answer.totals.size > 1 || answer.totals.any { it.currency != "USD" }
    val lines = ArrayList<String>(answer.transactions.size)
    val shown = ArrayList<BankTransaction>(answer.transactions.size)
    var left = room - HEADER_ROOM
    for (transaction in answer.transactions) {
        val line = transactionLine(transaction, sayCurrency)
        left -= line.length + 1
        if (left < 0) break
        lines += line
        shown += transaction
    }
    return buildString {
        if (shown.size < answer.count) {
            val omitted = answer.totals.map { total ->
                val same = shown.filter { it.currency == total.currency }
                MoneyTotal(total.currency, total.moneyOut - same.filter { it.amount > 0 }.sumOf { it.amount }, total.moneyIn + same.filter { it.amount < 0 }.sumOf { it.amount })
            }
            val before = shown.lastOrNull()?.let { " from ${answer.start} to ${it.date}" }.orEmpty()
            appendLine(
                "Too many to list: only the newest ${shown.size} of ${answer.count} are below. Left out: ${answer.count - shown.size}$before, " +
                    "${totalsText(omitted)}. Use from and to, search, category, account or direction to see them.",
            )
        }
        appendLine("date | amount | merchant | category | account | anything else")
        lines.forEach { appendLine(it) }
    }.trimEnd()
}

private fun transactionLine(transaction: BankTransaction, sayCurrency: Boolean): String = buildString {
    append(transaction.date).append(" | ").append(money(transaction.amount))
    if (sayCurrency) transaction.currency?.let { append(' ').append(it) }
    append(" | ").append(transaction.name.oneLine().ifEmpty { "-" })
    append(" | ").append(transaction.categoryDetail ?: transaction.category ?: "-").append(" | ").append(transaction.account)
    if (transaction.pending) append(" | pending")
    transaction.description?.let { append(" | bank says: ").append(it.oneLine()) }
    transaction.counterparty?.takeIf { !it.equals(transaction.name.trim(), ignoreCase = true) }?.let { append(" | with: ").append(it.oneLine()) }
    transaction.channel?.let { append(" | ").append(it) }
    listOfNotNull(transaction.city, transaction.region).takeIf { it.isNotEmpty() }?.let { append(" | at ").append(it.joinToString().oneLine()) }
    transaction.website?.let { append(" | ").append(it) }
    if (transaction.confidence == "LOW" || transaction.confidence == "VERY_LOW") append(" | category unsure")
    transaction.note?.let { append(" | note: ").append(it.oneLine()) }
}

private fun accountLine(account: LinkedAccount): String = buildString {
    append(account.key).append(" (").append(listOfNotNull(account.institution.takeIf { it.isNotEmpty() }, account.subtype ?: account.type).joinToString()).append(')')
    account.balance?.let { append(", balance ").append(money(it)) }
    if (account.unlinked) append(", no longer linked")
    if (!account.readsTransactions && !account.unlinked) {
        append(", its transactions aren't read")
    } else {
        if (account.first != null) append(", ${account.count} kept from ${account.first} to ${account.last}") else append(", none kept yet")
        if (account.type == "credit") append(if (account.inBudget) ", in the budget" else ", outside the budget")
    }
    if (account.needsRelink) append(", needs signing in to again so its newest may be missing")
}

private const val HEADER_ROOM = 400
