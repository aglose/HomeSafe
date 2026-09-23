package com.meticulouscreations.homesafe.uitest

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
import kotlin.test.assertTrue

/**
 * The round buttons under the camera screen's player. The captions' wording is pinned by
 * CameraQuickActionsLabelTest; this checks they are actually on screen next to the buttons, and
 * that the quality button opens a picker that explains its options rather than silently cycling.
 */
@OptIn(ExperimentalTestApi::class)
class CameraQuickActionsUiTest {

    @Test
    fun eachButtonIsCaptionedWithItsState() = runComposeUiTest {
        setContent {
            FrigatePreview {
                CameraQuickActions(
                    displayName = "Front Yard",
                    quality = StreamQuality.AUTO,
                    hasQualityChoice = true,
                    isMuted = true,
                    hasAudio = true,
                    alertsEnabled = false,
                    onQualitySelect = {},
                    onQualityUnavailable = {},
                    onToggleSound = {},
                    onToggleAlerts = {},
                )
            }
        }

        onNodeWithText("Auto").assertIsDisplayed()
        onNodeWithText("Muted").assertIsDisplayed()
        onNodeWithText("Alerts off").assertIsDisplayed()
        onNodeWithContentDescription("Turn on alerts for Front Yard", useUnmergedTree = true).assertIsDisplayed()
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
                    alertsEnabled = true,
                    onQualitySelect = { picked = it },
                    onQualityUnavailable = {},
                    onToggleSound = {},
                    onToggleAlerts = {},
                )
            }
        }

        onNodeWithText("Auto").performClick()
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
                    alertsEnabled = true,
                    onQualitySelect = {},
                    onQualityUnavailable = { explained = true },
                    onToggleSound = {},
                    onToggleAlerts = {},
                )
            }
        }

        onNodeWithText("Auto").performClick()

        assertTrue(explained)
        onNodeWithText("Video quality").assertDoesNotExist()
    }
}
