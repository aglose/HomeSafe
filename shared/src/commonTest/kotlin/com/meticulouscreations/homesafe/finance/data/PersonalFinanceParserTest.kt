package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.Owner
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PersonalFinanceParser] reads the household's real budget workbook, which is laid out for
 * people, not machines: labelled blocks found by title and read downward, not by cell address.
 * The fixture below replicates the "Home", "Forecasts", "New House" and "Old House" tabs' shapes
 * closely enough to exercise every block the parser looks for, including its fallbacks (a
 * label's alternate spelling, a joint account as one merged cell vs. two separate ones) and its
 * typo tolerance (the sheet's own "Antipated"). Names and amounts are entirely invented — this
 * repository is public.
 */
class PersonalFinanceParserTest {

    // ---- A1-style fixture builder ------------------------------------------------------------

    private fun a1(ref: String): Pair<Int, Int> {
        val letters = ref.takeWhile { it.isLetter() }
        val digits = ref.dropWhile { it.isLetter() }
        val col = letters.uppercase().fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        val row = digits.toInt() - 1
        return row to col
    }

    private fun cellValueOf(value: Any): CellValue = when (value) {
        is String -> CellValue.Text(value)
        is Boolean -> CellValue.Bool(value)
        is Int -> CellValue.Number(value.toDouble())
        is Double -> CellValue.Number(value)
        else -> error("unsupported fixture cell value: $value")
    }

    private fun mergeRef(range: String): MergedRange {
        val (startRef, endRef) = range.split(":")
        val (startRow, startCol) = a1(startRef)
        val (endRow, endCol) = a1(endRef)
        return MergedRange(startRow, endRow + 1, startCol, endCol + 1)
    }

    /** Builds a [SheetGrid] from a sparse map of 1-based A1 refs ("B6") to cell values. */
    private fun sheetGrid(title: String, cells: Map<String, Any>, merges: List<String> = emptyList()): SheetGrid {
        val byCell = cells.entries.associate { (ref, value) -> a1(ref) to cellValueOf(value) }
        val maxRow = byCell.keys.maxOf { it.first }
        val maxCol = byCell.keys.maxOf { it.second }
        val rows = (0..maxRow).map { r -> (0..maxCol).map { c -> byCell[r to c] ?: CellValue.Empty } }
        return SheetGrid(title, rows, merges.map(::mergeRef))
    }

    // ---- The "Home" tab ------------------------------------------------------------------------

    private val homeGrid = sheetGrid(
        "Home",
        mapOf(
            "A3" to "Monthly Cash Flow", "I3" to "Savings", "M3" to "Future Holdings",

            "A5" to "Flow In", "B5" to "Alex", "C5" to "Sam", "E5" to "Flow Out",
            "I5" to "Brokerage Accounts", "J5" to "Alex ", "K5" to "Sam ",
            "N5" to "Type", "O5" to "Amount", "P5" to "Post Tax",

            "A6" to "Monthly Income (Take Home)", "B6" to 7430.0, "C6" to 11260.0,

            // Expenses: all negative in the sheet, one legitimately zero (and skipped).
            "E6" to "Rent", "G6" to -8210.0,
            "E7" to "Student Loan", "G7" to -2340.0,
            "E8" to "Car Payment", "G8" to -540.0,
            "E9" to "Groceries", "G9" to -1110.0,
            "E10" to "Utilities", "G10" to -360.0,
            "E11" to "Insurance", "G11" to -470.0,
            "E12" to "Estimated Federal Tax", "G12" to 0.0,
            "E13" to "Subscriptions", "G13" to -135.0,
            "E14" to "Childcare", "G14" to -1870.0,
            "E35" to "Total Monthly Expenses", "G35" to -16870.0,

            // Brokerage accounts: one joint (merged), one split two ways, one Alex-only, one
            // Sam-only, and one more split two ways under a different category.
            "I6" to "Joint brokerage", "J6" to 46230.0,
            "I7" to "Roth IRA", "J7" to 18470.0, "K7" to 13920.0,
            "I8" to "Family 529", "J8" to 4310.0,
            "I9" to "401(k) Plan", "K9" to 27650.0,
            "I10" to "Checking Account", "J10" to 2780.0, "K10" to 3150.0,
            "I17" to "Total",

            "I19" to "Checking/Savings",
            "I20" to "Combined", "J20" to 8640.0,

            "I23" to "Grand Total", "K23" to 341750.0,
            "I35" to "Cash", "K35" to 94200.0,

            "A35" to "Monthly Combined Income", "C35" to 18690.0,
            "A38" to "Net Monthly Profit", "B38" to 1820.0,
            "A40" to "Total Assets", "B40" to 1847300.0,
            "A42" to "Emergency 6 months\nfund", "B42" to -87650.0,

            // Debt: a paid-off joint card, three Sam debts and the mortgage under Alex.
            "E37" to "Debt", "F38" to "Alex ", "G38" to "Sam ",
            "E39" to "Credit Cards", "F39" to 0.0, "G39" to 0.0,
            "E40" to "Student Loans \n(APR 3.08%, ~5 years left)", "G40" to -128400.0,
            "E41" to "Car Loan\n(APR 1.89%, 7 months left)", "G41" to -6420.0,
            "E42" to "Family Loan (APR 0%, 10 years)", "G42" to -162300.0,
            "E43" to "Mortgage (APR 5.99%, 30 years)", "F43" to -1138500.0,
            "E47" to "Grand Total",

            // Home equity: dollar amounts beside the 0.2 ownership/deposit fractions.
            "I39" to "Home Asset",
            "I41" to "Value at Sale", "K41" to 1382600.0,
            "I42" to "Cash Deposit", "J42" to 0.2, "K42" to 276400.0,
            "I44" to "Original Loan Amount", "K44" to 1104800.0,
            "I45" to "Unpaid Principle", "K45" to 1098200.0,
            "I47" to "Total Principle Paid", "K47" to 0.0,
            "I48" to "Total Equity", "J48" to 0.2, "K48" to 276400.0,
            "I50" to "Investments", "K50" to 21300.0,
            "I52" to "Deposit \n+ Principal Paid \n+ Investments\n= Value Added to Home", "K52" to 298700.0,
            "I55" to "Estimated Sale ($1.6M)\n- Unpaid Principle\n= Estimated Asset Value ", "K55" to 321400.0,

            "M39" to "Home Improvement",
            "M41" to "EV Charger", "O41" to 680.0,
            "M42" to "Carpet", "O42" to 8340.0,
            "M52" to "Total",

            // Future holdings (vesting): a bare year row switches the year for the rows below it.
            "M6" to 2026.0,
            "M7" to "Jun 12th", "N7" to "ESPP", "O7" to 9870.0, "P7" to 8930.0,
            "M8" to "Oct 9th", "N8" to "RSU", "O8" to 8640.0, "P8" to 5210.0,
            "M9" to 2027.0,
            "M10" to "Mar 3rd", "N10" to "RSU", "O10" to 7120.0, "P10" to 4380.0,
            "M15" to "Total",

            // Watchlist, including the sheet's own bare tickers that have no entry in the alias
            // table at all.
            "M18" to "Live PTON Price",
            "M20" to "Live TSLA Price",
            "M22" to "Live BTC Price",
            "M24" to "Live NVIDIA Price",
            "M26" to "Live APPL Price",

            // Running history: one header row, three dated rows, then a blank row that ends it.
            "S60" to "Date", "T60" to "Monthly Expenses", "U60" to "Monthly Income", "V60" to "Monthly Profit",
            "W60" to "Total Assets", "X60" to "Total Assets 2", "Y60" to "Total Assets Difference", "Z60" to "Debt",
            "S61" to 43796.0, "T61" to -13420.0, "U61" to 17850.0, "V61" to 3680.0, "W61" to 463200.0, "Z61" to -1298500.0,
            "S62" to 44000.0, "T62" to -14650.0, "U62" to 18320.0, "V62" to 3120.0, "W62" to 548700.0, "Z62" to -1298400.0,
            "S63" to 44200.0, "T63" to -15870.0, "U63" to 18990.0, "V63" to 2740.0, "W63" to 634900.0, "Z63" to -1211300.0,
        ),
        merges = listOf("J6:K6", "J20:K20"),
    )

    // ---- The "Forecasts" tab: a distractor "Year" table above the real one -------------------

    private val forecastsGrid = sheetGrid(
        "Forecasts",
        mapOf(
            "A3" to "Year", "B3" to "Some Other Metric",

            "J10" to "Year", "K10" to "Income Pre-Tax", "L10" to "Taxes Paid", "M10" to "Take Home",
            "N10" to "Effective Tax Rate", "O10" to "Investments Made", "P10" to "Investment Percentage",
            "J11" to 2022.0, "K11" to 138400.0, "L11" to 36900.0, "M11" to 101500.0, "N11" to 0.2531, "O11" to 18200.0, "P11" to 0.1793,
            "J12" to 2023.0, "K12" to 146700.0, "L12" to 38950.0, "M12" to 107750.0, "N12" to 0.2487, "O12" to 19600.0, "P12" to 0.1819,
        ),
    )

    // ---- The "New House" tab: mortgage calculator plus one affordability row -----------------

    private val newHouseGrid = sheetGrid(
        "New House",
        mapOf(
            "H7" to "Mortgage Calculator",
            "H8" to "Home Price", "I8" to 1312000.0,
            "H9" to "Down Payment (%)", "I9" to 0.18,
            "H12" to "Interest Rate", "I12" to 0.0675,
            "H13" to "Loan Term (Years)", "I13" to 30.0,
            "H15" to "Property Tax (Annual)", "I15" to 15640.0,
            "H16" to "Home Insurance (Annual)", "I16" to 2180.0,
            "H17" to "HOA (Monthly)", "I17" to 0.0,
            "F16" to "Estimated Affordable\nMortgage",
            "C17" to "Antipated Monthly \nExpenses Sept 2026", "D17" to -17650.0, "F17" to 8420.0,
        ),
    )

    // ---- The "Old House" tab ---------------------------------------------------------------

    private val oldHouseGrid = sheetGrid(
        "Old House",
        mapOf(
            "I3" to "Purchase Price",
            "K3" to 876300.0,
            "I17" to "Sold Price",
            "K17" to 1256800.0,
            "I23" to "Total Profit",
            "K23" to 84200.0,
            "I24" to "ROI Percentage ",
            "K24" to 0.192,
        ),
    )

    private val finance = PersonalFinanceParser.parse(
        title = "Budget",
        fetchedAtEpochSeconds = 1_790_800_000L,
        sheets = listOf(homeGrid, forecastsGrid, newHouseGrid, oldHouseGrid),
        sourceUrl = "https://docs.google.com/spreadsheets/d/fake",
    )

    // ---- People, income, expenses -------------------------------------------------------------

    @Test
    fun peopleComeFromTheFlowInHeader() {
        assertEquals(listOf("Alex", "Sam"), finance.people)
    }

    @Test
    fun incomeIsOneLinePerPerson() {
        assertEquals(setOf("Alex" to 7430.0, "Sam" to 11260.0), finance.income.map { it.person to it.monthly }.toSet())
        assertEquals(18690.0, finance.monthlyIncome)
        assertEquals(16870.0, finance.monthlyExpenses, "stored negative, read back positive")
        assertEquals(1820.0, finance.netMonthly)
    }

    @Test
    fun expensesAreSortedDescendingAllPositiveAndTheZeroLineIsSkipped() {
        assertEquals(
            listOf("Rent" to 8210.0, "Student Loan" to 2340.0, "Childcare" to 1870.0, "Groceries" to 1110.0, "Car Payment" to 540.0, "Insurance" to 470.0, "Utilities" to 360.0, "Subscriptions" to 135.0),
            finance.expenses.map { it.name to it.monthly },
        )
        assertTrue(finance.expenses.none { it.name == "Estimated Federal Tax" })
        assertTrue(finance.expenses.all { it.monthly > 0 })
    }

    // ---- Accounts -------------------------------------------------------------------------

    @Test
    fun aMergedCellIsOneJointAccountAndSeparateCellsAreTwoAccounts() {
        val joint = finance.accounts.single { it.name == "Joint brokerage" }
        assertEquals(Owner.Joint, joint.owner)
        assertEquals(46230.0, joint.balance)

        val roth = finance.accounts.filter { it.name == "Roth IRA" }
        assertEquals(setOf(Owner.Person("Alex") to 18470.0, Owner.Person("Sam") to 13920.0), roth.map { it.owner to it.balance }.toSet())
    }

    @Test
    fun aLoneColumnMakesAOnePersonAccount() {
        val family529 = finance.accounts.single { it.name == "Family 529" }
        assertEquals(Owner.Person("Alex"), family529.owner)
        val k401 = finance.accounts.single { it.name == "401(k) Plan" }
        assertEquals(Owner.Person("Sam"), k401.owner)
    }

    @Test
    fun accountsAreCategorisedByNameKeyword() {
        assertEquals(AccountCategory.INVESTING, finance.accounts.single { it.name == "Joint brokerage" }.category)
        assertEquals(AccountCategory.RETIREMENT, finance.accounts.first { it.name == "Roth IRA" }.category)
        assertEquals(AccountCategory.RETIREMENT, finance.accounts.single { it.name == "401(k) Plan" }.category)
        assertEquals(AccountCategory.EDUCATION, finance.accounts.single { it.name == "Family 529" }.category)
        assertTrue(finance.accounts.filter { it.name == "Checking Account" }.all { it.category == AccountCategory.CASH })
    }

    @Test
    fun cashAccountsIncludeTheCombinedCheckingAndTheLoneCashLine() {
        val combined = finance.accounts.single { it.name == "Checking & savings" }
        assertEquals(Owner.Joint, combined.owner)
        assertEquals(8640.0, combined.balance)
        assertEquals(AccountCategory.CASH, combined.category)

        val cash = finance.accounts.single { it.name == "Cash" }
        assertEquals(94200.0, cash.balance)
    }

    @Test
    fun homeEquityAccountUsesTheValueAddedFigureNotTheRawEquity() {
        val homeAccount = finance.accounts.single { it.name == "Home equity" }
        assertEquals(298700.0, homeAccount.balance)
        assertEquals(AccountCategory.HOME, homeAccount.category)
    }

    @Test
    fun investmentsTotalIsTheGrandTotalCellAndTotalAssetsIsItsOwnLabel() {
        assertEquals(341750.0, finance.investmentsTotal)
        assertEquals(1847300.0, finance.totalAssets)
        assertEquals(87650.0, finance.emergencyTarget, "a negative in the sheet, a positive target")
    }

    // ---- Debt ---------------------------------------------------------------------------------

    @Test
    fun aJointDebtRowAtZeroBothColumnsIsAPaidOffJointDebt() {
        val cards = finance.debts.single { it.name == "Credit Cards" }
        assertEquals(Owner.Joint, cards.owner)
        assertTrue(cards.isPaidOff)
        assertNull(cards.apr)
        assertNull(cards.note)
    }

    @Test
    fun debtLabelsAreSplitIntoNameAprAndNote() {
        val loan = finance.debts.single { it.name == "Student Loans" }
        assertEquals(Owner.Person("Sam"), loan.owner)
        assertEquals(128400.0, loan.balance)
        assertEquals(3.08, loan.apr)
        assertEquals("~5 years left", loan.note)

        val carLoan = finance.debts.single { it.name == "Car Loan" }
        assertEquals(6420.0, carLoan.balance)
        assertEquals(1.89, carLoan.apr)

        val zeroApr = finance.debts.single { it.name == "Family Loan" }
        assertEquals(0.0, zeroApr.apr, "0% is a real APR, not a missing one")
    }

    @Test
    fun theMortgageIsOwnedByItsColumnAndExcludedFromConsumerDebt() {
        val mortgage = finance.debts.single { it.name == "Mortgage" }
        assertEquals(Owner.Person("Alex"), mortgage.owner)
        assertEquals(1138500.0, mortgage.balance)
        assertTrue(mortgage.isMortgage)

        assertEquals(297120.0, finance.consumerDebt, "everything owed except the mortgage: 0 + 128400 + 6420 + 162300")
        assertEquals(1550180.0, finance.netWorth, "total assets (1,847,300) minus consumer debt (297,120)")
    }

    // ---- Home equity ----------------------------------------------------------------------

    @Test
    fun homeEquityReadsTheDollarAmountsNotTheOwnershipFractions() {
        val home = assertNotNull(finance.home)
        assertEquals(1382600.0, home.value)
        assertEquals(276400.0, home.deposit, "the dollar deposit, not the 0.2 fraction beside it")
        assertEquals(1104800.0, home.originalLoan)
        assertEquals(1098200.0, home.unpaidPrincipal)
        assertEquals(0.0, home.principalPaid)
        assertEquals(276400.0, home.equity, "the dollar equity, not the 0.2 fraction beside it")
        assertEquals(298700.0, home.valueAdded)
        assertEquals(321400.0, home.estimatedAssetValue)
        assertEquals(listOf("EV Charger" to 680.0, "Carpet" to 8340.0), home.improvements.map { it.name to it.monthly })
    }

    // ---- Vesting, watchlist --------------------------------------------------------------

    @Test
    fun vestingEventsApplyTheYearRowAboveThem() {
        assertEquals(3, finance.vesting.size)
        val espp = finance.vesting.single { it.type == "ESPP" }
        assertEquals(9870.0, espp.amount)
        assertEquals(8930.0, espp.postTax)
        assertEquals(PersonalFinanceParser.parseMonthDay("Jun 12th", 2026), espp.epochSeconds)
        assertEquals(LocalDate(2026, 6, 12).atStartOfDayIn(TimeZone.UTC).epochSeconds, espp.epochSeconds)

        val nextYear = finance.vesting.single { it.label.startsWith("Mar 3rd") }
        assertEquals(PersonalFinanceParser.parseMonthDay("Mar 3rd", 2027), nextYear.epochSeconds)
    }

    @Test
    fun watchlistMapsTheSheetsNamesToYahooSymbols() {
        // PTON and TSLA aren't in the alias table at all (the bare uppercase ticker is already
        // right); APPL is the sheet's own typo, and the alias table is what fixes it to AAPL.
        assertEquals(listOf("PTON", "TSLA", "BTC-USD", "NVDA", "AAPL"), finance.watchlist)
    }

    // ---- History ------------------------------------------------------------------------------

    @Test
    fun historyParsesEveryDatedRowUntilTheBlankOneAndFlipsDebtAndExpensesPositive() {
        assertEquals(3, finance.history.size)
        val first = finance.history.first()
        assertEquals(PersonalFinanceParser.sheetsSerialToEpochSeconds(43796.0), first.epochSeconds)
        assertEquals(13420.0, first.monthlyExpenses)
        assertEquals(1298500.0, first.debt)
        assertTrue(finance.history.all { (it.monthlyExpenses ?: 0.0) >= 0 && (it.debt ?: 0.0) >= 0 })
        assertEquals(finance.history.sortedBy { it.epochSeconds }, finance.history)
    }

    // ---- Tax years (Forecasts tab) --------------------------------------------------------

    @Test
    fun taxYearsOnlyComeFromTheTableThatHasBothYearAndTakeHome() {
        assertEquals(listOf(2022, 2023), finance.taxYears.map { it.year })
        val y2022 = finance.taxYears.first { it.year == 2022 }
        assertEquals(138400.0, y2022.incomePreTax)
        assertEquals(101500.0, y2022.takeHome)
        assertEquals(0.2531, y2022.effectiveRate)
    }

    // ---- Mortgage plan and old house (New House / Old House tabs) ------------------------

    @Test
    fun mortgagePlanReadsTheCalculatorInputsAndOneAffordabilityPoint() {
        val plan = assertNotNull(finance.mortgagePlan)
        assertEquals(1312000.0, plan.homePrice)
        assertEquals(0.18, plan.downPaymentFraction)
        assertEquals(0.0675, plan.rate)
        assertEquals(30, plan.termYears)
        assertEquals(15640.0, plan.propertyTaxAnnual)
        assertEquals(2180.0, plan.insuranceAnnual)
        assertEquals(0.0, plan.hoaMonthly)
        val point = plan.affordability.single()
        assertEquals("Sept 2026", point.label, "the sheet's own 'Antipated' typo is still matched")
        assertEquals(17650.0, point.monthlyExpenses)
        assertEquals(8420.0, point.affordableMortgage)
    }

    @Test
    fun oldHouseReadsThePurchaseAndSaleAndLeavesUnlistedFieldsNull() {
        val sale = assertNotNull(finance.oldHouse)
        assertEquals(876300.0, sale.purchasePrice)
        assertEquals(1256800.0, sale.soldPrice)
        assertEquals(84200.0, sale.profit)
        assertEquals(0.192, sale.roi)
        assertNull(sale.cashReceived, "the sheet never labelled a 'Total Money Received from Sale' cell")
    }

    // ---- Missing sections ------------------------------------------------------------------

    @Test
    fun aWorkbookMissingEverySectionYieldsEmptyPartsWithoutThrowing() {
        val empty = PersonalFinanceParser.parse(title = "Empty", fetchedAtEpochSeconds = 0L, sheets = emptyList())
        assertEquals(emptyList(), empty.people)
        assertEquals(emptyList(), empty.income)
        assertNull(empty.monthlyIncome)
        assertNull(empty.monthlyExpenses)
        assertNull(empty.netMonthly)
        assertEquals(emptyList(), empty.expenses)
        assertEquals(emptyList(), empty.accounts)
        assertNull(empty.investmentsTotal)
        assertNull(empty.totalAssets)
        assertNull(empty.emergencyTarget)
        assertEquals(emptyList(), empty.debts)
        assertNull(empty.home)
        assertEquals(emptyList(), empty.vesting)
        assertEquals(emptyList(), empty.watchlist)
        assertEquals(emptyList(), empty.history)
        assertEquals(emptyList(), empty.taxYears)
        assertNull(empty.mortgagePlan)
        assertNull(empty.oldHouse)
    }

    // ---- The small internal helpers, directly ----------------------------------------------

    @Test
    fun splitDebtLabelSeparatesNameAprAndNote() {
        assertEquals(Triple("Student Loans", 3.08, "~5 years left"), PersonalFinanceParser.splitDebtLabel("Student Loans (APR 3.08%, ~5 years left)"))
        assertEquals(Triple("Family Loan", 0.0, "10 years"), PersonalFinanceParser.splitDebtLabel("Family Loan (APR 0%, 10 years)"))
        assertEquals(Triple("Credit Cards", null, null), PersonalFinanceParser.splitDebtLabel("Credit Cards"))
        assertEquals(Triple("Personal Loan", null, "from a friend"), PersonalFinanceParser.splitDebtLabel("Personal Loan (from a friend)"), "no APR token: the note is everything inside the parens")
    }

    @Test
    fun parseMonthDayHandlesOrdinalsAndAbbreviationsWithAPeriod() {
        assertEquals(LocalDate(2026, 6, 12).atStartOfDayIn(TimeZone.UTC).epochSeconds, PersonalFinanceParser.parseMonthDay("Jun 12th", 2026))
        assertEquals(LocalDate(2027, 3, 3).atStartOfDayIn(TimeZone.UTC).epochSeconds, PersonalFinanceParser.parseMonthDay("Mar 3rd", 2027))
        assertEquals(LocalDate(2026, 9, 21).atStartOfDayIn(TimeZone.UTC).epochSeconds, PersonalFinanceParser.parseMonthDay("Sep. 21", 2026))
        assertNull(PersonalFinanceParser.parseMonthDay("Someday", 2026))
    }

    @Test
    fun categoriseMatchesByKeyword() {
        assertEquals(AccountCategory.EDUCATION, PersonalFinanceParser.categorise("529 College Fund"))
        assertEquals(AccountCategory.RETIREMENT, PersonalFinanceParser.categorise("Roth IRA"))
        assertEquals(AccountCategory.RETIREMENT, PersonalFinanceParser.categorise("401(k) Plan"))
        assertEquals(AccountCategory.RETIREMENT, PersonalFinanceParser.categorise("HSA Account"))
        assertEquals(AccountCategory.CASH, PersonalFinanceParser.categorise("Checking Account"))
        assertEquals(AccountCategory.CASH, PersonalFinanceParser.categorise("Savings Account"))
        assertEquals(AccountCategory.INVESTING, PersonalFinanceParser.categorise("Joint brokerage"))
    }

    @Test
    fun sheetsSerialToEpochSecondsMatchesKnownDates() {
        assertEquals(LocalDate(2019, 11, 27).atStartOfDayIn(TimeZone.UTC).epochSeconds, PersonalFinanceParser.sheetsSerialToEpochSeconds(43796.0))
    }
}
