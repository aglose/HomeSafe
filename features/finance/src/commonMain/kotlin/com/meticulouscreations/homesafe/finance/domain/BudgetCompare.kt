package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.max

/**
 * An earlier month set beside the month under way, each as it stood by the same day of the month:
 * a month that is over has every day in it, so its total alone would always look like more.
 */
@Immutable
data class BudgetCompare(
    /** The day of the month both are counted up to: today, or the earlier month's last day when it is shorter. */
    val day: Int,
    /** What the earlier month had come to by [day]. */
    val then: Double,
    /** What the month under way has come to by [day]. */
    val now: Double,
) {
    /** How far the month under way is ahead of the earlier one (behind, below zero). */
    val difference: Double get() = now - then

    /** The same, or within a fiftieth of the larger (refunds count by their size): too near to call either more. */
    val level: Boolean get() = difference == 0.0 || abs(difference) < max(abs(then), abs(now)) * LEVEL_SHARE

    companion object {
        private const val LEVEL_SHARE = 0.02

        /** [earlier] against [current], the month under way; null unless they are an earlier month and the one under way. */
        fun of(earlier: Budget, current: Budget): BudgetCompare? {
            if (!current.isCurrentMonth || earlier.isCurrentMonth || earlier.month >= current.month) return null
            val day = minOf(current.day, earlier.daysInMonth)
            if (day <= 0) return null
            return BudgetCompare(day, earlier.daily.take(day).sum(), current.daily.take(day).sum())
        }
    }
}
