package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.CameraQuickActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun onAPhoneOfOrdinaryWidthTheStatusSitsBesideTheButtons() = runComposeUiTest {
        val overflowed = setBar(width = 412.dp, fontScale = 1f)

        val status = onNodeWithTag(STATUS).getBoundsInRoot()
        val quality = onNodeWithContentDescription(QUALITY_AUTO).getBoundsInRoot()
        assertTrue(status.right <= quality.left, "the status ends before the buttons start: $status, $quality")
        assertTrue(status.top < quality.bottom && quality.top < status.bottom, "one row: $status, $quality")
        assertFalse(overflowed(), "the status is shown whole")
    }

    @Test
    fun onASmallPhoneWithLargeTextTheStatusTakesARowOfItsOwnAndKeepsAllItsWords() = runComposeUiTest {
        val overflowed = setBar(width = 320.dp, fontScale = 1.5f)

        val bar = onNodeWithTag(BAR).getBoundsInRoot()
        val status = onNodeWithTag(STATUS).getBoundsInRoot()
        val quality = onNodeWithContentDescription(QUALITY_AUTO).getBoundsInRoot()
        assertTrue(status.bottom <= quality.top, "the status is above the buttons: $status, $quality")
        assertTrue(status.right <= bar.right, "the status fits inside the bar: $status, $bar")
        // Not squeezed into what the buttons leave and cut short: the whole line is there.
        assertFalse(overflowed(), "the status is shown whole")
        listOf(QUALITY_AUTO, "Turn sound on", "Clip a video from Front Yard").forEach { button ->
            onNodeWithContentDescription(button).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    /** The bar at [width] with text at [fontScale], a one-line status at its start; returns whether that line was cut short. */
    private fun ComposeUiTest.setBar(width: Dp, fontScale: Float): () -> Boolean {
        var overflowed = false
        setContent {
            FrigatePreview {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width)) {
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
                            modifier = Modifier.testTag(BAR),
                        ) {
                            Text(
                                text = "GO LIVE 12:41:55 PM",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                onTextLayout = { overflowed = it.hasVisualOverflow },
                                modifier = Modifier.testTag(STATUS),
                            )
                        }
                    }
                }
            }
        }
        return { overflowed }
    }

    private companion object {
        const val QUALITY_AUTO = "Video quality: Auto"
        const val BAR = "bar"
        const val STATUS = "status"
    }
}
