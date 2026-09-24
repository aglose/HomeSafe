package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.SharedTransitionPreview
import com.meticulouscreations.homesafe.ui.screens.CameraCard
import com.meticulouscreations.homesafe.ui.screens.CameraCardZoomState
import com.meticulouscreations.homesafe.ui.screens.rememberCameraCardZoomState
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One camera card on its own, outside the feed: what it says about its camera, and what a plain
 * tap does. The pinch and the long press are [CameraCardZoomUiTest]'s; here a tap must open the
 * camera and nothing else. The tiles have no stream, so no player is composed and the badge is
 * whatever the card says before a player has reported anything.
 *
 * [SharedTransitionPreview] stands in for the Home tab's shared-element scope and Navigation 3's
 * animated content scope, both of which the card's video needs to exist.
 *
 * `mainClock.autoAdvance = false` as in [HomeFeedUiTest]: an enabled camera's badge runs its
 * buffering dots forever, so the composition is never idle.
 */
@OptIn(ExperimentalTestApi::class)
class CameraCardUiTest {

    private fun ComposeUiTest.setUpCard(camera: Camera, onClick: () -> Unit = {}): CameraCardZoomState {
        lateinit var state: CameraCardZoomState
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SharedTransitionPreview {
                    state = rememberCameraCardZoomState()
                    Column {
                        CameraCard(
                            tile = CameraTile(camera = camera, streamUrl = null, posterUrl = null),
                            sharedTransitionScope = this@SharedTransitionPreview,
                            zoomState = state,
                            onClick = onClick,
                            modifier = Modifier.testTag(CARD_TAG),
                        )
                    }
                }
            }
        }
        return state
    }

    @Test
    fun namesTheCameraAndSaysItIsStillConnecting() = runComposeUiTest {
        setUpCard(Camera(name = "front_door", enabled = true))

        onNodeWithText("Front Door").assertIsDisplayed()
        // No player has reported a frame, so the card must not claim to be live.
        onNodeWithText("Connecting").assertIsDisplayed()
        onAllNodesWithText("Live").assertCountEquals(0)
    }

    @Test
    fun aCameraTurnedOffInFrigateSaysDisabled() = runComposeUiTest {
        setUpCard(Camera(name = "back_yard", enabled = false))

        onNodeWithText("Back Yard").assertIsDisplayed()
        onNodeWithText("Disabled").assertIsDisplayed()
    }

    @Test
    fun aCameraWithAKnownNameIsCalledByItNotByItsConfigKey() = runComposeUiTest {
        setUpCard(Camera(name = "hikvision_2", enabled = true))

        onNodeWithText("Backyard").assertIsDisplayed()
        onAllNodesWithText("hikvision_2").assertCountEquals(0)
    }

    @Test
    fun aTapOpensTheCameraWithoutLiftingItIntoTheQuickLook() = runComposeUiTest {
        var opened = 0
        val state = setUpCard(Camera(name = "front_door", enabled = true), onClick = { opened++ })

        onNodeWithTag(CARD_TAG).performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        assertEquals(1, opened, "one tap should open the camera exactly once")
        assertNull(state.target, "a tap is not a hold: the quick look should stay closed")
    }

    private companion object {
        const val CARD_TAG = "camera-card"

        /** Longer than a long press's timeout, so a tap mistaken for a hold would have opened by now. */
        const val SETTLE_MS = 1_000L
    }
}
