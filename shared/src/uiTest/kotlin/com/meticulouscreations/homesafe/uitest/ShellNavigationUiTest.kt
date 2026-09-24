package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell's navigation state — [ShellNavigation] and the per-tab back stack under it — driven
 * through the real scaffold by tapping the real bottom nav, the way `FrigateAppShell` wires the
 * two together. The tab screens themselves each need a view model from the graph, so a line of
 * text naming the tab stands in for them; what is under test is which tab is up, what Back does,
 * and when the shell's top bar gives way to a nested screen's own header.
 *
 * The clock is left to run: nothing here animates forever, and waiting for idle is what lets the
 * top bar's fades finish before the assertions look.
 */
@OptIn(ExperimentalTestApi::class)
class ShellNavigationUiTest {

    private val detection = MomentEvent(
        id = "event-1",
        cameraName = "front_door",
        label = "person",
        subLabel = null,
        startEpochSeconds = 1_789_000_000.0,
        endEpochSeconds = 1_789_000_012.0,
        topScore = 0.9,
        hasClip = true,
        hasSnapshot = false,
    )

    private fun ComposeUiTest.setUpShell(nav: ShellNavigation) {
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = nav.showsTopBar(nav.selectedTab),
                    topBar = { Text("Top bar") },
                    selectedTab = nav.selectedTab,
                    onSelectTab = nav::selectTab,
                ) {
                    Text("Showing ${nav.selectedTab.label}")
                }
            }
        }
    }

    @Test
    fun tappingATabBringsItUp() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Showing Home").assertIsDisplayed()

        onNodeWithText("Moments").performClick()

        onNodeWithText("Showing Moments").assertIsDisplayed()
        assertEquals(listOf(TopLevelRoute.Home, TopLevelRoute.Moments), nav.topLevel.backStack.toList())
    }

    @Test
    fun goingBackToATabMovesItToTheTopRatherThanStackingItTwice() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithText("Moments").performClick()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Moments").performClick()

        onNodeWithText("Showing Moments").assertIsDisplayed()
        assertEquals(
            listOf(TopLevelRoute.Home, TopLevelRoute.Settings, TopLevelRoute.Moments),
            nav.topLevel.backStack.toList(),
        )
    }

    @Test
    fun backReturnsToTheTabVisitedBefore() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Moments").performClick()
        onNodeWithText("Settings").performClick()

        runOnIdle { nav.topLevel.removeLast() }
        onNodeWithText("Showing Moments").assertIsDisplayed()

        runOnIdle { nav.topLevel.removeLast() }
        onNodeWithText("Showing Home").assertIsDisplayed()
        assertEquals(listOf(TopLevelRoute.Home), nav.topLevel.backStack.toList())
    }

    @Test
    fun theHostHearsEveryTabComposeSelects() = runComposeUiTest {
        val heard = mutableListOf<TopLevelRoute>()
        val nav = ShellNavigation(onTabSelected = { heard += it })
        setUpShell(nav)

        onNodeWithText("Settings").performClick()
        onNodeWithText("Home").performClick()

        // Under iOS 26's native tab bar this is the only way the platform's bar learns of a switch.
        assertEquals(listOf(TopLevelRoute.Settings, TopLevelRoute.Home), heard)
    }

    @Test
    fun drillingIntoACameraHidesTheShellsTopBarOnHomeAlone() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Top bar").assertIsDisplayed()

        runOnIdle { nav.homeBackStack.add("front_door") }
        onNodeWithText("Top bar").assertDoesNotExist()

        // Moments has no nested screens, so its root always sits under the shell's bar...
        onNodeWithText("Moments").performClick()
        onNodeWithText("Top bar").assertIsDisplayed()

        // ...and Home is still inside the camera it was left in.
        onNodeWithText("Home").performClick()
        onNodeWithText("Showing Home").assertIsDisplayed()
        onNodeWithText("Top bar").assertDoesNotExist()
    }

    @Test
    fun fullScreenOnADetectionLandsOnHomeInsideTheCamera() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Moments").performClick()

        runOnIdle { nav.openDetection(detection) }

        onNodeWithText("Showing Home").assertIsDisplayed()
        onNodeWithText("Top bar").assertDoesNotExist()
        assertEquals(2, nav.homeBackStack.size, "the camera screen goes on top of the camera list")
    }

    @Test
    fun aNotificationForTheMomentAlreadyUpDoesNotStackASecondCopy() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        runOnIdle { nav.openDetection(detection) }

        // The same moment, arriving the notification's way: it is the same destination.
        runOnIdle {
            nav.openMoment(
                MomentDeepLink(
                    eventId = detection.id,
                    cameraName = detection.cameraName,
                    startEpochSeconds = detection.startEpochSeconds,
                ),
            )
        }

        onNodeWithText("Showing Home").assertIsDisplayed()
        assertEquals(2, nav.homeBackStack.size, "a re-delivered tap must not push the camera twice")
    }
}
