package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetPace
import com.meticulouscreations.homesafe.finance.domain.PaceStatus
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_headline_close
import homesafe.shared.generated.resources.fin_budget_headline_dipping
import homesafe.shared.generated.resources.fin_budget_headline_no_limit
import homesafe.shared.generated.resources.fin_budget_headline_over
import homesafe.shared.generated.resources.fin_budget_headline_projected_over
import homesafe.shared.generated.resources.fin_budget_headline_under
import homesafe.shared.generated.resources.fin_budget_verdict_close
import homesafe.shared.generated.resources.fin_budget_verdict_dipping
import homesafe.shared.generated.resources.fin_budget_verdict_ended_over
import homesafe.shared.generated.resources.fin_budget_verdict_ended_under
import homesafe.shared.generated.resources.fin_budget_verdict_last_day
import homesafe.shared.generated.resources.fin_budget_verdict_no_limit
import homesafe.shared.generated.resources.fin_budget_verdict_over
import homesafe.shared.generated.resources.fin_budget_verdict_over_near_savings
import homesafe.shared.generated.resources.fin_budget_verdict_projected_over
import homesafe.shared.generated.resources.fin_budget_verdict_projected_over_on
import homesafe.shared.generated.resources.fin_budget_verdict_under
import org.jetbrains.compose.resources.StringResource

/**
 * The Budget page's plain words for where the month stands: two or three words over the number
 * ([headline]) and one sentence under it ([verdict]). Each is a whole sentence from the strings
 * file, with the amounts written into it.
 */
internal object BudgetNarrator {

    fun headline(status: PaceStatus): StringResource = when (status) {
        PaceStatus.NO_LIMIT -> Res.string.fin_budget_headline_no_limit
        PaceStatus.UNDER -> Res.string.fin_budget_headline_under
        PaceStatus.PROJECTED_OVER -> Res.string.fin_budget_headline_projected_over
        PaceStatus.CLOSE -> Res.string.fin_budget_headline_close
        PaceStatus.OVER -> Res.string.fin_budget_headline_over
        PaceStatus.DIPPING -> Res.string.fin_budget_headline_dipping
    }

    fun verdict(budget: Budget, pace: BudgetPace): UiText {
        val limit = pace.limit
        val left = limit?.let { it - pace.spent } ?: 0.0
        fun money(value: Double) = FinanceFormat.money(value, 0)
        return when {
            pace.status == PaceStatus.DIPPING -> UiText.of(Res.string.fin_budget_verdict_dipping, money(pace.spent - (pace.savingsLine ?: 0.0)))

            limit == null -> UiText.of(Res.string.fin_budget_verdict_no_limit)

            // A month that is over is summed up, not projected.
            !budget.isCurrentMonth -> if (left >= 0) UiText.of(Res.string.fin_budget_verdict_ended_under, money(left)) else UiText.of(Res.string.fin_budget_verdict_ended_over, money(-left))

            pace.status == PaceStatus.OVER -> {
                val toSavings = pace.savingsLine?.let { it - pace.spent }?.takeIf { it > 0 }
                if (toSavings != null) UiText.of(Res.string.fin_budget_verdict_over_near_savings, money(-left), money(toSavings)) else UiText.of(Res.string.fin_budget_verdict_over, money(-left))
            }

            pace.daysLeft <= 0 -> UiText.of(Res.string.fin_budget_verdict_last_day, money(left))

            pace.status == PaceStatus.CLOSE -> UiText.plural(Res.plurals.fin_budget_verdict_close, pace.daysLeft, money(left), pace.daysLeft)

            pace.status == PaceStatus.PROJECTED_OVER -> {
                val over = money(pace.projected - limit)
                val day = pace.limitDay
                if (day != null) {
                    UiText.of(Res.string.fin_budget_verdict_projected_over_on, money(pace.projected), over, FinanceFormat.dayOfMonth(budget.month, day))
                } else {
                    UiText.of(Res.string.fin_budget_verdict_projected_over, money(pace.projected), over)
                }
            }

            else -> UiText.plural(Res.plurals.fin_budget_verdict_under, pace.daysLeft, money(left), pace.daysLeft, money(pace.allowancePerDay ?: 0.0))
        }
    }
}
