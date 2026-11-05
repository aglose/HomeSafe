package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraCrop
import com.meticulouscreations.homesafe.ui.components.PinchZoomState
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.SharedTransitionPreview
import com.meticulouscreations.homesafe.ui.screens.CameraCard
import com.meticulouscreations.homesafe.ui.screens.CameraCardZoomOverlay
import com.meticulouscreations.homesafe.ui.screens.CameraCardZoomState
import com.meticulouscreations.homesafe.ui.screens.DismissQuickLookWhenHomeMoves
import com.meticulouscreations.homesafe.ui.screens.DoorsHomeRoute
import com.meticulouscreations.homesafe.ui.screens.HomeFeed
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.screens.rememberCameraCardZoomState
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quick look at a camera from the Home list: real cards in the real feed, with the overlay
 * over them, driven by injected touches. The cards have no stream, so no player is involved.
 *
 * `mainClock.autoAdvance = false` throughout, as in [HomeFeedUiTest]: the cards' status badges
 * pulse forever, so the composition is never idle. Time is advanced by hand past each animation.
 */
@OptIn(ExperimentalTestApi::class)
class CameraCardZoomUiTest {

    private fun tile(name: String, crop: CameraCrop = CameraCrop.WHOLE) = CameraTile(
        camera = Camera(name = name, enabled = true),
        streamUrl = null,
        posterUrl = null,
        crop = crop,
    )

    private fun ComposeUiTest.setUpHome(nav: ShellNavigation? = null): CameraCardZoomState {
        lateinit var state: CameraCardZoomState
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SharedTransitionPreview {
                    state = rememberCameraCardZoomState()
                    Box {
                        HomeFeed(
                            everyoneAway = false,
                            cameras = listOf(tile("front_door"), tile("back_yard"), tile("driveway", crop = LOW_CROP)),
                            onAwayBack = {},
                        ) { tile ->
                            CameraCard(
                                tile = tile,
                                sharedTransitionScope = this@SharedTransitionPreview,
                                zoomState = state,
                                onClick = {},
                                modifier = Modifier.testTag("card-${tile.camera.name}"),
                            )
                        }
                        if (nav != null) DismissQuickLookWhenHomeMoves(nav, state)
                        CameraCardZoomOverlay(state = state, onOpenCamera = {})
                    }
                }
            }
        }
        return state
    }

    @Test
    fun spreadingTwoFingersOnACardOpensTheQuickLookOnIt() = runComposeUiTest {
        val state = setUpHome()

        onNodeWithTag("card-front_door").performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.1f, 0f),
                end0 = center - Offset(width * 0.3f, 0f),
                start1 = center + Offset(width * 0.1f, 0f),
                end1 = center + Offset(width * 0.3f, 0f),
            )
        }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertEquals("front_door", state.target?.camera?.name)
        assertTrue(state.zoom.scale > 1f, "the spread should have zoomed in, was ${state.zoom.scale}")
        onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun aDoorNotificationTapTakesAnOpenLookAwaySoTheDoorsPageIsNotUnderIt() = runComposeUiTest {
        val nav = ShellNavigation()
        val state = setUpHome(nav)
        onNodeWithTag("card-back_yard").performTouchInput { longClick(center) }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("back_yard", state.target?.camera?.name)

        runOnUiThread { nav.openDoors() }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertEquals(DoorsHomeRoute, nav.homeBackStack.last())
        assertNull(state.target)
        onNodeWithContentDescription("Close").assertDoesNotExist()
    }

    @Test
    fun closingTwoFingersOnACardIsNotALook() = runComposeUiTest {
        val state = setUpHome()

        // Slow enough to outlast any platform's long-press timeout: two fingers on a card must
        // not read as one finger held on it.
        onNodeWithTag("card-front_door").performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.3f, 0f),
                end0 = center - Offset(width * 0.1f, 0f),
                start1 = center + Offset(width * 0.3f, 0f),
                end1 = center + Offset(width * 0.1f, 0f),
                durationMillis = 1_000,
            )
        }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertNull(state.target)
    }

    @Test
    fun aLookThatEndsBackAtOneXCloses() = runComposeUiTest {
        val state = setUpHome()
        onNodeWithTag("card-front_door").performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.1f, 0f),
                end0 = center - Offset(width * 0.3f, 0f),
                start1 = center + Offset(width * 0.1f, 0f),
                end1 = center + Offset(width * 0.3f, 0f),
            )
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("front_door", state.target?.camera?.name)

        // A second pinch, now on the overlay, closing the fingers well past 1x.
        onRoot().performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.4f, 0f),
                end0 = center - Offset(width * 0.02f, 0f),
                start1 = center + Offset(width * 0.4f, 0f),
                end1 = center + Offset(width * 0.02f, 0f),
            )
        }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertNull(state.target, "letting go at 1x should have settled the video back into its card")
    }

    @Test
    fun holdingACardOpensTheQuickLookZoomedInOnTheSpot() = runComposeUiTest {
        val state = setUpHome()

        onNodeWithTag("card-back_yard").performTouchInput { longClick(center) }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertEquals("back_yard", state.target?.camera?.name)
        assertEquals(2.5f, state.zoom.scale, 0.01f)
    }

    @Test
    fun theHoldingFingerDragsTheQuickLookWithoutLifting() = runComposeUiTest {
        val state = setUpHome()
        var cardWidth = 0f
        onNodeWithTag("card-back_yard").performTouchInput {
            cardWidth = width.toFloat()
            down(center)
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("back_yard", state.target?.camera?.name)
        val opened = state.zoom.offset

        // Same finger, still down, now under the overlay: its stream stays with the card.
        // Sideways only: in a portrait window a 2.5x video can still be too short to pan vertically.
        onNodeWithTag("card-back_yard").performTouchInput { moveBy(Offset(-cardWidth * 0.2f, 0f)) }
        mainClock.advanceTimeByFrame()

        assertEquals(opened.x - cardWidth * 0.2f, state.zoom.offset.x, 1f)

        onNodeWithTag("card-back_yard").performTouchInput { up() }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("back_yard", state.target?.camera?.name, "letting go of a drag should leave the look open")
    }

    @Test
    fun closeSettlesTheVideoBackIntoItsCard() = runComposeUiTest {
        val state = setUpHome()
        onNodeWithTag("card-back_yard").performTouchInput { longClick(center) }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("back_yard", state.target?.camera?.name)

        onNodeWithContentDescription("Close").performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        assertNull(state.target)
        assertEquals(1f, state.zoom.scale)
    }

    @Test
    fun aCroppedCardsLookSettlesBackOntoItsCropNotTheMiddleOfThePicture() = runComposeUiTest {
        val state = setUpHome()
        scrollIntoView(onNodeWithTag("card-driveway")).performTouchInput { longClick(center) }
        mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals("driveway", state.target?.camera?.name)
        assertEquals(PinchZoomState.DEFAULT_MAX_SCALE, state.zoom.scale, 0.01f, "2.5 times the crop's 2x, as far as the zoom goes")

        onNodeWithContentDescription("Close").performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        assertNull(state.target)
        // Resting on the crop: twice as close, slid up by a quarter of the picture twice over,
        // which is half the box. Bounded by the whole overlay, the slide was clamped to nothing.
        assertEquals(2f, state.zoom.scale, 0.01f)
        assertEquals(-state.contentSize.height / 2f, state.zoom.offset.y, 1f)
    }

    @Test
    fun aPinchOpeningACroppedCardsLookBeforeItIsLaidOutStillLandsOnTheCrop() = runComposeUiTest {
        val state = setUpHome()
        val card = scrollIntoView(onNodeWithTag("card-driveway")).fetchSemanticsNode().boundsInRoot
        // The card's pinch opens the look and hands it its first increment in the same breath,
        // before the overlay has composed, let alone measured: here, all the way to the zoom's
        // limit, about the card's middle.
        runOnUiThread {
            state.openByPinch(tile("driveway", crop = LOW_CROP), card)
            state.pinchFromCard(zoomChange = 2.5f, pan = Offset.Zero, centroidInRoot = card.center)
        }
        mainClock.advanceTimeBy(SETTLE_MS)

        assertEquals("driveway", state.target?.camera?.name)
        // Five boxes tall, taller than the screen, so the picture stays slid up toward the crop as
        // far as the screen allows. Applied before the crop had a place, it would sit in the middle.
        assertEquals(PinchZoomState.DEFAULT_MAX_SCALE, state.zoom.scale, 0.01f)
        assertTrue(state.zoom.offset.y < -state.contentSize.height * 0.25f, "slid up to the crop, was ${state.zoom.offset.y}")
    }

    private companion object {
        /** Twice as close, low in the picture: what Arrange mode saves for a card framed on a driveway's near end. */
        val LOW_CROP = CameraCrop(zoom = 2f, centerY = 0.75f)

        /** Longer than the lift and the zoom animations plus the frames either side of them. */
        const val SETTLE_MS = 2_000L
    }
}
