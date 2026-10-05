package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                    Text("Showing ${stringResource(nav.selectedTab.label)}")
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
    fun switchingTabsReplacesTheOneAboveHomeRatherThanStackingAHistory() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithText("Moments").performClick()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Moments").performClick()

        onNodeWithText("Showing Moments").assertIsDisplayed()
        assertEquals(listOf(TopLevelRoute.Home, TopLevelRoute.Moments), nav.topLevel.backStack.toList())

        onNodeWithText("Home").performClick()
        assertEquals(listOf(TopLevelRoute.Home), nav.topLevel.backStack.toList())
    }

    @Test
    fun backFromAnyTabReturnsToHome() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Moments").performClick()
        onNodeWithText("Settings").performClick()

        runOnIdle { nav.back() }

        onNodeWithText("Showing Home").assertIsDisplayed()
        assertEquals(listOf(TopLevelRoute.Home), nav.topLevel.backStack.toList())
    }

    @Test
    fun backToHomeLandsOnTheCameraListNotTheCameraItWasLeftIn() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        runOnIdle { nav.homeBackStack.add("front_door") }
        onNodeWithText("Moments").performClick()

        runOnIdle { nav.back() }

        onNodeWithText("Showing Home").assertIsDisplayed()
        onNodeWithText("Top bar").assertIsDisplayed()
        assertEquals(1, nav.homeBackStack.size)
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

        // ...and Home comes back at its root: the bottom nav clears what was drilled into.
        onNodeWithText("Home").performClick()
        onNodeWithText("Showing Home").assertIsDisplayed()
        onNodeWithText("Top bar").assertIsDisplayed()
        assertEquals(1, nav.homeBackStack.size)
    }

    @Test
    fun tappingTheTabThatIsUpPopsItBackToItsRoot() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithText("Settings").performClick()
        runOnIdle {
            nav.settingsBackStack.add("classifier")
            nav.settingsBackStack.add("faces")
        }
        onNodeWithText("Top bar").assertDoesNotExist()

        onNodeWithText("Settings").performClick()

        onNodeWithText("Showing Settings").assertIsDisplayed()
        onNodeWithText("Top bar").assertIsDisplayed()
        assertEquals(1, nav.settingsBackStack.size)
    }

    /** The shell around a long list standing in for Home's camera list, wired as `FrigateAppShell` wires a tab's root. */
    private fun ComposeUiTest.setUpShellOverAList(nav: ShellNavigation, listState: LazyListState) {
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = nav.showsTopBar(nav.selectedTab),
                    topBar = { Text("Top bar") },
                    selectedTab = nav.selectedTab,
                    onSelectTab = nav::selectTab,
                ) {
                    val scrollToTop = remember(nav) { nav.reselections(TopLevelRoute.Home) }
                    LaunchedEffect(listState, scrollToTop) { scrollToTop.collect { listState.animateScrollToItem(0) } }
                    LazyColumn(state = listState) {
                        items(50) { Text("Row $it", Modifier.height(120.dp)) }
                    }
                }
            }
        }
    }

    @Test
    fun tappingTheTabThatIsUpAtItsRootScrollsItBackToTheTop() = runComposeUiTest {
        val nav = ShellNavigation()
        val listState = LazyListState(firstVisibleItemIndex = 30)
        setUpShellOverAList(nav, listState)

        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).performClick()

        waitUntil(timeoutMillis = 5_000) { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }

    @Test
    fun tappingTheTabThatIsUpInsideANestedScreenPopsItWithoutScrollingTheRoot() = runComposeUiTest {
        val nav = ShellNavigation()
        val listState = LazyListState(firstVisibleItemIndex = 30)
        setUpShellOverAList(nav, listState)
        runOnIdle { nav.homeBackStack.add("front_door") }

        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).performClick()

        runOnIdle {
            assertEquals(1, nav.homeBackStack.size)
            assertEquals(30, listState.firstVisibleItemIndex, "the first tap only returns to the root; a second scrolls it")
        }
    }

    @Test
    fun aCameraOnAPhoneOnItsSideHasTheWindowToItselfAndTurningBackReturnsTheNav() {
        val nav = ShellNavigation()
        nav.openDetection(detection)
        assertFalse(nav.showsFullScreenVideo(TopLevelRoute.Home), "upright, the camera is a page under the nav")
        assertTrue(nav.showsBottomNav(TopLevelRoute.Home))

        nav.compactLandscape = true

        assertTrue(nav.showsFullScreenVideo(TopLevelRoute.Home))
        assertFalse(nav.showsBottomNav(TopLevelRoute.Home), "the video has the whole window")
        // Under iOS 26's native bar every tab asks; only the one showing the camera gives up its nav.
        assertFalse(nav.showsFullScreenVideo(TopLevelRoute.Moments))
        assertTrue(nav.showsBottomNav(TopLevelRoute.Moments))

        nav.compactLandscape = false

        assertFalse(nav.showsFullScreenVideo(TopLevelRoute.Home))
        assertTrue(nav.showsBottomNav(TopLevelRoute.Home))
    }

    @Test
    fun onlyACameraGoesFullScreenOnAPhoneOnItsSide() {
        val nav = ShellNavigation()
        nav.compactLandscape = true

        assertFalse(nav.showsFullScreenVideo(TopLevelRoute.Home), "the camera list keeps its nav, as a rail")
        assertTrue(nav.showsBottomNav(TopLevelRoute.Home))

        // A screen beyond the camera (its zone editor, say) is a page again.
        nav.openDetection(detection)
        nav.homeBackStack.add("zones")
        assertFalse(nav.showsFullScreenVideo(TopLevelRoute.Home))
        assertTrue(nav.showsBottomNav(TopLevelRoute.Home))
    }

    @Test
    fun aDetectionOpensDirectlyAboveTheCameraListWhateverHomeHadOpen() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        runOnIdle {
            nav.homeBackStack.add("back_yard")
            nav.homeBackStack.add("zones")
        }
        onNodeWithText("Moments").performClick()

        runOnIdle { nav.openDetection(detection) }

        onNodeWithText("Showing Home").assertIsDisplayed()
        assertEquals(2, nav.homeBackStack.size, "Back from the moment goes straight to the camera list")
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
