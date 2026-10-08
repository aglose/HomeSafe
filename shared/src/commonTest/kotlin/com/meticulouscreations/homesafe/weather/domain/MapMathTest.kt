package com.meticulouscreations.homesafe.weather.domain

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapMathTest {

    // ---- WebMercator --------------------------------------------------------------------------

    @Test
    fun theGreenwichMeridianAndTheEquatorAreTheMiddleOfTheWorld() {
        assertEquals(0.5, WebMercator.x(0.0), 1e-12)
        assertEquals(0.5, WebMercator.y(0.0), 1e-12)
    }

    @Test
    fun theAntimeridianIsTheWorldsEdges() {
        assertEquals(0.0, WebMercator.x(-180.0), 1e-12)
        assertEquals(1.0, WebMercator.x(180.0), 1e-12)
        assertEquals(0.25, WebMercator.x(-90.0), 1e-12)
    }

    @Test
    fun latitudeForty5IsAboutAThirdOfTheWayDown() {
        // ln(tan 45 + sec 45) / pi = 0.28055.
        assertEquals(0.35972, WebMercator.y(45.0), 1e-5)
        assertEquals(1.0 - WebMercator.y(45.0), WebMercator.y(-45.0), 1e-12)
    }

    @Test
    fun theMapEndsAtEightyFiveDegreesNorthAndSouth() {
        assertEquals(0.0, WebMercator.y(WebMercator.MAX_LATITUDE), 1e-4)
        assertEquals(1.0, WebMercator.y(-WebMercator.MAX_LATITUDE), 1e-4)
    }

    @Test
    fun beyondThatLatitudeIsClamped() {
        assertEquals(WebMercator.y(WebMercator.MAX_LATITUDE), WebMercator.y(89.9), 1e-12)
        assertEquals(WebMercator.y(WebMercator.MAX_LATITUDE), WebMercator.y(90.0), 1e-12)
        assertEquals(WebMercator.y(-WebMercator.MAX_LATITUDE), WebMercator.y(-90.0), 1e-12)
    }

    @Test
    fun yGrowsSouthward() {
        val ys = listOf(80.0, 45.0, 0.0, -45.0, -80.0).map { WebMercator.y(it) }
        assertEquals(ys.sorted(), ys)
    }

    @Test
    fun longitudeAndLatitudeInvertXAndY() {
        listOf(-179.0, -122.6762, -0.001, 0.0, 35.6762, 139.6503, 179.5).forEach {
            assertEquals(it, WebMercator.longitude(WebMercator.x(it)), 1e-9, "longitude $it")
        }
        listOf(-84.0, -45.0, -0.5, 0.0, 12.3, 45.5234, 47.6062, 84.9).forEach {
            assertEquals(it, WebMercator.latitude(WebMercator.y(it)), 1e-9, "latitude $it")
        }
    }

    @Test
    fun portlandIsAboutSixteenPercentAcrossTheWorld() {
        assertEquals(0.15923, WebMercator.x(-122.6762), 1e-5)
    }

    @Test
    fun aPixelIsHalfAsManyKilometresForEachZoomLevel() {
        val z0 = WebMercator.kmPerPixel(0.0, 0.0, 256.0)
        assertEquals(40_075.017 / 256, z0, 1e-6)
        assertEquals(z0 / 2, WebMercator.kmPerPixel(0.0, 1.0, 256.0), 1e-9)
        assertEquals(z0 / 1024, WebMercator.kmPerPixel(0.0, 10.0, 256.0), 1e-9)
    }

    @Test
    fun aPixelAtSixtyDegreesIsHalfAsLongAsAtTheEquator() {
        assertEquals(WebMercator.kmPerPixel(0.0, 5.0, 256.0) / 2, WebMercator.kmPerPixel(60.0, 5.0, 256.0), 1e-9)
    }

    @Test
    fun aBiggerTileMeansSmallerPixels() {
        assertEquals(WebMercator.kmPerPixel(0.0, 5.0, 256.0) / 2, WebMercator.kmPerPixel(0.0, 5.0, 512.0), 1e-9)
    }

    // ---- MapCamera.level ----------------------------------------------------------------------

    @Test
    fun theLevelIsTheWholeZoomBelow() {
        assertEquals(7, MapCamera(0.5, 0.5, 7.5).level)
        assertEquals(3, MapCamera(0.5, 0.5, 3.0).level)
        assertEquals(10, MapCamera(0.5, 0.5, 10.9).level)
    }

    @Test
    fun aZoomAHairUnderAWholeNumberRoundsUp() {
        // 8 less a floating-point whisker is level 8, not 7.
        assertEquals(8, MapCamera(0.5, 0.5, 7.99995).level)
    }

    @Test
    fun theLevelIsKeptWithinTheTileServersRange() {
        assertEquals(0, MapCamera(0.5, 0.5, -2.0).level)
        assertEquals(19, MapCamera(0.5, 0.5, 25.0).level)
    }

    @Test
    fun atPlacesTheCameraOnThePlace() {
        val camera = MapCamera.at(45.5234, -122.6762, 8.0)
        assertEquals(WebMercator.x(-122.6762), camera.centerX, 1e-12)
        assertEquals(WebMercator.y(45.5234), camera.centerY, 1e-12)
        assertEquals(8.0, camera.zoom)
    }

    // ---- project ------------------------------------------------------------------------------

    @Test
    fun theCentreProjectsToTheMiddleOfTheView() {
        val camera = MapCamera.at(45.5, -122.7, 6.0)
        val (x, y) = camera.project(45.5, -122.7, 256f)
        assertEquals(0f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun aPointOneTileEastIsATilesWidthRight() {
        // At a whole zoom a tile is tilePx wide, and a tile spans 360 / 2^z degrees of longitude.
        val camera = MapCamera.at(0.0, 10.0, 4.0)
        val (x, y) = camera.project(0.0, 10.0 + 360.0 / 16, 256f)
        assertEquals(256f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun northIsUpOnTheScreen() {
        val camera = MapCamera.at(45.0, 0.0, 5.0)
        assertTrue(camera.project(46.0, 0.0, 256f).second < 0f)
        assertTrue(camera.project(44.0, 0.0, 256f).second > 0f)
    }

    @Test
    fun projectTakesTheShortWayRoundTheWorld() {
        val camera = MapCamera.at(0.0, 179.0, 4.0)
        val (x, _) = camera.project(0.0, -179.0, 256f)
        // Two degrees east across the antimeridian, not 358 west.
        assertTrue(x > 0f && x < 100f, "x was $x")
    }

    @Test
    fun theSameCameraProjectsFartherAtGreaterZoom() {
        val place = 45.0 to 1.0
        val near = MapCamera.at(45.0, 0.0, 6.0).project(place.first, place.second, 256f).first
        val far = MapCamera.at(45.0, 0.0, 7.0).project(place.first, place.second, 256f).first
        assertEquals(near * 2, far, 0.01f)
    }

    // ---- panned -------------------------------------------------------------------------------

    @Test
    fun draggingTheMapMovesTheCentreTheOtherWay() {
        val camera = MapCamera(0.5, 0.5, 3.0)
        val panned = camera.panned(dx = 100f, dy = -50f, tilePx = 256f)
        val perPixel = 1.0 / (8 * 256)
        assertEquals(0.5 - 100 * perPixel, panned.centerX, 1e-12)
        assertEquals(0.5 + 50 * perPixel, panned.centerY, 1e-12)
        assertEquals(3.0, panned.zoom)
    }

    @Test
    fun aDragMovesMoreWorldWhenZoomedOut() {
        val out = MapCamera(0.5, 0.5, 3.0).panned(100f, 0f, 256f)
        val zoomedIn = MapCamera(0.5, 0.5, 6.0).panned(100f, 0f, 256f)
        assertTrue(abs(0.5 - out.centerX) > abs(0.5 - zoomedIn.centerX))
    }

    @Test
    fun panningPastTheAntimeridianWrapsToTheOtherSide() {
        val panned = MapCamera(0.01, 0.5, 3.0).panned(dx = 100f, dy = 0f, tilePx = 256f)
        assertTrue(panned.centerX > 0.9 && panned.centerX < 1.0, "centerX was ${panned.centerX}")
        val other = MapCamera(0.99, 0.5, 3.0).panned(dx = -100f, dy = 0f, tilePx = 256f)
        assertTrue(other.centerX >= 0.0 && other.centerX < 0.1, "centerX was ${other.centerX}")
    }

    @Test
    fun panningNorthOrSouthStopsNearThePoles() {
        val north = MapCamera(0.5, 0.03, 3.0).panned(0f, 5_000f, 256f)
        assertEquals(0.02, north.centerY, 1e-12)
        val south = MapCamera(0.5, 0.97, 3.0).panned(0f, -5_000f, 256f)
        assertEquals(0.98, south.centerY, 1e-12)
    }

    // ---- zoomed -------------------------------------------------------------------------------

    @Test
    fun doublingTheScaleAddsOneToTheZoom() {
        assertEquals(6.0, MapCamera(0.3, 0.4, 5.0).zoomed(2f, 0f, 0f, 256f).zoom, 1e-9)
        assertEquals(4.0, MapCamera(0.3, 0.4, 5.0).zoomed(0.5f, 0f, 0f, 256f).zoom, 1e-9)
        assertEquals(5.5, MapCamera(0.3, 0.4, 5.0).zoomed(1.4142135f, 0f, 0f, 256f).zoom, 1e-5)
    }

    @Test
    fun zoomingAboutTheCentreLeavesTheCentreAlone() {
        val zoomed = MapCamera(0.3, 0.4, 5.0).zoomed(2f, 0f, 0f, 256f)
        assertEquals(0.3, zoomed.centerX, 1e-12)
        assertEquals(0.4, zoomed.centerY, 1e-12)
    }

    @Test
    fun theThingUnderTheFingersStaysUnderTheFingers() {
        val before = MapCamera(0.3, 0.4, 5.0)
        // Whatever is 100 px right of and 50 px above the view's centre.
        val focusX = 100f
        val focusY = -50f
        val lon = WebMercator.longitude(0.3 + focusX / (32.0 * 256))
        val lat = WebMercator.latitude(0.4 + focusY / (32.0 * 256))
        val (bx, by) = before.project(lat, lon, 256f)
        assertEquals(focusX, bx, 0.01f)
        assertEquals(focusY, by, 0.01f)

        listOf(2f, 0.5f, 1.7f, 3.3f).forEach { factor ->
            val after = before.zoomed(factor, focusX, focusY, 256f)
            val (ax, ay) = after.project(lat, lon, 256f)
            assertEquals(focusX, ax, 0.05f, "x after ×$factor")
            assertEquals(focusY, ay, 0.05f, "y after ×$factor")
        }
    }

    @Test
    fun zoomingCannotGoPastTheMaximum() {
        val zoomed = MapCamera(0.5, 0.5, 10.5).zoomed(16f, 0f, 0f, 256f)
        assertEquals(MapCamera.MAX_ZOOM, zoomed.zoom)
    }

    @Test
    fun zoomingCannotGoPastTheMinimum() {
        val zoomed = MapCamera(0.5, 0.5, 3.5).zoomed(0.01f, 0f, 0f, 256f)
        assertEquals(MapCamera.MIN_ZOOM, zoomed.zoom)
    }

    @Test
    fun theLimitsCanBeNarrowed() {
        val camera = MapCamera(0.5, 0.5, 6.0)
        assertEquals(7.0, camera.zoomed(8f, 0f, 0f, 256f, minZoom = 5.0, maxZoom = 7.0).zoom)
        assertEquals(5.0, camera.zoomed(0.125f, 0f, 0f, 256f, minZoom = 5.0, maxZoom = 7.0).zoom)
    }

    @Test
    fun zoomingFurtherAtTheLimitChangesNothing() {
        val atMax = MapCamera(0.4, 0.4, MapCamera.MAX_ZOOM)
        assertSame(atMax, atMax.zoomed(2f, 80f, 80f, 256f))
        val atMin = MapCamera(0.4, 0.4, MapCamera.MIN_ZOOM)
        assertSame(atMin, atMin.zoomed(0.5f, 80f, 80f, 256f))
    }

    @Test
    fun aScaleOfOneChangesNothing() {
        val camera = MapCamera(0.4, 0.4, 6.0)
        assertSame(camera, camera.zoomed(1f, 80f, 80f, 256f))
    }

    // ---- tiles --------------------------------------------------------------------------------

    @Test
    fun anEmptyViewHasNoTiles() {
        val camera = MapCamera(0.5, 0.5, 5.0)
        assertTrue(camera.tiles(0f, 100f, 256f, 5).isEmpty())
        assertTrue(camera.tiles(100f, 0f, 256f, 5).isEmpty())
        assertTrue(camera.tiles(-1f, -1f, 256f, 5).isEmpty())
    }

    @Test
    fun tilesLandWhereTheArithmeticSaysAtAWholeZoom() {
        // The centre of the world at zoom 3 is the corner of tiles (3, 3), (4, 3), (3, 4) and (4, 4).
        val tiles = MapCamera(0.5, 0.5, 3.0).tiles(512f, 512f, 256f, 3)
        val first = tiles.first { it.x == 3 && it.y == 3 }
        assertEquals(0f, first.left, 1e-3f)
        assertEquals(0f, first.top, 1e-3f)
        assertEquals(256f, first.size, 1e-3f)
        val east = tiles.first { it.x == 4 && it.y == 3 }
        assertEquals(256f, east.left, 1e-3f)
        val south = tiles.first { it.x == 3 && it.y == 4 }
        assertEquals(256f, south.top, 1e-3f)
        assertTrue(tiles.all { it.z == 3 })
    }

    @Test
    fun theTileUnderTheCentreSitsAroundTheViewsMiddle() {
        val camera = MapCamera.at(45.5234, -122.6762, 9.0)
        val tiles = camera.tiles(800f, 600f, 256f, 9)
        val count = 1 shl 9
        val tx = (camera.centerX * count).toInt()
        val ty = (camera.centerY * count).toInt()
        val under = tiles.single { it.x == tx && it.y == ty }
        assertTrue(under.left <= 400f && under.left + under.size >= 400f, "left ${under.left}")
        assertTrue(under.top <= 300f && under.top + under.size >= 300f, "top ${under.top}")
    }

    private fun assertCovers(camera: MapCamera, width: Float, height: Float, level: Int, tilePx: Float = 256f) {
        val tiles = camera.tiles(width, height, tilePx, level)
        val label = "$camera at level $level in ${width}x$height"
        assertTrue(tiles.isNotEmpty(), label)
        val size = tiles.first().size
        assertTrue(tiles.all { it.size == size && it.z == level }, label)
        assertEquals((tilePx * 2.0.pow(camera.zoom - level)).toFloat(), size, 1e-3f, label)

        val lefts = tiles.map { it.left }.distinct().sorted()
        val tops = tiles.map { it.top }.distinct().sorted()
        // No gaps: each column is one tile-width from the last, each row one tile-height.
        lefts.zipWithNext().forEach { (a, b) -> assertEquals(size, b - a, 0.05f, "column gap in $label") }
        tops.zipWithNext().forEach { (a, b) -> assertEquals(size, b - a, 0.05f, "row gap in $label") }
        // Every cell of the grid is there, once.
        assertEquals(lefts.size * tops.size, tiles.size, label)
        // And the grid reaches every edge of the view.
        assertTrue(lefts.first() <= 0.05f, "left edge in $label: ${lefts.first()}")
        assertTrue(lefts.last() + size >= width - 0.05f, "right edge in $label")
        // Rows may stop short of the view only where the map itself ends (the poles); these cameras are clear of them.
        assertTrue(tops.first() <= 0.05f, "top edge in $label: ${tops.first()}")
        assertTrue(tops.last() + size >= height - 0.05f, "bottom edge in $label")
        // Every address is a real tile of the level.
        val count = 1 shl level
        assertTrue(tiles.all { it.x in 0 until count && it.y in 0 until count }, label)
    }

    @Test
    fun tilesCoverTheViewExactlyWhateverTheCameraAndLevel() {
        val cameras = listOf(
            MapCamera.at(45.5234, -122.6762, 8.0),
            MapCamera.at(45.5234, -122.6762, 7.5),
            MapCamera.at(35.6762, 139.6503, 10.37),
            MapCamera.at(-33.87, 151.21, 4.2),
            MapCamera(0.5, 0.5, 3.0),
        )
        cameras.forEach { camera ->
            listOf(camera.level, camera.level - 1, camera.level - 2).filter { it >= 0 }.forEach { level ->
                assertCovers(camera, 412f, 800f, level)
                assertCovers(camera, 1280f, 720f, level)
            }
        }
    }

    @Test
    fun aLevelCoarserThanTheZoomStretchesItsTiles() {
        val camera = MapCamera.at(45.0, -100.0, 8.0)
        val tiles = camera.tiles(600f, 600f, 256f, 6)
        assertEquals(1024f, tiles.first().size, 1e-2f)
        assertTrue(tiles.all { it.z == 6 })
    }

    @Test
    fun tileAddressesWrapAcrossTheAntimeridian() {
        // Zoom 3 has eight columns; this view is four tiles wide and centred just short of the seam.
        val camera = MapCamera(0.999, 0.5, 3.0)
        val tiles = camera.tiles(1024f, 512f, 256f, 3)
        val xs = tiles.map { it.x }.toSet()
        assertTrue(7 in xs && 0 in xs, "columns $xs")
        assertTrue(xs.all { it in 0..7 })
        // And they are drawn one after another: the column after 7 is 0, to its right.
        val seven = tiles.first { it.x == 7 }
        val zero = tiles.first { it.x == 0 && it.y == seven.y }
        assertEquals(seven.left + seven.size, zero.left, 0.05f)
    }

    @Test
    fun tileAddressesWrapWestOfTheSeamToo() {
        val camera = MapCamera(0.001, 0.5, 3.0)
        val tiles = camera.tiles(1024f, 512f, 256f, 3)
        val xs = tiles.map { it.x }.toSet()
        assertTrue(7 in xs && 0 in xs, "columns $xs")
        val seven = tiles.first { it.x == 7 }
        val zero = tiles.first { it.x == 0 && it.y == seven.y }
        assertEquals(seven.left + seven.size, zero.left, 0.05f)
    }

    @Test
    fun rowsAreClampedToTheMapNotWrapped() {
        // Looking at the top of the map with a view taller than what's there.
        val camera = MapCamera(0.5, 0.02, 3.0)
        val tiles = camera.tiles(512f, 2_000f, 256f, 3)
        assertTrue(tiles.all { it.y in 0..7 }, "rows ${tiles.map { it.y }.toSet()}")
        assertEquals(0, tiles.minOf { it.y })
        assertEquals(tiles.size, tiles.map { it.x to it.y }.toSet().size, "no tile twice")
    }

    @Test
    fun rowsAreClampedAtTheBottomToo() {
        val camera = MapCamera(0.5, 0.98, 3.0)
        val tiles = camera.tiles(512f, 2_000f, 256f, 3)
        assertEquals(7, tiles.maxOf { it.y })
        assertTrue(tiles.all { it.y in 0..7 })
    }

    @Test
    fun aViewWiderThanTheWorldRepeatsIt() {
        // Eight 256 px tiles are 2,048 px; this view is wider than the whole world at zoom 3.
        val tiles = MapCamera(0.5, 0.5, 3.0).tiles(3_000f, 512f, 256f, 3)
        val row = tiles.filter { it.y == 3 }
        assertTrue(row.size > 8)
        assertTrue(row.all { it.x in 0..7 })
    }
}
