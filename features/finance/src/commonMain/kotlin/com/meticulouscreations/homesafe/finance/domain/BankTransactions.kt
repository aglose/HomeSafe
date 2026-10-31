package com.meticulouscreations.homesafe.finance.domain

/**
 * Which of the transactions to read: between two days, `YYYY-MM-DD` (by default the last 30), those
 * mentioning [search], in [category], on [account], and going [direction]. Null leaves each open.
 */
data class BankTransactionQuery(
    val start: String? = null,
    val end: String? = null,
    val search: String? = null,
    val category: String? = null,
    val account: String? = null,
    val direction: MoneyDirection? = null,
)

/** [wire] is the relay's name for it (`finance_transactions` in `relay/relay.py`). */
enum class MoneyDirection(val wire: String) {
    IN("in"),
    OUT("out"),
}

/** An account linked through Plaid, what becomes of its transactions, and how far back those kept of it go. */
data class LinkedAccount(
    val key: String,
    val institution: String,
    val type: String?,
    val subtype: String?,
    val balance: Double?,
    /** False for what isn't a card or a bank account, a loan say, and for an institution not linked for its transactions. */
    val readsTransactions: Boolean,
    val needsRelink: Boolean,
    val inBudget: Boolean,
    val count: Int,
    val first: String?,
    val last: String?,
    /** No longer linked: all that's left of it is the transactions kept. */
    val unlinked: Boolean = false,
)

/** One transaction on a card or a bank account, whatever the budget makes of it. */
data class BankTransaction(
    /** The day of it, `YYYY-MM-DD`. */
    val date: String,
    /** As Plaid has it: positive for money going out. */
    val amount: Double,
    /** Its ISO code, or null when the bank didn't give one. */
    val currency: String?,
    /** The merchant as the bank or Plaid names it: data, shown as written. */
    val name: String,
    /** What the bank itself wrote, when that says more than [name]: data, shown as written. */
    val description: String?,
    val category: String?,
    val categoryDetail: String?,
    /** How sure Plaid is of the category (`LOW`). */
    val confidence: String?,
    val pending: Boolean,
    /** `spend`, `refund` or `payment`, which money moved on or between the household's own accounts can be too. */
    val kind: String?,
    val account: String,
    /** `checking`, `savings`, `credit card`; null once the account has been unlinked. */
    val accountType: String?,
    val channel: String?,
    val website: String?,
    val city: String?,
    val region: String?,
    val counterparty: String?,
    val note: String?,
)

/** What matches came to in one currency; a null [currency] is those whose bank didn't give one. */
data class MoneyTotal(val currency: String?, val moneyOut: Double, val moneyIn: Double)

/**
 * What matched a [BankTransactionQuery]. [count] and [totals] are of every match; [transactions]
 * are as many of the newest as the relay lists.
 */
data class BankTransactions(
    val start: String,
    val end: String,
    val count: Int,
    val totals: List<MoneyTotal>,
    val accounts: List<LinkedAccount>,
    val transactions: List<BankTransaction>,
)

/** Every card's and bank account's transactions as the relay kept them, read for the assistant. Never kept on the device. */
interface BankTransactionsRepository {
    suspend fun transactions(query: BankTransactionQuery): Result<BankTransactions>
}
