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
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.SharedTransitionPreview
import com.meticulouscreations.homesafe.ui.screens.CameraCard
import com.meticulouscreations.homesafe.ui.screens.CameraCardZoomOverlay
import com.meticulouscreations.homesafe.ui.screens.CameraCardZoomState
import com.meticulouscreations.homesafe.ui.screens.HomeFeed
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

    private fun tile(name: String) = CameraTile(
        camera = Camera(name = name, enabled = true),
        streamUrl = null,
        posterUrl = null,
    )

    private fun ComposeUiTest.setUpHome(): CameraCardZoomState {
        lateinit var state: CameraCardZoomState
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SharedTransitionPreview {
                    state = rememberCameraCardZoomState()
                    Box {
                        HomeFeed(
                            everyoneAway = false,
                            cameras = listOf(tile("front_door"), tile("back_yard")),
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
    fun closingTwoFingersOnACardIsNotALook() = runComposeUiTest {
        val state = setUpHome()

        onNodeWithTag("card-front_door").performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.3f, 0f),
                end0 = center - Offset(width * 0.1f, 0f),
                start1 = center + Offset(width * 0.3f, 0f),
                end1 = center + Offset(width * 0.1f, 0f),
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

    private companion object {
        /** Longer than the lift and the zoom animations plus the frames either side of them. */
        const val SETTLE_MS = 2_000L
    }
}
