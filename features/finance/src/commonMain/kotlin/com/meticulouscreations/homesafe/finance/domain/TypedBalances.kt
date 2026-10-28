package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_home_value
import kotlin.math.abs

/**
 * Where a balance sits in the budget sheet, the way the relay finds it again: the words in its
 * row's label cell, and how many columns to the right of that label the number is (as a line of
 * the relay's sheet fill names a cell). [row] is where the label was read, which tells two rows
 * with the same label apart. [value] is the number the cell held, signed as the sheet keeps it;
 * [calculated] when the cell is a formula (a total, or a lookup in the bank feed) rather than a
 * typed number. [negative] when the sheet keeps this balance below zero (some sheets keep what's
 * owed that way), so a number typed for it is written with that sign.
 */
@Immutable
data class SheetCell(
    val tab: String,
    val label: String,
    val row: Int,
    val right: Int,
    val value: Double?,
    val calculated: Boolean = false,
    val negative: Boolean = false,
) {
    /** The balance's id, as the relay keys it: `tab|label|right`, the label in lower case with its spacing folded. */
    val id: String get() = idOf(tab, label, right)

    /** [amount], a balance as the app shows it (what's held, or what's owed, positive), as the cell keeps it. */
    fun toSheet(amount: Double): Double = if (negative) -abs(amount) else amount

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun idOf(tab: String, label: String, right: Int): String = "${tab.trim()}|${label.trim().replace(WHITESPACE, " ").lowercase()}|$right"
    }
}

/** When a balance was last known to be right: typed in the app by [by], or written by the bank sync ([fromBank]). */
@Immutable
data class BalanceAge(val atEpochSeconds: Long, val by: String?, val fromBank: Boolean)

/** How out of date a balance is, by how long since it was last updated. */
enum class BalanceFreshness {
    /** Updated within the last month. */
    FRESH,

    /** One to two months old: worth a look. */
    AGING,

    /** Over two months old. */
    STALE,

    /** Never updated from the app or the bank sync, so how old it is can't be said. */
    UNKNOWN,
    ;

    /** Worth updating: anything but fresh. */
    val wantsUpdate: Boolean get() = this != FRESH

    companion object {
        const val FRESH_DAYS = 31
        const val AGING_DAYS = 62

        fun of(age: BalanceAge?, nowEpochSeconds: Long): BalanceFreshness {
            age ?: return UNKNOWN
            val days = (nowEpochSeconds - age.atEpochSeconds) / 86_400
            return when {
                days <= FRESH_DAYS -> FRESH
                days <= AGING_DAYS -> AGING
                else -> STALE
            }
        }
    }
}

enum class TypedBalanceKind { ACCOUNT, DEBT, HOME_VALUE }

/**
 * A balance typed into the budget sheet rather than worked out there: an account, a debt, or
 * what the home is worth. These are the numbers someone has to look up and copy over now and
 * then, and the app can type in for them (see [FinanceRepository.updateBalance]). [balance] is
 * as the app shows it: what's held, or what's owed (positive).
 */
@Immutable
data class TypedBalance(
    val name: UiText,
    val owner: Owner?,
    val kind: TypedBalanceKind,
    val balance: Double,
    val cell: SheetCell,
    val age: BalanceAge?,
) {
    /** The bank sync writes it each morning: a number typed in the app would be written over. */
    val fromBank: Boolean get() = age?.fromBank == true

    fun freshness(nowEpochSeconds: Long): BalanceFreshness = BalanceFreshness.of(age, nowEpochSeconds)
}

/**
 * Every typed balance in [finance], oldest first (those of unknown age leading), each with when
 * it was last updated. A cell the sheet works out is left out: it follows the cells it adds up.
 */
fun typedBalances(finance: PersonalFinance): List<TypedBalance> {
    val ages = finance.balanceAges
    val accounts = finance.accounts.mapNotNull { a ->
        a.cell?.takeUnless { it.calculated }?.let { TypedBalance(a.name, a.owner, TypedBalanceKind.ACCOUNT, a.balance, it, ages[it.id]) }
    }
    val debts = finance.debts.mapNotNull { d ->
        d.cell?.takeUnless { it.calculated }?.let { TypedBalance(UiText.verbatim(d.name), d.owner, TypedBalanceKind.DEBT, d.balance, it, ages[it.id]) }
    }
    val home = finance.home?.let { h ->
        val value = h.value ?: return@let null
        h.valueCell?.takeUnless { it.calculated }?.let { TypedBalance(UiText.of(Res.string.fin_data_home_value), null, TypedBalanceKind.HOME_VALUE, value, it, ages[it.id]) }
    }
    // One cell can be read twice (a joint row the parser saw from two sides): it is one balance.
    return (accounts + debts + listOfNotNull(home)).distinctBy { it.cell.id }.sortedBy { it.age?.atEpochSeconds ?: Long.MIN_VALUE }
}

/** Why a balance couldn't be typed into the sheet, each with its own words in the app. */
enum class BalanceProblem {
    /** The cell holds something other than what the app showed: the sheet was edited since it was read. */
    CHANGED,

    /** The cell is a formula or words now, not a typed number. */
    CALCULATED,

    /** The bank sync fills this balance in every morning. */
    BANK_FILLED,

    /** The label isn't in the sheet any more, or is there twice. */
    NOT_FOUND,

    /** The relay may not write to the budget sheet: it is shared with it as a Viewer only. */
    NOT_SHARED,

    /** A sync is under way; try again in a minute. */
    BUSY,

    /** The relay is older than this feature. */
    RELAY_OUTDATED,

    /** No server to send it to. */
    NOT_CONNECTED,

    OTHER,
}

class BalanceUpdateException(val problem: BalanceProblem, text: UiText, technical: String? = null) : LocalizedException(text, technical)
