package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.CameraQuickActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bar under the camera screen's player. The badge's wording is pinned by
 * CameraQuickActionsLabelTest; this checks each button says its state to a screen reader, that
 * whatever the caller puts at its start is shown, and that the quality button opens a picker that
 * explains its options rather than silently cycling.
 */
@OptIn(ExperimentalTestApi::class)
class CameraQuickActionsUiTest {

    @Test
    fun eachButtonSaysItsState() = runComposeUiTest {
        setContent {
            FrigatePreview {
                CameraQuickActions(
                    displayName = "Front Yard",
                    quality = StreamQuality.AUTO,
                    hasQualityChoice = true,
                    isMuted = true,
                    hasAudio = true,
                    onQualitySelect = {},
                    onQualityUnavailable = {},
                    onToggleSound = {},
                    onClip = {},
                ) {
                    Text("LIVE")
                }
            }
        }

        onNodeWithText("LIVE").assertIsDisplayed()
        onNodeWithContentDescription(QUALITY_AUTO).assertIsDisplayed()
        onNodeWithContentDescription("Turn sound on").assertIsDisplayed()
        onNodeWithContentDescription("Clip a video from Front Yard").assertIsDisplayed()
    }

    @Test
    fun theScissorsOpenTheClipEditorFromWhereTheyAre() = runComposeUiTest {
        var origin: Offset? = null
        setContent {
            FrigatePreview {
                CameraQuickActions(
                    displayName = "Front Yard",
                    quality = StreamQuality.AUTO,
                    hasQualityChoice = true,
                    isMuted = true,
                    hasAudio = true,
                    onQualitySelect = {},
                    onQualityUnavailable = {},
                    onToggleSound = {},
                    onClip = { origin = it },
                )
            }
        }

        onNodeWithContentDescription("Clip a video from Front Yard").performClick()

        // The splash lands on the button: right of centre (the bar's last button), inside the window.
        val landed = assertNotNull(origin)
        assertTrue(landed.x in 0.5f..1f, "the clip button is the right-hand slot, got $landed")
        assertTrue(landed.y in 0f..1f, "the origin is a fraction of the window, got $landed")
    }

    @Test
    fun qualityOpensAPickerThatSaysWhatEachChoiceDoes() = runComposeUiTest {
        var picked: StreamQuality? = null
        setContent {
            FrigatePreview {
                CameraQuickActions(
                    displayName = "Front Yard",
                    quality = StreamQuality.AUTO,
                    hasQualityChoice = true,
                    isMuted = true,
                    hasAudio = true,
                    onQualitySelect = { picked = it },
                    onQualityUnavailable = {},
                    onToggleSound = {},
                    onClip = {},
                )
            }
        }

        onNodeWithContentDescription(QUALITY_AUTO).performClick()
        onNodeWithText("Lighter on data, no sound").assertIsDisplayed()
        onNodeWithText("SD").performClick()

        assertEquals(StreamQuality.LOW, picked)
    }

    @Test
    fun aCameraWithOneStreamExplainsInsteadOfOpeningThePicker() = runComposeUiTest {
        var explained = false
        setContent {
            FrigatePreview {
                CameraQuickActions(
                    displayName = "Front Yard",
                    quality = StreamQuality.AUTO,
                    hasQualityChoice = false,
                    isMuted = true,
                    hasAudio = true,
                    onQualitySelect = {},
                    onQualityUnavailable = { explained = true },
                    onToggleSound = {},
                    onClip = {},
                )
            }
        }

        onNodeWithContentDescription(QUALITY_AUTO).performClick()

        assertTrue(explained)
        onNodeWithText("Video quality").assertDoesNotExist()
    }

    private companion object {
        const val QUALITY_AUTO = "Video quality: Auto"
    }
}
