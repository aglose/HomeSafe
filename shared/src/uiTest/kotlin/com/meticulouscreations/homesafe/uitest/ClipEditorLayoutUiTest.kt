package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.meticulouscreations.homesafe.ui.screens.StageAndControls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clip editor's picture and controls, re-arranged as the phone turns. The picture holds the
 * editor's own player, so what matters is that turning the phone moves and re-sizes it without
 * composing it afresh: a new composition is a new player, and the clip starts over.
 */
@OptIn(ExperimentalTestApi::class)
class ClipEditorLayoutUiTest {

    @Test
    fun turningThePhoneReArrangesThePictureAndControlsWithoutComposingThemAgain() = runComposeUiTest {
        var sideBySide by mutableStateOf(false)
        var stagesMade = 0
        var stagesDisposed = 0
        setContent {
            StageAndControls(
                sideBySide = sideBySide,
                modifier = Modifier.fillMaxSize(),
                stage = {
                    // What a player is to the real stage: made once, and gone for good if disposed.
                    remember { ++stagesMade }
                    DisposableEffect(Unit) { onDispose { stagesDisposed++ } }
                    Box(Modifier.fillMaxSize().testTag("stage"))
                },
                controls = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("controls")) },
            )
        }

        // Upright: the controls under the picture, which has the rest of the height.
        val stageUpright = onNodeWithTag("stage").getUnclippedBoundsInRoot()
        val controlsUpright = onNodeWithTag("controls").getUnclippedBoundsInRoot()
        assertEquals(stageUpright.bottom, controlsUpright.top)
        assertEquals(stageUpright.width, controlsUpright.width)

        sideBySide = true
        waitForIdle()

        // On its side: the controls beside the picture, both the full height.
        val stageOnItsSide = onNodeWithTag("stage").getUnclippedBoundsInRoot()
        val controlsOnItsSide = onNodeWithTag("controls").getUnclippedBoundsInRoot()
        assertEquals(stageOnItsSide.right, controlsOnItsSide.left)
        assertEquals(stageOnItsSide.top, controlsOnItsSide.top)
        assertTrue(stageOnItsSide.width > controlsOnItsSide.width, "the picture has the larger share")

        sideBySide = false
        waitForIdle()

        assertEquals(1, stagesMade, "the same stage throughout")
        assertEquals(0, stagesDisposed)
    }
}
