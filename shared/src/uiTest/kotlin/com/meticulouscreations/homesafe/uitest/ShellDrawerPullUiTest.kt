package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ShellDrawer
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.screens.ShellScaffold
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawer drawn out by a swipe across the shell, so it can be reached with the thumb of the
 * hand holding the phone rather than only from the menu button at the top: through the real
 * scaffold and the real drawer, wired to [ShellNavigation] the way `FrigateAppShell` wires them.
 */
@OptIn(ExperimentalTestApi::class)
class ShellDrawerPullUiTest {

    private var sidewaysDrag = 0f
    private var firstVisibleRow: () -> Int = { 0 }

    private fun ComposeUiTest.setUpShell(nav: ShellNavigation) {
        setContent {
            FrigatePreview {
                ShellScaffold(
                    showTopBar = nav.showsTopBar(nav.selectedTab),
                    topBar = { Text("Top bar", Modifier.fillMaxWidth().testTag("top_bar")) },
                    selectedTab = nav.selectedTab,
                    onSelectTab = nav::selectTab,
                    overlay = {
                        ShellDrawer(
                            open = nav.drawerOpen,
                            pull = nav.drawerPull,
                            onClose = { nav.drawerOpen = false },
                        )
                    },
                    showBottomNav = nav.showsBottomNav(nav.selectedTab),
                    contentObscured = nav.drawerOpen || nav.appOpen,
                    drawerPull = nav.drawerPullOn(nav.selectedTab),
                ) {
                    val list = rememberLazyListState()
                    firstVisibleRow = { list.firstVisibleItemIndex }
                    LazyColumn(Modifier.fillMaxSize().testTag("content"), state = list) {
                        // Under the top bar, which lets a touch through to whatever it floats over.
                        item { Text("First row", Modifier.fillMaxWidth().height(80.dp)) }
                        item {
                            // Something with a sideways gesture of its own, as a row of cards has.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .testTag("sideways")
                                    .draggable(rememberDraggableState { sidewaysDrag += it }, Orientation.Horizontal),
                            )
                        }
                        items(40) { Text("Row $it", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.drawerLeft() = onNodeWithTag("drawer_finance", useUnmergedTree = true).getUnclippedBoundsInRoot().left

    @Test
    fun aSwipeAcrossThePageOpensTheDrawer() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithTag("shell_drawer").assertDoesNotExist()

        // Low on the page, where a thumb is, and clear of the screen's edge.
        onNodeWithTag("content").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.9f) }

        runOnIdle { assertTrue(nav.drawerOpen) }
        onNodeWithTag("shell_drawer").assertIsDisplayed()
        assertFalse(nav.drawerPull.active)
    }

    @Test
    fun theDrawerFollowsTheFingerAndShutsWhenLetGoEarly() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithTag("content").performTouchInput {
            down(Offset(width * 0.3f, height * 0.7f))
            // Past the slop and a little further, slowly: a drag, not a flick.
            moveBy(Offset(viewConfiguration.touchSlop, 0f), delayMillis = 60)
            repeat(4) { moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 60) }
        }
        runOnIdle {
            assertTrue(nav.drawerPull.active)
            assertFalse(nav.drawerOpen, "not out until it is let go far enough")
        }
        val partWayOut = drawerLeft()
        assertTrue(partWayOut < 0.dp, "still part of the way off the screen's edge: $partWayOut")
        // Under the drawer as it comes, the shell is out of a screen reader's reach already; the
        // finger is found by the root from here on.
        onNodeWithText("First row").assertDoesNotExist()
        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Moments)).assertDoesNotExist()

        onRoot().performTouchInput {
            moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 60)
        }
        waitForIdle()
        assertTrue(nav.drawerPull.active, "taking the shell's semantics away didn't cut the swipe off")
        assertTrue(drawerLeft() > partWayOut, "moves with the finger")

        onRoot().performTouchInput {
            // Held still before lifting, so the finger leaves with no speed to speak of.
            advanceEventTime(400)
            up()
        }
        runOnIdle {
            assertFalse(nav.drawerPull.active)
            assertFalse(nav.drawerOpen)
        }
        onNodeWithTag("shell_drawer").assertDoesNotExist()
        onNodeWithText("First row").assertIsDisplayed()
    }

    @Test
    fun draggedPastAThirdOfItsWidthItStaysOut() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithTag("content").performTouchInput {
            down(Offset(width * 0.2f, height * 0.7f))
            repeat(16) { moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 60) }
            advanceEventTime(400)
            up()
        }

        runOnIdle { assertTrue(nav.drawerOpen) }
        val left = drawerLeft()
        assertTrue(left > 15.dp && left < 17.dp, "all the way out, at the drawer's own padding: $left")
    }

    @Test
    fun aSwipeAlongEitherBarOpensItToo() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithTag(bottomNavTestTag(TopLevelRoute.Home)).performTouchInput { swipeRight(startX = centerX, endX = centerX + 250.dp.toPx()) }
        runOnIdle {
            assertTrue(nav.drawerOpen)
            nav.drawerOpen = false
        }
        onNodeWithTag("shell_drawer").assertDoesNotExist()

        onNodeWithTag("top_bar").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.9f) }
        runOnIdle { assertTrue(nav.drawerOpen) }
    }

    @Test
    fun aSwipeTheOtherWayOrUpAndDownIsNotTheDrawers() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithTag("content").performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.2f) }
        runOnIdle { assertFalse(nav.drawerOpen) }
        onNodeWithTag("shell_drawer").assertDoesNotExist()

        onNodeWithTag("content").performTouchInput { swipeUp() }
        runOnIdle {
            assertFalse(nav.drawerOpen)
            assertTrue(firstVisibleRow() > 0, "the list still scrolls")
        }
        onNodeWithTag("content").performTouchInput { swipeDown() }
        runOnIdle { assertFalse(nav.drawerOpen) }
        onNodeWithTag("shell_drawer").assertDoesNotExist()
    }

    @Test
    fun whatIsUnderTheFingerKeepsItsOwnSidewaysSwipe() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)

        onNodeWithTag("sideways").performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.9f) }

        runOnIdle {
            assertTrue(sidewaysDrag > 0f, "the row was dragged")
            assertFalse(nav.drawerOpen)
        }
        onNodeWithTag("shell_drawer").assertDoesNotExist()
    }

    /**
     * A swipe always starts the drawer from shut, which is only right because none can start while
     * it is anywhere else: sliding shut, it and its scrim are still over the shell and take the touch.
     */
    @Test
    fun whileTheDrawerIsStillSlidingShutNoSwipeStartsUnderIt() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        runOnIdle { nav.drawerOpen = true }
        onNodeWithTag("shell_drawer").assertIsDisplayed()

        mainClock.autoAdvance = false
        runOnUiThread { nav.drawerOpen = false }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("shell_drawer").assertExists()
        val slidingShut = drawerLeft()
        assertTrue(slidingShut < 16.dp && slidingShut > (16 - 304).dp, "part of the way shut: $slidingShut")

        onRoot().performTouchInput {
            down(Offset(width * 0.9f, height * 0.7f))
            moveBy(Offset(viewConfiguration.touchSlop, 0f), delayMillis = 0)
            moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 0)
        }
        runOnUiThread { assertFalse(nav.drawerPull.active) }
        val after = drawerLeft()
        assertTrue(after > slidingShut - 1.dp && after < slidingShut + 1.dp, "where it was, not jumped shut: $after from $slidingShut")
        onRoot().performTouchInput { up() }

        mainClock.autoAdvance = true
        onNodeWithTag("shell_drawer").assertDoesNotExist()
        runOnIdle { assertFalse(nav.drawerOpen) }
    }

    @Test
    fun aNestedScreenHasNoDrawerToSwipeOut() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        // A camera's own screen: no menu button there, and sideways gestures of its own.
        runOnIdle { nav.homeBackStack.add("front_door") }

        onNodeWithTag("content").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.9f) }

        runOnIdle { assertFalse(nav.drawerOpen) }
        onNodeWithTag("shell_drawer").assertDoesNotExist()
    }

    @Test
    fun theOpenDrawerStillShutsOnASwipeBack() = runComposeUiTest {
        val nav = ShellNavigation()
        setUpShell(nav)
        onNodeWithTag("content").performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.9f) }
        runOnIdle { assertTrue(nav.drawerOpen) }

        onNodeWithTag("drawer_weather").performTouchInput { swipeLeft() }

        runOnIdle { assertFalse(nav.drawerOpen) }
        onNodeWithTag("shell_drawer").assertDoesNotExist()
    }
}
