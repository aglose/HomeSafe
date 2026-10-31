package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.RecurringCharge
import com.meticulouscreations.homesafe.finance.domain.RecurringCharges
import com.meticulouscreations.homesafe.finance.domain.RecurringRhythm
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.fin_recurring_a_month
import homesafe.shared.generated.resources.fin_recurring_about
import homesafe.shared.generated.resources.fin_recurring_bill_due
import homesafe.shared.generated.resources.fin_recurring_bill_next
import homesafe.shared.generated.resources.fin_recurring_checking
import homesafe.shared.generated.resources.fin_recurring_down
import homesafe.shared.generated.resources.fin_recurring_fewer
import homesafe.shared.generated.resources.fin_recurring_headline
import homesafe.shared.generated.resources.fin_recurring_how
import homesafe.shared.generated.resources.fin_recurring_last
import homesafe.shared.generated.resources.fin_recurring_monthly_due
import homesafe.shared.generated.resources.fin_recurring_monthly_next
import homesafe.shared.generated.resources.fin_recurring_more
import homesafe.shared.generated.resources.fin_recurring_running
import homesafe.shared.generated.resources.fin_recurring_stopped
import homesafe.shared.generated.resources.fin_recurring_up
import homesafe.shared.generated.resources.fin_recurring_weekly_due
import homesafe.shared.generated.resources.fin_recurring_weekly_next
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How many of the charges still running are listed before the rest are folded away. */
private const val RECURRING_SHOWN = 6

/** Whether the Budget page has recurring charges to show: only the month under way does, as of today. */
internal val Budget.showsRecurring: Boolean get() = isCurrentMonth && recurring?.charges?.isNotEmpty() == true

/**
 * What the cards and the checking account pay every week or month: what those still running come
 * to a month, each with when the next one is due, the sheet's line it is in, and whose it is (or
 * that checking pays it), a price that changed at its last charge, and
 * under them those that stopped (cancelled, or moved off the cards), so a cancellation can be
 * seen to have taken. The dearest first; past the first few, the rest unfold on a tap.
 */
@Composable
internal fun RecurringBlock(recurring: RecurringCharges, budget: Budget, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    var all by rememberSaveable { mutableStateOf(false) }
    val running = recurring.running
    val today = "${budget.month}-${budget.day.toString().padStart(2, '0')}"
    FinanceCard(modifier.padding(bottom = 10.dp).testTag("finance_budget_recurring"), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.fin_recurring_headline, FinanceFormat.money(recurring.monthly, 0)),
                style = FinanceTheme.type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(pluralStringResource(Res.plurals.fin_recurring_running, running.size, running.size), style = FinanceTheme.type.label, color = colors.textTertiary)
        }
        Spacer(Modifier.height(4.dp))
        (if (all) running else running.take(RECURRING_SHOWN)).forEach { RecurringRow(it, budget.config.people, today) }
        if (running.size > RECURRING_SHOWN) {
            Text(
                if (all) stringResource(Res.string.fin_recurring_fewer) else pluralStringResource(Res.plurals.fin_recurring_more, running.size - RECURRING_SHOWN, running.size - RECURRING_SHOWN),
                style = FinanceTheme.type.bodyStrong,
                color = colors.accent,
                modifier = Modifier.padding(vertical = 8.dp).handCursor().clickable(role = Role.Button) { all = !all }.testTag("finance_budget_recurring_more"),
            )
        }
        val stopped = recurring.stopped
        if (stopped.isNotEmpty()) {
            Text(stringResource(Res.string.fin_recurring_stopped), style = FinanceTheme.type.label, color = colors.textTertiary, modifier = Modifier.padding(top = 12.dp))
            stopped.forEach { RecurringRow(it, budget.config.people, today) }
        }
        Text(stringResource(Res.string.fin_recurring_how), style = FinanceTheme.type.micro, color = colors.textTertiary, modifier = Modifier.padding(top = 10.dp))
    }
}

/**
 * One recurring charge: its merchant, how often and when next (or when it last came, once it
 * stopped) and whose, and what it costs: a bill what it comes to a month, a weekly one what it
 * is each time and a month. A price that changed says what it was.
 */
@Composable
private fun RecurringRow(charge: RecurringCharge, people: List<String>, today: String) {
    val colors = FinanceTheme.colors
    val strong = if (charge.stopped) colors.textSecondary else colors.textPrimary
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(charge.name, style = FinanceTheme.type.body, color = strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Checking's is the household's, not a cardholder's; the sheet's line it is in is data, shown as written.
            val whose = if (charge.fromChecking) {
                stringResource(Res.string.fin_recurring_checking)
            } else {
                charge.bucket.takeIf { it != BucketId.Unassigned && (it !is BucketId.Person || it.name in people) }?.label()
            }
            Text(
                listOfNotNull(recurringWhen(charge, today), charge.line, whose).joinToString(stringResource(Res.string.common_dot_separator)),
                style = FinanceTheme.type.label,
                color = colors.textTertiary,
                // Two lines for an overdue one in a longer language, with whose it is.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (charge.bill) stringResource(Res.string.fin_recurring_about, FinanceFormat.money(charge.monthly, 0)) else FinanceFormat.money(charge.amount),
                style = FinanceTheme.type.bodyStrong,
                color = strong,
            )
            val was = charge.was
            when {
                was != null && !charge.stopped -> {
                    val up = charge.amount > was
                    val tint = if (up) colors.watch else colors.gain
                    Text(
                        stringResource(if (up) Res.string.fin_recurring_up else Res.string.fin_recurring_down, FinanceFormat.money(was)),
                        style = FinanceTheme.type.micro,
                        color = tint,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                charge.rhythm == RecurringRhythm.WEEKLY && !charge.stopped ->
                    Text(stringResource(Res.string.fin_recurring_a_month, FinanceFormat.money(charge.monthly, 0)), style = FinanceTheme.type.label, color = colors.textTertiary)
            }
        }
    }
}

/** How often it comes and when the next is due ("Monthly · next Nov 26"), that one is overdue, or, once it stopped, when it last came. */
@Composable
private fun recurringWhen(charge: RecurringCharge, today: String): String {
    val next = charge.next
    if (charge.stopped || next == null) return stringResource(Res.string.fin_recurring_last, FinanceFormat.dayOfMonth(charge.last).resolve())
    val day = FinanceFormat.dayOfMonth(next).resolve()
    val late = next < today
    return stringResource(
        when {
            charge.bill -> if (late) Res.string.fin_recurring_bill_due else Res.string.fin_recurring_bill_next
            charge.rhythm == RecurringRhythm.WEEKLY -> if (late) Res.string.fin_recurring_weekly_due else Res.string.fin_recurring_weekly_next
            else -> if (late) Res.string.fin_recurring_monthly_due else Res.string.fin_recurring_monthly_next
        },
        day,
    )
}

@Preview(name = "Budget · recurring charges", widthDp = 412, heightDp = 1100)
@Preview(name = "Budget · recurring charges, in Spanish", widthDp = 412, heightDp = 1100, locale = "es")
@Composable
private fun FinanceBudgetRecurringPreview() {
    FinanceStage {
        Column(Modifier.padding(previewPadding)) {
            val budget = BudgetFixtures.withRecurring
            RecurringBlock(budget.recurring!!, budget)
        }
    }
}
