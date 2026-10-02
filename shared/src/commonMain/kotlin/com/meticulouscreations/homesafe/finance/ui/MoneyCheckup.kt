package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A part of the Wallet page a checkup figure comes from, by its header's list key, to jump to. */
internal enum class WalletSection(val key: String, val label: String) {
    ACCOUNTS("acct-h", "Accounts"),
    ALLOCATION("alloc-h", "Where your money sits"),
    CASH_FLOW("flow-h", "Monthly cash flow"),
    DEBT("debt-h", "Debt"),
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
internal data class CheckItem(val label: String, val detail: String?, val amount: String, val flagged: Boolean = false)

/**
 * A line of a check's working-out, read top to bottom like a sum on paper: [op] is the sign in
 * front of it ("−", "÷", "="), [items] what the figure is made of (shown when the line is opened)
 * and [section] where on the Wallet page those come from.
 */
@Immutable
internal data class CheckLedgerRow(
    val op: String?,
    val label: String,
    val amount: String,
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
    val targetLabel: String,
    val stretch: Double? = null,
    val stretchLabel: String? = null,
)

/** What a what-if amount would make of the check: the new figure, whether it passes, and a line more. */
@Immutable
internal data class WhatIfOutcome(val headline: String, val ok: Boolean, val note: String?)

/** A slider over one lever that moves the check, from today's numbers: [outcome] answers for each amount. */
@Immutable
internal class WhatIf(val label: String, val max: Double, val step: Double, val outcome: (Double) -> WhatIfOutcome)

/**
 * One line of the money checkup, with everything its breakdown shows: the figure and how it was
 * worked out from the sheet's accounts, expenses and loans, the rule of thumb it's held to, what
 * would move it (in dollars), and a what-if to try.
 */
@Immutable
internal data class Check(
    val kind: CheckKind,
    val ok: Boolean,
    val title: String,
    /** The household's number, short: "7.1 months", "19.7%". */
    val figure: String,
    val detail: String,
    val tip: String,
    /** The rule of thumb the figure is held to, in a sentence. */
    val rule: String,
    val explainerId: String,
    val gauge: CheckGauge?,
    val ledger: List<CheckLedgerRow>,
    /** What would improve it, or keep it healthy, with the amounts worked out. */
    val steps: List<String>,
    val whatIf: WhatIf?,
    /** What's counted and what isn't, in small print. */
    val note: String? = null,
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
internal fun priorityReason(kind: CheckKind): String = when (kind) {
    CheckKind.EMERGENCY_FUND -> "A cash cushion comes first, so a surprise bill or a lost job doesn't land on a credit card."
    CheckKind.COSTLY_DEBT -> "High-rate debt next: every dollar paid off earns its interest rate, guaranteed."
    CheckKind.SAVINGS_RATE -> "Then the monthly gap between pay and spending — it's what pays for every other goal."
    CheckKind.RETIREMENT -> "Then tax-advantaged retirement saving, where growth isn't taxed each year."
    CheckKind.DEBT_RATIO -> "Overall debt falls as the rest fall into place."
}

private const val EMERGENCY_MONTHS = 3.0
private const val EMERGENCY_STRONG_MONTHS = 6.0
private const val SAVINGS_GOAL = 0.15
private const val SAVINGS_STRETCH = 0.20
private const val DEBT_RATIO_LINE = 0.25
private const val RETIREMENT_SHARE = 0.25

/** The rate a loan has to beat to be "costly": about what savings earn, never under 5%. */
internal fun costlyDebtHurdle(fedRate: Double?): Double = (fedRate ?: 5.0).coerceAtLeast(5.0)

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

private fun months(m: Double) = FinanceFormat.grouped(m, 1) + " months"

private fun pct(fraction: Double, decimals: Int = 0) = FinanceFormat.fractionPercent(fraction, decimals)

private fun roundUp(v: Double, step: Double) = ceil(v / step) * step

private fun accountItem(a: Account) = CheckItem(a.name, "${a.owner.label} · ${a.category.label}", money(a.balance))

private fun expenseItems(finance: PersonalFinance) = finance.expenses.map { CheckItem(it.name, null, money(it.monthly) + " / mo") }

/** "22%", "24.9%", "3.08%": a rate with no trailing zeros. */
private fun rate(r: Double) = FinanceFormat.grouped(r, 2).trimEnd('0').trimEnd('.') + "%"

private fun Debt.yearlyInterest(): Double? = apr?.let { balance * it / 100 }

private fun debtItem(d: Debt, flagged: Boolean = false) = CheckItem(
    d.name,
    listOfNotNull(
        d.owner.label,
        d.apr?.let { "${rate(it)} APR" } ?: "rate not in the sheet",
        d.yearlyInterest()?.let { "~${money(it)} interest a year" },
    ).joinToString(" · "),
    money(d.balance),
    flagged,
)

/** "about N months" until [gap] is covered at [perMonth] a month, or null when nothing's left over. */
private fun monthsAway(gap: Double, perMonth: Double?): String? {
    if (perMonth == null || perMonth <= 0 || gap <= 0) return null
    val n = ceil(gap / perMonth).toInt()
    return if (n <= 1) "about a month" else "about $n months"
}

/** Every account by category, biggest first, as items. */
private fun categoryItems(accounts: List<Account>): List<CheckItem> =
    accounts.groupBy { it.category }
        .map { (cat, list) -> cat to list.sumOf { it.balance } }
        .sortedByDescending { it.second }
        .map { (cat, sum) -> CheckItem(cat.label, null, money(sum)) }

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
                add("Set aside ${money(toThree)} more to reach 3 months (${money(EMERGENCY_MONTHS * spend)} in all)." + (monthsAway(toThree, left)?.let { " Saving all of the ${money(left!!)} left over each month, that's $it away." } ?: ""))
                add("Spending less shrinks the goal too: every ${money(100.0)} a month trimmed lowers a 3-month cushion by ${money(300.0)}.")
                add("Keep it somewhere easy to reach that pays interest, like a high-yield savings account.")
            }

            m < EMERGENCY_STRONG_MONTHS -> {
                add("${money(toSix)} more would reach the stronger 6-month cushion." + (monthsAway(toSix, left)?.let { " At ${money(left!!)} left over a month, $it." } ?: ""))
                add("Toward six months matters most if one income carries the household or work is uncertain.")
            }

            else -> {
                val extra = -toSix
                if (extra > spend) {
                    add("About ${money(extra)} sits beyond a 6-month cushion — money that could earn more invested, or pay down costly debt.")
                } else {
                    add("Keep it topped up as spending grows: each ${money(100.0)} a month of new spending adds ${money(600.0)} to a 6-month cushion.")
                }
            }
        }
        finance.emergencyTarget?.takeIf { it > 0 }?.let { target ->
            add("Your sheet's own emergency target is ${money(target)}: you have ${pct(cash / target)} of it.")
        }
    }
    val cashAccounts = finance.accounts.filter { it.category == AccountCategory.CASH }.sortedByDescending { it.balance }
    return Check(
        kind = CheckKind.EMERGENCY_FUND,
        ok = ok,
        title = "Emergency fund",
        figure = months(m),
        detail = "Your cash covers ${months(m)} of expenses.",
        tip = when {
            m >= EMERGENCY_STRONG_MONTHS -> "Comfortably past the 3–6 months planners suggest."
            ok -> "Within the 3–6 months planners suggest; toward six is safer if one job carries the household."
            else -> "Planners suggest 3–6 months; building cash first protects you against a layoff."
        },
        rule = "Planners suggest keeping 3 to 6 months of spending in cash. The checkup passes from 3.",
        explainerId = "runway",
        gauge = CheckGauge(m, EMERGENCY_MONTHS, max(9.0, min(m * 1.15, 24.0)), higherIsBetter = true, targetLabel = "3 mo", stretch = EMERGENCY_STRONG_MONTHS, stretchLabel = "6 mo"),
        ledger = listOf(
            CheckLedgerRow(null, "Cash in the bank", money(cash), cashAccounts.map(::accountItem), WalletSection.ACCOUNTS.takeIf { finance.accounts.isNotEmpty() }),
            CheckLedgerRow("÷", "Spending each month", money(spend), expenseItems(finance), WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() }),
            CheckLedgerRow("=", "Months covered", months(m), result = true),
        ),
        steps = steps,
        whatIf = WhatIf(
            label = "Add to cash",
            max = roundUp(max(toSix, EMERGENCY_MONTHS * spend), 1000.0),
            step = 500.0,
        ) { x ->
            val m2 = (cash + x) / spend
            WhatIfOutcome(
                "${months(m2)} covered",
                m2 >= EMERGENCY_MONTHS,
                when {
                    m2 >= EMERGENCY_STRONG_MONTHS -> "Past the 6-month cushion."
                    m2 >= EMERGENCY_MONTHS -> "Within the 3–6 month range."
                    else -> "${money(EMERGENCY_MONTHS * spend - cash - x)} short of 3 months."
                },
            )
        },
        note = "Only accounts the sheet names as cash (checking, savings) count: investments can be down just when you'd need them.",
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
            add("Keeping ${money(gap)} more a month reaches 15%.")
            finance.expenses.filter { it.monthly >= gap }.take(2).forEach { e ->
                add("${e.name} is ${money(e.monthly)} a month — trimming it by ${pct(gap / e.monthly)} would cover that on its own.")
            }
            add("More pay counts too: ${money(gap / (1 - SAVINGS_GOAL))} more take-home a month, with spending held where it is, gets there.")
            add("401(k) contributions taken from your paycheck aren't in take-home, so they count on top of this.")
        } else {
            add("You keep ${money(-gap)} a month more than the 15% line.")
            if (r < SAVINGS_STRETCH) {
                add("${money(SAVINGS_STRETCH * income - net)} more a month reaches 20%, the stretch goal.")
            } else {
                add("Past the 20% stretch goal too — the gap is doing real work. Make sure it's being invested, not piling up in checking.")
            }
        }
    }
    val ledger = buildList {
        add(CheckLedgerRow(null, "Take-home pay", money(income), finance.income.map { CheckItem(it.person, null, money(it.monthly) + " / mo") }, WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() }))
        add(CheckLedgerRow("−", "Spending", money(spend), expenseItems(finance), WalletSection.CASH_FLOW.takeIf { finance.expenses.isNotEmpty() }))
        // The sheet's own "left over" can differ from income less expenses (a line it adds or leaves out).
        val other = income - spend - net
        if (abs(other) >= 1) add(CheckLedgerRow("−", "Other differences in the sheet", money(other)))
        add(CheckLedgerRow("=", "Left over", FinanceFormat.signedMoney(net, 0)))
        add(CheckLedgerRow("÷", "Take-home pay", money(income)))
        add(CheckLedgerRow("=", "Kept each month", pct(r, 1), result = true))
    }
    return Check(
        kind = CheckKind.SAVINGS_RATE,
        ok = ok,
        title = "Saving each month",
        figure = pct(r, 1),
        detail = "You keep ${pct(r, 1)} of take-home pay after expenses.",
        tip = if (ok) "At or above the common 15% goal." else "The common goal is 15–20% (401(k) contributions taken from your paycheck count on top). Trimming a big recurring expense moves this most.",
        rule = "A common goal is keeping 15% of take-home pay, with 20% a stretch. The checkup passes from 15%.",
        explainerId = "savingsrate",
        gauge = CheckGauge(r.coerceAtLeast(0.0), SAVINGS_GOAL, max(0.35, r * 1.15), higherIsBetter = true, targetLabel = "15%", stretch = SAVINGS_STRETCH, stretchLabel = "20%"),
        ledger = ledger,
        steps = steps,
        whatIf = WhatIf(
            label = "Spend less each month",
            max = roundUp(min(max(gap * 1.5, income * 0.1), spend.coerceAtLeast(100.0)), 100.0),
            step = 25.0,
        ) { x ->
            val r2 = (net + x) / income
            WhatIfOutcome("${pct(r2, 1)} kept", r2 >= SAVINGS_GOAL, "${FinanceFormat.signedMoney(net + x, 0)} left over a month.")
        },
    )
}

private fun costlyDebt(finance: PersonalFinance, fedRate: Double?): Check? {
    val open = finance.debts.filter { !it.isMortgage && !it.isPaidOff }.sortedByDescending { it.apr ?: -1.0 }
    if (open.isEmpty()) return null
    val hurdle = costlyDebtHurdle(fedRate)
    val costly = open.filter { (it.apr ?: 0.0) > hurdle }
    val ok = costly.isEmpty()
    val hurdleText = rate(hurdle)
    val interest = open.sumOf { it.yearlyInterest() ?: 0.0 }
    val unknown = open.filter { it.apr == null }
    val spare = finance.monthlyExpenses?.let { finance.liquidCash - EMERGENCY_MONTHS * it }
    val steps = buildList {
        if (!ok) {
            val first = costly.first()
            add("Pay ${first.name} first — at ${rate(first.apr!!)} it costs about ${money(first.yearlyInterest()!!)} a year. Paying it off early is a guaranteed ${rate(first.apr)} return.")
            if (costly.size > 1) add("Then ${costly.drop(1).joinToString { it.name }}: highest rate first saves the most interest.")
            if (spare != null && spare > 0) {
                val costlyTotal = costly.sumOf { it.balance }
                add(
                    if (spare >= costlyTotal) {
                        "You have ${money(spare)} in cash beyond a 3-month cushion — enough to clear every loan above the line (${money(costlyTotal)}) and keep the cushion."
                    } else {
                        "You have ${money(spare)} in cash beyond a 3-month cushion; putting it toward ${first.name} would leave ${money(costlyTotal - spare)} above the line."
                    },
                )
            }
        } else {
            add("Every loan costs less than the $hurdleText line, so paying them on schedule is reasonable while savings earn about as much.")
        }
        if (interest > 0) add("Altogether your loans cost about ${money(interest)} a year in interest (${money(interest / 12)} a month).")
        if (unknown.isNotEmpty()) {
            add("${unknown.joinToString { it.name }} ${if (unknown.size == 1) "has" else "have"} no rate in the sheet — add it to the name, like \"Car loan (APR 3.4%)\", so the checkup can judge ${if (unknown.size == 1) "it" else "them"}.")
        }
    }
    val maxApr = open.mapNotNull { it.apr }.maxOrNull()
    return Check(
        kind = CheckKind.COSTLY_DEBT,
        ok = ok,
        title = "Costly debt",
        figure = if (ok) "None" else "${costly.size} loan${if (costly.size == 1) "" else "s"}",
        detail = if (ok) "None of your loans charge more than about $hurdleText." else "Charging more than $hurdleText: ${costly.joinToString { it.name }}.",
        tip = if (ok) "Low-rate loans can be paid on schedule while savings earn about as much." else "Paying these down early is a guaranteed return equal to their rate — usually better than savings pay.",
        rule = "A loan is costly when its rate beats what savings earn — about the Fed's rate, and never under 5%. The mortgage isn't counted.",
        explainerId = "debt",
        gauge = maxApr?.let { CheckGauge(it, hurdle, max(hurdle * 2, it * 1.2), higherIsBetter = false, targetLabel = hurdleText) },
        ledger = listOf(
            CheckLedgerRow(null, "Loans you're paying", money(open.sumOf { it.balance }), open.map { debtItem(it, it in costly) }, WalletSection.DEBT),
            CheckLedgerRow(
                "vs",
                "The costly line",
                hurdleText,
                listOf(
                    CheckItem("Fed funds rate today", "roughly what savings accounts pay", fedRate?.let(::rate) ?: "not loaded"),
                    CheckItem("Floor", "the line never drops below this", "5%"),
                ),
            ),
            CheckLedgerRow("=", "Above the line", if (ok) "None" else "${costly.size} · ${money(costly.sumOf { it.balance })}", costly.map { debtItem(it, true) }, result = true),
        ),
        steps = steps,
        whatIf = WhatIf(label = "Pay toward loans now, highest rate first", max = roundUp(open.sumOf { it.balance }, 500.0), step = 250.0) { x ->
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
                "${money(saved)} a year less interest",
                stillCostly == 0,
                when {
                    costly.isNotEmpty() && stillCostly == 0 -> "Every loan above the line is cleared."
                    stillCostly > 0 -> "$stillCostly loan${if (stillCostly == 1) "" else "s"} still above the line."
                    cleared.isNotEmpty() -> "${cleared.joinToString()} paid off."
                    else -> null
                },
            )
        },
        note = "A loan's rate is read from its name in the sheet. Paying early only beats saving when the rate is higher than savings pay.",
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
            add("Paying down ${money(fromSavings)} from savings would bring it under 25% (savings shrink too, so it takes a little more than the gap).")
            add("Or ${money(owed - DEBT_RATIO_LINE * assets)} paid down from income over time, leaving savings where they are.")
            open.firstOrNull { it.apr != null }?.let { add("Start with the highest-rate loan, ${open.maxBy { d -> d.apr ?: -1.0 }.name}, to save the most interest on the way.") }
        } else if (owed == 0.0) {
            add("Nothing owed besides the mortgage.")
        } else {
            add("A comfortable margin: debts would have to grow by ${money(DEBT_RATIO_LINE * assets - owed)} to reach the 25% line.")
            add("Assets growing helps as much as debt shrinking — both move this the right way.")
        }
        if (finance.mortgageBalance > 0) {
            add("The mortgage (${money(finance.mortgageBalance)}) isn't counted: the house counts as the equity built in it rather than its full value, as the sheet does.")
        }
    }
    val accountsSum = finance.accounts.sumOf { it.balance }
    val assetItems = categoryItems(finance.accounts) + listOfNotNull(
        (assets - accountsSum).takeIf { finance.accounts.isNotEmpty() && abs(it) >= 1 }?.let { CheckItem("Elsewhere in the sheet's total", "not in the accounts list", money(it)) },
    )
    return Check(
        kind = CheckKind.DEBT_RATIO,
        ok = ok,
        title = "Debt vs what you own",
        figure = pct(ratio),
        detail = "You owe ${pct(ratio)} as much as you own (not counting the mortgage).",
        tip = if (ok) "A comfortable margin." else "Over a quarter is worth bringing down.",
        rule = "Owing under a quarter of what you own leaves room for a bad year. The mortgage is left out, as the sheet leaves it out of net worth.",
        explainerId = "networth",
        gauge = CheckGauge(ratio, DEBT_RATIO_LINE, max(0.5, ratio * 1.2), higherIsBetter = false, targetLabel = "25%"),
        ledger = listOf(
            CheckLedgerRow(null, "Owed, not counting the mortgage", money(owed), open.map { debtItem(it) }, WalletSection.DEBT.takeIf { finance.debts.isNotEmpty() }),
            CheckLedgerRow("÷", "What you own", money(assets), assetItems, WalletSection.ACCOUNTS.takeIf { finance.accounts.isNotEmpty() }),
            CheckLedgerRow("=", "Owed for what you own", pct(ratio), result = true),
        ),
        steps = steps,
        whatIf = if (owed > 0) {
            WhatIf("Pay down from savings", roundUp(owed, 500.0), 250.0) { x ->
                val r2 = (owed - x) / (assets - x)
                WhatIfOutcome("${pct(r2)} owed vs owned", r2 < DEBT_RATIO_LINE, "${money(owed - x)} still owed.")
            }
        } else {
            null
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
            add("Adding ${money(toGoal)} to retirement accounts would reach 25%." + (monthsAway(toGoal, finance.netMonthly)?.let { " Putting all of the ${money(finance.netMonthly!!)} left over each month in, that's $it." } ?: ""))
            add("A traditional 401(k) takes contributions before tax, so each ${money(100.0)} saved costs less than ${money(100.0)} of take-home.")
            add("Take any employer match in full first — it's part of your pay.")
        } else {
            add("Tax-advantaged accounts are doing a lot of the work.")
        }
        val byPerson = finance.people.map { p -> p to accounts.filter { it.owner.label == p }.sumOf { it.balance } }
        if (byPerson.size >= 2) {
            val (low, high) = byPerson.minBy { it.second } to byPerson.maxBy { it.second }
            if (high.second > 0 && low.second < high.second * 0.5) {
                add("${low.first} has ${money(low.second)} in retirement accounts to ${high.first}'s ${money(high.second)} — worth checking both are getting any employer match.")
            }
        }
    }
    return Check(
        kind = CheckKind.RETIREMENT,
        ok = ok,
        title = "Saving for later",
        figure = pct(share),
        detail = "${pct(share)} of what you own is in retirement accounts.",
        tip = if (ok) "Tax-advantaged accounts are doing a lot of the work." else "Retirement accounts grow tax-free or tax-deferred; topping them up is often the cheapest way to invest.",
        rule = "With a quarter or more of what you own in retirement accounts, tax-free growth is doing a good share of the work.",
        explainerId = "allocation",
        gauge = CheckGauge(share, RETIREMENT_SHARE, 1.0, higherIsBetter = true, targetLabel = "25%"),
        ledger = listOf(
            CheckLedgerRow(null, "Retirement accounts", money(saved), accounts.map(::accountItem), WalletSection.ACCOUNTS),
            CheckLedgerRow("÷", "Everything in your accounts", money(total), categoryItems(finance.accounts), WalletSection.ALLOCATION),
            CheckLedgerRow("=", "Saved for later", pct(share), result = true),
        ),
        steps = steps,
        whatIf = WhatIf("Add to retirement accounts", roundUp(max(toGoal * 1.5, total * 0.1), 1000.0), 500.0) { x ->
            val s2 = (saved + x) / (total + x)
            WhatIfOutcome("${pct(s2)} saved for later", s2 >= RETIREMENT_SHARE, "${money(saved + x)} in retirement accounts.")
        },
        note = "Retirement accounts are told apart by their names in the sheet (401(k), IRA, Roth…). Home equity counts toward what you own.",
    )
}
