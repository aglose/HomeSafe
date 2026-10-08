package com.meticulouscreations.homesafe.finance.domain

/**
 * A push from the relay saying the month's card spending crossed a line (`budget_check` in
 * `relay/relay.py`), as the push's data spells it out. The relay also sends a title and a body
 * in English; a phone that reads these words its own notification from them instead.
 */
data class BudgetAlert(
    /** The notification's identity: a later push about the same line of the same month replaces it. */
    val id: String,
    val kind: Kind,
    /** `YYYY-MM`. */
    val month: String,
    val spent: Double,
    /** The line that was crossed: a limit, or what take-home leaves after the bills. */
    val limit: Double,
    /** Whose limit it was, for [Kind.PERSON_OVER]. */
    val person: String?,
    /** Days of the month still to come; zero on its last day. */
    val daysLeft: Int,
) {
    /** Which line was crossed; [wire] is the relay's `budget_kind`. */
    enum class Kind(val wire: String) {
        /** Four fifths of the month's limit. */
        CLOSE("total_80"),

        /** The month's limit. */
        OVER("total_100"),

        /** What take-home leaves after the bills that aren't on a card. */
        SAVINGS("savings"),

        /** One person's own limit. */
        PERSON_OVER("person_100"),

        /** The family card's limit. */
        FAMILY_OVER("family_100"),
    }

    companion object {
        const val KEY_BUDGET = "budget"
        const val KEY_KIND = "budget_kind"
        const val KEY_MONTH = "month"
        const val KEY_SPENT = "spent"
        const val KEY_LIMIT = "limit"
        const val KEY_PERSON = "person"
        const val KEY_DAYS_LEFT = "days_left"
        const val KEY_ID = "notif_id"

        /** Whether a push is about the budget at all, whatever else this build makes of it. */
        fun isBudget(values: (String) -> String?): Boolean = values(KEY_BUDGET) == "1"

        /**
         * From a push's data, or null when it isn't about the budget or doesn't say enough to
         * word: a kind this build doesn't know (a newer relay's), an amount or a count of days
         * that isn't one, a person's limit with no person. The caller then shows the relay's own
         * words.
         */
        fun from(values: (String) -> String?): BudgetAlert? {
            if (!isBudget(values)) return null
            val kind = Kind.entries.firstOrNull { it.wire == values(KEY_KIND) } ?: return null
            val spent = values(KEY_SPENT)?.toDoubleOrNull() ?: return null
            val limit = values(KEY_LIMIT)?.toDoubleOrNull() ?: return null
            val daysLeft = values(KEY_DAYS_LEFT)?.toIntOrNull() ?: return null
            val person = values(KEY_PERSON)?.takeIf { it.isNotBlank() }
            if (kind == Kind.PERSON_OVER && person == null) return null
            val month = values(KEY_MONTH).orEmpty()
            return BudgetAlert(
                id = values(KEY_ID)?.takeIf { it.isNotBlank() } ?: "budget-$month-${kind.wire}",
                kind = kind,
                month = month,
                spent = spent,
                limit = limit,
                person = person,
                daysLeft = daysLeft.coerceAtLeast(0),
            )
        }
    }
}
