package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/**
 * Whose a purchase is: one person's, the whole family's, or nobody's yet. [wire] is the relay's
 * name for it (`budget_buckets` in `relay/relay.py`); a purchase nobody has been given has none.
 */
@Immutable
sealed interface BucketId {
    val wire: String?

    @Immutable
    data class Person(val name: String) : BucketId {
        override val wire: String get() = PERSON_PREFIX + name
    }

    @Immutable
    data object Family : BucketId {
        override val wire: String get() = "family"
    }

    @Immutable
    data object Unassigned : BucketId {
        override val wire: String? get() = null
    }

    companion object {
        internal const val PERSON_PREFIX = "person:"

        fun ofWire(wire: String?): BucketId = when {
            wire == "family" -> Family
            wire != null && wire.startsWith(PERSON_PREFIX) -> Person(wire.removePrefix(PERSON_PREFIX))
            else -> Unassigned
        }
    }
}

/**
 * What a card is to the budget: carried by two people so each purchase has to be put to one of
 * them ([Split]), the family's ([Family]), one person's own ([Person]), no part of the budget
 * ([Ignore]), or not said yet ([Unset]), in which case none of its purchases count.
 */
@Immutable
sealed interface CardRole {
    val wire: String?

    @Immutable
    data object Unset : CardRole {
        override val wire: String? get() = null
    }

    @Immutable
    data object Split : CardRole {
        override val wire: String get() = "split"
    }

    @Immutable
    data object Family : CardRole {
        override val wire: String get() = "family"
    }

    @Immutable
    data object Ignore : CardRole {
        override val wire: String get() = "ignore"
    }

    @Immutable
    data class Person(val name: String) : CardRole {
        override val wire: String get() = BucketId.PERSON_PREFIX + name
    }

    companion object {
        fun ofWire(wire: String?): CardRole = when {
            wire == "split" -> Split
            wire == "family" -> Family
            wire == "ignore" -> Ignore
            wire != null && wire.startsWith(BucketId.PERSON_PREFIX) -> Person(wire.removePrefix(BucketId.PERSON_PREFIX))
            else -> Unset
        }
    }
}

/**
 * A mark the bank puts on a card's purchases for who made them, as it writes it: a cardholder's
 * name ("SAM RIVERA 1006"), or only the last four digits of the card that was used ("1203").
 * [count] purchases carry it. [person] is whose it is: whoever someone [said], or else the one
 * person the mark itself names, which needs no saying; null while it is nobody's yet.
 */
@Immutable
data class CardHolder(val mark: String, val count: Int, val person: String?, val said: Boolean = false)

/** A linked credit card. [key] is its name in the feed sheet, which is also what its [role] is kept by. */
@Immutable
data class BudgetCard(
    val key: String,
    /** The institution it was linked through, for signing in to it again. */
    val institutionId: String,
    val institution: String,
    val name: String,
    val mask: String?,
    val role: CardRole,
    /** What is owed on it. */
    val balance: Double?,
    val needsRelink: Boolean,
    /** False for a card linked as a loan: Plaid was never asked for its purchases. */
    val readsPurchases: Boolean,
    /** The bank's marks for who made each purchase, the commonest first; empty when it makes none. */
    val holders: List<CardHolder> = emptyList(),
) {
    /** The marks nobody has put a person to yet, on a card whose purchases are put to people. */
    val unsaidHolders: List<CardHolder> get() = if (role == CardRole.Split) holders.filter { it.person == null } else emptyList()
}

/** What one bucket has spent this month, against its limit if it has one. */
@Immutable
data class BudgetBucket(val id: BucketId, val spent: Double, val limit: Double?, val count: Int)

/** How a purchase came to be in its bucket. */
enum class BucketSource { MANUAL, ACCOUNT, BANK, RULE, NONE }

/** One purchase (or, with a negative [amount], a refund) on a card that is part of the budget. */
@Immutable
data class Purchase(
    val id: String,
    /** The day it was bought, `YYYY-MM-DD`. */
    val date: String,
    val amount: Double,
    /** The merchant as the bank or Plaid names it: data, shown as written. */
    val name: String,
    /** Plaid's category for it (`FOOD_AND_DRINK`), or null. */
    val category: String?,
    /** Not settled yet: the amount can still change. */
    val pending: Boolean,
    val cardKey: String,
    val bucket: BucketId,
    val source: BucketSource,
    /** Its merchant's purchases are always put the same way. */
    val remembered: Boolean,
) {
    val isRefund: Boolean get() = amount < 0
}

/** The month's limits. A person with no entry, or a null, has none. */
@Immutable
data class BudgetLimits(val total: Double? = null, val people: Map<String, Double?> = emptyMap(), val family: Double? = null)

/** Which lines being crossed the phones are told about. */
@Immutable
data class BudgetAlertSwitches(val total: Boolean = false, val savings: Boolean = false, val buckets: Boolean = false)

/** A merchant whose purchases always go to one bucket; [merchantKey] is the relay's name for the merchant. */
@Immutable
data class MerchantRule(val merchantKey: String, val bucket: BucketId)

/**
 * The two numbers only the budget sheet knows, as this app last reported them to the relay: the
 * month's take-home, and the bills that are paid from the bank rather than on a card.
 */
@Immutable
data class BudgetSheetFigures(val takeHome: Double?, val billsOffCard: Double?) {
    companion object {
        /**
         * Read from the sheet: every expense line counts as a bill unless it is one of
         * [cardPaidLines], the lines whose money goes out through the cards.
         */
        fun of(finance: PersonalFinance, cardPaidLines: Collection<String>): BudgetSheetFigures = BudgetSheetFigures(
            takeHome = finance.monthlyIncome ?: finance.income.takeIf { it.isNotEmpty() }?.sumOf { it.monthly },
            billsOffCard = finance.expenses.takeIf { it.isNotEmpty() }?.filter { it.name !in cardPaidLines }?.sumOf { it.monthly },
        )
    }
}

@Immutable
data class BudgetConfig(
    val people: List<String> = emptyList(),
    val limits: BudgetLimits = BudgetLimits(),
    /** The budget sheet's expense lines that are paid by card. */
    val cardPaidLines: List<String> = emptyList(),
    val alerts: BudgetAlertSwitches = BudgetAlertSwitches(),
    val rules: List<MerchantRule> = emptyList(),
    val sheet: BudgetSheetFigures = BudgetSheetFigures(null, null),
)

/**
 * A change to the settings: each part is left as it stands when null. [roles] is by card key;
 * [holders] by card key and then by the bank's mark ([CardHolder.mark]), a null person taking the say-so back.
 */
@Immutable
data class BudgetConfigPatch(
    val people: List<String>? = null,
    val limits: BudgetLimits? = null,
    val roles: Map<String, CardRole>? = null,
    val holders: Map<String, Map<String, String?>>? = null,
    val cardPaidLines: List<String>? = null,
    val alerts: BudgetAlertSwitches? = null,
    val sheet: BudgetSheetFigures? = null,
)

/** A share of the month's spending: one of Plaid's categories, or one merchant. */
@Immutable
data class BudgetSlice(val name: String, val spent: Double, val count: Int)

/** What an earlier month came to. */
@Immutable
data class BudgetPastMonth(val month: String, val spent: Double, val days: Int)

/**
 * A month of card spending against the budget, as the relay worked it out (`budget_month` in
 * `relay/relay.py`). Days are the household's: [day] is today's day of the month for the month
 * under way, the month's last for one that is over.
 */
@Immutable
data class Budget(
    /** The relay has Plaid keys; without them no card can be linked. */
    val configured: Boolean,
    /** Plaid's sandbox: test institutions with made-up purchases. */
    val sandbox: Boolean,
    /** `YYYY-MM`. */
    val month: String,
    /** The month is the one under way on the household's clock, not one looked back at. */
    val isCurrentMonth: Boolean,
    val day: Int,
    val daysInMonth: Int,
    /** When the cards were last asked, and when that last found anything new. */
    val syncedAtEpochSeconds: Long?,
    val changedAtEpochSeconds: Long?,
    val syncing: Boolean,
    val nextSyncAtEpochSeconds: Long?,
    /** False while Plaid is still fetching a card's first purchases. */
    val ready: Boolean,
    val cards: List<BudgetCard>,
    val config: BudgetConfig,
    /** What take-home leaves after the bills that aren't on a card: spending past it comes out of savings. */
    val savingsLine: Double?,
    val spent: Double,
    /** How much of [spent] hasn't settled yet. */
    val pending: Double,
    val buckets: List<BudgetBucket>,
    /** What was spent on each day so far, the 1st first. */
    val daily: List<Double>,
    val categories: List<BudgetSlice>,
    val merchants: List<BudgetSlice>,
    val purchases: List<Purchase>,
    /** The months before this one, the oldest first. */
    val history: List<BudgetPastMonth>,
) {
    /** The cards whose purchases count. */
    val budgetCards: List<BudgetCard> get() = cards.filter { it.role != CardRole.Unset && it.role != CardRole.Ignore }

    /** The purchases on a shared card that nobody has been put to yet. */
    val unassigned: List<Purchase> get() = purchases.filter { it.bucket == BucketId.Unassigned }
}

/**
 * The month's card spending and the budget's settings, kept by the relay. It reads the cards
 * through Plaid every hour; this only ever sees what it read.
 */
interface BudgetRepository {
    /** [month] as `YYYY-MM`, or the month under way. */
    suspend fun budget(month: String? = null): Result<Budget>

    suspend fun save(patch: BudgetConfigPatch): Result<Budget>

    /** Puts a purchase in [bucket] ([BucketId.Unassigned] takes it back out), and with [remember] its merchant's others too. */
    suspend fun tag(purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget>

    suspend fun forgetRule(merchantKey: String): Result<Budget>

    /** Asks the relay to read the cards now; the answer says whether that has begun. */
    suspend fun syncNow(): Result<Budget>
}
