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

/** The bank sync as the relay reports it. */
@Immutable
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
}
