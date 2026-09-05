package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FrigatePolygonFormatTest {

    @Test
    fun formatsRelativeCoordinatesWithAtMostThreeDecimals() {
        assertEquals("0.5", formatRelativeCoordinate(0.5))
        assertEquals("0.123", formatRelativeCoordinate(0.12345))
        assertEquals("0.124", formatRelativeCoordinate(0.1235))
        assertEquals("0.01", formatRelativeCoordinate(0.01))
        assertEquals("0.001", formatRelativeCoordinate(0.0006))
    }

    @Test
    fun fullScaleIsWrittenExactlyAsFrigateExpects() {
        // Frigate checks `x > "1.0"` as strings: "1.000" would be rejected, "1.0" is accepted.
        assertEquals("1.0", formatRelativeCoordinate(1.0))
        assertEquals("1.0", formatRelativeCoordinate(0.9996))
        assertEquals("1.0", formatRelativeCoordinate(7.0))
        assertEquals("0.0", formatRelativeCoordinate(0.0))
        assertEquals("0.0", formatRelativeCoordinate(-3.0))
    }

    @Test
    fun serialisesAPolygonAsAFlatCommaSeparatedString() {
        val polygon = MaskPolygon(listOf(MaskPoint(0.0, 0.0), MaskPoint(1.0, 0.0), MaskPoint(0.5, 0.75)))
        assertEquals("0.0,0.0,1.0,0.0,0.5,0.75", polygon.toFrigateCoordinates())
    }

    @Test
    fun parsesWhatItSerialises() {
        val original = MaskPolygon(listOf(MaskPoint(0.1, 0.2), MaskPoint(0.9, 0.2), MaskPoint(0.9, 0.8), MaskPoint(0.1, 0.8)))
        assertEquals(original, parseFrigatePolygon(original.toFrigateCoordinates()))
    }

    @Test
    fun parsesFrigatesOwnFormatting() {
        val parsed = parseFrigatePolygon("0.100,0.200,0.900,0.200,0.500,0.800")
        assertNotNull(parsed)
        assertEquals(3, parsed.points.size)
        assertEquals(MaskPoint(0.5, 0.8), parsed.points[2])
    }

    @Test
    fun rejectsUnusableMasks() {
        assertNull(parseFrigatePolygon(""), "empty")
        assertNull(parseFrigatePolygon("0.1,0.2,0.3,0.4"), "two points")
        assertNull(parseFrigatePolygon("0.1,0.2,0.3,0.4,0.5"), "odd count")
        assertNull(parseFrigatePolygon("0.1,0.2,0.3,x,0.5,0.6"), "non-numeric")
        assertNull(parseFrigatePolygon("100,200,300,200,300,400"), "absolute pixels")
    }

    @Test
    fun parsesAListSkippingBrokenEntries() {
        val polygons = parseFrigatePolygons(listOf("0,0,1,0,1,1", "bad", "0,0,0.5,0,0.5,0.5,0,0.5"))
        assertEquals(listOf(3, 4), polygons.map { it.points.size })
    }

    @Test
    fun pointInPolygonHitTestsATriangle() {
        val triangle = MaskPolygon(listOf(MaskPoint(0.0, 0.0), MaskPoint(1.0, 0.0), MaskPoint(0.0, 1.0)))
        assertEquals(true, triangle.contains(MaskPoint(0.2, 0.2)))
        assertEquals(false, triangle.contains(MaskPoint(0.8, 0.8)))
    }
}
