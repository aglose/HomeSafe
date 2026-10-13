package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.ui.AtlasMap
import com.meticulouscreations.homesafe.atlas.ui.AtlasMapState
import com.meticulouscreations.homesafe.atlas.ui.LocalAtlasShaders
import com.meticulouscreations.homesafe.atlas.ui.MapMarker
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The atlas map under the finger: its gestures are its own (`detectMapGestures`), since the
 * framework's transform detector has no run-on after a flick. A drag against a tap, a pinch whose
 * fingers lift one after the other, a flick and where it goes, and a finger put down on a map
 * that is moving by itself.
 *
 * The map is alone on screen with one place at its middle, the shaders off and nothing animating,
 * and each test steps the clock itself: what moves the map after a finger has gone is an
 * animation, and it should move exactly as far as the test lets it.
 */
@OptIn(ExperimentalTestApi::class)
class AtlasMapGestureUiTest {
    private val start = MapCamera.at(40.0, -89.0, 10.0)
    private val place = MapMarker("gym", 40.0, -89.0, VisitRole.OTHER, 0.5f)

    private class Shown(val map: AtlasMapState) {
        val tapped = ArrayList<String>()
    }

    private fun ComposeUiTest.show(): Shown {
        val shown = Shown(AtlasMapState(start))
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalAtlasShaders provides false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(320.dp, 480.dp)) {
                            AtlasMap(shown.map, NoTiles, Modifier.fillMaxSize().testTag("map"), markers = listOf(place), animated = false, onTapMarker = { shown.tapped += it.key })
                        }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(200, ignoreFrameDuration = true)
        return shown
    }

    /** As long as any run-on, glide or double-tap wait could last. */
    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(6_000, ignoreFrameDuration = true)

    @Test
    fun aTapOnAPlaceOpensItAndADragFromItMovesTheMapInstead() = runComposeUiTest {
        val shown = show()
        onNodeWithTag("map").performTouchInput { click(center) }
        settle()
        assertEquals(listOf("gym"), shown.tapped)
        assertEquals(start, shown.map.camera)

        // The same finger, on the same place, but moved before it is lifted, and held still a moment first.
        onNodeWithTag("map").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width / 40f, 0f), delayMillis = 30) }
            advanceEventTime(400)
            up()
        }
        val dragged = shown.map.camera
        // The map followed the finger east, so its middle is further west, and no further north or south.
        assertTrue(dragged.centerX < start.centerX, "moved to ${dragged.centerX} from ${start.centerX}")
        assertEquals(start.centerY, dragged.centerY, 1e-12)
        assertEquals(start.zoom, dragged.zoom)
        // Let go of at rest, it stays where it was let go of; and the place was not opened again.
        settle()
        assertEquals(dragged, shown.map.camera)
        assertEquals(listOf("gym"), shown.tapped)
    }

    @Test
    fun aTapAwayFromAnyPlaceOpensNothing() = runComposeUiTest {
        val shown = show()
        onNodeWithTag("map").performTouchInput { click(Offset(width * 0.1f, height * 0.1f)) }
        settle()
        assertTrue(shown.tapped.isEmpty())
        assertEquals(start, shown.map.camera)
    }

    @Test
    fun aFlickRunsOnTheWayItWasGoingAndThenStops() = runComposeUiTest {
        val shown = show()
        // Up the screen, fast: the map's middle goes south.
        onNodeWithTag("map").performTouchInput { swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.3f), durationMillis = 80) }
        val letGo = shown.map.camera
        assertTrue(letGo.centerY > start.centerY)
        mainClock.advanceTimeBy(160, ignoreFrameDuration = true)
        val running = shown.map.camera
        assertTrue(running.centerY > letGo.centerY, "ran on to ${running.centerY} from ${letGo.centerY}")
        assertEquals(letGo.centerX, running.centerX, 1e-9)
        assertEquals(start.zoom, running.zoom)
        settle()
        val rested = shown.map.camera
        assertTrue(rested.centerY > running.centerY)
        settle()
        assertEquals(rested, shown.map.camera)
    }

    @Test
    fun aFingerPutDownStopsAMapThatIsRunningOn() = runComposeUiTest {
        val shown = show()
        onNodeWithTag("map").performTouchInput { swipe(Offset(width * 0.8f, centerY), Offset(width * 0.2f, centerY), durationMillis = 80) }
        mainClock.advanceTimeBy(64, ignoreFrameDuration = true)
        val running = shown.map.camera
        assertTrue(running.centerX > start.centerX)
        onNodeWithTag("map").performTouchInput { down(Offset(width * 0.2f, height * 0.2f)) }
        val caught = shown.map.camera
        mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
        assertEquals(caught, shown.map.camera)
        onNodeWithTag("map").performTouchInput { up() }
        settle()
        assertEquals(caught, shown.map.camera)
        // Catching the map is not a tap on it.
        assertTrue(shown.tapped.isEmpty())
    }

    @Test
    fun aPinchZoomsAboutTheFingersAndTheFingerLeftBehindDoesNotThrowTheMap() = runComposeUiTest {
        val shown = show()
        onNodeWithTag("map").performTouchInput {
            down(0, center + Offset(-width * 0.1f, 0f))
            down(1, center + Offset(width * 0.1f, 0f))
            repeat(10) {
                moveBy(0, Offset(-width * 0.02f, 0f), delayMillis = 0)
                moveBy(1, Offset(width * 0.02f, 0f), delayMillis = 16)
            }
        }
        val pinched = shown.map.camera
        assertTrue(pinched.zoom > start.zoom + 0.5, "zoomed to ${pinched.zoom}")
        // The fingers parted evenly about the middle, which is where the map still looks.
        assertEquals(start.centerX, pinched.centerX, 1e-6)
        assertEquals(start.centerY, pinched.centerY, 1e-6)

        // One finger lifts and the other is whisked away after it, as fingers leaving a pinch are.
        onNodeWithTag("map").performTouchInput {
            up(1)
            repeat(3) { moveBy(0, Offset(-width * 0.05f, 0f), delayMillis = 8) }
            up(0)
        }
        val letGo = shown.map.camera
        assertEquals(pinched.zoom, letGo.zoom)
        settle()
        assertEquals(letGo, shown.map.camera)
        assertTrue(shown.tapped.isEmpty())
    }

    @Test
    fun aDoubleTapTravelsOneLevelInAndAFingerStopsItOnTheWay() = runComposeUiTest {
        val shown = show()
        onNodeWithTag("map").performTouchInput { doubleClick(center) }
        mainClock.advanceTimeBy(96, ignoreFrameDuration = true)
        val onTheWay = shown.map.camera.zoom
        assertTrue(onTheWay > start.zoom && onTheWay < start.zoom + 1, "on the way at $onTheWay")
        settle()
        assertEquals(start.zoom + 1, shown.map.camera.zoom, 1e-9)
        assertEquals(start.centerX, shown.map.camera.centerX, 1e-9)

        // Again, and caught part of the way there.
        onNodeWithTag("map").performTouchInput { doubleClick(center) }
        mainClock.advanceTimeBy(96, ignoreFrameDuration = true)
        onNodeWithTag("map").performTouchInput { down(Offset(width * 0.2f, height * 0.2f)) }
        val caught = shown.map.camera.zoom
        mainClock.advanceTimeBy(1_000, ignoreFrameDuration = true)
        assertEquals(caught, shown.map.camera.zoom)
        assertTrue(caught < start.zoom + 2)
        onNodeWithTag("map").performTouchInput { up() }
    }
}
