package com.meticulouscreations.homesafe.car3d

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CarViewerControlsTest {
    @Test
    fun theKeyboardTurnsAndZoomsIt() = runDesktopComposeUiTest(width = 600, height = 400) {
        val orbit = CarOrbitState()
        setContent { CarViewer(CarModel.RED_LONG_RANGE, Modifier.testTag("viewer"), orbit) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(CarOrbitState.START_AZIMUTH - CAR_TURN_STEP, orbit.azimuth)
        onRoot().performKeyInput { pressKey(Key.Equals) }
        waitForIdle()
        assertEquals(CAR_ZOOM_STEP, orbit.zoom)
    }

    @Test
    fun aScreenReaderCanTurnAndZoomIt() = runDesktopComposeUiTest(width = 600, height = 400) {
        val orbit = CarOrbitState()
        setContent { CarViewer(CarModel.RED_LONG_RANGE, Modifier.testTag("viewer"), orbit) }
        waitForIdle()
        val actions = onNodeWithTag("viewer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(6, actions.size)
        actions[0].action()
        assertEquals(CarOrbitState.START_AZIMUTH + CAR_TURN_STEP, orbit.azimuth)
        actions[4].action()
        assertEquals(CAR_ZOOM_STEP, orbit.zoom)
    }

    @Test
    fun aDragHeldStillBeforeLettingGoDoesNotCoast() = runDesktopComposeUiTest(width = 600, height = 400) {
        val orbit = CarOrbitState()
        setContent { CarViewer(CarModel.RED_LONG_RANGE, Modifier.testTag("viewer"), orbit) }
        waitForIdle()
        // All one gesture: the pause has to be in the event times the viewer's tracker sees.
        onNodeWithTag("viewer").performTouchInput {
            down(center)
            repeat(5) {
                advanceEventTime(16)
                moveBy(Offset(40f, 0f))
            }
            advanceEventTime(600)
            up()
        }
        waitForIdle()
        assertEquals(CarOrbitState.START_AZIMUTH - 200f * DEGREES_PER_PIXEL, orbit.azimuth, 0.01f)
    }
}
