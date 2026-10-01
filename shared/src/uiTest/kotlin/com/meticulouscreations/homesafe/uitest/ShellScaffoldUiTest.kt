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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.LocalNativeTabBar
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import com.meticulouscreations.homesafe.ui.screens.bottomNavClearance
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun runClearance(nativeTabBar: Boolean, expectedDp: Int) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalNativeTabBar provides nativeTabBar) {
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
