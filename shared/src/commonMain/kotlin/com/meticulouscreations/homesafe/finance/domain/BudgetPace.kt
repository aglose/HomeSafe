package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.max

/** Where the month stands against its limit and against the savings, the worst that is true. */
enum class PaceStatus {
    /** No limit has been set, and the savings line isn't known or isn't crossed. */
    NO_LIMIT,

    /** Under the limit, and on course to stay there. */
    UNDER,

    /** Under the limit today, but spending at a rate that ends the month over it. */
    PROJECTED_OVER,

    /** Within a fifth of the limit. */
    CLOSE,

    /** Past the limit. */
    OVER,

    /** Past what take-home leaves after the bills: the rest of the month comes out of savings. */
    DIPPING,
}

/**
 * How fast the month is being spent, and where that leads. The projection is a straight line at
 * the month's rate so far, which early in the month is steadied by last month's (one big shop on
 * the 2nd isn't the month's pace); a single large charge still bends it.
 */
@Immutable
data class BudgetPace(
    val spent: Double,
    val limit: Double?,
    val savingsLine: Double?,
    val day: Int,
    val daysInMonth: Int,
    /** What a day costs at the month's rate. */
    val dailyRate: Double,
    /** Where the month ends at that rate. */
    val projected: Double,
    /** What would have been spent by today if the limit were spread evenly over the month. */
    val evenPaceSoFar: Double?,
    /** What a day can cost from tomorrow on and still end the month at the limit; zero once it is passed. */
    val allowancePerDay: Double?,
    /** The day of the month the limit was passed, or will be at this rate; null when it won't be. */
    val limitDay: Int?,
    /** The same for the savings line. */
    val savingsDay: Int?,
    val status: PaceStatus,
) {
    val daysLeft: Int get() = daysInMonth - day

    /** How much of the limit is spent: 1 at the limit, more past it. */
    val level: Float get() = if (limit != null && limit > 0) (spent / limit).toFloat().coerceAtLeast(0f) else 0f

    /** How near trouble the month is, for colour: 0 while comfortably under, 1 at the limit and past it. */
    val heat: Float get() = heatOf(level)

    companion object {
        const val CLOSE_FRACTION = 0.8

        /** The share of the limit at which the month starts to warm. */
        private const val HEAT_FROM = 0.55f

        /** The same warming for any share of any limit: a person's, the family's. */
        fun heatOf(level: Float): Float = ((level - HEAT_FROM) / (1f - HEAT_FROM)).coerceIn(0f, 1f)

        /** How many days of last month's pace the month starts out with, before its own days outweigh them. */
        private const val PRIOR_DAYS = 3

        fun of(budget: Budget): BudgetPace {
            val limit = budget.config.limits.total?.takeIf { it > 0 }
            val savingsLine = budget.savingsLine?.takeIf { it > 0 }
            val spent = budget.spent
            val day = budget.day
            val days = budget.daysInMonth
            val last = budget.history.lastOrNull()?.takeIf { it.spent > 0 && it.days > 0 }
            val prior = last?.let { it.spent / it.days } ?: limit?.let { it / days } ?: if (day > 0) spent / day else 0.0
            val rate = if (day <= 0) 0.0 else max(0.0, (spent + prior * PRIOR_DAYS) / (day + PRIOR_DAYS))
            val projected = if (day >= days) spent else spent + (days - day) * rate

            fun crossing(line: Double?): Int? {
                if (line == null) return null
                var total = 0.0
                budget.daily.forEachIndexed { index, amount ->
                    total += amount
                    if (total >= line) return index + 1
                }
                if (spent >= line) return day.coerceAtLeast(1)
                if (rate <= 0 || day >= days) return null
                return (day + ceil((line - spent) / rate).toInt()).takeIf { it <= days }
            }

            val status = when {
                savingsLine != null && spent >= savingsLine -> PaceStatus.DIPPING
                limit == null -> PaceStatus.NO_LIMIT
                spent >= limit -> PaceStatus.OVER
                spent >= limit * CLOSE_FRACTION -> PaceStatus.CLOSE
                projected > limit -> PaceStatus.PROJECTED_OVER
                else -> PaceStatus.UNDER
            }
            return BudgetPace(
                spent = spent,
                limit = limit,
                savingsLine = savingsLine,
                day = day,
                daysInMonth = days,
                dailyRate = rate,
                projected = projected,
                evenPaceSoFar = limit?.let { it * day / days },
                allowancePerDay = limit?.let { max(0.0, it - spent) / max(1, days - day) },
                limitDay = crossing(limit),
                savingsDay = crossing(savingsLine),
                status = status,
            )
        }
    }
}
