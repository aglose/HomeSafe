package com.meticulouscreations.homesafe.atlas.domain

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeatRasterTest {
    private val side = 256
    private val raster = HeatRaster(side, apron = 8)

    // A square of the map a thousandth of the world wide, somewhere in the middle of it.
    private val originX = 0.5
    private val originY = 0.5
    private val scale = side / 0.001

    /** A path from one pixel of the square to another, at [scale] pixels to the world. */
    private fun track(fromX: Float, fromY: Float, toX: Float, toY: Float, scale: Double = this.scale): Track {
        val start = CivilDate.dayOf(2024, 6, 1) * SECONDS_PER_DAY
        return Track(start, 0, originX, originY, floatArrayOf((fromX / scale).toFloat(), (fromY / scale).toFloat(), (toX / scale).toFloat(), (toY / scale).toFloat()), intArrayOf(0, 600))
    }

    private fun IntArray.body(x: Int, y: Int): Int = this[y * side + x] shr 16 and 0xFF

    private fun IntArray.halo(x: Int, y: Int): Int = this[y * side + x] shr 8 and 0xFF

    private fun lines(vararg tracks: Track, years: Int = -1): IntArray? {
        raster.clear()
        raster.lines(tracks.toList(), IntArray(tracks.size) { 1 }, years, originX, originY, scale, halfWidth = 4f)
        return raster.picture(spread = 3f)
    }

    @Test
    fun aSquareWithNothingInItHasNoPicture() {
        raster.clear()
        assertTrue(raster.isEmpty)
        assertNull(raster.picture(spread = 3f))
        // A path that passes well clear of the square leaves it empty too.
        assertNull(lines(track(-400f, -300f, -40f, -300f)))
    }

    @Test
    fun aJourneyIsALineAndTheRestIsDark() {
        val picture = assertNotNull(lines(track(50f, 128.5f, 200f, 128.5f)))
        assertEquals(HeatRaster.JOURNEY.toInt(), picture.body(128, 128))
        assertEquals(HeatRaster.JOURNEY.toInt(), picture.body(60, 128))
        // Off the end of it, and well to one side.
        assertEquals(0, picture.body(230, 128))
        assertEquals(0, picture.body(128, 100))
        assertEquals(0, picture.halo(128, 100))
        // Every pixel is opaque: a picture replaces whatever is under it.
        assertTrue(picture.all { it ushr 24 == 0xFF })
    }

    @Test
    fun theHaloReachesFurtherThanTheLine() {
        val picture = assertNotNull(lines(track(50f, 128.5f, 200f, 128.5f)))
        assertEquals(0, picture.body(128, 121))
        assertTrue(picture.halo(128, 121) > 0, "halo ${picture.halo(128, 121)}")
        assertTrue(picture.halo(128, 128) > picture.halo(128, 121))
    }

    @Test
    fun journeysAlongTheSameRoadAddUpUntilItIsAsBrightAsItGets() {
        val road = track(50f, 128.5f, 200f, 128.5f)
        val once = assertNotNull(lines(road)).body(128, 128)
        val twice = assertNotNull(lines(road, road)).body(128, 128)
        assertEquals(once * 2, twice)
        assertEquals(255, assertNotNull(lines(road, road, road, road, road, road, road)).body(128, 128))
    }

    @Test
    fun aSlantedLineIsAsWideAsALevelOne() {
        val picture = assertNotNull(lines(track(40f, 40f, 216f, 216f)))
        assertEquals(HeatRaster.JOURNEY.toInt(), picture.body(128, 128))
        // Eleven pixels across the line from its middle is well outside it.
        assertEquals(0, picture.body(136, 120))
        // Steep or shallow, it is the same line.
        val steep = assertNotNull(lines(track(100f, 20f, 140f, 230f)))
        assertEquals(HeatRaster.JOURNEY.toInt(), steep.body(120, 125))
    }

    @Test
    fun aPathJustOutsideStillLightsTheEdge() {
        // Two pixels beyond the left edge, running down beside it.
        val picture = assertNotNull(lines(track(-2f, 40f, -2f, 200f)))
        assertTrue(picture.body(0, 120) > 0)
        assertEquals(0, picture.body(20, 120))
    }

    @Test
    fun onlyTheYearsAskedForAreDrawn() {
        raster.clear()
        val tracks = listOf(track(50f, 100.5f, 200f, 100.5f), track(50f, 160.5f, 200f, 160.5f))
        // The first is in the year whose bit is 1, the second in the year whose bit is 2.
        raster.lines(tracks, intArrayOf(1, 2), 2, originX, originY, scale, halfWidth = 4f)
        val picture = assertNotNull(raster.picture(spread = 3f))
        assertEquals(0, picture.body(128, 100))
        assertEquals(HeatRaster.JOURNEY.toInt(), picture.body(128, 160))
    }

    @Test
    fun twoFixesAHundredAndFiftyKilometresApartAreNotARoad() {
        val start = CivilDate.dayOf(2024, 6, 1) * SECONDS_PER_DAY
        // A flight across the square: one fix far to the west of it, the next far to the east.
        val flight = Track(start, 0, originX, originY, floatArrayOf(-0.01f, 0.0005f, 0.01f, 0.0005f), intArrayOf(0, 3600))
        assertNull(lines(flight))
    }

    @Test
    fun countedSquaresAreDotsAndTheBrighterOfTwoShowsNotTheirSum() {
        // One path along a street once, and another street driven twenty times, in a square
        // close enough in that the finest counted squares are four pixels across.
        val scale = 4.0 * (1 shl AtlasDensity.LEVELS.last())
        val once = track(60f, 60f, 190f, 60f, scale)
        val often = List(20) { track(60f, 180f, 190f, 180f, scale) }
        val density = AtlasDensity.of(listOf(once) + often)
        val level = density.levels.last()
        raster.clear()
        raster.dots(level, -1, originX, originY, scale, radius = 3f)
        val picture = assertNotNull(raster.picture(spread = 3f))

        fun at(i: Int) = ((level.x[i] - originX) * scale).roundToInt() to ((level.y[i] - originY) * scale).roundToInt()
        val faint = (0 until level.size).first { level.weight[it] == 1f }
        val bright = (0 until level.size).first { level.weight[it] == 20f }
        val (faintX, faintY) = at(faint)
        val (brightX, brightY) = at(bright)
        assertTrue(picture.body(faintX, faintY) > 0)
        assertTrue(picture.body(brightX, brightY) > picture.body(faintX, faintY) * 2, "${picture.body(brightX, brightY)} against ${picture.body(faintX, faintY)}")
        // Along the street the dots overlap, and it is no brighter between two of them than on one.
        val row = (60..190).map { picture.body(it, faintY) }
        assertTrue(row.max() - row.min() <= row.max() / 3, "an even line: $row")
        assertTrue(row.max() <= (HeatRaster.FAINTEST * 255 * 2).toInt(), "not summed: ${row.max()}")

        // None of it in a year that isn't asked for.
        raster.clear()
        raster.dots(level, density.yearBit(2025), originX, originY, scale, radius = 3f)
        assertNull(raster.picture(spread = 3f))
    }

    @Test
    fun aFlightIsAnArcBowedTowardThePoleAndNoPartOfTheGlow() {
        fun flights(vararg px: Float): IntArray? {
            raster.clear()
            raster.arcs(DoubleArray(px.size) { originX.takeIf { _ -> it % 2 == 0 } ?: originY }.also { ends -> px.forEachIndexed { i, at -> ends[i] += at / scale } }, originX, originY, scale, core = 1f, wide = 3f, least = 16f)
            return raster.picture(spread = 3f)
        }
        val picture = assertNotNull(flights(50f, 200.5f, 200f, 200.5f))
        // Half-way along it is above the straight line between its ends by half of how far it bows.
        val top = 200 - (150 * HeatRaster.BOW / 2).toInt()
        assertEquals(255, picture[top * side + 125] and 0xFF)
        assertEquals(0, picture[200 * side + 125] and 0xFF)
        // The faint band either side of the line, and nothing beyond it.
        assertEquals(HeatRaster.BAND.toInt(), picture[(top - 2) * side + 125] and 0xFF)
        assertEquals(0, picture[(top - 8) * side + 125] and 0xFF)
        // It is at both its ends, and it lights nothing of the glow.
        assertEquals(255, picture[200 * side + 51] and 0xFF)
        assertEquals(255, picture[200 * side + 199] and 0xFF)
        assertTrue(picture.all { it shr 8 and 0xFFFF == 0 })
        // A hop too short to read is left out.
        assertNull(flights(50f, 200.5f, 60f, 200.5f))
    }

    @Test
    fun clearingLeavesNothingBehind() {
        assertNotNull(lines(track(50f, 128.5f, 200f, 128.5f)))
        val other = assertNotNull(lines(track(50f, 30.5f, 200f, 30.5f)))
        assertEquals(0, other.body(128, 128))
        assertEquals(HeatRaster.JOURNEY.toInt(), other.body(128, 30))
    }
}
