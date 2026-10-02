package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.domain.IncomeLine
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
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
        taxYears = emptyList(),
        mortgagePlan = null,
        oldHouse = null,
    )

    private val checkup = moneyCheckup(household(), fedRate = 4.33)

    private fun check(kind: CheckKind) = checkup.byKind(kind)!!

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
        assertEquals("2.0 months", c.figure)
        val cash = c.ledger.first()
        assertEquals("$18,000", cash.amount)
        assertEquals(listOf("Savings", "Checking"), cash.items.map { it.label }, "biggest first")
        assertEquals(WalletSection.ACCOUNTS, cash.section)
        assertEquals(WalletSection.CASH_FLOW, c.ledger[1].section)
        assertEquals("Set aside $9,000 more to reach 3 months ($27,000 in all). Saving all of the $1,000 left over each month, that's about 9 months away.", c.steps.first())
        assertFalse(c.whatIf!!.outcome(8_500.0).ok)
        assertTrue(c.whatIf.outcome(9_000.0).ok)
    }

    @Test
    fun theSavingsRateSaysHowMuchMoreAMonthReaches15Percent() {
        val c = check(CheckKind.SAVINGS_RATE)
        assertEquals("10.0%", c.figure)
        assertEquals("Keeping $500 more a month reaches 15%.", c.steps.first())
        assertTrue(c.steps[1].startsWith("Rent is $4,000 a month — trimming it by 13%"), c.steps[1])
        assertEquals("Kept each month", c.ledger.last().label)
        assertTrue(c.ledger.none { it.label == "Other differences in the sheet" }, "income less spending is the sheet's left over")
        assertFalse(c.whatIf!!.outcome(475.0).ok)
        assertTrue(c.whatIf.outcome(500.0).ok)
    }

    @Test
    fun aSheetLeftOverThatDisagreesWithTheSumGetsItsOwnLine() {
        val c = moneyCheckup(household(netMonthly = 800.0), fedRate = null).byKind(CheckKind.SAVINGS_RATE)!!
        val other = c.ledger.single { it.label == "Other differences in the sheet" }
        assertEquals("$200", other.amount)
    }

    @Test
    fun costlyDebtFlagsOnlyTheLoansAboveTheLineAndPricesPayingThemOff() {
        val c = check(CheckKind.COSTLY_DEBT)
        assertFalse(c.ok)
        assertEquals("Charging more than 5%: Credit card.", c.detail)
        val loans = c.ledger.first()
        assertEquals(listOf("Credit card" to true, "Car loan" to false), loans.items.map { it.label to it.flagged }, "highest rate first, the mortgage left out")
        assertTrue(c.steps.first().startsWith("Pay Credit card first — at 22% it costs about $1,100 a year."), c.steps.first())
        val cleared = c.whatIf!!.outcome(5_000.0)
        assertTrue(cleared.ok)
        assertEquals("$1,100 a year less interest", cleared.headline)
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
        assertEquals("15%", c.figure)
        assertEquals("$15,000", c.ledger.first().amount)
        // $88,000 in the accounts against the sheet's $100,000 total: the rest is said, not hidden.
        assertEquals("$12,000", c.ledger[1].items.single { it.label == "Elsewhere in the sheet's total" }.amount)
        assertTrue(c.steps.any { "The mortgage ($300,000) isn't counted" in it })
    }

    @Test
    fun savingForLaterSaysWhatAdditionReaches25Percent() {
        val c = check(CheckKind.RETIREMENT)
        assertEquals("23%", c.figure)
        // (25% of $88,000 − $20,000) ÷ 75%: new money grows the total too.
        assertTrue(c.steps.first().startsWith("Adding $2,667 to retirement accounts would reach 25%."), c.steps.first())
        assertFalse(c.whatIf!!.outcome(2_600.0).ok)
        assertTrue(c.whatIf.outcome(2_667.0).ok)
        assertTrue(c.steps.any { it.startsWith("Alex has $0 in retirement accounts to Sam's $20,000") })
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
        assertEquals("$2,000 / mo", spending.items.single { it.label == "Not itemised in the sheet" }.amount)
        assertEquals("$2,000 / mo", check(CheckKind.SAVINGS_RATE).ledger.single { it.label == "Spending" }.items.last().amount)
    }

    @Test
    fun loansWithoutARateAreNotJudgedAndWithNoRatesAtAllTheLineIsLeftOut() {
        val noRates = household(debts = listOf(Debt("Family loan", Owner.Joint, 4_000.0, null, null)))
        assertNull(moneyCheckup(noRates, fedRate = null).byKind(CheckKind.COSTLY_DEBT))

        val someRates = household(debts = listOf(Debt("Car loan", Owner.Joint, 10_000.0, 3.0, null), Debt("Family loan", Owner.Joint, 4_000.0, null, null)))
        val c = moneyCheckup(someRates, fedRate = null).byKind(CheckKind.COSTLY_DEBT)!!
        assertTrue(c.ok)
        assertEquals("None of your loans with a rate charge more than about 5%. Family loan has no rate in the sheet, so it wasn't judged.", c.detail)
    }

    @Test
    fun theQuotedPayDownLeavesTheRatioStrictlyUnder25Percent() {
        // $28,000 owed of $100,000: ($28,000 − $25,000) ÷ 75% is exactly $4,000, which lands on 25% — not under it.
        val c = moneyCheckup(household(debts = listOf(Debt("Car loan", Owner.Joint, 28_000.0, 3.0, null))), fedRate = null).byKind(CheckKind.DEBT_RATIO)!!
        assertFalse(c.ok)
        assertEquals("Paying down $4,001 from savings would bring it under 25% (savings shrink too, so it takes a little more than the gap).", c.steps.first())
        assertFalse(c.whatIf!!.outcome(4_000.0).ok)
        assertTrue(c.whatIf.outcome(4_001.0).ok)
    }

    @Test
    fun thePayDownSliderStopsAtWhatIsOwedOrTheCashThereIs() {
        assertEquals(15_000.0, check(CheckKind.DEBT_RATIO).whatIf!!.max, "owed, not rounded past it")
        val deep = moneyCheckup(household(debts = listOf(Debt("Car loan", Owner.Joint, 40_000.0, 3.0, null))), fedRate = null).byKind(CheckKind.DEBT_RATIO)!!
        assertEquals(18_000.0, deep.whatIf!!.max, "no more than the cash there is")
        assertEquals("$22,000 still owed.", deep.whatIf.outcome(25_000.0).note, "an amount past the lever is held to it")
    }
}
