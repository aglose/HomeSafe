package com.meticulouscreations.homesafe.finance.ui

import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetPace
import com.meticulouscreations.homesafe.finance.domain.PaceStatus
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_headline_close
import homesafe.shared.generated.resources.fin_budget_headline_dipping
import homesafe.shared.generated.resources.fin_budget_headline_ended_under
import homesafe.shared.generated.resources.fin_budget_headline_over
import homesafe.shared.generated.resources.fin_budget_headline_under
import homesafe.shared.generated.resources.fin_budget_verdict_close
import homesafe.shared.generated.resources.fin_budget_verdict_dipping
import homesafe.shared.generated.resources.fin_budget_verdict_ended_over
import homesafe.shared.generated.resources.fin_budget_verdict_ended_under
import homesafe.shared.generated.resources.fin_budget_verdict_last_day
import homesafe.shared.generated.resources.fin_budget_verdict_no_limit
import homesafe.shared.generated.resources.fin_budget_verdict_over
import homesafe.shared.generated.resources.fin_budget_verdict_over_near_savings
import homesafe.shared.generated.resources.fin_budget_verdict_projected_over_on
import homesafe.shared.generated.resources.fin_budget_verdict_under
import homesafe.shared.generated.resources.finance_date_month_day
import homesafe.shared.generated.resources.finance_month_oct
import kotlin.test.Test
import kotlin.test.assertEquals

/** [BudgetNarrator]: the one sentence under the month's number, for each way a month can stand. */
class BudgetNarratorTest {

    /** A 31-day month on its [day]th day with [perDay] spent on each day so far, against a $3,100 limit. */
    private fun month(day: Int, perDay: Double, limit: Double? = 3_100.0, savingsLine: Double? = null, current: Boolean = true): Budget {
        val base = BudgetFixtures.month(day = day)
        return base.copy(
            isCurrentMonth = current,
            config = base.config.copy(limits = BudgetLimits(total = limit)),
            savingsLine = savingsLine,
            spent = perDay * day,
            daily = List(day) { perDay },
            history = emptyList(),
        )
    }

    private fun verdict(budget: Budget): UiText = BudgetNarrator.verdict(budget, BudgetPace.of(budget))

    @Test
    fun underTheLimitSaysWhatIsLeftAndWhatADayCanCost() {
        assertEquals(UiText.plural(Res.plurals.fin_budget_verdict_under, 21, "$2,100", 21, "$100"), verdict(month(day = 10, perDay = 100.0)))
    }

    @Test
    fun runningAheadSaysWhereTheMonthEndsAndWhenTheLimitGoes() {
        assertEquals(
            UiText.of(Res.string.fin_budget_verdict_projected_over_on, "$4,408", "$1,308", UiText.of(Res.string.finance_date_month_day, UiText.of(Res.string.finance_month_oct), 22)),
            verdict(month(day = 10, perDay = 150.0)),
        )
    }

    @Test
    fun closeToTheLimitCountsTheDaysToGo() {
        assertEquals(UiText.plural(Res.plurals.fin_budget_verdict_close, 6, "$600", 6), verdict(month(day = 25, perDay = 100.0)))
    }

    @Test
    fun overTheLimitSaysByHowMuchAndHowFarTheSavingsAre() {
        assertEquals(UiText.of(Res.string.fin_budget_verdict_over, "$900"), verdict(month(day = 8, perDay = 500.0)))
        assertEquals(UiText.of(Res.string.fin_budget_verdict_over_near_savings, "$900", "$500"), verdict(month(day = 8, perDay = 500.0, savingsLine = 4_500.0)))
    }

    @Test
    fun intoTheSavingsSaysHowFar() {
        val budget = month(day = 10, perDay = 500.0, savingsLine = 4_500.0)
        assertEquals(UiText.of(Res.string.fin_budget_verdict_dipping, "$500"), verdict(budget))
        assertEquals(Res.string.fin_budget_headline_dipping, BudgetNarrator.headline(PaceStatus.DIPPING))
    }

    @Test
    fun theLastDayIsNotDividedIntoDays() {
        assertEquals(UiText.of(Res.string.fin_budget_verdict_last_day, "$1,550"), verdict(month(day = 31, perDay = 50.0)))
    }

    @Test
    fun aMonthLookedBackAtIsSummedUp() {
        assertEquals(UiText.of(Res.string.fin_budget_verdict_ended_under, "$310"), verdict(month(day = 31, perDay = 90.0, current = false)))
        assertEquals(UiText.of(Res.string.fin_budget_verdict_ended_over, "$310"), verdict(month(day = 31, perDay = 110.0, current = false)))
        // "Close to the limit" is said of a month that can still go over, not of one that didn't.
        assertEquals(Res.string.fin_budget_headline_ended_under, BudgetNarrator.headline(PaceStatus.CLOSE, current = false))
        assertEquals(Res.string.fin_budget_headline_close, BudgetNarrator.headline(PaceStatus.CLOSE))
        assertEquals(Res.string.fin_budget_headline_over, BudgetNarrator.headline(PaceStatus.OVER, current = false))
    }

    @Test
    fun withoutALimitItAsksForOne() {
        assertEquals(UiText.of(Res.string.fin_budget_verdict_no_limit), verdict(month(day = 10, perDay = 100.0, limit = null)))
        assertEquals(Res.string.fin_budget_headline_under, BudgetNarrator.headline(PaceStatus.UNDER))
    }
}
