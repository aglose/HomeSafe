package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.weather.domain.RadarSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RadarDecoderTest {

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or rgb

    private fun grey(level: Int): Int = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    private fun decode(argb: Int, table: RadarColorTable): Int = RadarDecoder.decode(intArrayOf(argb), table).single()

    // ---- level / dbz --------------------------------------------------------------------------

    @Test
    fun theWeakestEchoIsLevelZeroAndTheStrongestLevelOne() {
        assertEquals(0f, RadarDecoder.level(RadarDecoder.MIN_DBZ))
        assertEquals(1f, RadarDecoder.level(RadarDecoder.MAX_DBZ))
    }

    @Test
    fun levelRunsInAStraightLineBetween() {
        // 8 to 75 dBZ is 67 wide; 41.5 is the middle.
        assertEquals(0.5f, RadarDecoder.level(41.5f), 1e-6f)
        assertEquals(5f / 67f, RadarDecoder.level(13f), 1e-6f)
    }

    @Test
    fun levelIsClampedOutsideTheScale() {
        assertEquals(0f, RadarDecoder.level(-30f))
        assertEquals(0f, RadarDecoder.level(0f))
        assertEquals(1f, RadarDecoder.level(95f))
    }

    @Test
    fun dbzUndoesLevelWithinTheScale() {
        listOf(8f, 13f, 27.3f, 41.5f, 60f, 75f).forEach {
            assertEquals(it, RadarDecoder.dbz(RadarDecoder.level(it)), 1e-4f, "dbz $it")
        }
        assertEquals(RadarDecoder.MIN_DBZ, RadarDecoder.dbz(0f))
        assertEquals(RadarDecoder.MAX_DBZ, RadarDecoder.dbz(1f))
    }

    @Test
    fun levelUndoesDbzAcrossTheScale() {
        listOf(0f, 0.1f, 0.5f, 0.99f, 1f).forEach {
            assertEquals(it, RadarDecoder.level(RadarDecoder.dbz(it)), 1e-5f, "level $it")
        }
    }

    // ---- tableFor -----------------------------------------------------------------------------

    @Test
    fun theMrmsMosaicIsReadAgainstLcrefAndItsForecastAgainstN0q() {
        assertSame(RadarPalettes.lcref, RadarDecoder.tableFor(RadarSource.US_MRMS, forecast = false))
        assertSame(RadarPalettes.n0q, RadarDecoder.tableFor(RadarSource.US_MRMS, forecast = true))
    }

    @Test
    fun rainViewerIsReadAgainstItsOwnTableEitherWay() {
        assertSame(RadarPalettes.rainViewer, RadarDecoder.tableFor(RadarSource.RAINVIEWER, forecast = false))
        assertSame(RadarPalettes.rainViewer, RadarDecoder.tableFor(RadarSource.RAINVIEWER, forecast = true))
    }

    // ---- The palettes -------------------------------------------------------------------------

    @Test
    fun theIemTablesHave255EntriesFromMinus32InHalfSteps() {
        listOf(RadarPalettes.n0q, RadarPalettes.lcref).forEach { table ->
            assertEquals(255, table.colors.size)
            assertEquals(-32f, table.firstDbz)
            assertEquals(0.5f, table.stepDbz)
            assertEquals(-32f, table.dbz(0))
            assertEquals(95f, table.dbz(254))
        }
    }

    @Test
    fun rainViewersTableHas128EntriesFromMinus32InWholeSteps() {
        val table = RadarPalettes.rainViewer
        assertEquals(128, table.colors.size)
        assertEquals(-32f, table.firstDbz)
        assertEquals(1f, table.stepDbz)
        assertEquals(95f, table.dbz(127))
    }

    @Test
    fun rainViewersTransparentEntriesAreZero() {
        // Nothing is drawn for the lightest echoes: the first 22 entries (-32 to -11 dBZ) are fully clear.
        assertTrue((0 until 22).all { RadarPalettes.rainViewer.colors[it] == 0 })
        assertTrue(RadarPalettes.rainViewer.colors.drop(22).any { it != 0 })
    }

    @Test
    fun noPaletteEntryHasAnAlphaChannelInItsColour() {
        listOf(RadarPalettes.n0q, RadarPalettes.lcref, RadarPalettes.rainViewer).forEach { table ->
            assertTrue(table.colors.all { it ushr 24 == 0 })
        }
    }

    @Test
    fun lcrefIsBlackBelowItsFirstEcho() {
        // The first 86 entries (to 10.5 dBZ) are black: no echo.
        assertTrue((0 until 86).all { RadarPalettes.lcref.colors[it] == 0 })
        assertEquals(0xA4A4FF, RadarPalettes.lcref.colors[86])
    }

    @Test
    fun theGreyLookupIsBuiltOnceAndMapsEachColourToItsStrength() {
        val table = RadarPalettes.lcref
        assertSame(table.greys, table.greys)
        assertEquals(12, table.greys[0xA4A4FF])
        assertEquals(19, table.greys[0x9797F2])
        // No-echo black is not in it: it is the absence of an echo.
        assertEquals(null, table.greys[0])
    }

    @Test
    fun aRunOfOneColourStandsForTheLowestStrengthOfTheRun() {
        val table = RadarPalettes.lcref
        val first = table.colors.indexOfFirst { it == 0x808080 }
        assertEquals(255, table.greys[0x808080])
        assertTrue(first > 0)
    }

    @Test
    fun everyColourInEveryTableHasAGrey() {
        listOf(RadarPalettes.n0q, RadarPalettes.lcref, RadarPalettes.rainViewer).forEach { table ->
            table.colors.filter { it != 0 }.forEach { assertTrue(table.greys.containsKey(it), "colour ${it.toString(16)}") }
            assertTrue(table.greys.values.all { it in 0..255 })
        }
    }

    @Test
    fun decodingRepeatedlyGivesTheSameAnswer() {
        val table = RadarPalettes.lcref
        val expected = decode(opaque(0x9797F2), table)
        repeat(200) { assertEquals(expected, decode(opaque(0x9797F2), table)) }
    }

    // ---- decode(IntArray) ---------------------------------------------------------------------

    @Test
    fun aClearPixelStaysClear() {
        assertEquals(0, decode(0, RadarPalettes.lcref))
        // Clear with a colour still in its channels, as a transparent PNG may decode.
        assertEquals(0, decode(0x00_9797F2, RadarPalettes.lcref))
    }

    @Test
    fun aMostlyClearPixelIsClear() {
        assertEquals(0, decode((0x3F shl 24) or 0x9797F2, RadarPalettes.lcref))
        assertEquals(grey(19), decode((0x40 shl 24) or 0x9797F2, RadarPalettes.lcref))
    }

    @Test
    fun anExactLcrefColourBecomesItsStrengthAsAGrey() {
        // 9797F2 is 13 dBZ: level 5/67 of the way up, so 18.96 of 254, plus one so that no echo is ever 0.
        assertEquals(grey(19), decode(opaque(0x9797F2), RadarPalettes.lcref))
    }

    @Test
    fun theLightestLcrefEchoIsStillAboveZero() {
        // A4A4FF is 11 dBZ.
        assertEquals(grey(12), decode(opaque(0xA4A4FF), RadarPalettes.lcref))
    }

    @Test
    fun theStrongestLcrefColourIsWhite() {
        assertEquals(grey(255), decode(opaque(0x808080), RadarPalettes.lcref))
    }

    @Test
    fun strongerEchoesDecodeToBrighterGreys() {
        val table = RadarPalettes.lcref
        val light = decode(opaque(table.colors[90]), table) and 0xFF
        val moderate = decode(opaque(table.colors[130]), table) and 0xFF
        val heavy = decode(opaque(table.colors[190]), table) and 0xFF
        assertTrue(light < moderate && moderate < heavy, "$light < $moderate < $heavy")
    }

    @Test
    fun n0qColoursBelowTheScaleBecomeClear() {
        val table = RadarPalettes.n0q
        // Entry 0 (-32 dBZ) and entry 79 (7.5 dBZ) are under MIN_DBZ.
        assertEquals(0, decode(opaque(table.colors[0]), table))
        assertEquals(0, decode(opaque(table.colors[79]), table))
    }

    @Test
    fun n0qColourAtTheScalesFloorIsTheFaintestGrey() {
        // Entry 80 is 8 dBZ: level 0, which the +1 lifts to 1.
        assertEquals(grey(1), decode(opaque(RadarPalettes.n0q.colors[80]), RadarPalettes.n0q))
    }

    @Test
    fun anOffTableColourSnapsToItsNearestEntry() {
        // One step off 9797F2 in blue.
        assertEquals(grey(19), decode(opaque(0x9797F3), RadarPalettes.lcref))
        // A smoothed edge a little darker and bluer than 9797F2 is nearer to it than to its neighbours.
        assertEquals(grey(19), decode(opaque(0x9595F4), RadarPalettes.lcref))
    }

    @Test
    fun snappingGivesTheSameAnswerEveryTimeTheColourRecurs() {
        val pixels = intArrayOf(opaque(0x9797F3), opaque(0x9797F3), opaque(0x9797F2), opaque(0x9797F3))
        RadarDecoder.decode(pixels, RadarPalettes.lcref)
        assertEquals(List(4) { grey(19) }, pixels.toList())
    }

    @Test
    fun decodingWorksInPlaceAndReturnsTheSameArray() {
        val pixels = intArrayOf(opaque(0x9797F2), 0)
        val result = RadarDecoder.decode(pixels, RadarPalettes.lcref)
        assertSame(pixels, result)
        assertEquals(listOf(grey(19), 0), pixels.toList())
    }

    @Test
    fun everyOutputPixelIsClearOrAnOpaqueGrey() {
        val table = RadarPalettes.n0q
        val pixels = IntArray(table.colors.size + 3) { i -> if (i < table.colors.size) opaque(table.colors[i]) else 0 }
        RadarDecoder.decode(pixels, table)
        pixels.forEach { p ->
            val ok = p == 0 || (p ushr 24 == 0xFF && (p shr 16 and 0xFF) == (p shr 8 and 0xFF) && (p shr 8 and 0xFF) == (p and 0xFF) && (p and 0xFF) > 0)
            assertTrue(ok, "pixel ${p.toUInt().toString(16)}")
        }
    }

    @Test
    fun anEmptyTileDecodesToAnEmptyTile() {
        assertEquals(0, RadarDecoder.decode(IntArray(0), RadarPalettes.lcref).size)
    }

    @Test
    fun aWholeTileOfNoEchoStaysEntirelyClear() {
        val tile = IntArray(256 * 256)
        RadarDecoder.decode(tile, RadarPalettes.lcref)
        assertTrue(tile.all { it == 0 })
    }

    @Test
    fun rainViewerColoursAreReadAgainstRainViewersTable() {
        val table = RadarPalettes.rainViewer
        // Entry 60 is 28 dBZ (006295): level 20/67, 75.8 of 254, plus one.
        assertEquals(grey(76), decode(opaque(table.colors[60]), table))
        assertEquals(grey(255), decode(opaque(0x00FF00), table))
    }

    @Test
    fun theSameColourMeansDifferentStrengthsInDifferentTables() {
        // N0Q's entry 80 is 8 dBZ; the same colour is not in LCREF, so it is snapped to whatever is nearest there.
        val colour = opaque(RadarPalettes.n0q.colors[80])
        assertEquals(grey(1), decode(colour, RadarPalettes.n0q))
        assertTrue(decode(colour, RadarPalettes.lcref) != grey(1))
    }
}
