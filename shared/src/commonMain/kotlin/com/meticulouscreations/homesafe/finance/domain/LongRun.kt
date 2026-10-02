package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlin.math.pow

/**
 * A symbol over its whole life on Yahoo, from its [ChartRange.MAX] history: its record high and
 * how far below it the price sits, what it has done over the last 1, 5 and 10 years, and since
 * its first price. Price only — an index's or a fund's dividends aren't in it.
 */
@Immutable
data class LongRunStats(
    val allTimeHigh: Double,
    val allTimeHighEpochSeconds: Long?,
    /** How far below the record the price is, as a percentage (0 at a record, negative below it). */
    val fromHighPercent: Double,
    val firstEpochSeconds: Long,
    val firstPrice: Double,
    val sinceStartPercent: Double,
    /** The yearly rate that compounds to [sinceStartPercent]; null under a year of history. */
    val perYearPercent: Double?,
    /** Price change over the last 1, 5 and 10 years, where the history goes back that far. */
    val oneYearPercent: Double?,
    val fiveYearPercent: Double?,
    val tenYearPercent: Double?,
) {
    companion object {
        private const val YEAR_SECONDS = 365.25 * 86_400

        /**
         * From [history] (a [ChartRange.MAX] one) and the latest [quote], which can be past the
         * history's last bar — and above its high, on a day that sets a record. Null without a
         * history to measure from.
         */
        fun of(history: PriceHistory, quote: Quote?, nowEpochSeconds: Long): LongRunStats? {
            val series = history.series
            if (series.size < 2) return null
            val first = series.values.first()
            val firstTime = series.times.first()
            if (first <= 0) return null
            val price = quote?.price ?: series.values.last()

            val closeHigh = series.values.indices.maxBy { series.values[it] }
            var high = history.highest ?: series.values[closeHigh]
            var highAt = if (history.highest != null) history.highestEpochSeconds else series.times[closeHigh]
            val todayHigh = maxOf(quote?.dayHigh ?: price, price)
            if (todayHigh > high) {
                high = todayHigh
                highAt = quote?.marketTimeEpochSeconds?.takeIf { it > 0 } ?: nowEpochSeconds
            }

            fun changeOver(years: Int): Double? {
                val from = nowEpochSeconds - (years * YEAR_SECONDS).toLong()
                // Not when the history starts after then: that would quote a shorter span as this one.
                if (firstTime > from + 14 * 86_400) return null
                val then = series.valueAtOrBefore(from) ?: first
                return if (then > 0) (price - then) / then * 100 else null
            }

            val since = (price - first) / first * 100
            val spanYears = (nowEpochSeconds - firstTime) / YEAR_SECONDS
            val perYear = if (spanYears >= 1) ((price / first).pow(1 / spanYears) - 1) * 100 else null
            return LongRunStats(
                allTimeHigh = high,
                allTimeHighEpochSeconds = highAt,
                fromHighPercent = if (high > 0) (price - high) / high * 100 else 0.0,
                firstEpochSeconds = firstTime,
                firstPrice = first,
                sinceStartPercent = since,
                perYearPercent = perYear,
                oneYearPercent = changeOver(1),
                fiveYearPercent = changeOver(5),
                tenYearPercent = changeOver(10),
            )
        }
    }
}
