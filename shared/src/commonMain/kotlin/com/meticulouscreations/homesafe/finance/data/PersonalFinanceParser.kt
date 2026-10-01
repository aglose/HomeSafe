package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.Account
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.AffordabilityPoint
import com.meticulouscreations.homesafe.finance.domain.Debt
import com.meticulouscreations.homesafe.finance.domain.ExpenseLine
import com.meticulouscreations.homesafe.finance.domain.HomeEquity
import com.meticulouscreations.homesafe.finance.domain.HouseSale
import com.meticulouscreations.homesafe.finance.domain.IncomeLine
import com.meticulouscreations.homesafe.finance.domain.MortgagePlan
import com.meticulouscreations.homesafe.finance.domain.Owner
import com.meticulouscreations.homesafe.finance.domain.ParseNote
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.SectionHealth
import com.meticulouscreations.homesafe.finance.domain.SectionStatus
import com.meticulouscreations.homesafe.finance.domain.SheetHealth
import com.meticulouscreations.homesafe.finance.domain.SheetSection
import com.meticulouscreations.homesafe.finance.domain.Snapshot
import com.meticulouscreations.homesafe.finance.domain.TaxYear
import com.meticulouscreations.homesafe.finance.domain.VestEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the household budget workbook into [PersonalFinance]. The sheet is laid out for people,
 * not machines — blocks of labelled cells side by side on one tab — so nothing is found by
 * address: each block is found by its title ("Brokerage Accounts", "Flow Out", "Debt") and read
 * downwards from there until a total or a run of blank rows. Rows can be added, removed or moved
 * and the parser follows; a block it can't find is simply left out.
 */
object PersonalFinanceParser {

    fun parse(title: String, fetchedAtEpochSeconds: Long, sheets: List<SheetGrid>, sourceUrl: String? = null): PersonalFinance {
        val home = sheets.firstOrNull { it.contains("Brokerage Accounts") || it.contains("Monthly Cash Flow") }
        val forecasts = sheets.firstOrNull { s -> s.find { it == "take home" } != null && s.find { it == "year" } != null }
        val newHouse = sheets.firstOrNull { it.contains("Mortgage Calculator") }
        val oldHouse = sheets.firstOrNull { it.contains("Sold Price") }

        val people = home?.let(::people).orEmpty()
        val accounts = home?.let { accounts(it, people.map { p -> p.second }) }.orEmpty() + home?.let(::cashAccounts).orEmpty()
        val notes = mutableListOf<ParseNote>()
        val finance = PersonalFinance(
            title = title,
            fetchedAtEpochSeconds = fetchedAtEpochSeconds,
            sourceUrl = sourceUrl,
            people = people.map { it.second },
            income = home?.let { income(it, people) }.orEmpty(),
            monthlyIncome = home?.valueOf("Monthly Combined Income"),
            monthlyExpenses = home?.valueOf("Total Monthly Expenses")?.let(::abs),
            netMonthly = home?.valueOf("Net Monthly Profit"),
            expenses = home?.let(::expenses).orEmpty(),
            accounts = accounts + listOfNotNull(home?.let(::homeEquityAccount)),
            investmentsTotal = home?.let(::grandTotal),
            totalAssets = home?.valueOf("Total Assets"),
            emergencyTarget = home?.findStartingWith("Emergency")?.let { (r, c) -> home.numberRightOf(r, c) }?.let(::abs),
            debts = home?.let { debts(it, people.map { p -> p.second }) }.orEmpty(),
            home = home?.let(::homeEquity),
            vesting = home?.let(::vesting).orEmpty(),
            watchlist = home?.let(::watchlist).orEmpty(),
            history = home?.let { history(it, notes) }.orEmpty(),
            taxYears = forecasts?.let(::taxYears).orEmpty(),
            mortgagePlan = newHouse?.let(::mortgagePlan),
            oldHouse = oldHouse?.let(::houseSale),
        )
        val totals = listOfNotNull(finance.monthlyIncome, finance.monthlyExpenses, finance.netMonthly).size
        if (totals in 1..2) notes += ParseNote(SheetSection.TOTALS, "Only $totals of the 3 monthly totals were found")
        return finance.copy(health = SheetHealth(sectionHealth(finance, sheets), emptyList(), notes))
    }

    /**
     * Each part of the sheet: read, found but empty (its title is there, nothing under it could be
     * read), or missing (no title). A part's title is looked for on every tab, so a part moved to
     * another tab that the parser doesn't read there still shows as empty rather than missing.
     */
    internal fun sectionHealth(f: PersonalFinance, sheets: List<SheetGrid>): List<SectionHealth> {
        fun present(vararg labels: String) = labels.any { label -> sheets.any { it.contains(label) } }
        fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"
        fun health(section: SheetSection, titled: Boolean, found: String?) = SectionHealth(
            section,
            when {
                found != null -> SectionStatus.OK
                titled -> SectionStatus.EMPTY
                else -> SectionStatus.MISSING
            },
            found,
        )
        val invested = f.accounts.count { it.category != AccountCategory.CASH && it.category != AccountCategory.HOME }
        val cash = f.accounts.count { it.category == AccountCategory.CASH }
        val totals = listOfNotNull(f.monthlyIncome, f.monthlyExpenses, f.netMonthly).size
        return listOf(
            health(SheetSection.INCOME, present("Flow In"), f.income.size.takeIf { it > 0 }?.let { plural(it, "person", "people") }),
            health(SheetSection.EXPENSES, present("Flow Out"), f.expenses.size.takeIf { it > 0 }?.let { plural(it, "line") }),
            health(
                SheetSection.TOTALS,
                present("Monthly Combined Income", "Total Monthly Expenses", "Net Monthly Profit"),
                totals.takeIf { it > 0 }?.let { if (it == 3) "all 3" else "$it of 3" },
            ),
            health(SheetSection.ACCOUNTS, present("Brokerage Accounts"), invested.takeIf { it > 0 }?.let { plural(it, "account") }),
            health(SheetSection.CASH, present("Checking/Savings", "Cash"), cash.takeIf { it > 0 }?.let { plural(it, "balance") }),
            health(SheetSection.TOTAL_ASSETS, present("Total Assets"), f.totalAssets?.let { "read" }),
            health(SheetSection.DEBTS, present("Debt"), f.debts.size.takeIf { it > 0 }?.let { plural(it, "debt") }),
            health(SheetSection.HOME, present("Home Asset"), f.home?.takeIf { it.equity != null || it.valueAdded != null }?.let { "read" }),
            health(SheetSection.VESTING, present("Future Holdings"), f.vesting.size.takeIf { it > 0 }?.let { plural(it, "payout") }),
            health(SheetSection.WATCHLIST, false, f.watchlist.size.takeIf { it > 0 }?.let { plural(it, "ticker") }),
            health(SheetSection.HISTORY, present("Date"), f.history.size.takeIf { it > 0 }?.let { plural(it, "snapshot") }),
            health(SheetSection.TAX_YEARS, present("Take Home"), f.taxYears.size.takeIf { it > 0 }?.let { plural(it, "year") }),
            health(SheetSection.MORTGAGE, present("Mortgage Calculator"), f.mortgagePlan?.let { "read" }),
            health(SheetSection.HOUSE_SALE, present("Sold Price"), f.oldHouse?.soldPrice?.let { "read" }),
        )
    }

    /** The names over the income columns ("Flow In | Andrew | Sarah"), with their columns. */
    private fun people(grid: SheetGrid): List<Pair<Int, String>> {
        val (r, c) = grid.findLabel("Flow In") ?: return emptyList()
        return (c + 1..c + 3).mapNotNull { col -> grid.text(r, col)?.let { col to it } }
    }

    private fun income(grid: SheetGrid, people: List<Pair<Int, String>>): List<IncomeLine> {
        val (r, _) = grid.findStartingWith("Monthly Income") ?: return emptyList()
        return people.mapNotNull { (col, name) -> grid.number(r, col)?.let { IncomeLine(name, it) } }
    }

    private fun expenses(grid: SheetGrid): List<ExpenseLine> {
        val (r0, c) = grid.findLabel("Flow Out") ?: return emptyList()
        val lines = mutableListOf<ExpenseLine>()
        forEachRowBelow(grid, r0, c) { r, label ->
            val amount = grid.numberRightOf(r, c, maxColumns = 3)
            if (amount != null && amount != 0.0) lines += ExpenseLine(label, abs(amount))
        }
        return lines.sortedByDescending { it.monthly }
    }

    /**
     * The brokerage block: each row an account, each person's column their balance; one amount
     * merged across both columns is a joint account. Two separate amounts are two accounts of
     * the same kind, one each.
     */
    private fun accounts(grid: SheetGrid, people: List<String>): List<Account> {
        val (r0, c) = grid.findLabel("Brokerage Accounts") ?: return emptyList()
        val personColumns = headerColumns(grid, r0, c, people)
        if (personColumns.isEmpty()) return emptyList()
        val out = mutableListOf<Account>()
        forEachRowBelow(grid, r0, c) { r, label ->
            val first = personColumns.first().first
            val merge = grid.mergeAt(r, first)
            if (merge != null && merge.endColumn > personColumns.last().first) {
                grid.number(r, first)?.takeIf { it != 0.0 }?.let { out += Account(label, Owner.Joint, it, categorise(label)) }
            } else {
                personColumns.forEach { (col, name) ->
                    grid.number(r, col)?.takeIf { it != 0.0 }?.let { out += Account(label, Owner.Person(name), it, categorise(label)) }
                }
            }
        }
        return out
    }

    /**
     * The people's columns in a block's header row, right of its title at [column]. With the
     * people known (from "Flow In"), only their names count, looked for a few columns over so an
     * inserted column doesn't lose them; otherwise the headings right beside the title.
     */
    private fun headerColumns(grid: SheetGrid, row: Int, column: Int, people: List<String>): List<Pair<Int, String>> {
        if (people.isEmpty()) return (column + 1..column + 3).mapNotNull { col -> grid.text(row, col)?.let { col to it } }
        return (column + 1..column + HEADER_REACH).mapNotNull { col -> grid.text(row, col)?.takeIf { t -> people.any { it.equals(t, true) } }?.let { col to it } }
    }

    /** "Checking/Savings" and a lone "Cash" line: money in the bank, shared. */
    private fun cashAccounts(grid: SheetGrid): List<Account> {
        val out = mutableListOf<Account>()
        grid.findLabel("Checking/Savings")?.let { (r0, c) ->
            forEachRowBelow(grid, r0, c, maxBlank = 1) { r, label ->
                grid.numberRightOf(r, c)?.takeIf { it != 0.0 }?.let {
                    out += Account(if (label.equals("combined", true)) "Checking & savings" else label, Owner.Joint, it, AccountCategory.CASH)
                }
            }
        }
        grid.findLabel("Cash")?.let { (r, c) ->
            grid.numberRightOf(r, c)?.takeIf { it != 0.0 }?.let { out += Account("Cash", Owner.Joint, it, AccountCategory.CASH) }
        }
        return out
    }

    /** The equity the house holds, as the sheet counts it toward total assets. */
    private fun homeEquityAccount(grid: SheetGrid): Account? {
        val equity = homeEquity(grid) ?: return null
        val v = equity.valueAdded ?: equity.equity ?: return null
        return Account("Home equity", Owner.Joint, v, AccountCategory.HOME)
    }

    private fun grandTotal(grid: SheetGrid): Double? {
        val (r0, c) = grid.findLabel("Brokerage Accounts") ?: return null
        return grid.findLabel("Grand Total", rowsFrom = r0, columns = c..c)?.let { (r, col) -> grid.numberRightOf(r, col) }
    }

    private fun debts(grid: SheetGrid, people: List<String>): List<Debt> {
        // The block's title is a "Debt" cell with the people's names heading the columns to its
        // right a row or two down — not, say, the history table's "Debt" column header.
        var c = -1
        var headerRow = -1
        val columns = mutableListOf<Pair<Int, String>>()
        var from = 0
        while (columns.isEmpty()) {
            val (r0, c0) = grid.findLabel("Debt", rowsFrom = from) ?: return emptyList()
            for (r in r0..r0 + 2) {
                val found = (c0 + 1..c0 + HEADER_REACH).mapNotNull { col -> grid.text(r, col)?.takeIf { t -> people.any { it.equals(t, true) } }?.let { col to it } }
                if (found.isNotEmpty()) {
                    columns += found
                    headerRow = r
                    c = c0
                    break
                }
            }
            from = r0 + 1
        }
        val out = mutableListOf<Debt>()
        forEachRowBelow(grid, headerRow, c) { r, label ->
            val (name, apr, note) = splitDebtLabel(label)
            val merge = grid.mergeAt(r, columns.first().first)
            if (merge != null && merge.endColumn > columns.last().first) {
                grid.number(r, columns.first().first)?.let { out += Debt(name, Owner.Joint, abs(it), apr, note) }
            } else {
                val amounts = columns.mapNotNull { (col, person) -> grid.number(r, col)?.let { person to it } }
                val owing = amounts.filter { it.second != 0.0 }
                when {
                    owing.isEmpty() && amounts.isNotEmpty() -> out += Debt(name, Owner.Joint, 0.0, apr, note)
                    else -> owing.forEach { (person, v) -> out += Debt(name, Owner.Person(person), abs(v), apr, note) }
                }
            }
        }
        return out
    }

    /** "Student Loans (APR 3.08%, ~5 years left)" → name, 3.08, "~5 years left". */
    internal fun splitDebtLabel(label: String): Triple<String, Double?, String?> {
        val open = label.indexOf('(')
        if (open < 0) return Triple(label.trim(), null, null)
        val name = label.substring(0, open).trim()
        val inside = label.substring(open + 1).substringBefore(')')
        val apr = Regex("APR\\s*([0-9.]+)\\s*%", RegexOption.IGNORE_CASE).find(inside)?.groupValues?.get(1)?.toDoubleOrNull()
        val note = inside.split(',').map { it.trim() }.filterNot { it.startsWith("APR", true) }.joinToString(", ").takeIf { it.isNotBlank() }
        return Triple(name, apr, note)
    }

    private fun homeEquity(grid: SheetGrid): HomeEquity? {
        val (r0, c) = grid.findLabel("Home Asset") ?: return null
        val to = r0 + 25
        fun v(label: String) = grid.findLabel(label, rowsFrom = r0, rowsTo = to, columns = c..c)?.let { (r, col) -> grid.lastNumberRightOf(r, col) }
        fun starting(prefix: String) = grid.findStartingWith(prefix, rowsFrom = r0, rowsTo = to, columns = c..c)?.let { (r, col) -> grid.lastNumberRightOf(r, col) }
        val improvements = mutableListOf<ExpenseLine>()
        grid.findLabel("Home Improvement", rowsFrom = r0 - 1, rowsTo = to)?.let { (ir, ic) ->
            forEachRowBelow(grid, ir, ic) { r, label ->
                grid.numberRightOf(r, ic)?.takeIf { it != 0.0 }?.let { improvements += ExpenseLine(label, it) }
            }
        }
        return HomeEquity(
            value = v("Value at Sale") ?: v("Home Value"),
            deposit = v("Cash Deposit"),
            originalLoan = v("Original Loan Amount"),
            unpaidPrincipal = v("Unpaid Principle") ?: v("Unpaid Principal"),
            principalPaid = v("Total Principle Paid") ?: v("Total Principal Paid"),
            equity = v("Total Equity"),
            valueAdded = starting("Deposit"),
            estimatedAssetValue = starting("Estimated Sale"),
            improvements = improvements,
        )
    }

    private fun vesting(grid: SheetGrid): List<VestEvent> {
        val (r0, c) = grid.findLabel("Future Holdings") ?: return emptyList()
        val typeHeader = grid.findLabel("Type", rowsFrom = r0, rowsTo = r0 + 4, columns = c..c + 3) ?: return emptyList()
        val typeCol = typeHeader.second
        val headerColumns = typeCol..typeCol + 4
        val amountCol = grid.findLabel("Amount", rowsFrom = typeHeader.first, rowsTo = typeHeader.first + 1, columns = headerColumns)?.second ?: (typeCol + 1)
        val postTaxCol = grid.findLabel("Post Tax", rowsFrom = typeHeader.first, rowsTo = typeHeader.first + 1, columns = headerColumns)?.second
        var year: Int? = null
        val out = mutableListOf<VestEvent>()
        var r = typeHeader.first + 1
        var blanks = 0
        while (r < grid.rowCount && blanks <= BLOCK_GAP) {
            if (grid.isMergeTail(r, c)) {
                r++
                continue
            }
            val label = grid.text(r, c)
            val asNumber = grid.number(r, c)
            when {
                label != null && label.lowercase().startsWith("total") -> break

                asNumber != null && asNumber in 1900.0..2200.0 && grid.isBlank(r, typeCol) -> {
                    year = asNumber.roundToInt()
                    blanks = 0
                }

                label != null -> {
                    blanks = 0
                    val amount = grid.number(r, amountCol)
                    val type = grid.text(r, typeCol).orEmpty()
                    if (amount != null && amount != 0.0) {
                        out += VestEvent(
                            epochSeconds = year?.let { y -> parseMonthDay(label, y) },
                            label = listOfNotNull(label, year?.toString()).joinToString(", "),
                            type = type,
                            amount = amount,
                            postTax = postTaxCol?.let { grid.number(r, it) },
                        )
                    }
                }

                else -> blanks++
            }
            r++
        }
        return out
    }

    /** "Aug 28th" in [year] → epoch seconds at that day's UTC midnight. */
    internal fun parseMonthDay(label: String, year: Int): Long? {
        val m = Regex("([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{1,2})").find(label) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].lowercase()).takeIf { it >= 0 }?.plus(1) ?: return null
        val day = m.groupValues[2].toInt()
        return runCatching { LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).epochSeconds }.getOrNull()
    }

    /** The tickers in the sheet's "Live TSLA Price" cells, in Yahoo's spelling. */
    private fun watchlist(grid: SheetGrid): List<String> {
        val out = linkedSetOf<String>()
        val pattern = Regex("^live\\s+(.+?)\\s+price$")
        for (r in 0 until grid.rowCount) {
            for (c in 0 until grid.columnCount(r)) {
                if (grid.isMergeTail(r, c)) continue
                val t = grid.text(r, c)?.lowercase() ?: continue
                val name = pattern.find(t)?.groupValues?.get(1) ?: continue
                out += TICKER_ALIASES[name] ?: name.uppercase()
            }
        }
        return out.toList()
    }

    private fun history(grid: SheetGrid, notes: MutableList<ParseNote>): List<Snapshot> {
        val dateCell = grid.find { it == "date" }?.let { first ->
            // The history's "Date" heads a row that also has "Total Assets".
            var at: Pair<Int, Int>? = first
            var from = 0
            while (at != null && grid.findLabel("Total Assets", rowsFrom = at.first, rowsTo = at.first + 1) == null) {
                from = at.first + 1
                at = grid.find(rowsFrom = from) { it == "date" }
            }
            at
        } ?: return emptyList()
        val (hr, dc) = dateCell
        fun col(name: String) = grid.findLabel(name, rowsFrom = hr, rowsTo = hr + 1)?.second
        val expensesCol = col("Monthly Expenses")
        val incomeCol = col("Monthly Income")
        val profitCol = col("Monthly Profit")
        val assetsCol = col("Total Assets")
        val debtCol = col("Debt")
        val out = mutableListOf<Snapshot>()
        val valueColumns = listOfNotNull(expensesCol, incomeCol, profitCol, assetsCol, debtCol)
        var gap = 0
        var undated = 0
        var pendingUndated = 0
        for (r in hr + 1 until grid.rowCount) {
            // A blank row or two inside the table (or a row whose date was cleared) is skipped,
            // not the table's end; a run of rows with no date is.
            val serial = grid.number(r, dc)?.takeIf { it > 0 }
            if (serial == null) {
                if (valueColumns.any { grid.number(r, it) != null }) pendingUndated++
                if (++gap > HISTORY_GAP) break
                continue
            }
            // Undated rows count as skipped only once a dated row follows them: below the table's
            // last date they're something else.
            undated += pendingUndated
            pendingUndated = 0
            gap = 0
            out += Snapshot(
                epochSeconds = sheetsSerialToEpochSeconds(serial),
                monthlyExpenses = expensesCol?.let { grid.number(r, it) }?.let(::abs),
                monthlyIncome = incomeCol?.let { grid.number(r, it) },
                monthlyProfit = profitCol?.let { grid.number(r, it) },
                totalAssets = assetsCol?.let { grid.number(r, it) },
                debt = debtCol?.let { grid.number(r, it) }?.let(::abs),
            )
        }
        if (undated > 0) notes += ParseNote(SheetSection.HISTORY, "$undated row${if (undated == 1) "" else "s"} in the history ${if (undated == 1) "has" else "have"} figures but no date, so ${if (undated == 1) "it was" else "they were"} left out")
        return out.sortedBy { it.epochSeconds }
    }

    /** The combined table: the header row that has both "Year" and "Take Home". */
    private fun taxYears(grid: SheetGrid): List<TaxYear> {
        // The first "Take Home" heading whose row also has "Year" (the per-person tables above it have no take-home column).
        var hr = -1
        var yearCol = -1
        var from = 0
        while (yearCol < 0) {
            val row = grid.find(rowsFrom = from) { it == "take home" }?.first ?: return emptyList()
            grid.findLabel("Year", rowsFrom = row, rowsTo = row + 1)?.second?.let {
                hr = row
                yearCol = it
            }
            from = row + 1
        }
        fun col(prefix: String) = grid.findStartingWith(prefix, rowsFrom = hr, rowsTo = hr + 1, columns = yearCol..yearCol + 10)?.second
        val incomeCol = col("Income Pre-Tax")
        val taxesCol = col("Taxes Paid")
        val takeHomeCol = col("Take Home")
        val rateCol = col("Effective Tax Rate")
        val investedCol = col("Investments Made")
        val investRateCol = col("Investment Percentage")
        val out = mutableListOf<TaxYear>()
        for (r in hr + 1 until grid.rowCount) {
            val year = grid.number(r, yearCol)?.roundToInt()?.takeIf { it in 1900..2200 } ?: break
            out += TaxYear(
                year = year,
                incomePreTax = incomeCol?.let { grid.number(r, it) },
                taxes = taxesCol?.let { grid.number(r, it) },
                takeHome = takeHomeCol?.let { grid.number(r, it) },
                effectiveRate = rateCol?.let { grid.number(r, it) },
                invested = investedCol?.let { grid.number(r, it) },
                investedRate = investRateCol?.let { grid.number(r, it) },
            )
        }
        return out
    }

    private fun mortgagePlan(grid: SheetGrid): MortgagePlan? {
        val (r0, c) = grid.findLabel("Mortgage Calculator") ?: return null
        val to = r0 + 20
        fun v(label: String) = grid.valueOf(label, rowsFrom = r0, rowsTo = to, columns = c..c)
        val price = v("Home Price") ?: return null
        val affordableCol = grid.findStartingWith("Estimated Affordable")?.second
        val affordability = mutableListOf<AffordabilityPoint>()
        if (affordableCol != null) {
            for (r in 0 until grid.rowCount) {
                for (col in 0 until grid.columnCount(r)) {
                    if (grid.isMergeTail(r, col)) continue
                    val t = grid.text(r, col) ?: continue
                    val lower = t.lowercase()
                    if (!(lower.startsWith("antipated") || lower.startsWith("anticipated"))) continue
                    val affordable = grid.number(r, affordableCol) ?: continue
                    val period = t.substringAfter("Expenses", t).trim().ifEmpty { t }
                    affordability += AffordabilityPoint(period, grid.numberRightOf(r, col, 1)?.let(::abs), affordable)
                }
            }
        }
        return MortgagePlan(
            homePrice = price,
            downPaymentFraction = v("Down Payment (%)")?.let { if (it > 1) it / 100 else it } ?: 0.2,
            rate = v("Interest Rate")?.let { if (it > 1) it / 100 else it } ?: 0.065,
            termYears = v("Loan Term (Years)")?.roundToInt() ?: 30,
            propertyTaxAnnual = v("Property Tax (Annual)") ?: 0.0,
            insuranceAnnual = v("Home Insurance (Annual)") ?: 0.0,
            hoaMonthly = v("HOA (Monthly)") ?: 0.0,
            affordability = affordability,
        )
    }

    private fun houseSale(grid: SheetGrid): HouseSale = HouseSale(
        purchasePrice = grid.valueOf("Purchase Price"),
        soldPrice = grid.valueOf("Sold Price"),
        profit = grid.valueOf("Total Profit"),
        roi = grid.findStartingWith("ROI")?.let { (r, c) -> grid.numberRightOf(r, c) },
        cashReceived = grid.valueOf("Total Money Received from Sale"),
    )

    /**
     * Calls [row] for each labelled row under [headerRow] in [column], stopping at a "Total"
     * label or after [maxBlank] blank rows in a row.
     */
    private inline fun forEachRowBelow(grid: SheetGrid, headerRow: Int, column: Int, maxBlank: Int = BLOCK_GAP, row: (Int, String) -> Unit) {
        var blanks = 0
        var r = headerRow + 1
        while (r < grid.rowCount && blanks <= maxBlank) {
            // A label merged down over several rows is one row of the block, not several.
            if (grid.isMergeTail(r, column)) {
                r++
                continue
            }
            val label = grid.text(r, column)
            when {
                label == null -> blanks++

                label.lowercase().startsWith("total") || label.lowercase().startsWith("grand total") -> return

                else -> {
                    blanks = 0
                    row(r, label)
                }
            }
            r++
        }
    }

    internal fun categorise(name: String): AccountCategory {
        val n = name.lowercase()
        return when {
            "529" in n -> AccountCategory.EDUCATION
            RETIREMENT_WORDS.any { it in n } -> AccountCategory.RETIREMENT
            listOf("checking", "savings", "cash").any { it in n } -> AccountCategory.CASH
            else -> AccountCategory.INVESTING
        }
    }

    /** How far right of a block's title its people's columns may sit. */
    private const val HEADER_REACH = 6

    /** Blank rows a block may have inside it before it's taken to have ended. */
    private const val BLOCK_GAP = 4

    /** Rows without a date the history may have inside it before it's taken to have ended. */
    private const val HISTORY_GAP = 4

    /** Google Sheets' serial day (days since 1899-12-30) → epoch seconds. */
    fun sheetsSerialToEpochSeconds(serial: Double): Long = ((serial - 25_569.0) * 86_400.0).toLong()

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * Words in an account's name that mark it as retirement savings: the account types, and the
     * big workplace-plan record keepers an account is often just named after.
     */
    private val RETIREMENT_WORDS = listOf(
        "roth", "401", "403", "457", "ira", "pension", "hsa", "retire", "tsp",
        "adp", "empower", "voya", "tiaa", "principal", "transamerica", "netbenefits",
    )

    /**
     * Common names a "Live … Price" cell might use instead of the ticker, in Yahoo's spelling
     * (anything else is taken as a ticker as written). "appl" is the usual slip for Apple.
     */
    private val TICKER_ALIASES = mapOf(
        "apple" to "AAPL",
        "appl" to "AAPL",
        "nvidia" to "NVDA",
        "tesla" to "TSLA",
        "google" to "GOOGL",
        "alphabet" to "GOOGL",
        "amazon" to "AMZN",
        "microsoft" to "MSFT",
        "meta" to "META",
        "btc" to "BTC-USD",
        "bitcoin" to "BTC-USD",
        "eth" to "ETH-USD",
        "ethereum" to "ETH-USD",
    )
}
