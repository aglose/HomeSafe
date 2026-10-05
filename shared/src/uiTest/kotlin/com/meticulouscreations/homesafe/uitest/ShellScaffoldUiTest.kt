package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.LocalCompactLandscape
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.LocalNativeTabBar
import com.meticulouscreations.homesafe.ui.screens.NAV_RAIL_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import com.meticulouscreations.homesafe.ui.screens.bottomNavClearance
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's chrome with and without a platform-drawn tab bar. On iOS 26 the bar is SwiftUI's
 * Liquid Glass `TabView` (see `IosShell.kt`), and the shell must then draw no bottom nav of its
 * own and keep its content clear of the native bar only, not of the Compose one it isn't drawing.
 */
@OptIn(ExperimentalTestApi::class)
class ShellScaffoldUiTest {

    private fun runShell(nativeTabBar: Boolean, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalNativeTabBar provides nativeTabBar) {
                    ShellScaffold(
                        showTopBar = false,
                        topBar = {},
                        selectedTab = TopLevelRoute.Home,
                        onSelectTab = {},
                    ) {
                        Text("Tab content")
                    }
                }
            }
        }
        block()
    }

    @Test
    fun drawsTheBottomNavWhenComposeOwnsIt() = runShell(nativeTabBar = false) {
        onNodeWithText("Moments").assertIsDisplayed()
        onNodeWithText("Tab content").assertIsDisplayed()
    }

    @Test
    fun leavesTheBottomNavToThePlatformUnderANativeTabBar() = runShell(nativeTabBar = true) {
        onNodeWithText("Moments").assertDoesNotExist()
        onNodeWithText("Tab content").assertIsDisplayed()
    }

    private fun runClearance(nativeTabBar: Boolean, expectedDp: Int, onItsSide: Boolean = false) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalNativeTabBar provides nativeTabBar, LocalCompactLandscape provides onItsSide) {
                Box(Modifier.testTag("clearance").width(10.dp).height(bottomNavClearance()))
            }
        }

        // No system navigation bar in the test host, so the clearance is the chrome alone.
        onNodeWithTag("clearance").assertHeightIsEqualTo(expectedDp.dp)
    }

    @Test
    fun reservesRoomForTheFloatingNavWhenComposeDrawsIt() = runClearance(nativeTabBar = false, expectedDp = 112)

    @Test
    fun reservesOnlyAGapAboveANativeTabBar() = runClearance(nativeTabBar = true, expectedDp = 16)

    @Test
    fun reservesOnlyAGapAtTheFootWhenTheNavIsAtTheSide() = runClearance(nativeTabBar = false, expectedDp = 24, onItsSide = true)

    /**
     * The shell laid out for a phone on its side, whatever size the test host's window is (see
     * [LocalCompactLandscape]): there is no height there to float a bar over the foot of every page.
     */
    private fun runShellOnItsSide(
        nativeTabBar: Boolean = false,
        showBottomNav: Boolean = true,
        onSelectTab: (TopLevelRoute) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalNativeTabBar provides nativeTabBar, LocalCompactLandscape provides true) {
                    ShellScaffold(
                        showTopBar = false,
                        topBar = {},
                        selectedTab = TopLevelRoute.Home,
                        onSelectTab = onSelectTab,
                        showBottomNav = showBottomNav,
                    ) {
                        Text("Tab content", Modifier.testTag("content"))
                    }
                }
            }
        }
        block()
    }

    @Test
    fun aPhoneOnItsSideGetsTheNavAsARailBesideTheContent() = runShellOnItsSide {
        val rail = onNodeWithTag(NAV_RAIL_TEST_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        val content = onNodeWithTag("content").assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue(content.left >= rail.right, "the content starts clear of the rail: content at ${content.left}, rail ends at ${rail.right}")
        // Stood on end: the tabs are one above the other, in the bar's order.
        val home = onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).getUnclippedBoundsInRoot()
        val moments = onNodeWithTag(bottomNavTestTag(TopLevelRoute.Moments)).getUnclippedBoundsInRoot()
        val settings = onNodeWithTag(bottomNavTestTag(TopLevelRoute.Settings)).getUnclippedBoundsInRoot()
        assertTrue(home.bottom <= moments.top && moments.bottom <= settings.top, "Home, Moments, Settings from the top")
    }

    @Test
    fun theRailsTabsAreTheBarsTabs() {
        val tapped = mutableListOf<TopLevelRoute>()
        runShellOnItsSide(onSelectTab = { tapped += it }) {
            onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).assertIsSelected()

            onNodeWithTag(bottomNavTestTag(TopLevelRoute.Settings)).performClick()

            assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Settings), tapped)
        }
    }

    @Test
    fun theRailLeavesWithTheNavAndTheContentTakesItsStripBack() = runShellOnItsSide(showBottomNav = false) {
        onNodeWithTag(NAV_RAIL_TEST_TAG).assertDoesNotExist()
        onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun aNativeTabBarIsNeverARail() = runShellOnItsSide(nativeTabBar = true) {
        onNodeWithTag(NAV_RAIL_TEST_TAG).assertDoesNotExist()
        onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun heldUprightTheNavIsTheBarAlongTheBottom() = runComposeUiTest {
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalCompactLandscape provides false) {
                    ShellScaffold(showTopBar = false, topBar = {}, selectedTab = TopLevelRoute.Home, onSelectTab = {}) {
                        Text("Tab content", Modifier.testTag("content"))
                    }
                }
            }
        }

        onNodeWithTag(NAV_RAIL_TEST_TAG).assertDoesNotExist()
        onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(0.dp)
        val home = onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).assertIsDisplayed().getUnclippedBoundsInRoot()
        val moments = onNodeWithTag(bottomNavTestTag(TopLevelRoute.Moments)).getUnclippedBoundsInRoot()
        assertTrue(home.right <= moments.left, "side by side")
    }

    /**
     * While the finance app covers the shell the tab content leaves the composition — that's what
     * stops the camera streams — and comes back with its saveable state when it's uncovered.
     */
    @Test
    fun coveredContentIsDisposedAndComesBackWithItsSavedState() = runComposeUiTest {
        var covered by mutableStateOf(false)
        var disposals = 0
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = false,
                    topBar = {},
                    selectedTab = TopLevelRoute.Home,
                    onSelectTab = {},
                    contentCovered = covered,
                ) {
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    DisposableEffect(Unit) { onDispose { disposals++ } }
                    Text("Taps $taps", Modifier.clickable { taps++ })
                }
            }
        }
        onNodeWithText("Taps 0").performClick()
        onNodeWithText("Taps 1").performClick()
        onNodeWithText("Taps 2").assertIsDisplayed()

        covered = true
        waitForIdle()
        onNodeWithText("Taps 2").assertDoesNotExist()
        assertEquals(1, disposals)

        covered = false
        waitForIdle()
        onNodeWithText("Taps 2").assertIsDisplayed()
    }

    /** Under the drawer's scrim the shell is out of a screen reader's reach, so focus can't wander behind it. */
    @Test
    fun obscuredContentLeavesTheAccessibilityTree() = runComposeUiTest {
        var obscured by mutableStateOf(false)
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = false,
                    topBar = {},
                    selectedTab = TopLevelRoute.Home,
                    onSelectTab = {},
                    contentObscured = obscured,
                ) {
                    Text("Tab content")
                }
            }
        }
        onNodeWithText("Tab content").assertIsDisplayed()
        obscured = true
        waitForIdle()
        onNodeWithText("Tab content").assertDoesNotExist()
        onNodeWithText("Moments").assertDoesNotExist()
    }
}
