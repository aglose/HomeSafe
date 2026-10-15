package com.meticulouscreations.homesafe.atlas.ui

import com.meticulouscreations.homesafe.weather.domain.WebMercator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasMapTest {
    private val tilePx = 256f

    @Test
    fun theBoxRoundSomePlaces() {
        assertNull(MapBounds.of(emptyList()))
        assertEquals(MapBounds(40.0, -89.0, 40.0, -89.0), MapBounds.of(listOf(40.0 to -89.0)))
        assertEquals(MapBounds(36.0, -115.0, 43.0, -89.0), MapBounds.of(listOf(40.0 to -89.0, 43.0 to -89.4, 36.0 to -115.0)))
    }

    @Test
    fun placesEitherSideOfTheDateLineAreABoxOverItNotRoundTheWorld() {
        // Fiji and Samoa: ten degrees apart, not three hundred and fifty.
        val bounds = assertNotNull(MapBounds.of(listOf(-17.7 to 178.0, -13.8 to -171.8, -18.1 to 178.4)))
        assertEquals(MapBounds(-18.1, 178.0, -13.8, -171.8), bounds)

        val camera = cameraFor(bounds, 1080f, 2000f, tilePx)
        // Centred on the date line's side of the world, between the two, and close enough in to see them.
        val centre = WebMercator.longitude(camera.centerX)
        assertTrue(centre > 178.0 || centre < -171.8, "centred at $centre")
        assertTrue(camera.zoom > 5.5, "zoom ${camera.zoom}")
        // Both ends are on screen.
        listOf(178.0, -171.8).forEach { longitude ->
            val (dx, _) = camera.project(-16.0, longitude, tilePx)
            assertTrue(abs(dx) < 540f, "$longitude is $dx from the middle")
        }
    }

    @Test
    fun anOrdinaryBoxIsHeldWithRoomRoundIt() {
        val camera = cameraFor(MapBounds(39.78, -89.3, 40.22, -88.7), 1080f, 2000f, tilePx)
        assertEquals(-89.0, WebMercator.longitude(camera.centerX), 1e-6)
        val (west, _) = camera.project(40.0, -89.3, tilePx)
        val (east, _) = camera.project(40.0, -88.7, tilePx)
        // Seventy per cent of the width: the box is wider than it is tall, so the width decides.
        assertEquals(0.7f * 1080f, east - west, 2f)
    }

    @Test
    fun aSinglePointDoesNotZoomInWithoutEnd() {
        val camera = cameraFor(MapBounds(40.0, -89.0, 40.0, -89.0), 1080f, 2000f, tilePx)
        assertEquals(15.5, camera.zoom)
    }
}
