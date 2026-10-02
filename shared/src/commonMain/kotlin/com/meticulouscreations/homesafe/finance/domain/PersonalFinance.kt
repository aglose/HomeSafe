package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_category_cash
import homesafe.shared.generated.resources.fin_data_category_education
import homesafe.shared.generated.resources.fin_data_category_home
import homesafe.shared.generated.resources.fin_data_category_investing
import homesafe.shared.generated.resources.fin_data_category_retirement
import homesafe.shared.generated.resources.fin_data_owner_joint
import org.jetbrains.compose.resources.StringResource

/** Whose an account or a debt is: one person's, or both (a merged cell across the people's columns). */
@Immutable
sealed interface Owner {
    val label: UiText

    data class Person(val name: String) : Owner {
        override val label: UiText get() = name.asUiText()
    }

    data object Joint : Owner {
        override val label: UiText get() = UiText.of(Res.string.fin_data_owner_joint)
    }
}

/** What an account is for, guessed from its name; how the allocation donut groups them. */
enum class AccountCategory(val label: StringResource) {
    RETIREMENT(Res.string.fin_data_category_retirement),
    INVESTING(Res.string.fin_data_category_investing),
    EDUCATION(Res.string.fin_data_category_education),
    CASH(Res.string.fin_data_category_cash),
    HOME(Res.string.fin_data_category_home),
}

/** An account; [name] is the sheet's own label for it, or the app's words for one it adds (the home's equity). */
@Immutable
data class Account(val name: UiText, val owner: Owner, val balance: Double, val category: AccountCategory) {
    /** An account named as the sheet names it. */
    constructor(name: String, owner: Owner, balance: Double, category: AccountCategory) : this(name.asUiText(), owner, balance, category)
}

@Immutable
data class ExpenseLine(val name: String, val monthly: Double)

@Immutable
data class IncomeLine(val person: String, val monthly: Double)

/** A debt; [balance] is what's owed, positive. [apr] as a percentage when the label gives one. */
@Immutable
data class Debt(val name: String, val owner: Owner, val balance: Double, val apr: Double?, val note: String?) {
    /**
     * The house's loan. The sheet counts the house as the equity built in it rather than its full
     * value, so the mortgage against that full value stays out of net worth (as the sheet's own
     * history leaves it out of "Debt").
     */
    val isMortgage: Boolean get() = name.contains("mortgage", ignoreCase = true)

    val isPaidOff: Boolean get() = balance == 0.0
}

/** One row of the sheet's running history: a dated snapshot of the household's books. */
@Immutable
data class Snapshot(
    val epochSeconds: Long,
    val monthlyExpenses: Double?,
    val monthlyIncome: Double?,
    val monthlyProfit: Double?,
    val totalAssets: Double?,
    /** Positive: what was owed. */
    val debt: Double?,
) {
    val netWorth: Double? get() = totalAssets?.let { a -> a - (debt ?: 0.0) }
}

/** An RSU or ESPP payout on its way. */
@Immutable
data class VestEvent(val epochSeconds: Long?, val label: String, val type: String, val amount: Double, val postTax: Double?)

@Immutable
data class HomeEquity(
    val value: Double?,
    val deposit: Double?,
    val originalLoan: Double?,
    val unpaidPrincipal: Double?,
    val principalPaid: Double?,
    val equity: Double?,
    val valueAdded: Double?,
    val estimatedAssetValue: Double?,
    val improvements: List<ExpenseLine>,
)

@Immutable
data class TaxYear(
    val year: Int,
    val incomePreTax: Double?,
    val taxes: Double?,
    val takeHome: Double?,
    /** 0–1. */
    val effectiveRate: Double?,
    val invested: Double?,
    /** 0–1, of take-home. */
    val investedRate: Double?,
)

/** The sheet's mortgage calculator inputs, which seed the interactive one. Rate as a fraction. */
@Immutable
data class MortgagePlan(
    val homePrice: Double,
    val downPaymentFraction: Double,
    val rate: Double,
    val termYears: Int,
    val propertyTaxAnnual: Double,
    val insuranceAnnual: Double,
    val hoaMonthly: Double,
    /** The sheet's "Anticipated monthly expenses" rows: when, and the mortgage the budget could carry then. */
    val affordability: List<AffordabilityPoint>,
)

@Immutable
data class AffordabilityPoint(val label: String, val monthlyExpenses: Double?, val affordableMortgage: Double)

@Immutable
data class HouseSale(val purchasePrice: Double?, val soldPrice: Double?, val profit: Double?, val roi: Double?, val cashReceived: Double?)

/**
 * The household's books as the budget sheet has them. Every part is optional: a section the
 * parser couldn't find is null or empty and its card stays off the screen, so editing the sheet
 * can hide a card but never break the page.
 */
@Immutable
data class PersonalFinance(
    val title: String,
    val fetchedAtEpochSeconds: Long,
    /** The sheet in Google Sheets, for an "Open sheet" link; from the relay. */
    val sourceUrl: String?,
    val people: List<String>,
    val income: List<IncomeLine>,
    val monthlyIncome: Double?,
    val monthlyExpenses: Double?,
    val netMonthly: Double?,
    val expenses: List<ExpenseLine>,
    val accounts: List<Account>,
    val investmentsTotal: Double?,
    val totalAssets: Double?,
    val emergencyTarget: Double?,
    val debts: List<Debt>,
    val home: HomeEquity?,
    val vesting: List<VestEvent>,
    val watchlist: List<String>,
    val history: List<Snapshot>,
    val taxYears: List<TaxYear>,
    val mortgagePlan: MortgagePlan?,
    val oldHouse: HouseSale?,
    /** The sheet's own charts, in the order they sit in it. */
    val charts: List<SheetChart> = emptyList(),
    /** How this read of the sheet went, part by part (see [SheetHealth]). */
    val health: SheetHealth = SheetHealth.Unchecked,
) {
    /** Everything owed but the mortgage (see [Debt.isMortgage]): what net worth subtracts. */
    val consumerDebt: Double get() = debts.filterNot { it.isMortgage }.sumOf { it.balance }

    val mortgageBalance: Double get() = debts.filter { it.isMortgage }.sumOf { it.balance }

    /** Assets less debt, the headline number, counted the sheet's way. */
    val netWorth: Double? get() = totalAssets?.let { it - consumerDebt }

    val liquidCash: Double get() = accounts.filter { it.category == AccountCategory.CASH }.sumOf { it.balance }

    /** Months the cash would cover the monthly expenses. */
    val runwayMonths: Double? get() = monthlyExpenses?.takeIf { it > 0 }?.let { liquidCash / it }

    /** Share of take-home income left over each month, 0–1. */
    val savingsRate: Double? get() {
        val inc = monthlyIncome ?: income.sumOf { it.monthly }.takeIf { it > 0 } ?: return null
        val net = netMonthly ?: monthlyExpenses?.let { inc - it } ?: return null
        return net / inc
    }

    fun accountsTotal(owner: Owner): Double = accounts.filter { it.owner == owner }.sumOf { it.balance }
}
