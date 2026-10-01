package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable

/**
 * The parts of the budget sheet the app reads, each found by a title in the sheet rather than by
 * where it sits ([lookedFor] says what). Moving a part, or adding and removing rows in it, is
 * fine; renaming its title is what loses it, and the sync page says which title it needs.
 */
enum class SheetSection(val label: String, val lookedFor: String) {
    INCOME("Income", "a cell reading “Flow In” with each person's name to its right, and “Monthly Income” below"),
    EXPENSES("Monthly spending", "a cell reading “Flow Out”, with an amount beside each line under it"),
    TOTALS("Monthly totals", "cells reading “Monthly Combined Income”, “Total Monthly Expenses” and “Net Monthly Profit”, each with its number beside it"),
    ACCOUNTS("Accounts", "a cell reading “Brokerage Accounts” with each person's name in its row"),
    CASH("Cash", "a cell reading “Checking/Savings” (or “Cash”) with an amount beside it"),
    TOTAL_ASSETS("Total assets", "a cell reading “Total Assets” with its number beside it"),
    DEBTS("Debt", "a cell reading “Debt” with each person's name in the row below it, or in its own row"),
    HOME("Home", "a cell reading “Home Asset” with “Total Equity” or “Deposit…” under it"),
    VESTING("Vesting", "a cell reading “Future Holdings”, with a “Type” and an “Amount” heading just under it"),
    WATCHLIST("Watchlist", "cells like “Live TSLA Price”"),
    HISTORY("History", "a “Date” heading in the same row as “Total Assets”, with a date in each row under it"),
    TAX_YEARS("Income & taxes", "a “Take Home” heading in the same row as “Year”, with a year in each row under it"),
    MORTGAGE("Mortgage planner", "a cell reading “Mortgage Calculator” with “Home Price” under it"),
    HOUSE_SALE("Last house sale", "a cell reading “Sold Price” with its number beside it"),
}

/** Something the parser noticed while reading a part: rows it had to leave out, say. */
@Immutable
data class ParseNote(val section: SheetSection?, val message: String)

enum class SectionStatus {
    /** Found and read. */
    OK,

    /** Its title is in the sheet, but nothing could be read under it. */
    EMPTY,

    /** Its title isn't in the sheet. */
    MISSING,
}

@Immutable
data class SectionHealth(
    val section: SheetSection,
    val status: SectionStatus,
    /** What was read, as a phrase: "22 lines", "14 accounts". Null when nothing was. */
    val found: String?,
)

@Immutable
data class ChartHealth(val title: String, val tab: String, val problem: String?)

/**
 * How the last read of the sheet went, part by part and chart by chart. Built on every sync, so
 * an edit that loses a part shows up on the next one.
 */
@Immutable
data class SheetHealth(val sections: List<SectionHealth>, val charts: List<ChartHealth>, val notes: List<ParseNote>) {
    val sectionProblems: List<SectionHealth> get() = sections.filter { it.status != SectionStatus.OK }
    val chartProblems: List<ChartHealth> get() = charts.filter { it.problem != null }

    /** Everything that needs a look: parts not read, charts not drawn, rows left out. */
    val problemCount: Int get() = sectionProblems.size + chartProblems.size + notes.size

    companion object {
        val Unchecked = SheetHealth(emptyList(), emptyList(), emptyList())
    }
}
