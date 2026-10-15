package com.meticulouscreations.homesafe.data

import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.finance.domain.BudgetAlert
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A budget alert's words come out of Android's own string resources, with the push's amounts
 * written into them: here they are read as a phone reads them, plurals and all.
 */
class BudgetNotificationTextTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun alert(kind: BudgetAlert.Kind, daysLeft: Int, person: String? = null) =
        BudgetAlert("budget-2026-10-${kind.wire}", kind, "2026-10", spent = 4_560.4, limit = 4_500.0, person = person, daysLeft = daysLeft)

    @Test
    fun overTheMonthsLimitSaysWhatWasSpentAndTheDaysLeft() {
        assertEquals(
            "Over the month's budget" to "$4,560 spent of $4,500, with 12 days left this month.",
            BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.OVER, daysLeft = 12)),
        )
    }

    @Test
    fun oneDayIsADayAndTheLastDayIsSaidSo() {
        assertEquals("$4,560 spent of $4,500, with 1 day left this month.", BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.CLOSE, daysLeft = 1)).second)
        assertEquals("$4,560 spent of $4,500, on the last day of the month.", BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.CLOSE, daysLeft = 0)).second)
        assertEquals("Close to the month's budget", BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.CLOSE, daysLeft = 0)).first)
    }

    @Test
    fun aPersonsLimitNamesThePersonAndTheFamilyCardItself() {
        assertEquals("Sam is over budget", BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.PERSON_OVER, daysLeft = 3, person = "Sam")).first)
        assertEquals("The family card is over budget", BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.FAMILY_OVER, daysLeft = 3)).first)
    }

    @Test
    fun intoTheSavingsSaysTheLineThatWasPassed() {
        assertEquals(
            "Dipping into savings" to "The cards are at $4,560 this month, past the $4,500 that take-home leaves after the bills.",
            BudgetNotificationPoster.text(context, alert(BudgetAlert.Kind.SAVINGS, daysLeft = 3)),
        )
    }
}
