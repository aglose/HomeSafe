package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import com.meticulouscreations.homesafe.ui.screens.ShellSkeleton
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shell's own chrome, drawn by Compose: the floating bottom nav and the top bar that fades
 * away over a nested screen's header, plus the skeleton of both that the sign-in screen shows
 * while the server is authenticating. [ShellScaffoldUiTest] covers leaving the nav to a native
 * tab bar; this covers the nav Compose does draw — every tab on it, only the one that is up
 * marked selected, and every tap reported as the tab it was on.
 *
 * The scaffold tests let the clock run, so the top bar's fades finish before anything is
 * asserted. The skeleton tests can't: its runner is a `while (true)` frame loop, so they set
 * `mainClock.autoAdvance = false` as [HomeFeedUiTest] does.
 */
@OptIn(ExperimentalTestApi::class)
class ShellChromeUiTest {

    @Test
    fun theBottomNavNamesEveryTab() = runComposeUiTest {
        setContent {
            FrigatePreview {
                ShellScaffold(showTopBar = false, topBar = {}, selectedTab = TopLevelRoute.Home, onSelectTab = {}) {
                    Text("Tab content")
                }
            }
        }

        onNodeWithText("Home").assertIsDisplayed()
        onNodeWithText("Moments").assertIsDisplayed()
        onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun eachTabIsDescribedByItsNameForAScreenReader() = runComposeUiTest {
        setContent {
            FrigatePreview {
                ShellScaffold(showTopBar = false, topBar = {}, selectedTab = TopLevelRoute.Moments, onSelectTab = {}) {
                    Text("Tab content")
                }
            }
        }

        TOP_LEVEL_ROUTES.forEach { route ->
            onNodeWithContentDescription(route.label).assertIsDisplayed()
        }
    }

    @Test
    fun onlyTheTabThatIsUpIsMarkedSelected() = runComposeUiTest {
        setContent {
            FrigatePreview {
                ShellScaffold(showTopBar = false, topBar = {}, selectedTab = TopLevelRoute.Moments, onSelectTab = {}) {
                    Text("Tab content")
                }
            }
        }

        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Moments)).assertIsSelected()
        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).assertIsNotSelected()
        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Settings)).assertIsNotSelected()
    }

    @Test
    fun tappingEachTabReportsThatTab() = runComposeUiTest {
        val tapped = mutableListOf<TopLevelRoute>()
        setContent {
            FrigatePreview {
                ShellScaffold(showTopBar = false, topBar = {}, selectedTab = TopLevelRoute.Home, onSelectTab = { tapped += it }) {
                    Text("Tab content")
                }
            }
        }

        onNodeWithText("Moments").performClick()
        onNodeWithText("Settings").performClick()
        // The tab already up is reported too: the shell, not the bar, decides that is a no-op.
        onNodeWithText("Home").performClick()

        assertEquals(listOf(TopLevelRoute.Moments, TopLevelRoute.Settings, TopLevelRoute.Home), tapped)
    }

    @Test
    fun theTopBarFadesAwayAndBackWithoutTakingTheContentWithIt() = runComposeUiTest {
        var showTopBar by mutableStateOf(true)
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = showTopBar,
                    topBar = { Text("Top bar") },
                    selectedTab = TopLevelRoute.Home,
                    onSelectTab = {},
                ) {
                    Text("Tab content")
                }
            }
        }
        onNodeWithText("Top bar").assertIsDisplayed()

        runOnIdle { showTopBar = false }

        onNodeWithText("Top bar").assertDoesNotExist()
        onNodeWithText("Tab content").assertIsDisplayed()
        onNodeWithText("Home").assertIsDisplayed()

        runOnIdle { showTopBar = true }

        onNodeWithText("Top bar").assertIsDisplayed()
    }

    @Test
    fun theSignInSkeletonWearsTheRealShellsChrome() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ShellSkeleton()
            }
        }

        onNodeWithText("PERCYSAFE").assertIsDisplayed()
        onNodeWithContentDescription("Menu").assertIsDisplayed()
        // Not signed in yet, so no route to badge: the plain status icon stands in its place.
        onNodeWithContentDescription("Status").assertIsDisplayed()
        onNodeWithText("Home").assertIsDisplayed()
        onNodeWithText("Moments").assertIsDisplayed()
        onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun theSignInSkeletonShowsHomeLoadingNotEmpty() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ShellSkeleton()
            }
        }

        // The skeleton is Home with its cameras still on the way, never Home with none.
        onAllNodesWithText("No cameras found on this server.").assertCountEquals(0)
    }
}
