package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BudgetAlert]: what a phone makes of the relay's budget push (`budget_check` in relay/relay.py). */
class BudgetAlertTest {

    /** The data of the relay's push for one person over their limit, as its own test pins it. */
    private val push = mapOf(
        "budget" to "1", "budget_kind" to "person_100", "month" to "2026-10", "spent" to "250.00", "limit" to "200.00", "person" to "Andrew", "days_left" to "24",
        "notif_id" to "budget-2026-10-person:Andrew_100", "title" to "Andrew is over budget", "body" to "$250 spent of $200, with 24 days left this month.",
    )

    private fun alert(data: Map<String, String>) = BudgetAlert.from { data[it] }

    @Test
    fun theRelaysPushReadsAsWhatItSays() {
        assertEquals(
            BudgetAlert("budget-2026-10-person:Andrew_100", BudgetAlert.Kind.PERSON_OVER, "2026-10", spent = 250.0, limit = 200.0, person = "Andrew", daysLeft = 24),
            alert(push),
        )
    }

    @Test
    fun everyLineTheRelayKnowsHasAKind() {
        val kinds = listOf("total_80", "total_100", "savings", "person_100", "family_100").map { alert(push + ("budget_kind" to it))?.kind }
        assertEquals(BudgetAlert.Kind.entries.toList(), kinds)
    }

    @Test
    fun aDetectionIsNotABudgetAlert() {
        val detection = mapOf("title" to "Front Door", "body" to "A person", "camera" to "front_door", "start_time" to "1791374400")
        assertFalse(BudgetAlert.isBudget { detection[it] })
        assertNull(alert(detection))
    }

    @Test
    fun aPushThisBuildCannotWordIsStillKnownToBeAboutTheBudget() {
        // A line a newer relay tells of, an amount that isn't one, a person's limit with nobody named, no word of the days left.
        for (broken in listOf(push + ("budget_kind" to "weekly_100"), push + ("spent" to "lots"), push - "limit", push + ("person" to ""), push - "days_left")) {
            assertTrue(BudgetAlert.isBudget { broken[it] })
            assertNull(alert(broken), broken.toString())
        }
    }

    @Test
    fun whatIsMissingButNotNeededIsFilledIn() {
        val bare = alert(mapOf("budget" to "1", "budget_kind" to "total_100", "month" to "2026-10", "spent" to "4560", "limit" to "4500", "person" to "", "days_left" to "-1"))
        assertEquals(BudgetAlert("budget-2026-10-total_100", BudgetAlert.Kind.OVER, "2026-10", spent = 4560.0, limit = 4500.0, person = null, daysLeft = 0), bare)
    }
}
