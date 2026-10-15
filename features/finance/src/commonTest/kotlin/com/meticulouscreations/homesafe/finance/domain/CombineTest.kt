package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CombineTest {

    private fun series(vararg points: Pair<Long, Double>) = Series.of(points.toList())

    private val first = series(10L to 6.0, 20L to 8.0, 30L to 9.0)

    @Test
    fun noneLeavesTheFirstSeriesAlone() {
        val combined = Combine.NONE.apply(first, series(10L to 2.0))
        assertContentEquals(first.values, combined.values)
    }

    @Test
    fun aRatioDividesAndARatioPercentScalesItToAHundred() {
        val second = series(10L to 2.0, 20L to 4.0, 30L to 3.0)
        assertContentEquals(doubleArrayOf(3.0, 2.0, 3.0), Combine.RATIO.apply(first, second).values)
        assertContentEquals(doubleArrayOf(300.0, 200.0, 300.0), Combine.RATIO_PERCENT.apply(first, second).values)
    }

    @Test
    fun aRatioDropsPointsWhereTheDenominatorIsZero() {
        val second = series(10L to 2.0, 20L to 0.0, 30L to 3.0)
        listOf(Combine.RATIO, Combine.RATIO_PERCENT).forEach { op ->
            val combined = op.apply(first, second)
            assertContentEquals(longArrayOf(10L, 30L), combined.times, op.name)
        }
    }

    @Test
    fun aDifferenceSubtractsAndKeepsZeros() {
        val second = series(10L to 6.0, 20L to 5.0, 30L to 0.0)
        assertContentEquals(doubleArrayOf(0.0, 3.0, 9.0), Combine.DIFFERENCE.apply(first, second).values)
    }

    @Test
    fun eachPointUsesTheSecondSeriesReadingAtOrBeforeIt() {
        // The second series reports less often and starts later: the first point has nothing to
        // pair with and is dropped; the others pair with the latest reading at or before them.
        val second = series(15L to 2.0, 28L to 4.0)
        val combined = Combine.DIFFERENCE.apply(first, second)
        assertContentEquals(longArrayOf(20L, 30L), combined.times)
        assertContentEquals(doubleArrayOf(6.0, 5.0), combined.values)
        assertEquals(2, Combine.RATIO.apply(first, second).size)
    }
}
