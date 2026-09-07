package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.HomeFeed
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The home grid, rendered for real on every target this source set reaches — the JVM and the iOS
 * simulator. [HomeFeed] is the stateless half of the home screen, so it can be driven straight
 * from fixtures with no graph behind it.
 *
 * Every test here sets `mainClock.autoAdvance = false`. The feed runs animations that never end —
 * `rememberLoadingPhase()` is a `while (true)` frame loop while the cameras are still loading, and
 * `PulsingDot` uses `rememberInfiniteTransition` — so the composition is never idle and anything
 * that waits for idle would hang rather than fail.
 */
@OptIn(ExperimentalTestApi::class)
class HomeFeedUiTest {

    private fun tile(name: String) = CameraTile(
        camera = Camera(name = name, enabled = true),
        streamUrl = "http://frigate.test/live/$name",
        posterUrl = "http://frigate.test/snapshot/$name",
    )

    @Test
    fun showsACardForEachCamera() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = listOf(tile("front_door"), tile("back_yard")),
                    onAwayBack = {},
                ) { tile -> Text(tile.camera.displayName) }
            }
        }

        onNodeWithText("Front Door").assertIsDisplayed()
        onNodeWithText("Back Yard").assertIsDisplayed()
    }

    @Test
    fun saysSoWhenTheServerHasNoCameras() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}) { }
            }
        }

        onNodeWithText("No cameras found on this server.").assertIsDisplayed()
    }

    @Test
    fun showsNoEmptyMessageWhileCamerasAreStillLoading() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                // null, not emptyList: the grid must not flash "no cameras" on the way in.
                HomeFeed(everyoneAway = false, cameras = null, onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("No cameras found on this server.").assertCountEquals(0)
    }

    @Test
    fun theAwayBannerAppearsOnlyWhenEveryoneIsAway() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("I'm back").assertCountEquals(0)
    }

    @Test
    fun imBackReportsTheTapOnce() = runComposeUiTest {
        var backTaps = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = true,
                    cameras = emptyList(),
                    onAwayBack = { backTaps++ },
                ) { }
            }
        }

        onNodeWithText("I'm back").performClick()

        assertEquals(1, backTaps, "one tap on 'I'm back' should mark this device home exactly once")
    }
}
