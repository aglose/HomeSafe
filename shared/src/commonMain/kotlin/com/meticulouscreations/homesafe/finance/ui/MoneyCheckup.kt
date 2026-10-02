package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.checkup_debt_detail_costly
import homesafe.shared.generated.resources.checkup_debt_detail_none_rated
import homesafe.shared.generated.resources.checkup_debt_figure_loans
import homesafe.shared.generated.resources.checkup_debt_figure_none
import homesafe.shared.generated.resources.checkup_debt_item_fed
import homesafe.shared.generated.resources.checkup_debt_item_fed_detail
import homesafe.shared.generated.resources.checkup_debt_item_floor
import homesafe.shared.generated.resources.checkup_debt_item_floor_detail
import homesafe.shared.generated.resources.checkup_debt_item_not_loaded
import homesafe.shared.generated.resources.checkup_debt_ledger_above
import homesafe.shared.generated.resources.checkup_debt_ledger_line
import homesafe.shared.generated.resources.checkup_debt_ledger_loans
import homesafe.shared.generated.resources.checkup_debt_note
import homesafe.shared.generated.resources.checkup_debt_rule
import homesafe.shared.generated.resources.checkup_debt_step_add_rate
import homesafe.shared.generated.resources.checkup_debt_step_all_below
import homesafe.shared.generated.resources.checkup_debt_step_interest
import homesafe.shared.generated.resources.checkup_debt_step_pay_first
import homesafe.shared.generated.resources.checkup_debt_step_spare_clears
import homesafe.shared.generated.resources.checkup_debt_step_spare_partial
import homesafe.shared.generated.resources.checkup_debt_step_then
import homesafe.shared.generated.resources.checkup_debt_unjudged
import homesafe.shared.generated.resources.checkup_debt_whatif
import homesafe.shared.generated.resources.checkup_debt_whatif_all_cleared
import homesafe.shared.generated.resources.checkup_debt_whatif_paid_off
import homesafe.shared.generated.resources.checkup_debt_whatif_saved
import homesafe.shared.generated.resources.checkup_debt_whatif_still_costly
import homesafe.shared.generated.resources.checkup_emergency_ledger_cash
import homesafe.shared.generated.resources.checkup_emergency_ledger_months
import homesafe.shared.generated.resources.checkup_emergency_ledger_spending
import homesafe.shared.generated.resources.checkup_emergency_note
import homesafe.shared.generated.resources.checkup_emergency_rule
import homesafe.shared.generated.resources.checkup_emergency_step_beyond
import homesafe.shared.generated.resources.checkup_emergency_step_short
import homesafe.shared.generated.resources.checkup_emergency_step_short_away
import homesafe.shared.generated.resources.checkup_emergency_step_six
import homesafe.shared.generated.resources.checkup_emergency_step_stronger
import homesafe.shared.generated.resources.checkup_emergency_step_stronger_away
import homesafe.shared.generated.resources.checkup_emergency_step_target
import homesafe.shared.generated.resources.checkup_emergency_step_top_up
import homesafe.shared.generated.resources.checkup_emergency_step_trim
import homesafe.shared.generated.resources.checkup_emergency_step_where
import homesafe.shared.generated.resources.checkup_emergency_whatif
import homesafe.shared.generated.resources.checkup_emergency_whatif_covered
import homesafe.shared.generated.resources.checkup_emergency_whatif_past
import homesafe.shared.generated.resources.checkup_emergency_whatif_short
import homesafe.shared.generated.resources.checkup_emergency_whatif_within
import homesafe.shared.generated.resources.checkup_gauge_months
import homesafe.shared.generated.resources.checkup_item_apr
import homesafe.shared.generated.resources.checkup_item_interest
import homesafe.shared.generated.resources.checkup_item_no_rate
import homesafe.shared.generated.resources.checkup_months
import homesafe.shared.generated.resources.checkup_not_itemised
import homesafe.shared.generated.resources.checkup_not_itemised_detail
import homesafe.shared.generated.resources.checkup_op_divide
import homesafe.shared.generated.resources.checkup_op_equals
import homesafe.shared.generated.resources.checkup_op_minus
import homesafe.shared.generated.resources.checkup_op_versus
import homesafe.shared.generated.resources.checkup_priority_costly_debt
import homesafe.shared.generated.resources.checkup_priority_debt_ratio
import homesafe.shared.generated.resources.checkup_priority_emergency
import homesafe.shared.generated.resources.checkup_priority_retirement
import homesafe.shared.generated.resources.checkup_priority_savings
import homesafe.shared.generated.resources.checkup_ratio_item_elsewhere
import homesafe.shared.generated.resources.checkup_ratio_item_elsewhere_detail
import homesafe.shared.generated.resources.checkup_ratio_ledger_owed
import homesafe.shared.generated.resources.checkup_ratio_ledger_owned
import homesafe.shared.generated.resources.checkup_ratio_ledger_result
import homesafe.shared.generated.resources.checkup_ratio_rule
import homesafe.shared.generated.resources.checkup_ratio_step_assets
import homesafe.shared.generated.resources.checkup_ratio_step_from_income
import homesafe.shared.generated.resources.checkup_ratio_step_from_savings
import homesafe.shared.generated.resources.checkup_ratio_step_highest_rate
import homesafe.shared.generated.resources.checkup_ratio_step_margin
import homesafe.shared.generated.resources.checkup_ratio_step_mortgage
import homesafe.shared.generated.resources.checkup_ratio_step_nothing_owed
import homesafe.shared.generated.resources.checkup_ratio_whatif
import homesafe.shared.generated.resources.checkup_ratio_whatif_all_paid
import homesafe.shared.generated.resources.checkup_ratio_whatif_nothing_left
import homesafe.shared.generated.resources.checkup_ratio_whatif_owed_vs_owned
import homesafe.shared.generated.resources.checkup_ratio_whatif_still_owed
import homesafe.shared.generated.resources.checkup_retirement_ledger_accounts
import homesafe.shared.generated.resources.checkup_retirement_ledger_result
import homesafe.shared.generated.resources.checkup_retirement_ledger_total
import homesafe.shared.generated.resources.checkup_retirement_note
import homesafe.shared.generated.resources.checkup_retirement_rule
import homesafe.shared.generated.resources.checkup_retirement_step_401k
import homesafe.shared.generated.resources.checkup_retirement_step_match
import homesafe.shared.generated.resources.checkup_retirement_step_reach
import homesafe.shared.generated.resources.checkup_retirement_step_reach_away
import homesafe.shared.generated.resources.checkup_retirement_step_uneven
import homesafe.shared.generated.resources.checkup_retirement_whatif
import homesafe.shared.generated.resources.checkup_retirement_whatif_in_accounts
import homesafe.shared.generated.resources.checkup_retirement_whatif_saved
import homesafe.shared.generated.resources.checkup_savings_ledger_kept
import homesafe.shared.generated.resources.checkup_savings_ledger_left_over
import homesafe.shared.generated.resources.checkup_savings_ledger_other
import homesafe.shared.generated.resources.checkup_savings_ledger_spending
import homesafe.shared.generated.resources.checkup_savings_ledger_take_home
import homesafe.shared.generated.resources.checkup_savings_rule
import homesafe.shared.generated.resources.checkup_savings_step_401k
import homesafe.shared.generated.resources.checkup_savings_step_above
import homesafe.shared.generated.resources.checkup_savings_step_more_pay
import homesafe.shared.generated.resources.checkup_savings_step_past_stretch
import homesafe.shared.generated.resources.checkup_savings_step_reach
import homesafe.shared.generated.resources.checkup_savings_step_stretch
import homesafe.shared.generated.resources.checkup_savings_step_trim_expense
import homesafe.shared.generated.resources.checkup_savings_whatif
import homesafe.shared.generated.resources.checkup_savings_whatif_kept
import homesafe.shared.generated.resources.checkup_savings_whatif_left_over
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.finance_wallet_accounts_title
import homesafe.shared.generated.resources.finance_wallet_allocation_title
import homesafe.shared.generated.resources.finance_wallet_cash_flow_title
import homesafe.shared.generated.resources.finance_wallet_debt_title
import homesafe.shared.generated.resources.finance_wallet_per_month
import homesafe.shared.generated.resources.layman_checkup_debt_none
import homesafe.shared.generated.resources.layman_checkup_debt_tip_costly
import homesafe.shared.generated.resources.layman_checkup_debt_tip_ok
import homesafe.shared.generated.resources.layman_checkup_debt_title
import homesafe.shared.generated.resources.layman_checkup_emergency_detail
import homesafe.shared.generated.resources.layman_checkup_emergency_tip_ok
import homesafe.shared.generated.resources.layman_checkup_emergency_tip_short
import homesafe.shared.generated.resources.layman_checkup_emergency_tip_solid
import homesafe.shared.generated.resources.layman_checkup_emergency_title
import homesafe.shared.generated.resources.layman_checkup_ratio_detail
import homesafe.shared.generated.resources.layman_checkup_ratio_tip_high
import homesafe.shared.generated.resources.layman_checkup_ratio_tip_ok
import homesafe.shared.generated.resources.layman_checkup_ratio_title
import homesafe.shared.generated.resources.layman_checkup_retirement_detail
import homesafe.shared.generated.resources.layman_checkup_retirement_tip_low
import homesafe.shared.generated.resources.layman_checkup_retirement_tip_ok
import homesafe.shared.generated.resources.layman_checkup_retirement_title
import homesafe.shared.generated.resources.layman_checkup_saving_detail
import homesafe.shared.generated.resources.layman_checkup_saving_tip_low
import homesafe.shared.generated.resources.layman_checkup_saving_tip_ok
import homesafe.shared.generated.resources.layman_checkup_saving_title
import homesafe.shared.generated.resources.narrator_two_sentences
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A part of the Wallet page a checkup figure comes from, by its header's list key, to jump to; [label] is the header's title. */
internal enum class WalletSection(val key: String, val label: StringResource) {
    ACCOUNTS("acct-h", Res.string.finance_wallet_accounts_title),
    ALLOCATION("alloc-h", Res.string.finance_wallet_allocation_title),
    CASH_FLOW("flow-h", Res.string.finance_wallet_cash_flow_title),
    DEBT("debt-h", Res.string.finance_wallet_debt_title),
}

/** The checkup's lines, in the order the card lists them. */
internal enum class CheckKind {
    EMERGENCY_FUND,
    SAVINGS_RATE,
    COSTLY_DEBT,
    DEBT_RATIO,
    RETIREMENT,
}

/** One thing that goes into a figure: an account, an expense, a loan. [flagged] marks the ones holding the check back. */
@Immutable
internal data class CheckItem(val label: UiText, val detail: UiText?, val amount: UiText, val flagged: Boolean = false)

/**
 * A line of a check's working-out, read top to bottom like a sum on paper: [op] is the sign in
 * front of it ("−", "÷", "="), [items] what the figure is made of (shown when the line is opened)
 * and [section] where on the Wallet page those come from.
 */
@Immutable
internal data class CheckLedgerRow(
    val op: StringResource?,
    val label: StringResource,
    val amount: UiText,
    val items: List<CheckItem> = emptyList(),
    val section: WalletSection? = null,
    val result: Boolean = false,
)

/**
 * Where the household's figure sits against the rule's line, for a bar: the bar runs 0 to [max],
 * with a tick at [target] (and a fainter one at [stretch], the stronger goal). [higherIsBetter]
 * says which side of the tick is the healthy one.
 */
@Immutable
internal data class CheckGauge(
    val value: Double,
    val target: Double,
    val max: Double,
    val higherIsBetter: Boolean,
    val targetLabel: UiText,
    val stretch: Double? = null,
    val stretchLabel: UiText? = null,
)

/** What a what-if amount would make of the check: the new figure, whether it passes, and a line more. */
@Immutable
internal data class WhatIfOutcome(val headline: UiText, val ok: Boolean, val note: UiText?)

/** A slider over one lever that moves the check, from today's numbers: [outcome] answers for each amount. */
@Immutable
internal class WhatIf(val label: StringResource, val max: Double, val step: Double, val outcome: (Double) -> WhatIfOutcome)

/**
 * One line of the money checkup, with everything its breakdown shows: the figure and how it was
 * worked out from the sheet's accounts, expenses and loans, the rule of thumb it's held to, what
 * would move it (in dollars), and a what-if to try.
 */
@Immutable
internal data class Check(
    val kind: CheckKind,
    val ok: Boolean,
    val title: StringResource,
    /** The household's number, short: "7.1 months", "19.7%". */
    val figure: UiText,
    val detail: UiText,
    val tip: StringResource,
    /** The rule of thumb the figure is held to, in a sentence. */
    val rule: StringResource,
    val explainerId: String,
    val gauge: CheckGauge?,
    val ledger: List<CheckLedgerRow>,
    /** What would improve it, or keep it healthy, with the amounts worked out. */
    val steps: List<UiText>,
    val whatIf: WhatIf?,
    /** What's counted and what isn't, in small print. */
    val note: StringResource? = null,
)

/** The whole checkup: its lines, and the order to work on the flagged ones. */
@Immutable
internal data class Checkup(val checks: List<Check>) {
    val passed: Int get() = checks.count { it.ok }

    /** The flagged lines, most pressing first (see [CheckupPriority]). */
    val toFix: List<Check> get() = CheckupPriority.mapNotNull { k -> checks.firstOrNull { it.kind == k && !it.ok } }

    fun byKind(kind: CheckKind): Check? = checks.firstOrNull { it.kind == kind }
}

/**
 * The usual order of operations: a cash cushion so a surprise doesn't go on a card, then costly
 * debt (paying it is a guaranteed return), then the monthly gap that funds everything, then
 * retirement, then overall debt, which the rest bring down.
 */
internal val CheckupPriority = listOf(CheckKind.EMERGENCY_FUND, CheckKind.COSTLY_DEBT, CheckKind.SAVINGS_RATE, CheckKind.RETIREMENT, CheckKind.DEBT_RATIO)

/** Why a flagged line sits where it does in [CheckupPriority], for the "where to start" list. */
internal fun priorityReason(kind: CheckKind): StringResource = when (kind) {
    CheckKind.EMERGENCY_FUND -> Res.string.checkup_priority_emergency
    CheckKind.COSTLY_DEBT -> Res.string.checkup_priority_costly_debt
    CheckKind.SAVINGS_RATE -> Res.string.checkup_priority_savings
    CheckKind.RETIREMENT -> Res.string.checkup_priority_retirement
    CheckKind.DEBT_RATIO -> Res.string.checkup_priority_debt_ratio
}

private const val EMERGENCY_MONTHS = 3.0
private const val EMERGENCY_STRONG_MONTHS = 6.0
private const val SAVINGS_GOAL = 0.15
private const val SAVINGS_STRETCH = 0.20
private const val DEBT_RATIO_LINE = 0.25
private const val RETIREMENT_SHARE = 0.25
private const val COSTLY_DEBT_FLOOR = 5.0

/** The rate a loan has to beat to be "costly": about what savings earn, never under 5%. */
internal fun costlyDebtHurdle(fedRate: Double?): Double = (fedRate ?: COSTLY_DEBT_FLOOR).coerceAtLeast(COSTLY_DEBT_FLOOR)

/**
 * The household's checkup from the sheet, each line a common rule of thumb. A line whose inputs
 * the sheet doesn't have is left out rather than guessed. [fedRate] (percent) sets the bar for
 * costly debt.
 */
internal fun moneyCheckup(finance: PersonalFinance, fedRate: Double?): Checkup = Checkup(
    listOfNotNull(
        emergencyFund(finance),
        savingsRate(finance),
        costlyDebt(finance, fedRate),
        debtRatio(finance),
        retirement(finance),
    ),
)

private fun money(v: Double) = FinanceFormat.money(v, 0)

/** A month count with one decimal, as the number alone ("7.1"), for sentences that say "months" themselves. */
private fun monthsNumber(m: Double) = FinanceFormat.grouped(m, 1)

/** "7.1 months". */
private fun months(m: Double): UiText = UiText.of(Res.string.checkup_months, monthsNumber(m))

/** "3 mo", under a gauge's tick. */
private fun gaugeMonths(m: Double): UiText = UiText.plural(Res.plurals.checkup_gauge_months, m.toInt())

private fun pct(fraction: Double, decimals: Int = 0) = FinanceFormat.fractionPercent(fraction, decimals)

/** An amount already formatted, as text: a figure, not words. */
private fun figure(formatted: String): UiText = formatted.asUiText()

/** "$1,240 / mo". */
private fun perMonth(v: Double): UiText = UiText.of(Res.string.finance_wallet_per_month, money(v))

/** Names from the sheet as a list: "Credit card, Car loan". */
private fun names(list: List<String>): UiText = UiText.Joined(list.map { it.asUiText() }, UiText.of(Res.string.common_list_separator))

private val dot: UiText = UiText.of(Res.string.common_dot_separator)

private fun roundUp(v: Double, step: Double) = ceil(v / step) * step

private fun accountItem(a: Account) = CheckItem(a.name, UiText.Joined(listOf(a.owner.label, UiText.of(a.category.label)), dot), figure(money(a.balance)))

/**
 * The sheet's expense lines as items, plus a line for whatever of [total] they don't add up to
 * (the sheet's total can count spending it doesn't itemise), so the parts always make the figure.
 */
private fun expenseItems(finance: PersonalFinance, total: Double): List<CheckItem> {
    val items = finance.expenses.map { CheckItem(it.name.asUiText(), null, perMonth(it.monthly)) }
    val rest = total - finance.expenses.sumOf { it.monthly }
    return if (finance.expenses.isNotEmpty() && abs(rest) >= 1) {
        items + CheckItem(UiText.of(Res.string.checkup_not_itemised), UiText.of(Res.string.checkup_not_itemised_detail), perMonth(rest))
    } else {
        items
    }
}

/** The smallest whole-dollar amount at least [v], so following a quoted amount always reaches the line. */
private fun atLeast(v: Double) = money(ceil(v))

/** The smallest whole-dollar amount over [v], for lines that pass only strictly past it. */
private fun over(v: Double) = money(floor(v) + 1)

/** "22%", "24.9%", "3.08%": a rate with no trailing zeros. */
private fun rate(r: Double) = FinanceFormat.grouped(r, 2).trimEnd('0').trimEnd('.') + "%"

private fun Debt.yearlyInterest(): Double? = apr?.let { balance * it / 100 }

private fun debtItem(d: Debt, flagged: Boolean = false) = CheckItem(
    d.name.asUiText(),
    UiText.Joined(
        listOfNotNull(
            d.owner.label,
            d.apr?.let { UiText.of(Res.string.checkup_item_apr, rate(it)) } ?: UiText.of(Res.string.checkup_item_no_rate),
            d.yearlyInterest()?.let { UiText.of(Res.string.checkup_item_interest, money(it)) },
        ),
        dot,
    ),
    figure(money(d.balance)),
    flagged,
)

/** How many months (at least one) until [gap] is covered at [perMonth] a month, or null when nothing's left over. */
private fun monthsAway(gap: Double, perMonth: Double?): Int? {
    if (perMonth == null || perMonth <= 0 || gap <= 0) return null
    return ceil(gap / perMonth).toInt().coerceAtLeast(1)
}

/** Every account by category, biggest first, as items. */
private fun categoryItems(accounts: List<Account>): List<CheckItem> =
    accounts.groupBy { it.category }
        .map { (cat, list) -> cat to list.sumOf { it.balance } }
        .sortedByDescending { it.second }
        .map { (cat, sum) -> CheckItem(UiText.of(cat.label), null, figure(money(sum))) }

private fun emergencyFund(finance: PersonalFinance): Check? {
    val spend = finance.monthlyExpenses?.takeIf { it > 0 } ?: return null
    val cash = finance.liquidCash
    val m = cash / spend
    val ok = m >= EMERGENCY_MONTHS
    val toThree = EMERGENCY_MONTHS * spend - cash
    val toSix = EMERGENCY_STRONG_MONTHS * spend - cash
    val left = finance.netMonthly
    val steps = buildList {
        when {
            m < EMERGENCY_MONTHS -> {
                val away = monthsAway(toThree, left)
                add(
                    if (away == null) {
                        UiText.of(Res.string.checkup_emergency_step_short, atLeast(toThree), money(EMERGENCY_MONTHS * spend))
                    } else {
                        UiText.plural(Res.plurals.checkup_emergency_step_short_away, away, atLeast(toThree), money(EMERGENCY_MONTHS * spend), money(left!!), away)
                    },
                )
                add(UiText.of(Res.string.checkup_emergency_step_trim, money(100.0), money(300.0)))
                add(UiText.of(Res.string.checkup_emergency_step_where))
            }

            m < EMERGENCY_STRONG_MONTHS -> {
                val away = monthsAway(toSix, left)
                add(
                    if (away == null) {
                        UiText.of(Res.string.checkup_emergency_step_stronger, atLeast(toSix))
                    } else {
                        UiText.plural(Res.plurals.checkup_emergency_step_stronger_away, away, atLeast(toSix), money(left!!), away)
                    },
                )
                add(UiText.of(Res.string.checkup_emergency_step_six))
            }

            else -> {
                val extra = -toSix
                if (extra > spend) {
                    add(UiText.of(Res.string.checkup_emergency_step_beyond, money(extra)))
                } else {
                    add(UiText.of(Res.string.checkup_emergency_step_top_up, money(100.0), money(600.0)))
                }
            }
        }
        finance.emergencyTarget?.takeIf { it > 0 }?.let { target ->
            add(UiText.of(Res.string.checkup_emergency_step_target, money(target), pct(cash / target)))
        }
    }
    val cashAccounts = finance.accounts.filter { it.category == AccountCategory.CASH }.sortedByDescending { it.balance }
    return Check(
        kind = CheckKind.EMERGENCY_FUND,
        ok = ok,
        title = Res.string.layman_checkup_emergency_title,
        figure = months(m),
        detail = UiText.of(Res.string.layman_checkup_emergency_detail, monthsNumber(m)),
        tip = when {
            m >= EMERGENCY_STRONG_MONTHS -> Res.string.layman_checkup_emergency_tip_solid
            ok -> Res.string.layman_checkup_emergency_tip_ok
            else -> Res.string.layman_checkup_emergency_tip_short
        },
        rule = Res.string.checkup_emergency_rule,
        explainerId = "runway",
        gauge = CheckGauge(
            m,
            EMERGENCY_MONTHS,
            max(9.0, min(m * 1.15, 24.0)),
            higherIsBetter = true,
            targetLabel = gaugeMonths(EMERGENCY_MONTHS),
            stretch = EMERGENCY_STRONG_MONTHS,
            stretchLabel = gaugeMonths(EMERGENCY_STRONG_MONTHS),
        ),
        ledger = listOf(
            CheckLedgerRow(null, Res.string.checkup_emergency_ledger_cash, figure(money(cash)), cashAccounts.map(::accountItem), WalletSection.ACCOUNTS.takeIf { finance.accounts.isNotEmpty() }),
            CheckLedgerRow(Res.string.checkup_op_divide, Res.string.checkup_emergency_ledger_spending, figure(money(spend)), expenseItems(finance, spend), WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() }),
            CheckLedgerRow(Res.string.checkup_op_equals, Res.string.checkup_emergency_ledger_months, months(m), result = true),
        ),
        steps = steps,
        whatIf = WhatIf(
            label = Res.string.checkup_emergency_whatif,
            max = roundUp(max(toSix, EMERGENCY_MONTHS * spend), 1000.0),
            step = 500.0,
        ) { x ->
            val m2 = (cash + x) / spend
            WhatIfOutcome(
                UiText.of(Res.string.checkup_emergency_whatif_covered, monthsNumber(m2)),
                m2 >= EMERGENCY_MONTHS,
                when {
                    m2 >= EMERGENCY_STRONG_MONTHS -> UiText.of(Res.string.checkup_emergency_whatif_past)
                    m2 >= EMERGENCY_MONTHS -> UiText.of(Res.string.checkup_emergency_whatif_within)
                    else -> UiText.of(Res.string.checkup_emergency_whatif_short, money(EMERGENCY_MONTHS * spend - cash - x))
                },
            )
        },
        note = Res.string.checkup_emergency_note,
    )
}

private fun savingsRate(finance: PersonalFinance): Check? {
    val r = finance.savingsRate ?: return null
    val income = finance.monthlyIncome ?: finance.income.sumOf { it.monthly }.takeIf { it > 0 } ?: return null
    val net = r * income
    val spend = finance.monthlyExpenses ?: (income - net)
    val ok = r >= SAVINGS_GOAL
    val gap = SAVINGS_GOAL * income - net
    val steps = buildList {
        if (!ok) {
            add(UiText.of(Res.string.checkup_savings_step_reach, atLeast(gap)))
            finance.expenses.filter { it.monthly >= gap }.take(2).forEach { e ->
                add(UiText.of(Res.string.checkup_savings_step_trim_expense, e.name, money(e.monthly), pct(gap / e.monthly)))
            }
            add(UiText.of(Res.string.checkup_savings_step_more_pay, atLeast(gap / (1 - SAVINGS_GOAL))))
            add(UiText.of(Res.string.checkup_savings_step_401k))
        } else {
            add(UiText.of(Res.string.checkup_savings_step_above, money(-gap)))
            if (r < SAVINGS_STRETCH) {
                add(UiText.of(Res.string.checkup_savings_step_stretch, money(SAVINGS_STRETCH * income - net)))
            } else {
                add(UiText.of(Res.string.checkup_savings_step_past_stretch))
            }
        }
    }
    val ledger = buildList {
        add(
            CheckLedgerRow(
                null,
                Res.string.checkup_savings_ledger_take_home,
                figure(money(income)),
                finance.income.map { CheckItem(it.person.asUiText(), null, perMonth(it.monthly)) },
                WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() },
            ),
        )
        add(CheckLedgerRow(Res.string.checkup_op_minus, Res.string.checkup_savings_ledger_spending, figure(money(spend)), expenseItems(finance, spend), WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() }))
        // The sheet's own "left over" can differ from income less expenses (a line it adds or leaves out).
        val other = income - spend - net
        if (abs(other) >= 1) add(CheckLedgerRow(Res.string.checkup_op_minus, Res.string.checkup_savings_ledger_other, figure(money(other))))
        add(CheckLedgerRow(Res.string.checkup_op_equals, Res.string.checkup_savings_ledger_left_over, figure(FinanceFormat.signedMoney(net, 0))))
        add(CheckLedgerRow(Res.string.checkup_op_divide, Res.string.checkup_savings_ledger_take_home, figure(money(income))))
        add(CheckLedgerRow(Res.string.checkup_op_equals, Res.string.checkup_savings_ledger_kept, figure(pct(r, 1)), result = true))
    }
    return Check(
        kind = CheckKind.SAVINGS_RATE,
        ok = ok,
        title = Res.string.layman_checkup_saving_title,
        figure = figure(pct(r, 1)),
        detail = UiText.of(Res.string.layman_checkup_saving_detail, pct(r, 1)),
        tip = if (ok) Res.string.layman_checkup_saving_tip_ok else Res.string.layman_checkup_saving_tip_low,
        rule = Res.string.checkup_savings_rule,
        explainerId = "savingsrate",
        gauge = CheckGauge(
            r.coerceAtLeast(0.0),
            SAVINGS_GOAL,
            max(0.35, r * 1.15),
            higherIsBetter = true,
            targetLabel = figure(pct(SAVINGS_GOAL)),
            stretch = SAVINGS_STRETCH,
            stretchLabel = figure(pct(SAVINGS_STRETCH)),
        ),
        ledger = ledger,
        steps = steps,
        whatIf = WhatIf(
            label = Res.string.checkup_savings_whatif,
            max = roundUp(min(max(gap * 1.5, income * 0.1), spend.coerceAtLeast(100.0)), 100.0),
            step = 25.0,
        ) { x ->
            val r2 = (net + x) / income
            WhatIfOutcome(
                UiText.of(Res.string.checkup_savings_whatif_kept, pct(r2, 1)),
                r2 >= SAVINGS_GOAL,
                UiText.of(Res.string.checkup_savings_whatif_left_over, FinanceFormat.signedMoney(net + x, 0)),
            )
        },
    )
}

private fun costlyDebt(finance: PersonalFinance, fedRate: Double?): Check? {
    val open = finance.debts.filter { !it.isMortgage && !it.isPaidOff }.sortedByDescending { it.apr ?: -1.0 }
    if (open.isEmpty()) return null
    // A loan with no rate in the sheet can't be judged, so it neither passes nor fails the line;
    // with no rates at all there's nothing to judge, and the line is left out.
    val unknown = open.filter { it.apr == null }
    if (unknown.size == open.size) return null
    val hurdle = costlyDebtHurdle(fedRate)
    val costly = open.filter { it.apr != null && it.apr > hurdle }
    val ok = costly.isEmpty()
    val hurdleText = rate(hurdle)
    val interest = open.sumOf { it.yearlyInterest() ?: 0.0 }
    val unjudged = if (unknown.isEmpty()) null else UiText.plural(Res.plurals.checkup_debt_unjudged, unknown.size, names(unknown.map { it.name }))
    val spare = finance.monthlyExpenses?.let { finance.liquidCash - EMERGENCY_MONTHS * it }
    val steps = buildList {
        if (!ok) {
            val first = costly.first()
            add(UiText.of(Res.string.checkup_debt_step_pay_first, first.name, rate(first.apr!!), money(first.yearlyInterest()!!)))
            if (costly.size > 1) add(UiText.of(Res.string.checkup_debt_step_then, names(costly.drop(1).map { it.name })))
            if (spare != null && spare > 0) {
                val costlyTotal = costly.sumOf { it.balance }
                add(
                    if (spare >= costlyTotal) {
                        UiText.of(Res.string.checkup_debt_step_spare_clears, money(spare), money(costlyTotal))
                    } else {
                        UiText.of(Res.string.checkup_debt_step_spare_partial, money(spare), first.name, money(costlyTotal - spare))
                    },
                )
            }
        } else {
            add(UiText.of(Res.string.checkup_debt_step_all_below, hurdleText))
        }
        if (interest > 0) add(UiText.of(Res.string.checkup_debt_step_interest, money(interest), money(interest / 12)))
        if (unknown.isNotEmpty()) {
            add(UiText.plural(Res.plurals.checkup_debt_step_add_rate, unknown.size, names(unknown.map { it.name })))
        }
    }
    val maxApr = open.mapNotNull { it.apr }.maxOrNull()
    val none = UiText.of(Res.string.checkup_debt_figure_none)
    val verdict = when {
        !ok -> UiText.of(Res.string.checkup_debt_detail_costly, hurdleText, names(costly.map { it.name }))
        unknown.isEmpty() -> UiText.of(Res.string.layman_checkup_debt_none, hurdleText)
        else -> UiText.of(Res.string.checkup_debt_detail_none_rated, hurdleText)
    }
    return Check(
        kind = CheckKind.COSTLY_DEBT,
        ok = ok,
        title = Res.string.layman_checkup_debt_title,
        figure = if (ok) none else UiText.plural(Res.plurals.checkup_debt_figure_loans, costly.size),
        detail = if (unjudged == null) verdict else UiText.of(Res.string.narrator_two_sentences, verdict, unjudged),
        tip = if (ok) Res.string.layman_checkup_debt_tip_ok else Res.string.layman_checkup_debt_tip_costly,
        rule = Res.string.checkup_debt_rule,
        explainerId = "debt",
        gauge = maxApr?.let { CheckGauge(it, hurdle, max(hurdle * 2, it * 1.2), higherIsBetter = false, targetLabel = figure(hurdleText)) },
        ledger = listOf(
            CheckLedgerRow(null, Res.string.checkup_debt_ledger_loans, figure(money(open.sumOf { it.balance })), open.map { debtItem(it, it in costly) }, WalletSection.DEBT),
            CheckLedgerRow(
                Res.string.checkup_op_versus,
                Res.string.checkup_debt_ledger_line,
                figure(hurdleText),
                listOf(
                    CheckItem(
                        UiText.of(Res.string.checkup_debt_item_fed),
                        UiText.of(Res.string.checkup_debt_item_fed_detail),
                        fedRate?.let { figure(rate(it)) } ?: UiText.of(Res.string.checkup_debt_item_not_loaded),
                    ),
                    CheckItem(UiText.of(Res.string.checkup_debt_item_floor), UiText.of(Res.string.checkup_debt_item_floor_detail), figure(rate(COSTLY_DEBT_FLOOR))),
                ),
            ),
            CheckLedgerRow(
                Res.string.checkup_op_equals,
                Res.string.checkup_debt_ledger_above,
                if (ok) none else UiText.Joined(listOf(figure(costly.size.toString()), figure(money(costly.sumOf { it.balance }))), dot),
                costly.map { debtItem(it, true) },
                result = true,
            ),
        ),
        steps = steps,
        whatIf = WhatIf(label = Res.string.checkup_debt_whatif, max = roundUp(open.sumOf { it.balance }, 500.0), step = 250.0) { x ->
            var left = x
            var saved = 0.0
            val cleared = mutableListOf<String>()
            for (d in open) {
                if (left <= 0) break
                val paid = min(left, d.balance)
                left -= paid
                saved += paid * (d.apr ?: 0.0) / 100
                if (paid >= d.balance) cleared += d.name
            }
            val stillCostly = costly.count { it.name !in cleared }
            WhatIfOutcome(
                UiText.of(Res.string.checkup_debt_whatif_saved, money(saved)),
                stillCostly == 0,
                when {
                    costly.isNotEmpty() && stillCostly == 0 -> UiText.of(Res.string.checkup_debt_whatif_all_cleared)
                    stillCostly > 0 -> UiText.plural(Res.plurals.checkup_debt_whatif_still_costly, stillCostly)
                    cleared.isNotEmpty() -> UiText.of(Res.string.checkup_debt_whatif_paid_off, names(cleared))
                    else -> null
                },
            )
        },
        note = Res.string.checkup_debt_note,
    )
}

private fun debtRatio(finance: PersonalFinance): Check? {
    val assets = finance.totalAssets?.takeIf { it > 0 } ?: return null
    val owed = finance.consumerDebt
    val ratio = owed / assets
    val ok = ratio < DEBT_RATIO_LINE
    val open = finance.debts.filter { !it.isMortgage && !it.isPaidOff }.sortedByDescending { it.balance }
    val fromSavings = (owed - DEBT_RATIO_LINE * assets) / (1 - DEBT_RATIO_LINE)
    val steps = buildList {
        if (!ok) {
            add(UiText.of(Res.string.checkup_ratio_step_from_savings, over(fromSavings)))
            add(UiText.of(Res.string.checkup_ratio_step_from_income, over(owed - DEBT_RATIO_LINE * assets)))
            open.firstOrNull { it.apr != null }?.let { add(UiText.of(Res.string.checkup_ratio_step_highest_rate, open.maxBy { d -> d.apr ?: -1.0 }.name)) }
        } else if (owed == 0.0) {
            add(UiText.of(Res.string.checkup_ratio_step_nothing_owed))
        } else {
            add(UiText.of(Res.string.checkup_ratio_step_margin, money(DEBT_RATIO_LINE * assets - owed)))
            add(UiText.of(Res.string.checkup_ratio_step_assets))
        }
        if (finance.mortgageBalance > 0) {
            add(UiText.of(Res.string.checkup_ratio_step_mortgage, money(finance.mortgageBalance)))
        }
    }
    val accountsSum = finance.accounts.sumOf { it.balance }
    val assetItems = categoryItems(finance.accounts) + listOfNotNull(
        (assets - accountsSum).takeIf { finance.accounts.isNotEmpty() && abs(it) >= 1 }?.let {
            CheckItem(UiText.of(Res.string.checkup_ratio_item_elsewhere), UiText.of(Res.string.checkup_ratio_item_elsewhere_detail), figure(money(it)))
        },
    )
    return Check(
        kind = CheckKind.DEBT_RATIO,
        ok = ok,
        title = Res.string.layman_checkup_ratio_title,
        figure = figure(pct(ratio)),
        detail = UiText.of(Res.string.layman_checkup_ratio_detail, pct(ratio)),
        tip = if (ok) Res.string.layman_checkup_ratio_tip_ok else Res.string.layman_checkup_ratio_tip_high,
        rule = Res.string.checkup_ratio_rule,
        explainerId = "networth",
        gauge = CheckGauge(ratio, DEBT_RATIO_LINE, max(0.5, ratio * 1.2), higherIsBetter = false, targetLabel = figure(pct(DEBT_RATIO_LINE))),
        ledger = listOf(
            CheckLedgerRow(null, Res.string.checkup_ratio_ledger_owed, figure(money(owed)), open.map { debtItem(it) }, WalletSection.DEBT.takeIf { finance.debts.isNotEmpty() }),
            CheckLedgerRow(Res.string.checkup_op_divide, Res.string.checkup_ratio_ledger_owned, figure(money(assets)), assetItems, WalletSection.ACCOUNTS.takeIf { finance.accounts.isNotEmpty() }),
            CheckLedgerRow(Res.string.checkup_op_equals, Res.string.checkup_ratio_ledger_result, figure(pct(ratio)), result = true),
        ),
        steps = steps,
        // Paid from cash, so no more than is owed or than the cash there is.
        whatIf = min(owed, finance.liquidCash).takeIf { it > 0 }?.let { payable ->
            WhatIf(Res.string.checkup_ratio_whatif, payable, 250.0) { amount ->
                val x = amount.coerceIn(0.0, payable)
                val left = assets - x
                val stillOwed = UiText.of(Res.string.checkup_ratio_whatif_still_owed, money(owed - x))
                if (left <= 0) {
                    WhatIfOutcome(UiText.of(Res.string.checkup_ratio_whatif_nothing_left), false, stillOwed)
                } else {
                    val r2 = (owed - x) / left
                    WhatIfOutcome(
                        UiText.of(Res.string.checkup_ratio_whatif_owed_vs_owned, pct(r2)),
                        r2 < DEBT_RATIO_LINE,
                        if (x >= owed) UiText.of(Res.string.checkup_ratio_whatif_all_paid) else stillOwed,
                    )
                }
            }
        },
    )
}

private fun retirement(finance: PersonalFinance): Check? {
    val total = finance.accounts.sumOf { it.balance }.takeIf { it > 0 } ?: return null
    val accounts = finance.accounts.filter { it.category == AccountCategory.RETIREMENT }.sortedByDescending { it.balance }
    val saved = accounts.sumOf { it.balance }
    val share = saved / total
    val ok = share >= RETIREMENT_SHARE
    val toGoal = (RETIREMENT_SHARE * total - saved) / (1 - RETIREMENT_SHARE)
    val steps = buildList {
        if (!ok) {
            val away = monthsAway(toGoal, finance.netMonthly)
            add(
                if (away == null) {
                    UiText.of(Res.string.checkup_retirement_step_reach, atLeast(toGoal))
                } else {
                    UiText.plural(Res.plurals.checkup_retirement_step_reach_away, away, atLeast(toGoal), money(finance.netMonthly!!), away)
                },
            )
            add(UiText.of(Res.string.checkup_retirement_step_401k, money(100.0)))
            add(UiText.of(Res.string.checkup_retirement_step_match))
        } else {
            add(UiText.of(Res.string.layman_checkup_retirement_tip_ok))
        }
        val byPerson = finance.people.map { p -> p to accounts.filter { it.owner == Owner.Person(p) }.sumOf { it.balance } }
        if (byPerson.size >= 2) {
            val (low, high) = byPerson.minBy { it.second } to byPerson.maxBy { it.second }
            if (high.second > 0 && low.second < high.second * 0.5) {
                add(UiText.of(Res.string.checkup_retirement_step_uneven, low.first, money(low.second), high.first, money(high.second)))
            }
        }
    }
    return Check(
        kind = CheckKind.RETIREMENT,
        ok = ok,
        title = Res.string.layman_checkup_retirement_title,
        figure = figure(pct(share)),
        detail = UiText.of(Res.string.layman_checkup_retirement_detail, pct(share)),
        tip = if (ok) Res.string.layman_checkup_retirement_tip_ok else Res.string.layman_checkup_retirement_tip_low,
        rule = Res.string.checkup_retirement_rule,
        explainerId = "allocation",
        gauge = CheckGauge(share, RETIREMENT_SHARE, 1.0, higherIsBetter = true, targetLabel = figure(pct(RETIREMENT_SHARE))),
        ledger = listOf(
            CheckLedgerRow(null, Res.string.checkup_retirement_ledger_accounts, figure(money(saved)), accounts.map(::accountItem), WalletSection.ACCOUNTS),
            CheckLedgerRow(Res.string.checkup_op_divide, Res.string.checkup_retirement_ledger_total, figure(money(total)), categoryItems(finance.accounts), WalletSection.ALLOCATION),
            CheckLedgerRow(Res.string.checkup_op_equals, Res.string.checkup_retirement_ledger_result, figure(pct(share)), result = true),
        ),
        steps = steps,
        whatIf = WhatIf(Res.string.checkup_retirement_whatif, roundUp(max(toGoal * 1.5, total * 0.1), 1000.0), 500.0) { x ->
            val s2 = (saved + x) / (total + x)
            WhatIfOutcome(
                UiText.of(Res.string.checkup_retirement_whatif_saved, pct(s2)),
                s2 >= RETIREMENT_SHARE,
                UiText.of(Res.string.checkup_retirement_whatif_in_accounts, money(saved + x)),
            )
        },
        note = Res.string.checkup_retirement_note,
    )
}
