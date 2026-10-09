package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_date_month_short_year
import homesafe.shared.generated.resources.finance_date_month_year
import org.jetbrains.compose.resources.stringResource

/**
 * The finance app's date words already read from the strings file, for the places that write a
 * date where nothing can be looked up: a chart formats its axis labels while it draws, for times
 * that slide as one range morphs into the next. Everywhere else a date is a
 * [com.meticulouscreations.homesafe.text.UiText] from [FinanceFormat].
 *
 * [months] are the twelve short names, January first; the patterns are the strings' own
 * (`%1$s %2$d`), filled in here the way the resource formatter fills them, so a translation's
 * names and word order hold on an axis too. In a composition, [rememberFinanceDates] reads them.
 */
@Immutable
class FinanceDates(months: List<String>, private val monthYearPattern: String, private val monthShortYearPattern: String) {
    private val months: List<String> = months.toList()

    /** "Sep 2026". */
    fun monthYear(epochSeconds: Long, offsetSeconds: Int = 0): String {
        val d = FinanceFormat.local(epochSeconds, offsetSeconds)
        return monthYearPattern.filled(months[d.month.ordinal], d.year)
    }

    /** "Nov '19", where a full year doesn't fit. */
    fun monthShortYear(epochSeconds: Long): String {
        val d = FinanceFormat.local(epochSeconds, 0)
        return monthShortYearPattern.filled(months[d.month.ordinal], FinanceFormat.twoDigitYear(epochSeconds))
    }

    override fun equals(other: Any?) = other is FinanceDates && months == other.months && monthYearPattern == other.monthYearPattern && monthShortYearPattern == other.monthShortYearPattern
    override fun hashCode() = 31 * (31 * months.hashCode() + monthYearPattern.hashCode()) + monthShortYearPattern.hashCode()
}

/** The positional placeholders a string resource may carry: `%1$s`, `%2$d`. */
private val placeholder = Regex("""%(\d+)\$[ds]""")

/** This pattern with each placeholder replaced by the argument it numbers; one that numbers no argument stays as written. */
private fun String.filled(vararg args: Any): String = placeholder.replace(this) { match -> args.getOrNull(match.groupValues[1].toInt() - 1)?.toString() ?: match.value }

/** The date words in the reader's language, read once and kept until the language changes. */
@Composable
fun rememberFinanceDates(): FinanceDates {
    val months = FinanceFormat.MONTHS.map { stringResource(it) }
    val monthYear = stringResource(Res.string.finance_date_month_year)
    val monthShortYear = stringResource(Res.string.finance_date_month_short_year)
    return remember(months, monthYear, monthShortYear) { FinanceDates(months, monthYear, monthShortYear) }
}
