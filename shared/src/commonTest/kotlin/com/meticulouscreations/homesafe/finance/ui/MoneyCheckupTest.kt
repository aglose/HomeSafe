package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Contribution
import com.meticulouscreations.homesafe.finance.domain.ContributionYear
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.domain.IncomeLine
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.TaxYear
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.checkup_debt_detail_costly
import homesafe.shared.generated.resources.checkup_debt_detail_none_rated
import homesafe.shared.generated.resources.checkup_debt_step_pay_first
import homesafe.shared.generated.resources.checkup_debt_unjudged
import homesafe.shared.generated.resources.checkup_debt_whatif_saved
import homesafe.shared.generated.resources.checkup_emergency_step_short_away
import homesafe.shared.generated.resources.checkup_months
import homesafe.shared.generated.resources.checkup_not_itemised
import homesafe.shared.generated.resources.checkup_ratio_item_elsewhere
import homesafe.shared.generated.resources.checkup_ratio_step_from_savings
import homesafe.shared.generated.resources.checkup_ratio_step_mortgage
import homesafe.shared.generated.resources.checkup_ratio_whatif_still_owed
import homesafe.shared.generated.resources.checkup_retirement_step_reach_away
import homesafe.shared.generated.resources.checkup_retirement_step_uneven
import homesafe.shared.generated.resources.checkup_savings_item_in_year
import homesafe.shared.generated.resources.checkup_savings_ledger_kept
import homesafe.shared.generated.resources.checkup_savings_ledger_other
import homesafe.shared.generated.resources.checkup_savings_ledger_pay
import homesafe.shared.generated.resources.checkup_savings_ledger_saved
import homesafe.shared.generated.resources.checkup_savings_ledger_set_aside
import homesafe.shared.generated.resources.checkup_savings_ledger_share
import homesafe.shared.generated.resources.checkup_savings_ledger_spending
import homesafe.shared.generated.resources.checkup_savings_note_paycheck
import homesafe.shared.generated.resources.checkup_savings_rule_with_paycheck
import homesafe.shared.generated.resources.checkup_savings_step_401k
import homesafe.shared.generated.resources.checkup_savings_step_above
import homesafe.shared.generated.resources.checkup_savings_step_reach
import homesafe.shared.generated.resources.checkup_savings_step_sheet_year
import homesafe.shared.generated.resources.checkup_savings_step_trim_expense
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.fin_data_owner_joint
import homesafe.shared.generated.resources.finance_wallet_per_month
import homesafe.shared.generated.resources.layman_checkup_saving_detail_with_paycheck
import homesafe.shared.generated.resources.narrator_two_sentences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The checkup's lines and the dollar amounts its breakdowns quote, from a household small enough to check by hand. */
class MoneyCheckupTest {

    /**
     * $10,000 take-home, $9,000 spent ($1,000 left over, 10%); $18,000 cash (2 months); a 22% card
     * and a 3% car loan ($15,000 owed against $100,000 owned, 15%); $20,000 of $88,000 in
     * retirement accounts (22.7%).
     */
    private fun household(
        monthlyExpenses: Double? = 9_000.0,
        netMonthly: Double? = 1_000.0,
        debts: List<Debt> = listOf(
            Debt("Credit card", Owner.Joint, 5_000.0, 22.0, null),
            Debt("Car loan", Owner.Person("Sam"), 10_000.0, 3.0, null),
            Debt("Mortgage", Owner.Joint, 300_000.0, 6.0, null),
        ),
        contributions: List<ContributionYear> = emptyList(),
        taxYears: List<TaxYear> = emptyList(),
    ) = PersonalFinance(
        title = "Test",
        fetchedAtEpochSeconds = 0,
        sourceUrl = null,
        people = listOf("Alex", "Sam"),
        income = listOf(IncomeLine("Alex", 4_000.0), IncomeLine("Sam", 6_000.0)),
        monthlyIncome = 10_000.0,
        monthlyExpenses = monthlyExpenses,
        netMonthly = netMonthly,
        expenses = listOf(ExpenseLine("Rent", 4_000.0), ExpenseLine("Daycare", 2_000.0), ExpenseLine("Groceries", 1_000.0)),
        accounts = listOf(
            Account("Checking", Owner.Joint, 6_000.0, AccountCategory.CASH),
            Account("Savings", Owner.Joint, 12_000.0, AccountCategory.CASH),
            Account("401(k)", Owner.Person("Sam"), 20_000.0, AccountCategory.RETIREMENT),
            Account("Brokerage", Owner.Joint, 50_000.0, AccountCategory.INVESTING),
        ),
        investmentsTotal = null,
        totalAssets = 100_000.0,
        emergencyTarget = null,
        debts = debts,
        home = null,
        vesting = emptyList(),
        watchlist = emptyList(),
        history = emptyList(),
        taxYears = taxYears,
        mortgagePlan = null,
        oldHouse = null,
        contributions = contributions,
    )

    private val checkup = moneyCheckup(household(), fedRate = 4.33)

    private fun check(kind: CheckKind) = checkup.byKind(kind)!!

    /** Names from the sheet as the checkup lists them. */
    private fun names(vararg names: String): UiText = UiText.Joined(names.map { it.asUiText() }, UiText.of(Res.string.common_list_separator))

    @Test
    fun listsEveryLineAndOrdersTheFlaggedOnesCushionFirst() {
        assertEquals(CheckKind.entries, checkup.checks.map { it.kind })
        assertEquals(1, checkup.passed)
        assertEquals(listOf(CheckKind.EMERGENCY_FUND, CheckKind.COSTLY_DEBT, CheckKind.SAVINGS_RATE, CheckKind.RETIREMENT), checkup.toFix.map { it.kind })
    }

    @Test
    fun theEmergencyFundShowsTheCashAccountsAndHowFarFromThreeMonths() {
        val c = check(CheckKind.EMERGENCY_FUND)
        assertFalse(c.ok)
        assertEquals(UiText.of(Res.string.checkup_months, "2.0"), c.figure)
        val cash = c.ledger.first()
        assertEquals("$18,000".asUiText(), cash.amount)
        assertEquals(listOf("Savings".asUiText(), "Checking".asUiText()), cash.items.map { it.label }, "biggest first")
        assertEquals(WalletSection.ACCOUNTS, cash.section)
        assertEquals(WalletSection.CASH_FLOW, c.ledger[1].section)
        // "Set aside $9,000 more to reach 3 months ($27,000 in all). Saving all of the $1,000 left over each month, that's about 9 months away."
        assertEquals(UiText.plural(Res.plurals.checkup_emergency_step_short_away, 9, "$9,000", "$27,000", "$1,000", 9), c.steps.first())
        assertFalse(c.whatIf!!.outcome(8_500.0).ok)
        assertTrue(c.whatIf.outcome(9_000.0).ok)
    }

    @Test
    fun theSavingsRateSaysHowMuchMoreAMonthReaches15Percent() {
        val c = check(CheckKind.SAVINGS_RATE)
        assertEquals("10.0%".asUiText(), c.figure)
        assertEquals(UiText.of(Res.string.checkup_savings_step_reach, "$500"), c.steps.first())
        assertEquals(UiText.of(Res.string.checkup_savings_step_trim_expense, "Rent", "$4,000", "13%"), c.steps[1])
        assertEquals(Res.string.checkup_savings_ledger_kept, c.ledger.last().label)
        assertTrue(c.ledger.none { it.label == Res.string.checkup_savings_ledger_other }, "income less spending is the sheet's left over")
        assertFalse(c.whatIf!!.outcome(475.0).ok)
        assertTrue(c.whatIf.outcome(500.0).ok)
    }

    @Test
    fun aSheetLeftOverThatDisagreesWithTheSumGetsItsOwnLine() {
        val c = moneyCheckup(household(netMonthly = 800.0), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        val other = c.ledger.single { it.label == Res.string.checkup_savings_ledger_other }
        assertEquals("$200".asUiText(), other.amount)
    }

    /** $24,000 a year into the 401(k)s, $2,000 a month that never reaches take-home, and $6,000 into a brokerage account that does. */
    private val invested = listOf(
        ContributionYear(2024, listOf(Contribution("401k", Owner.Person("Sam"), 60_000.0))),
        ContributionYear(
            2025,
            listOf(
                Contribution("401k", Owner.Person("Alex"), 9_600.0),
                Contribution("401k", Owner.Person("Sam"), 14_400.0),
                Contribution("Joint Brokerage", Owner.Joint, 6_000.0),
            ),
        ),
    )

    @Test
    fun moneyTakenFromThePaycheckCountsAsSavedAndAsPay() {
        val c = moneyCheckup(household(contributions = invested), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        // $1,000 left over + $2,000 set aside, of $10,000 take-home + $2,000 set aside.
        assertEquals("25.0%".asUiText(), c.figure)
        assertTrue(c.ok)
        assertEquals(UiText.of(Res.string.layman_checkup_saving_detail_with_paycheck, "25.0%", "$2,000"), c.detail)
        assertEquals(Res.string.checkup_savings_rule_with_paycheck, c.rule)
        assertEquals(Res.string.checkup_savings_note_paycheck, c.note)
        val setAside = c.ledger.single { it.label == Res.string.checkup_savings_ledger_set_aside }
        assertEquals("$2,000".asUiText(), setAside.amount)
        assertEquals(listOf("401k".asUiText(), "401k".asUiText()), setAside.items.map { it.label }, "the brokerage account is paid from take-home")
        val sam = setAside.items.first()
        assertEquals(UiText.of(Res.string.finance_wallet_per_month, "$1,200"), sam.amount, "biggest first")
        assertEquals(UiText.Joined(listOf("Sam".asUiText(), UiText.of(Res.string.checkup_savings_item_in_year, "$14,400", "2025")), UiText.of(Res.string.common_dot_separator)), sam.detail)
        assertEquals("+$3,000".asUiText(), c.ledger.single { it.label == Res.string.checkup_savings_ledger_saved }.amount)
        assertEquals("$12,000".asUiText(), c.ledger.single { it.label == Res.string.checkup_savings_ledger_pay }.amount)
        assertEquals(Res.string.checkup_savings_ledger_share, c.ledger.last().label)
        assertEquals("25.0%".asUiText(), c.ledger.last().amount)
        // 15% of $12,000 is $1,800.
        assertEquals(UiText.of(Res.string.checkup_savings_step_above, "$1,200"), c.steps.first())
    }

    @Test
    fun aSetAsideThatStillFallsShortSaysHowMuchMoreOfTheWholePayReaches15Percent() {
        val small = listOf(ContributionYear(2025, listOf(Contribution("403(b)", Owner.Joint, 2_400.0))))
        val c = moneyCheckup(household(contributions = small), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        // $1,200 saved of $10,200: 15% is $1,530.
        assertEquals("11.8%".asUiText(), c.figure)
        assertFalse(c.ok)
        assertEquals(UiText.of(Res.string.checkup_savings_step_reach, "$330"), c.steps.first())
        assertTrue(c.steps.none { it == UiText.of(Res.string.checkup_savings_step_401k) }, "the paycheck's share is already in the figure")
        assertEquals(UiText.of(Res.string.fin_data_owner_joint), (c.ledger.single { it.label == Res.string.checkup_savings_ledger_set_aside }.items.single().detail as UiText.Joined).parts.first())
        assertFalse(c.whatIf!!.outcome(325.0).ok)
        assertTrue(c.whatIf.outcome(350.0).ok)
    }

    @Test
    fun aSheetThatListsNoPaycheckPlansKeepsTheTakeHomeFigure() {
        val brokerageOnly = listOf(ContributionYear(2025, listOf(Contribution("Joint Brokerage", Owner.Joint, 6_000.0))))
        val c = moneyCheckup(household(contributions = brokerageOnly), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        assertEquals("10.0%".asUiText(), c.figure)
        assertEquals(Res.string.checkup_savings_ledger_kept, c.ledger.last().label)
        assertNull(c.note)
        assertTrue(c.steps.any { it == UiText.of(Res.string.checkup_savings_step_401k) })
    }

    @Test
    fun theSheetsOwnYearlyCountIsQuotedBesideTheMonthlyFigure() {
        val years = listOf(
            TaxYear(2024, 150_000.0, 40_000.0, 110_000.0, 0.2667, 22_000.0, 0.2),
            TaxYear(2025, 160_000.0, 40_000.0, 120_000.0, 0.25, 30_000.0, null),
            TaxYear(2026, 170_000.0, null, null, null, null, null),
        )
        val c = moneyCheckup(household(contributions = invested, taxYears = years), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        // The latest year with a figure; its share worked out from take-home when the sheet has none.
        assertEquals(UiText.of(Res.string.checkup_savings_step_sheet_year, "2025", "$30,000", "25.0%"), c.steps.last())
    }

    @Test
    fun costlyDebtFlagsOnlyTheLoansAboveTheLineAndPricesPayingThemOff() {
        val c = check(CheckKind.COSTLY_DEBT)
        assertFalse(c.ok)
        assertEquals(UiText.of(Res.string.checkup_debt_detail_costly, "5%", names("Credit card")), c.detail)
        val loans = c.ledger.first()
        assertEquals(
            listOf("Credit card".asUiText() to true, "Car loan".asUiText() to false),
            loans.items.map { it.label to it.flagged },
            "highest rate first, the mortgage left out",
        )
        assertEquals(UiText.of(Res.string.checkup_debt_step_pay_first, "Credit card", "22%", "$1,100"), c.steps.first())
        val cleared = c.whatIf!!.outcome(5_000.0)
        assertTrue(cleared.ok)
        assertEquals(UiText.of(Res.string.checkup_debt_whatif_saved, "$1,100"), cleared.headline)
    }

    @Test
    fun theCostlyLineFollowsTheFedButNeverDropsBelowFivePercent() {
        assertEquals(5.0, costlyDebtHurdle(null))
        assertEquals(5.0, costlyDebtHurdle(4.33))
        assertEquals(5.33, costlyDebtHurdle(5.33))
    }

    @Test
    fun debtAgainstWhatYouOwnLeavesTheMortgageOut() {
        val c = check(CheckKind.DEBT_RATIO)
        assertTrue(c.ok)
        assertEquals("15%".asUiText(), c.figure)
        assertEquals("$15,000".asUiText(), c.ledger.first().amount)
        // $88,000 in the accounts against the sheet's $100,000 total: the rest is said, not hidden.
        assertEquals("$12,000".asUiText(), c.ledger[1].items.single { it.label == UiText.of(Res.string.checkup_ratio_item_elsewhere) }.amount)
        assertTrue(UiText.of(Res.string.checkup_ratio_step_mortgage, "$300,000") in c.steps)
    }

    @Test
    fun savingForLaterSaysWhatAdditionReaches25Percent() {
        val c = check(CheckKind.RETIREMENT)
        assertEquals("23%".asUiText(), c.figure)
        // (25% of $88,000 − $20,000) ÷ 75%: new money grows the total too; at $1,000 a month that's 3 months.
        assertEquals(UiText.plural(Res.plurals.checkup_retirement_step_reach_away, 3, "$2,667", "$1,000", 3), c.steps.first())
        assertFalse(c.whatIf!!.outcome(2_600.0).ok)
        assertTrue(c.whatIf.outcome(2_667.0).ok)
        assertTrue(UiText.of(Res.string.checkup_retirement_step_uneven, "Alex", "$0", "Sam", "$20,000") in c.steps)
    }

    @Test
    fun linesTheSheetHasNoFiguresForAreLeftOut() {
        val sparse = moneyCheckup(household(monthlyExpenses = null, debts = emptyList()), fedRate = null)
        assertNull(sparse.byKind(CheckKind.EMERGENCY_FUND))
        assertNull(sparse.byKind(CheckKind.COSTLY_DEBT))
        assertEquals(0.0, sparse.byKind(CheckKind.DEBT_RATIO)!!.gauge!!.value)
        assertNull(sparse.byKind(CheckKind.DEBT_RATIO)!!.whatIf, "nothing owed, nothing to pay down")
    }

    @Test
    fun spendingThatTheSheetDoesNotItemiseIsShownSoThePartsAddUp() {
        // $9,000 spent; the three lines come to $7,000.
        val spending = check(CheckKind.EMERGENCY_FUND).ledger[1]
        val perMonth = UiText.of(Res.string.finance_wallet_per_month, "$2,000")
        assertEquals(perMonth, spending.items.single { it.label == UiText.of(Res.string.checkup_not_itemised) }.amount)
        assertEquals(perMonth, check(CheckKind.SAVINGS_RATE).ledger.single { it.label == Res.string.checkup_savings_ledger_spending }.items.last().amount)
    }

    @Test
    fun loansWithoutARateAreNotJudgedAndWithNoRatesAtAllTheLineIsLeftOut() {
        val noRates = household(debts = listOf(Debt("Family loan", Owner.Joint, 4_000.0, null, null)))
        assertNull(moneyCheckup(noRates, fedRate = null).byKind(CheckKind.COSTLY_DEBT))

        val someRates = household(debts = listOf(Debt("Car loan", Owner.Joint, 10_000.0, 3.0, null), Debt("Family loan", Owner.Joint, 4_000.0, null, null)))
        val c = moneyCheckup(someRates, fedRate = null).byKind(CheckKind.COSTLY_DEBT)!!
        assertTrue(c.ok)
        // "None of your loans with a rate charge more than about 5%. Family loan has no rate in the sheet, so it wasn't judged."
        assertEquals(
            UiText.of(
                Res.string.narrator_two_sentences,
                UiText.of(Res.string.checkup_debt_detail_none_rated, "5%"),
                UiText.plural(Res.plurals.checkup_debt_unjudged, 1, names("Family loan")),
            ),
            c.detail,
        )
    }

    @Test
    fun theQuotedPayDownLeavesTheRatioStrictlyUnder25Percent() {
        // $28,000 owed of $100,000: ($28,000 − $25,000) ÷ 75% is exactly $4,000, which lands on 25% — not under it.
        val c = moneyCheckup(household(debts = listOf(Debt("Car loan", Owner.Joint, 28_000.0, 3.0, null))), fedRate = null).byKind(CheckKind.DEBT_RATIO)!!
        assertFalse(c.ok)
        assertEquals(UiText.of(Res.string.checkup_ratio_step_from_savings, "$4,001"), c.steps.first())
        assertFalse(c.whatIf!!.outcome(4_000.0).ok)
        assertTrue(c.whatIf.outcome(4_001.0).ok)
    }

    @Test
    fun thePayDownSliderStopsAtWhatIsOwedOrTheCashThereIs() {
        assertEquals(15_000.0, check(CheckKind.DEBT_RATIO).whatIf!!.max, "owed, not rounded past it")
        val deep = moneyCheckup(household(debts = listOf(Debt("Car loan", Owner.Joint, 40_000.0, 3.0, null))), fedRate = null).byKind(CheckKind.DEBT_RATIO)!!
        assertEquals(18_000.0, deep.whatIf!!.max, "no more than the cash there is")
        assertEquals(UiText.of(Res.string.checkup_ratio_whatif_still_owed, "$22,000"), deep.whatIf.outcome(25_000.0).note, "an amount past the lever is held to it")
    }
}
