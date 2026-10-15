package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [Series] is the parallel-array time series every chart and indicator walks. These tests pin
 * down the binary search at its edges (nothing before the first point, nothing after the last)
 * and the derived series ([Series.since], [Series.yearOverYearPercent], [Series.combine],
 * [Series.downsample]) that the rest of the finance feature builds on.
 */
class SeriesTest {

    private fun series(vararg points: Pair<Long, Double>) = Series.of(points.toList())

    @Test
    fun sinceReturnsTheSameInstanceWhenNothingIsBeforeTheCutoff() {
        val s = series(0L to 1.0, 10L to 2.0, 20L to 3.0)
        assertSame(s, s.since(0L), "the first point already qualifies: no copy needed")
    }

    @Test
    fun sinceDropsPointsBeforeTheCutoff() {
        val s = series(0L to 1.0, 10L to 2.0, 20L to 3.0, 30L to 4.0)
        val trimmed = s.since(10L)
        assertEquals(listOf(10L, 20L, 30L), trimmed.times.toList())
        assertEquals(listOf(2.0, 3.0, 4.0), trimmed.values.toList())
    }

    @Test
    fun sinceAfterTheLastPointIsEmpty() {
        val s = series(0L to 1.0, 10L to 2.0)
        val trimmed = s.since(35L)
        assertTrue(trimmed.isEmpty)
        assertEquals(0, trimmed.size)
    }

    @Test
    fun valueAtOrBeforeIsNullWhenTheSeriesStartsAfterIt() {
        val s = series(10L to 1.0, 20L to 2.0, 30L to 3.0, 40L to 4.0)
        assertNull(s.valueAtOrBefore(5L))
    }

    @Test
    fun valueAtOrBeforeAnExactTimeReturnsThatReading() {
        val s = series(10L to 1.0, 20L to 2.0, 30L to 3.0, 40L to 4.0)
        assertEquals(2.0, s.valueAtOrBefore(20L))
    }

    @Test
    fun valueAtOrBeforeBetweenTwoReadingsReturnsTheEarlierOne() {
        val s = series(10L to 1.0, 20L to 2.0, 30L to 3.0, 40L to 4.0)
        assertEquals(2.0, s.valueAtOrBefore(25L))
    }

    @Test
    fun valueAtOrBeforeAfterTheLastReadingReturnsTheLastOne() {
        val s = series(10L to 1.0, 20L to 2.0, 30L to 3.0, 40L to 4.0)
        assertEquals(4.0, s.valueAtOrBefore(1_000L))
    }

    /**
     * Thirteen points exactly a twelfth of a year apart, so the thirteenth lands exactly a year
     * after the first (within the 3-day grace either side). Every earlier point has no reading
     * close enough to a year back and is dropped — the series' way of refusing to compare a young
     * series against itself.
     */
    @Test
    fun yearOverYearPercentComputesTheChangeAndDropsPointsWithNoYearBack() {
        val month = Series.YEAR_SECONDS / 12
        val points = (0..12).map { i -> (i * month) to 100.0 }.toMutableList()
        points[12] = points[12].first to 103.0
        val yoy = series(*points.toTypedArray()).yearOverYearPercent()
        assertEquals(1, yoy.size, "every point but the last has no reading within grace of a year back")
        assertEquals(12 * month, yoy.times.single())
        assertEquals(3.0, yoy.values.single(), 1e-9)
    }

    @Test
    fun combineAppliesTheOperationAtThisSeriesTimesUsingTheOthersPriorReading() {
        val a = series(0L to 1.0, 100L to 2.0, 200L to 3.0)
        val b = series(0L to 10.0, 50L to 20.0, 150L to 30.0, 250L to 40.0)
        val sum = a.combine(b) { x, y -> x + y }
        assertEquals(listOf(0L, 100L, 200L), sum.times.toList())
        // t=0 -> b's reading at-or-before 0 is 10; t=100 -> b's at-50 reading, 20; t=200 -> b's at-150 reading, 30.
        assertEquals(listOf(11.0, 22.0, 33.0), sum.values.toList())
    }

    @Test
    fun combineDropsPointsWhereTheOperationOrTheOtherSeriesHasNothing() {
        val a = series(0L to 1.0, 100L to 2.0, 200L to 3.0)
        val b = series(0L to 10.0, 50L to 20.0, 150L to 30.0)
        val filtered = a.combine(b) { x, y -> if (y > 25.0) null else x + y }
        assertEquals(listOf(0L, 100L), filtered.times.toList(), "t=200 pairs with b=30 and the op rejects it")

        val tooLate = series(500L to 99.0)
        val empty = a.combine(tooLate) { x, y -> x + y }
        assertTrue(empty.isEmpty, "the other series starts after every point of this one")
    }

    @Test
    fun downsampleKeepsTheLastPointAndShrinksToTheRequestedSize() {
        val s = series(*(0..9).map { it.toLong() to it.toDouble() }.toTypedArray())
        val thinned = s.downsample(3)
        assertEquals(3, thinned.size)
        assertEquals(9L, thinned.times.last(), "the last point is always kept")
        assertEquals(9.0, thinned.values.last())
        assertEquals(0L, thinned.times.first())
    }

    @Test
    fun downsampleLeavesASeriesAtOrUnderTheLimitAlone() {
        val s = series(0L to 1.0, 1L to 2.0)
        assertSame(s, s.downsample(5))
        assertSame(s, s.downsample(1), "maxPoints below 2 is treated as 'don't bother'")
    }

    @Test
    fun equalityComparesContentsNotArrayIdentity() {
        val a = Series(longArrayOf(1L, 2L), doubleArrayOf(1.0, 2.0))
        val b = Series(longArrayOf(1L, 2L), doubleArrayOf(1.0, 2.0))
        val c = Series(longArrayOf(1L, 2L), doubleArrayOf(1.0, 9.0))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }
}
