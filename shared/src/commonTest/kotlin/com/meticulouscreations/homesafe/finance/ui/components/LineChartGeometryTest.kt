package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.ui.graphics.Color
import com.meticulouscreations.homesafe.finance.domain.Series
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the line chart puts its points and its scrub dot. The dot used to take its height from
 * the raw value on the unpadded range while the line was drawn on a padded one, so it floated
 * above the line the higher the finger went; these pin it to the line as drawn.
 */
class LineChartGeometryTest {

    private fun series(values: DoubleArray) = Series(LongArray(values.size) { it * 60L }, values)

    private fun noisy(n: Int) = series(DoubleArray(n) { i -> 100 + 8 * sin(i / 37.0) + 3 * sin(i * 12.9898) })

    private fun geometry(s: Series, sharp: Boolean, baseline: Double? = null) =
        geometryOf(listOf(ChartLine(s, Color.Green)), baseline, emptyList(), emptyList(), timeAxis = false, extent = 1f, fitZones = false, sharp = sharp)

    @Test
    fun aSharpLineKeepsEveryRealPoint() {
        val s = noisy(100)
        val line = geometry(s, sharp = true).lines.single()

        assertTrue(line.exact)
        assertEquals(100, line.xs.size)
        val highest = s.values.indices.maxBy { s.values[it] }
        val lowest = s.values.indices.minBy { s.values[it] }
        assertEquals(line.ys.min(), line.ys[highest])
        assertEquals(line.ys.max(), line.ys[lowest])
    }

    @Test
    fun theDotAtTheHighestPointSitsOnThePeakNotAboveIt() {
        val s = noisy(100)
        val geo = geometry(s, sharp = true, baseline = 95.0)
        val line = geo.lines.single()
        val highest = s.values.indices.maxBy { s.values[it] }

        val dotY = line.yAt(xOfIndex(highest, s, geo, timeAxis = false, extent = 1f))

        assertEquals(line.ys.min(), dotY, 1e-5f)
        // The plot keeps headroom above the peak; the old dot ignored it and sat at the very top.
        assertTrue(dotY > 0.02f, "peak drawn at $dotY")
    }

    @Test
    fun theDotFollowsTheDrawnLineEverywhereOnASmoothLine() {
        for (n in listOf(30, 2_000)) {
            val s = noisy(n)
            val geo = geometry(s, sharp = false)
            val line = geo.lines.single()
            for (i in 0 until n) {
                val x = xOfIndex(i, s, geo, timeAxis = false, extent = 1f)
                val y = line.yAt(x)
                // Between the two drawn samples either side of the finger.
                val k = line.xs.indexOfFirst { it >= x }.coerceAtLeast(1)
                val lo = minOf(line.ys[k - 1], line.ys[k])
                val hi = maxOf(line.ys[k - 1], line.ys[k])
                assertTrue(y in (lo - 1e-5f)..(hi + 1e-5f), "n=$n i=$i y=$y not in $lo..$hi")
            }
        }
    }

    @Test
    fun aSmoothLinesBucketsAreCentredWhereTheyreDrawn() {
        // A lone spike late in a long series: the thinned line's peak must sit over it, not drift
        // right of it the way buckets measured from i/240 but drawn at i/239 did.
        val n = 2_400
        val spikeAt = 1_800
        val s = series(DoubleArray(n) { if (it == spikeAt) 10.0 else 0.0 })
        val geo = geometry(s, sharp = false)
        val line = geo.lines.single()
        val peak = line.ys.indices.minBy { line.ys[it] }
        val halfSpacing = 0.5f / (line.xs.size - 1)

        assertTrue(abs(line.xs[peak] - xOfIndex(spikeAt, s, geo, timeAxis = false, extent = 1f)) <= halfSpacing)
    }

    @Test
    fun aLongSharpLineIsThinnedButKeepsItsHighestAndLowestPoints() {
        val s = noisy(5_000)
        val line = geometry(s, sharp = true).lines.single()

        assertFalse(line.exact)
        assertTrue(line.xs.size <= 720)
        // The range has 8% headroom either side, so the series' highest point is drawn at
        // 0.08 / 1.16 down and its lowest at 1.08 / 1.16: the thinned line must still reach both.
        assertEquals(0.08f / 1.16f, line.ys.min(), 1e-4f)
        assertEquals(1.08f / 1.16f, line.ys.max(), 1e-4f)
        // And runs left to right, so the scrub dot's lookup holds.
        assertTrue((1 until line.xs.size).all { line.xs[it] >= line.xs[it - 1] })
    }

    @Test
    fun aSmoothLineMorphsIntoASharpOneOfADifferentPointCount() {
        val s = noisy(100)
        val smooth = geometry(s, sharp = false)
        val sharp = geometry(s, sharp = true)

        val mid = lerp(smooth, sharp, 0.5f).lines.single()
        val end = lerp(smooth, sharp, 1f).lines.single()

        assertEquals(100, mid.xs.size)
        assertTrue(sharp.lines.single().ys.contentEquals(end.ys))
        // Halfway is between the two shapes, not a jump to either.
        assertFalse(mid.ys.contentEquals(sharp.lines.single().ys))
    }

    @Test
    fun landmarksAreTheHighTheLowAndCrossingTheBaseline() {
        val s = series(doubleArrayOf(5.0, 6.0, 9.0, 6.0, 4.0, 1.0, 4.0, 5.5))
        val extremes = extremesOf(s)

        assertFalse(passedLandmark(s, -1, 2, extremes, null), "touching down isn't a landmark")
        assertTrue(passedLandmark(s, 1, 2, extremes, null), "reaching the high")
        assertTrue(passedLandmark(s, 0, 3, extremes, null), "sweeping past the high")
        assertFalse(passedLandmark(s, 2, 3, extremes, null), "leaving the high")
        assertTrue(passedLandmark(s, 4, 5, extremes, null), "reaching the low")
        assertTrue(passedLandmark(s, 3, 4, extremes, 5.0), "dropping through the baseline")
        assertFalse(passedLandmark(s, 0, 1, extremes, 5.0), "staying above it")
    }

    @Test
    fun aFastScrubThatDipsUnderTheBaselineAndBackStillCountsAsACrossing() {
        // Both ends are above 5, but the finger skipped over 4.0 on the way.
        val s = series(doubleArrayOf(6.0, 7.0, 4.0, 6.5, 8.0, 1.0))
        val extremes = extremesOf(s)

        assertTrue(passedLandmark(s, 1, 3, extremes, 5.0))
        assertTrue(passedLandmark(s, 3, 1, extremes, 5.0), "and going back the other way")
        assertFalse(passedLandmark(s, 0, 1, extremes, 5.0))
    }
}
