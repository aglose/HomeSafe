package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_kind_bank
import homesafe.shared.generated.resources.fin_bank_kind_bank_detail
import homesafe.shared.generated.resources.fin_bank_kind_investments
import homesafe.shared.generated.resources.fin_bank_kind_investments_detail
import homesafe.shared.generated.resources.fin_bank_kind_loans
import homesafe.shared.generated.resources.fin_bank_kind_loans_detail
import homesafe.shared.generated.resources.fin_bank_type_cash
import homesafe.shared.generated.resources.fin_bank_type_credit
import homesafe.shared.generated.resources.fin_bank_type_investment
import homesafe.shared.generated.resources.fin_bank_type_loan
import homesafe.shared.generated.resources.fin_bank_type_other
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

/**
 * What is being linked, which decides the institutions Plaid's page lists: [wire] is the relay's
 * name for it (`PLAID_KINDS` in `relay/relay.py`). Whatever is picked, an institution's
 * investments and loans come along where it has them.
 */
enum class BankLinkKind(val wire: String, val label: StringResource, val detail: StringResource) {
    BANK("bank", Res.string.fin_bank_kind_bank, Res.string.fin_bank_kind_bank_detail),
    INVESTMENTS("investments", Res.string.fin_bank_kind_investments, Res.string.fin_bank_kind_investments_detail),
    LOANS("loans", Res.string.fin_bank_kind_loans, Res.string.fin_bank_kind_loans_detail),
}

/** Plaid's account types, as the page groups and words them. */
enum class BankAccountType(val label: StringResource) {
    CASH(Res.string.fin_bank_type_cash),
    CREDIT(Res.string.fin_bank_type_credit),
    LOAN(Res.string.fin_bank_type_loan),
    INVESTMENT(Res.string.fin_bank_type_investment),
    OTHER(Res.string.fin_bank_type_other),
    ;

    /** A balance of this type is money owed, not money held. */
    val isDebt: Boolean get() = this == CREDIT || this == LOAN

    companion object {
        fun ofPlaid(type: String?): BankAccountType = when (type) {
            "depository" -> CASH
            "credit" -> CREDIT
            "loan" -> LOAN
            "investment", "brokerage" -> INVESTMENT
            else -> OTHER
        }
    }
}

/**
 * One account at a linked institution, as Plaid last reported it. [key] is the name its row goes
 * by in the feed sheet, which the budget sheet's formulas look up. [balance] is what's held, or
 * for a card or a loan what's owed.
 */
@Immutable
@Serializable
data class BankAccount(
    val id: String,
    val key: String,
    val name: String,
    val mask: String?,
    val type: BankAccountType,
    /** Plaid's own word for the kind of account ("checking", "roth", "mortgage"): data, shown as written. */
    val subtype: String?,
    val balance: Double?,
    val currency: String?,
    /** A card's or a loan's yearly rate, as a percentage. */
    val apr: Double?,
    /** How many positions an investment account holds. */
    val holdings: Int,
)

/** A bank, brokerage or lender linked through Plaid. [error] is Plaid's code for why its last read failed. */
@Immutable
@Serializable
data class BankInstitution(
    val id: String,
    val name: String,
    val syncedAtEpochSeconds: Long?,
    val error: String?,
    /** Plaid's own words for [error], shown as written. */
    val errorMessage: String?,
    /** The institution wants its sign-in again before Plaid can read it. */
    val needsRelink: Boolean,
    val accounts: List<BankAccount>,
)

/** How the relay's last write of the balances to the feed sheet went. */
@Immutable
@Serializable
data class BankFeed(
    /** The relay has a feed sheet to write to. */
    val configured: Boolean,
    val url: String?,
    val writtenAtEpochSeconds: Long?,
    /** The relay's code for why the last write failed: `not_shared`, `api_disabled`, `not_found`, `no_key`, `google_error`. */
    val error: String?,
    val message: String?,
    /** The Google account the feed sheet must be shared with as an editor. */
    val serviceAccount: String?,
    val activationUrl: String?,
)

/**
 * How a line of the sheet fill came out of the last sync: the relay's word for it (`fill_plan` in
 * `relay/relay.py`).
 */
enum class SheetFillStatus(val wire: String) {
    /** The cell was changed. */
    WRITTEN("written"),

    /** The cell already held the number. */
    SAME("same"),

    /** Added since the last sync, so not tried yet. */
    WAITING("waiting"),
    PAUSED("paused"),

    /** One of the accounts it adds up isn't linked. */
    NO_ACCOUNT("no_account"),

    /** One of its accounts has no balance yet. */
    NO_BALANCE("no_balance"),
    NO_TAB("no_tab"),
    NO_LABEL("no_label"),

    /** The label is in the tab more than once. */
    AMBIGUOUS("ambiguous"),

    /** The cell holds a formula or words, which are never written over. */
    NOT_A_NUMBER("not_a_number"),

    /** Another line comes to the same cell, so neither is written. */
    SAME_CELL("same_cell"),

    /** A vest's line whose vest has happened: the cell keeps its last estimate. */
    VESTED("vested"),

    /** A vest's line with no vest expected near its day. */
    NO_VEST("no_vest"),
    ;

    /** The line was left alone for a reason only a person can clear. */
    val needsALook: Boolean get() = this != WRITTEN && this != SAME && this != WAITING && this != PAUSED && this != VESTED

    companion object {
        /** A word this build doesn't know (a newer relay's) reads as not tried yet rather than as a fault. */
        fun ofWire(wire: String?): SheetFillStatus = entries.firstOrNull { it.wire == wire } ?: WAITING
    }
}

/**
 * One cell of the budget sheet the relay keeps filled in: the row is found by [label] in [tab],
 * and the number is the sum of [accounts] (feed keys). [cell] and [value] are where it was found
 * and what it came to at the last sync.
 */
@Immutable
@Serializable
data class SheetFillLine(
    val id: String,
    val tab: String,
    val label: String,
    val accounts: List<String>,
    val status: SheetFillStatus,
    val cell: String?,
    val value: Double?,
    /** The accounts of [accounts] that aren't linked, when that is why it was left alone. */
    val missing: List<String>,
) {
    val paused: Boolean get() = status == SheetFillStatus.PAUSED
}

/** What one write did to one cell. [before] is null for a cell that was empty. [restored] once the write was undone: whether this cell was put back. */
@Immutable
@Serializable
data class SheetChange(val label: String, val tab: String, val cell: String?, val before: Double?, val after: Double?, val restored: Boolean?)

/** One time the relay wrote balances into the budget sheet's cells, and everything it changed. */
@Immutable
@Serializable
data class SheetWrite(
    val id: Long,
    val atEpochSeconds: Long,
    val undoneAtEpochSeconds: Long?,
    val undoneBy: String?,
    val changes: List<SheetChange>,
) {
    val undone: Boolean get() = undoneAtEpochSeconds != null
}

/** The sheet fill: the budget sheet's cells the relay writes the balances into, and what it wrote lately. */
@Immutable
@Serializable
data class SheetFill(
    /** The relay has a list of cells to fill. */
    val configured: Boolean = false,
    val checkedAtEpochSeconds: Long? = null,
    /** The relay's code for why the last fill failed: `bad_file`, `not_shared`, `api_disabled`, `not_found`, `no_key`, `google_error`. */
    val error: String? = null,
    /** With `bad_file`, what is wrong with the list of cells: `unreadable`, `no_lines`, `bad_line`, `same_line`. */
    val errorReason: String? = null,
    /** The line of the list [errorReason] is about, counted from 1. */
    val errorLine: Int? = null,
    /** The relay's or Google's own words for [error]: detail, in English. */
    val message: String? = null,
    /** The Google account the budget sheet must be shared with as an editor. */
    val serviceAccount: String? = null,
    val lines: List<SheetFillLine> = emptyList(),
    /** The newest first. */
    val writes: List<SheetWrite> = emptyList(),
)

/** Shares vesting on a day (`YYYY-MM-DD`): one that was read from the plan's activity, or one worked out from those. [value] is at today's price. */
@Immutable
@Serializable
data class StockVest(val date: String, val shares: Double, val value: Double? = null)

/**
 * An employer's stock plan and what its past vests say about the ones to come (`stock_plan_outlook`
 * in `relay/relay.py`): one every [everyMonths], each of [perVest] shares, until the [unvested]
 * shares run out. An estimate, since the plan itself reports no dates.
 */
@Immutable
@Serializable
data class StockPlan(
    /** The account's key in the feed. */
    val account: String,
    val name: String,
    val institution: String,
    val ticker: String,
    val price: Double?,
    val unvested: Double,
    /** Null until two vests have been read: the pace isn't known from one. */
    val everyMonths: Int?,
    val perVest: Double,
    /** The vests read, oldest first. */
    val vests: List<StockVest>,
    val upcoming: List<StockVest>,
)

/** The bank sync as the relay reports it. */
@Immutable
@Serializable
data class BankSync(
    /** The relay has Plaid keys; without them nothing can be linked. */
    val configured: Boolean,
    /** Plaid's sandbox: test institutions with made-up money. */
    val sandbox: Boolean,
    val institutions: List<BankInstitution>,
    val syncedAtEpochSeconds: Long?,
    val syncing: Boolean,
    val nextSyncAtEpochSeconds: Long?,
    val feed: BankFeed,
    val fill: SheetFill = SheetFill(),
    val stockPlans: List<StockPlan> = emptyList(),
)

/** A link under way: Plaid's page to open, and the token its progress is asked by. */
@Immutable
data class BankLinkStart(val token: String, val url: String, val expiresAtEpochSeconds: Long)

enum class BankLinkStatus { PENDING, LINKED, EXITED, EXPIRED }

/** How a link is getting on; once [BankLinkStatus.LINKED], who was linked and the sync as it now stands. */
@Immutable
data class BankLinkProgress(val status: BankLinkStatus, val institutions: List<String> = emptyList(), val bank: BankSync? = null)

/** Why a bank sync call failed. */
enum class BankProblem {
    /** The relay is older than this feature. */
    RELAY_OUTDATED,

    /** Signed in, but as an account the relay won't show the household's money to. */
    NOT_ALLOWED,

    /** No server session (signed out, or not connected). */
    SIGNED_OUT,

    /** The relay has no Plaid keys. */
    NOT_CONFIGURED,

    /** Plaid refused; the text is its own explanation. */
    PLAID,

    /**
     * Nothing answered, and the server is reached through Tailscale while this device isn't on the
     * tailnet: Tailscale is off, or another VPN took its place (a phone runs one VPN at a time).
     */
    TAILSCALE_OFF,

    /** Nothing answered, and Tailscale isn't known to be why. */
    UNREACHABLE,

    OTHER,
}

class BankSyncException(val problem: BankProblem, text: UiText, technical: String? = null) : LocalizedException(text, technical)

/**
 * The household's banks, brokerages and lenders, linked through Plaid by the relay. The relay
 * holds each institution's access token and reads the balances once a day; this only ever sees
 * what it read.
 */
interface BankSyncRepository {
    suspend fun status(): Result<BankSync>

    /** Starts linking a new institution of [kind]. */
    suspend fun startLink(kind: BankLinkKind): Result<BankLinkStart>

    /** Starts a fresh sign-in to an institution already linked, when it has asked for one. */
    suspend fun startRelink(institutionId: String): Result<BankLinkStart>

    suspend fun linkProgress(token: String): Result<BankLinkProgress>

    /** Asks the relay to read every institution now; the answer says whether that has begun. */
    suspend fun syncNow(): Result<BankSync>

    suspend fun unlink(institutionId: String): Result<BankSync>

    /** Puts back what one write to the budget sheet changed; the relay pauses its lines. */
    suspend fun undoWrite(writeId: Long): Result<BankSync>

    /** Pauses a line of the sheet fill, or resumes it (the relay writes a resumed line at once). */
    suspend fun setLinePaused(lineId: String, paused: Boolean): Result<BankSync>
}
