package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Whose a purchase is: one person's, the whole family's, or nobody's yet. [wire] is the relay's
 * name for it (`budget_buckets` in `relay/relay.py`); a purchase nobody has been given has none.
 */
@Immutable
@Serializable(with = BucketIdSerializer::class)
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
@Serializable(with = CardRoleSerializer::class)
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
 * A [BucketId] or a [CardRole] kept on the device is its [BucketId.wire] name, the one the relay
 * uses; the one with none (nobody's yet, not said yet) is kept as an empty string.
 */
internal object BucketIdSerializer : KSerializer<BucketId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BucketId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BucketId) = encoder.encodeString(value.wire.orEmpty())

    override fun deserialize(decoder: Decoder): BucketId = BucketId.ofWire(decoder.decodeString().ifEmpty { null })
}

internal object CardRoleSerializer : KSerializer<CardRole> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CardRole", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CardRole) = encoder.encodeString(value.wire.orEmpty())

    override fun deserialize(decoder: Decoder): CardRole = CardRole.ofWire(decoder.decodeString().ifEmpty { null })
}

/**
 * A mark the bank puts on a card's purchases for who made them, as it writes it: a cardholder's
 * name ("SAM RIVERA 1006"), or only the last four digits of the card that was used ("1203").
 * [count] purchases carry it; [person] is whose it was said to be, once someone has said.
 */
@Immutable
@Serializable
data class CardHolder(val mark: String, val count: Int, val person: String?)

/** A linked credit card. [key] is its name in the feed sheet, which is also what its [role] is kept by. */
@Immutable
@Serializable
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
@Serializable
data class BudgetBucket(val id: BucketId, val spent: Double, val limit: Double?, val count: Int)

/** How a purchase came to be in its bucket. */
enum class BucketSource { MANUAL, ACCOUNT, BANK, RULE, NONE }

/** How a purchase came to be in its budget line: by hand, by its merchant's rule, on its own (by what it is), or it is in none. */
enum class LineSource { MANUAL, RULE, AUTO, NONE }

/**
 * Where a purchase is to go among the budget sheet's card-paid lines: one of them by name, none of
 * them ([None]), or wherever it falls on its own ([Automatic], taking back a choice made by hand).
 * [wire] is how the relay is told (`put_budget_transaction_line` in `relay/relay.py`).
 */
@Immutable
sealed interface LinePick {
    val wire: String?

    @Immutable
    data class Line(val name: String) : LinePick {
        override val wire: String get() = name
    }

    @Immutable
    data object None : LinePick {
        override val wire: String get() = ""
    }

    @Immutable
    data object Automatic : LinePick {
        override val wire: String? get() = null
    }
}

/**
 * One of the budget sheet's lines that is paid by card ("Groceries"): what the sheet plans for it
 * a month, what this month's purchases in it have come to, and what it usually comes to (the
 * average of the months before, once there are two to go by).
 */
@Immutable
@Serializable
data class BudgetLine(val name: String, val planned: Double?, val spent: Double, val count: Int, val usual: Double? = null)

/** One purchase (or, with a negative [amount], a refund) on a card that is part of the budget. */
@Immutable
@Serializable
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
    /** The card-paid line of the budget sheet it is in, or null for none. */
    val line: String? = null,
    val lineSource: LineSource = LineSource.NONE,
    /** Its merchant's purchases always go in the same line (or in none). */
    val lineRemembered: Boolean = false,
    /** One of Amazon's charges for an order (not AWS, Prime and the like), whether or not its order was found. */
    val fromAmazon: Boolean = false,
    /** The Amazon order it paid for, once the relay matched it to one of the order emails. */
    val amazon: AmazonOrder? = null,
) {
    val isRefund: Boolean get() = amount < 0
}

/** Whether something bought is a need or a want. [wire] is the relay's name for it (`AMAZON_NEEDS` in `relay/relay.py`). */
enum class AmazonNeed(val wire: String) {
    ESSENTIAL("essential"),
    DISCRETIONARY("discretionary"),
    ;

    companion object {
        fun ofWire(wire: String?): AmazonNeed? = entries.firstOrNull { it.wire == wire }
    }
}

/** Who sorted an item: someone by hand, the relay's word lists, the box's model, or nobody yet. */
enum class NeedSource { USER, RULE, MODEL, NONE }

/** How a charge was matched to its order: a shipment's total, the order's total, or several charges adding up to it. */
enum class AmazonMatch { SHIPMENT, ORDER, SPLIT }

/**
 * One line of an Amazon order: a product, or (as Amazon's emails mostly say now) Amazon's
 * category for it. [title] is Amazon's, data shown as written; [price] is each one's, when the
 * email said. [orderId] is the order it is in, when a charge paid toward more than one.
 */
@Immutable
@Serializable
data class AmazonItem(
    val position: Int,
    val title: String,
    val quantity: Int,
    val price: Double?,
    val need: AmazonNeed?,
    val sortedBy: NeedSource,
    val orderId: String? = null,
)

/**
 * The Amazon order a charge paid for, and the charge shared out by the order's item prices:
 * [essential], [discretionary], and [unsorted] for items nobody has sorted yet.
 */
@Immutable
@Serializable
data class AmazonOrder(
    val orderId: String,
    val match: AmazonMatch,
    /** The day it was ordered, `YYYY-MM-DD`. */
    val ordered: String,
    val items: List<AmazonItem>,
    val essential: Double,
    val discretionary: Double,
    val unsorted: Double,
)

/**
 * The month's Amazon charges, summed: shared out between [essential], [discretionary] and
 * [unsorted], and [unmatched] for charges no order email was found for. [configured] is whether
 * the relay reads anyone's order emails at all; [failedMailboxes] are those its last read couldn't.
 */
@Immutable
@Serializable
data class AmazonSummary(
    val configured: Boolean,
    val readAtEpochSeconds: Long?,
    val failedMailboxes: List<String>,
    val charges: Int,
    val essential: Double,
    val discretionary: Double,
    val unsorted: Double,
    val unmatched: Double,
) {
    val total: Double get() = essential + discretionary + unsorted + unmatched
}

/** The month's limits. A person with no entry, or a null, has none. */
@Immutable
@Serializable
data class BudgetLimits(val total: Double? = null, val people: Map<String, Double?> = emptyMap(), val family: Double? = null)

/** A merchant whose purchases always go to one bucket; [merchantKey] is the relay's name for the merchant. */
@Immutable
@Serializable
data class MerchantRule(val merchantKey: String, val bucket: BucketId)

/** A merchant whose purchases always go in one of the card-paid lines, or ([line] null) in none of them. */
@Immutable
@Serializable
data class LineRule(val merchantKey: String, val line: String?)

/**
 * The numbers only the budget sheet knows, as this app last reported them to the relay: the
 * month's take-home, the bills that are paid from the bank rather than on a card, and what each
 * line that is paid by card plans for a month, by its name.
 */
@Immutable
@Serializable
data class BudgetSheetFigures(val takeHome: Double?, val billsOffCard: Double?, val lines: Map<String, Double> = emptyMap()) {
    companion object {
        /**
         * Read from the sheet: every expense line counts as a bill unless it is one of
         * [cardPaidLines], the lines whose money goes out through the cards. Two lines of the
         * same name are ticked together, so they plan together.
         */
        fun of(finance: PersonalFinance, cardPaidLines: Collection<String>): BudgetSheetFigures = BudgetSheetFigures(
            takeHome = finance.monthlyIncome ?: finance.income.takeIf { it.isNotEmpty() }?.sumOf { it.monthly },
            billsOffCard = finance.expenses.takeIf { it.isNotEmpty() }?.filter { it.name !in cardPaidLines }?.sumOf { it.monthly },
            lines = finance.expenses.filter { it.name in cardPaidLines }.groupBy { it.name }.mapValues { (_, same) -> same.sumOf { it.monthly } },
        )
    }
}

@Immutable
@Serializable
data class BudgetConfig(
    val people: List<String> = emptyList(),
    val limits: BudgetLimits = BudgetLimits(),
    /** The budget sheet's expense lines that are paid by card. */
    val cardPaidLines: List<String> = emptyList(),
    val alerts: BudgetAlertSwitches = BudgetAlertSwitches(),
    val rules: List<MerchantRule> = emptyList(),
    val lineRules: List<LineRule> = emptyList(),
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
@Serializable
data class BudgetSlice(val name: String, val spent: Double, val count: Int)

/** What an earlier month came to. */
@Immutable
@Serializable
data class BudgetPastMonth(val month: String, val spent: Double, val days: Int)

/**
 * A month of card spending against the budget, as the relay worked it out (`budget_month` in
 * `relay/relay.py`). Days are the household's: [day] is today's day of the month for the month
 * under way, the month's last for one that is over.
 */
@Immutable
@Serializable
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
    /** Each of the budget sheet's card-paid lines, in [BudgetConfig.cardPaidLines]' order. */
    val lines: List<BudgetLine> = emptyList(),
    /** What the purchases in none of the [lines] come to. */
    val outsideLines: BudgetSlice = BudgetSlice("", 0.0, 0),
    /** What was spent on each day so far, the 1st first. */
    val daily: List<Double>,
    val categories: List<BudgetSlice>,
    val merchants: List<BudgetSlice>,
    val purchases: List<Purchase>,
    /** The months before this one, the oldest first. */
    val history: List<BudgetPastMonth>,
    /** What the month's Amazon charges were for, from the order emails; null from a relay that doesn't read them. */
    val amazon: AmazonSummary? = null,
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

    /** Puts a purchase in one of the card-paid lines, or none, or back where it falls on its own; with [remember] its merchant's others too. */
    suspend fun tagLine(purchaseId: String, line: LinePick, remember: Boolean): Result<Budget>

    suspend fun forgetLineRule(merchantKey: String): Result<Budget>

    /**
     * Says whether one item of an Amazon order was essential or discretionary; null lets it sort
     * itself again. The relay refuses it when [item] is no longer what is at its place.
     */
    suspend fun sayNeed(orderId: String, item: AmazonItem, need: AmazonNeed?): Result<Budget>

    /** Asks the relay to read the cards now; the answer says whether that has begun. */
    suspend fun syncNow(): Result<Budget>
}
