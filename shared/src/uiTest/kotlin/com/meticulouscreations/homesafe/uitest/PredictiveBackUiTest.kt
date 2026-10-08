package com.meticulouscreations.homesafe.uitest

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.meticulouscreations.homesafe.ui.BackScope
import com.meticulouscreations.homesafe.ui.PredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.ui.rememberPredictiveBackTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ui/PredictiveBack.kt` driven by an actual back gesture rather than a tapped back button: each
 * test hands the composition a dispatcher of its own through [LocalNavigationEventDispatcherOwner]
 * and feeds it started / progressed / cancelled / completed events through a
 * [DirectNavigationEventInput], as Android's predictive back, iOS's edge swipe and Escape on the
 * desktop do in the app.
 *
 * Events are sent from [ComposeUiTest.runOnIdle], on the UI thread, as the platform sends them.
 * `mainClock.autoAdvance = false` throughout, so a cancelled swipe's settle and an interrupted
 * scrub can be looked at part of the way through; time is advanced by hand.
 */
@OptIn(ExperimentalTestApi::class)
class PredictiveBackUiTest {

    /** A root back dispatcher with a hand-driven input, as the platform's would be. */
    private class BackHost {
        val dispatcher = NavigationEventDispatcher()
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        val input = DirectNavigationEventInput().also { dispatcher.addInput(it) }
    }

    @Composable
    private fun ProvideBack(host: BackHost, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides host.owner, content = content)

    private fun ComposeUiTest.swipeStarted(host: BackHost) =
        runOnIdle { host.input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f)) }

    private fun ComposeUiTest.swipeProgressed(host: BackHost, progress: Float) =
        runOnIdle { host.input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)) }

    private fun ComposeUiTest.swipeCancelled(host: BackHost) = runOnIdle { host.input.backCancelled() }

    private fun ComposeUiTest.swipeCompleted(host: BackHost) = runOnIdle { host.input.backCompleted() }

    /** Long enough for any settle, scrub or page change here to finish. */
    private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(1_000)

    // --- PredictiveBack.progress ---

    /** One surface that Back closes, drawn from [PredictiveBack.progress] as the app's are. */
    private class BackSurface {
        lateinit var back: PredictiveBack
        val releasedAt = mutableListOf<Float>()
    }

    private fun ComposeUiTest.setUpSurface(host: BackHost): BackSurface {
        val surface = BackSurface()
        mainClock.autoAdvance = false
        setContent {
            ProvideBack(host) {
                surface.back = rememberPredictiveBack { surface.releasedAt += it }
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = surface.back.progress * size.width })
            }
        }
        mainClock.advanceTimeByFrame()
        return surface
    }

    private fun ComposeUiTest.progressOf(surface: BackSurface) = runOnIdle { surface.back.progress }

    @Test
    fun progressRisesWithTheSwipeAndSettlesBackToZeroAfterACancel() = runComposeUiTest {
        val host = BackHost()
        val surface = setUpSurface(host)
        assertEquals(0f, progressOf(surface))

        swipeStarted(host)
        assertTrue(runOnIdle { surface.back.inProgress })
        assertEquals(0f, progressOf(surface), absoluteTolerance = 1e-4f)

        swipeProgressed(host, 0.2f)
        val early = progressOf(surface)
        swipeProgressed(host, 0.6f)
        val late = progressOf(surface)
        assertTrue(early > 0f, "progress at 0.2 of the swipe was $early")
        assertTrue(late > early, "progress went from $early to $late as the swipe went from 0.2 to 0.6")
        assertTrue(late < 1f, "progress at 0.6 of the swipe was $late")

        swipeCancelled(host)
        assertFalse(runOnIdle { surface.back.inProgress })
        // Let go short of the commit point: it starts from where the finger left it...
        assertEquals(late, progressOf(surface))
        mainClock.advanceTimeBy(48)
        val settling = progressOf(surface)
        assertTrue(settling > 0f && settling < late, "part of the way through the settle, progress was $settling (let go at $late)")

        // ...and comes to rest at 0, without Back having happened.
        settle()
        assertEquals(0f, progressOf(surface))
        assertEquals(emptyList<Float>(), surface.releasedAt)
    }

    @Test
    fun aCommittedSwipeReadsZeroAtOnceAndHandsOnBackTheValueItWasLetGoAt() = runComposeUiTest {
        val host = BackHost()
        val surface = setUpSurface(host)

        swipeStarted(host)
        swipeProgressed(host, 0.5f)
        mainClock.advanceTimeByFrame()
        val lettingGoAt = progressOf(surface)
        assertTrue(lettingGoAt > 0f && lettingGoAt < 1f, "progress at 0.5 of the swipe was $lettingGoAt")

        swipeCompleted(host)
        assertEquals(listOf(lettingGoAt), surface.releasedAt)
        assertEquals(0f, progressOf(surface))
        assertFalse(runOnIdle { surface.back.inProgress })

        // Nothing is left to settle: it stays at rest.
        settle()
        assertEquals(0f, progressOf(surface))
    }

    @Test
    fun aPlainBackPressHandsOnBackZero() = runComposeUiTest {
        val host = BackHost()
        val surface = setUpSurface(host)

        // The back button, Escape: completed with no swipe before it.
        swipeCompleted(host)

        assertEquals(listOf(0f), surface.releasedAt)
        assertEquals(0f, progressOf(surface))
    }

    @Test
    fun aSecondSwipeMadeWhileTheFirstSettlesFollowsItsOwnFinger() = runComposeUiTest {
        val host = BackHost()
        val surface = setUpSurface(host)

        swipeStarted(host)
        swipeProgressed(host, 0.2f)
        val atAFifth = progressOf(surface)
        swipeProgressed(host, 0.6f)
        val firstLetGoAt = progressOf(surface)
        swipeCancelled(host)
        mainClock.advanceTimeBy(48)
        val settling = progressOf(surface)
        assertTrue(settling > 0f && settling < firstLetGoAt, "the first swipe should still be settling, at $settling")

        // The second swipe takes over from the settle rather than adding to it or waiting for it.
        swipeStarted(host)
        swipeProgressed(host, 0.2f)
        assertEquals(atAFifth, progressOf(surface))
        swipeProgressed(host, 0.4f)
        val secondLetGoAt = progressOf(surface)

        // The settle the first one started doesn't drag it back down while the finger holds still.
        mainClock.advanceTimeBy(500)
        assertEquals(secondLetGoAt, progressOf(surface))

        swipeCompleted(host)
        assertEquals(listOf(secondLetGoAt), surface.releasedAt)
        assertEquals(0f, progressOf(surface))
        settle()
        assertEquals(0f, progressOf(surface))
    }

    // --- rememberPredictiveBackTransition ---

    /** An `AnimatedContent` page stack popped by Back, as the Weather, Finance and Fitness apps have. */
    private class Pages(vararg initial: String) {
        val stack = mutableStateListOf(*initial)
        lateinit var back: PredictiveBack
    }

    private fun ComposeUiTest.setUpPages(host: BackHost, pages: Pages) {
        mainClock.autoAdvance = false
        setContent {
            ProvideBack(host) {
                val stack = pages.stack
                pages.back = rememberPredictiveBack(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
                rememberPredictiveBackTransition(
                    target = stack.last(),
                    previous = stack.getOrNull(stack.lastIndex - 1),
                    back = pages.back,
                    label = "testPages",
                ).AnimatedContent { page ->
                    Box(Modifier.fillMaxSize().testTag("page-$page"))
                }
            }
        }
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.assertShowsOnly(page: String, other: String) {
        onNodeWithTag("page-$page").assertExists()
        onNodeWithTag("page-$other").assertDoesNotExist()
    }

    private fun ComposeUiTest.assertShowsBoth(first: String, second: String) {
        onNodeWithTag("page-$first").assertExists()
        onNodeWithTag("page-$second").assertExists()
    }

    @Test
    fun midSwipeBothPagesAreComposedAndACancelReturnsToTheOriginalPage() = runComposeUiTest {
        val host = BackHost()
        val pages = Pages("list", "detail")
        setUpPages(host, pages)
        assertShowsOnly("detail", other = "list")

        swipeStarted(host)
        swipeProgressed(host, 0.5f)
        mainClock.advanceTimeBy(100)
        assertShowsBoth("detail", "list")

        swipeCancelled(host)
        settle()
        assertShowsOnly("detail", other = "list")
        assertEquals(listOf("list", "detail"), pages.stack.toList())
    }

    @Test
    fun aCommittedSwipeLandsOnThePreviousPage() = runComposeUiTest {
        val host = BackHost()
        val pages = Pages("list", "detail")
        setUpPages(host, pages)

        swipeStarted(host)
        swipeProgressed(host, 0.5f)
        mainClock.advanceTimeBy(100)
        assertShowsBoth("detail", "list")

        swipeCompleted(host)
        settle()
        assertShowsOnly("list", other = "detail")
        assertEquals(listOf("list"), pages.stack.toList())
    }

    @Test
    fun aSecondSwipeMadeWhileACancelledOneStillScrubsBackLandsOnThePreviousPage() = runComposeUiTest {
        val host = BackHost()
        val pages = Pages("list", "detail")
        setUpPages(host, pages)

        swipeStarted(host)
        swipeProgressed(host, 0.8f)
        mainClock.advanceTimeBy(100)
        swipeCancelled(host)
        // Part of the way back: the page underneath is still composed.
        mainClock.advanceTimeBy(48)
        assertShowsBoth("detail", "list")

        swipeStarted(host)
        swipeProgressed(host, 0.5f)
        mainClock.advanceTimeBy(500)
        // The second swipe has the scrub now, from the same page it started on.
        assertTrue(runOnIdle { pages.back.inProgress })
        assertShowsBoth("detail", "list")
        assertEquals(listOf("list", "detail"), pages.stack.toList())

        swipeCompleted(host)
        settle()
        assertShowsOnly("list", other = "detail")
        // One pop for one committed swipe.
        assertEquals(listOf("list"), pages.stack.toList())
    }

    @Test
    fun aSecondSwipeCancelledWhileTheFirstStillScrubsBackStaysOnTheOriginalPage() = runComposeUiTest {
        val host = BackHost()
        val pages = Pages("list", "detail")
        setUpPages(host, pages)

        swipeStarted(host)
        swipeProgressed(host, 0.8f)
        mainClock.advanceTimeBy(100)
        swipeCancelled(host)
        mainClock.advanceTimeBy(48)

        swipeStarted(host)
        swipeProgressed(host, 0.3f)
        mainClock.advanceTimeBy(48)
        swipeCancelled(host)
        settle()

        assertShowsOnly("detail", other = "list")
        assertEquals(listOf("list", "detail"), pages.stack.toList())
        assertEquals(0f, runOnIdle { pages.back.progress })
    }

    // --- BackScope ---

    /**
     * How many times Back reaches each of two handlers (outer to inner) when the newer one sits in
     * a `BackScope(enabled = scopeEnabled)`: one committed swipe, then one plain press.
     */
    private fun ComposeUiTest.backReachesWith(scopeEnabled: Boolean): Pair<Int, Int> {
        val host = BackHost()
        var outer = 0
        var inner = 0
        lateinit var outerBack: PredictiveBack
        lateinit var innerBack: PredictiveBack
        mainClock.autoAdvance = false
        setContent {
            ProvideBack(host) {
                outerBack = rememberPredictiveBack { outer++ }
                // Registered after the outer handler, so without the scope it would hear Back first.
                BackScope(enabled = scopeEnabled) {
                    innerBack = rememberPredictiveBack { inner++ }
                }
            }
        }
        mainClock.advanceTimeByFrame()

        swipeStarted(host)
        swipeProgressed(host, 0.5f)
        val swiping = runOnIdle { outerBack.inProgress to innerBack.inProgress }
        assertEquals(!scopeEnabled to scopeEnabled, swiping, "which surface (outer to inner) the swipe moves")
        swipeCompleted(host)

        // And a plain press, which skips the swipe.
        swipeCompleted(host)
        return outer to inner
    }

    @Test
    fun aHandlerInsideADisabledBackScopeDoesNotHearBackAndTheOuterOneDoes() = runComposeUiTest {
        assertEquals(2 to 0, backReachesWith(scopeEnabled = false), "Back counts (outer to inner)")
    }

    @Test
    fun aHandlerInsideAnEnabledBackScopeHearsBackBeforeTheOuterOne() = runComposeUiTest {
        assertEquals(0 to 2, backReachesWith(scopeEnabled = true), "Back counts (outer to inner)")
    }
}
