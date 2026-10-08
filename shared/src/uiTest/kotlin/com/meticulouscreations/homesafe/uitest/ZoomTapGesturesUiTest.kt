package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.components.PinchZoomState
import com.meticulouscreations.homesafe.ui.components.pinchZoomContent
import com.meticulouscreations.homesafe.ui.components.pinchZoomGestures
import com.meticulouscreations.homesafe.ui.components.zoomTapGestures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The camera screen's player, as far as a finger is concerned: a 16:9 picture at the top of a
 * scrolling page, pinch-zoomable, with the layer over it that takes taps — built here from the
 * same three modifiers, without the player or its view model.
 *
 * What is pinned: a finger held on the picture zooms in on the spot it is holding, as a held
 * Home card does, and can drag the picture from there without lifting, the page under it staying
 * put; a hold on a picture already zoomed in only drags it. None of which takes anything from
 * what was there before — a tap is a tap, a double tap zooms in and out, a swipe scrolls the
 * page, and two fingers resting on the picture are a pinch, not a hold.
 */
@OptIn(ExperimentalTestApi::class)
class ZoomTapGesturesUiTest {

    private val zoom = PinchZoomState()
    private val scroll = ScrollState(initial = 0)
    private var taps = 0
    private var holdZooms = 0

    private fun ComposeUiTest.setUpPlayer() {
        setContent {
            val onTap: () -> Unit = remember { { taps++ } }
            val onHoldZoom: () -> Unit = remember { { holdZooms++ } }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clipToBounds().pinchZoomGestures(zoom)) {
                    Box(Modifier.fillMaxSize().pinchZoomContent(zoom))
                    Box(Modifier.fillMaxSize().testTag(PLAYER_TAG).zoomTapGestures(zoom, onTap = onTap, onHoldZoom = onHoldZoom))
                }
                // The rest of the page: more than a window of it, so there is something to scroll.
                Spacer(Modifier.height(2_000.dp))
            }
        }
    }

    @Test
    fun holdingAFingerOnThePictureZoomsInOnThatSpot() = runComposeUiTest {
        setUpPlayer()
        var playerWidth = 0f

        onNodeWithTag(PLAYER_TAG).performTouchInput {
            playerWidth = width.toFloat()
            longClick(Offset(width * 0.75f, centerY))
        }
        waitForIdle()

        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)
        // The held spot, a quarter of the width right of centre, is still under the finger.
        assertEquals(playerWidth * 0.25f * (1f - PinchZoomState.DOUBLE_TAP_SCALE), zoom.offset.x, 1f)
        assertEquals(1, holdZooms)
        assertEquals(0, taps, "a hold is not a tap")
    }

    @Test
    fun theHoldingFingerDragsThePictureWithoutLifting() = runComposeUiTest {
        setUpPlayer()

        // Held until the zoom has settled: a picture still on its way up from 1x has nowhere to go yet.
        onNodeWithTag(PLAYER_TAG).performTouchInput { down(center) }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)

        var playerWidth = 0f
        var playerHeight = 0f
        onNodeWithTag(PLAYER_TAG).performTouchInput {
            playerWidth = width.toFloat()
            playerHeight = height.toFloat()
            repeat(4) { moveBy(Offset(-width * 0.05f, -height * 0.05f)) }
            up()
        }
        waitForIdle()

        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)
        assertEquals(-playerWidth * 0.2f, zoom.offset.x, 1f)
        assertEquals(-playerHeight * 0.2f, zoom.offset.y, 1f)
        assertEquals(0, scroll.value, "the page must not scroll under a held drag")
        assertEquals(0, taps)
    }

    @Test
    fun aHoldOnAZoomedPictureOnlyDragsIt() = runComposeUiTest {
        setUpPlayer()
        onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)

        // Held past the long-press timeout before it moves, so this is the hold's drag and not
        // the one-finger pan a zoomed picture takes from any drag.
        onNodeWithTag(PLAYER_TAG).performTouchInput { down(center) }
        mainClock.advanceTimeBy(SETTLE_MS)
        var playerWidth = 0f
        onNodeWithTag(PLAYER_TAG).performTouchInput {
            playerWidth = width.toFloat()
            repeat(4) { moveBy(Offset(width * 0.05f, 0f)) }
            up()
        }
        waitForIdle()

        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)
        assertEquals(playerWidth * 0.2f, zoom.offset.x, 1f)
        assertEquals(0, holdZooms, "nothing zoomed, so nothing to tick for")
    }

    @Test
    fun aTapIsStillATapAndADoubleTapStillZoomsInAndOut() = runComposeUiTest {
        setUpPlayer()

        onNodeWithTag(PLAYER_TAG).performTouchInput { click(center) }
        // A tap is only known to be one once the wait for a second has run out.
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(1, taps)
        assertEquals(1f, zoom.scale)

        onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        assertEquals(PinchZoomState.DOUBLE_TAP_SCALE, zoom.scale, 0.01f)

        onNodeWithTag(PLAYER_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        assertEquals(1f, zoom.scale, 0.01f)
        assertEquals(1, taps, "a double tap is not two taps")
        assertEquals(0, holdZooms)
    }

    @Test
    fun aSwipeStillScrollsThePage() = runComposeUiTest {
        setUpPlayer()

        onNodeWithTag(PLAYER_TAG).performTouchInput { swipeUp() }
        waitForIdle()

        assertEquals(true, scroll.value > 0, "the page should have scrolled, was at ${scroll.value}")
        assertEquals(1f, zoom.scale)
        assertEquals(0, holdZooms)
    }

    @Test
    fun twoFingersRestingOnThePictureAreNotAHold() = runComposeUiTest {
        setUpPlayer()

        // Closing, so the pinch itself zooms nothing, and slow enough to outlast the long-press timeout.
        onNodeWithTag(PLAYER_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.3f, 0f),
                end0 = center - Offset(width * 0.1f, 0f),
                start1 = center + Offset(width * 0.3f, 0f),
                end1 = center + Offset(width * 0.1f, 0f),
                durationMillis = 1_000,
            )
        }
        waitForIdle()

        assertEquals(1f, zoom.scale)
        assertEquals(0, holdZooms)
    }

    private companion object {
        const val PLAYER_TAG = "player"

        /** Longer than the long-press and double-tap timeouts, and the zoom animation after them. */
        const val SETTLE_MS = 2_000L
    }
}
