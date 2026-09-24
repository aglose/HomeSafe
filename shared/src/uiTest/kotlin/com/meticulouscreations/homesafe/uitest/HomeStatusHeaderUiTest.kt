package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.HOME_STATUS_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.HomeStatusHeader
import kotlin.test.Test

/**
 * The two-line summary at the top of Home, on its own. [HomeFeedUiTest] already checks that the
 * feed shows it and that a tap opens the Moments tab; this pins down the header's own promises —
 * that a line still loading holds its place, so the cards below don't jump when it lands; that a
 * new summary replaces the old one rather than piling up under it; and that a screen reader is
 * told where a tap goes, since the header looks like text rather than a button.
 *
 * Wrapped in a [Column] because [FrigatePreview]'s Surface hands its full height down as a
 * minimum, and the header would otherwise be stretched to fill the window.
 *
 * Nothing here animates forever, so the clock is left to run: waiting for idle is what lets
 * each crossfade finish before the assertions look.
 */
@OptIn(ExperimentalTestApi::class)
class HomeStatusHeaderUiTest {

    @Test
    fun tellsAScreenReaderThatATapOpensTheMoments() = runComposeUiTest {
        setContent {
            FrigatePreview {
                Column {
                    HomeStatusHeader(headline = "Person at Backyard", details = "3 min ago", onClick = {})
                }
            }
        }

        onNodeWithTag(HOME_STATUS_TEST_TAG).assert(
            SemanticsMatcher("its click is labelled \"Open moments\"") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open moments"
            },
        )
    }

    @Test
    fun theLinesFillInWithoutMovingThePage() = runComposeUiTest {
        var headline by mutableStateOf<String?>(null)
        var details by mutableStateOf<String?>(null)
        setContent {
            FrigatePreview {
                Column {
                    HomeStatusHeader(headline = headline, details = details, onClick = {})
                }
            }
        }
        val loadingHeight = onNodeWithTag(HOME_STATUS_TEST_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }

        runOnIdle {
            headline = "All quiet"
            details = "2 cameras on"
        }

        onNodeWithText("All quiet").assertIsDisplayed()
        onNodeWithText("2 cameras on").assertIsDisplayed()
        // Both blank lines were already a line tall, so the summary landing moves nothing below it.
        onNodeWithTag(HOME_STATUS_TEST_TAG).assertHeightIsEqualTo(loadingHeight)
    }

    @Test
    fun aNewSummaryReplacesTheOldOne() = runComposeUiTest {
        var headline by mutableStateOf<String?>("Person at Backyard")
        setContent {
            FrigatePreview {
                Column {
                    HomeStatusHeader(headline = headline, details = "Just now", onClick = {})
                }
            }
        }
        onNodeWithText("Person at Backyard").assertIsDisplayed()

        runOnIdle { headline = "Car at Front Door" }

        onNodeWithText("Car at Front Door").assertIsDisplayed()
        onNodeWithText("Person at Backyard").assertDoesNotExist()
    }
}
