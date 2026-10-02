package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_section_accounts
import homesafe.shared.generated.resources.fin_data_section_accounts_looked_for
import homesafe.shared.generated.resources.fin_data_section_cash
import homesafe.shared.generated.resources.fin_data_section_cash_looked_for
import homesafe.shared.generated.resources.fin_data_section_debts
import homesafe.shared.generated.resources.fin_data_section_debts_looked_for
import homesafe.shared.generated.resources.fin_data_section_expenses
import homesafe.shared.generated.resources.fin_data_section_expenses_looked_for
import homesafe.shared.generated.resources.fin_data_section_history
import homesafe.shared.generated.resources.fin_data_section_history_looked_for
import homesafe.shared.generated.resources.fin_data_section_home
import homesafe.shared.generated.resources.fin_data_section_home_looked_for
import homesafe.shared.generated.resources.fin_data_section_house_sale
import homesafe.shared.generated.resources.fin_data_section_house_sale_looked_for
import homesafe.shared.generated.resources.fin_data_section_income
import homesafe.shared.generated.resources.fin_data_section_income_looked_for
import homesafe.shared.generated.resources.fin_data_section_mortgage
import homesafe.shared.generated.resources.fin_data_section_mortgage_looked_for
import homesafe.shared.generated.resources.fin_data_section_tax_years
import homesafe.shared.generated.resources.fin_data_section_tax_years_looked_for
import homesafe.shared.generated.resources.fin_data_section_total_assets
import homesafe.shared.generated.resources.fin_data_section_total_assets_looked_for
import homesafe.shared.generated.resources.fin_data_section_totals
import homesafe.shared.generated.resources.fin_data_section_totals_looked_for
import homesafe.shared.generated.resources.fin_data_section_vesting
import homesafe.shared.generated.resources.fin_data_section_vesting_looked_for
import homesafe.shared.generated.resources.fin_data_section_watchlist
import homesafe.shared.generated.resources.fin_data_section_watchlist_looked_for
import org.jetbrains.compose.resources.StringResource

/**
 * The parts of the budget sheet the app reads, each found by a title in the sheet rather than by
 * where it sits ([lookedFor] says what). Moving a part, or adding and removing rows in it, is
 * fine; renaming its title is what loses it, and the sync page says which title it needs.
 */
enum class SheetSection(val label: StringResource, val lookedFor: StringResource) {
    INCOME(Res.string.fin_data_section_income, Res.string.fin_data_section_income_looked_for),
    EXPENSES(Res.string.fin_data_section_expenses, Res.string.fin_data_section_expenses_looked_for),
    TOTALS(Res.string.fin_data_section_totals, Res.string.fin_data_section_totals_looked_for),
    ACCOUNTS(Res.string.fin_data_section_accounts, Res.string.fin_data_section_accounts_looked_for),
    CASH(Res.string.fin_data_section_cash, Res.string.fin_data_section_cash_looked_for),
    TOTAL_ASSETS(Res.string.fin_data_section_total_assets, Res.string.fin_data_section_total_assets_looked_for),
    DEBTS(Res.string.fin_data_section_debts, Res.string.fin_data_section_debts_looked_for),
    HOME(Res.string.fin_data_section_home, Res.string.fin_data_section_home_looked_for),
    VESTING(Res.string.fin_data_section_vesting, Res.string.fin_data_section_vesting_looked_for),
    WATCHLIST(Res.string.fin_data_section_watchlist, Res.string.fin_data_section_watchlist_looked_for),
    HISTORY(Res.string.fin_data_section_history, Res.string.fin_data_section_history_looked_for),
    TAX_YEARS(Res.string.fin_data_section_tax_years, Res.string.fin_data_section_tax_years_looked_for),
    MORTGAGE(Res.string.fin_data_section_mortgage, Res.string.fin_data_section_mortgage_looked_for),
    HOUSE_SALE(Res.string.fin_data_section_house_sale, Res.string.fin_data_section_house_sale_looked_for),
}

/** Something the parser noticed while reading a part: rows it had to leave out, say. */
@Immutable
data class ParseNote(val section: SheetSection?, val message: UiText)

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
    val found: UiText?,
)

/** One of the sheet's charts: its [title] as the card shows it, its [tab], and why it can't be drawn when it can't. */
@Immutable
data class ChartHealth(val title: UiText, val tab: String, val problem: UiText?)

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
