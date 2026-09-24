package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.components.BufferingDots
import com.meticulouscreations.homesafe.ui.components.LiveViewUnavailablePlaceholder
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.components.SkeletonCameraCard
import com.meticulouscreations.homesafe.ui.components.rememberLoadingPhase
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The small things that stand in while something is on its way: the skeleton card and the
 * runner going round it, the pulsing dot and the three buffering dots, and the placeholder a
 * platform with no live player shows. None of them has semantics beyond a size, so what is
 * pinned is their shape — a skeleton is exactly a camera card's 16:9, three dots are three dots
 * wide — and, for the runner, that it is driven by the frame clock's absolute time: that is what
 * keeps the sign-in screen's skeleton and Home's in step across the hand-over between them.
 *
 * Each piece sits in a [Box] so [FrigatePreview]'s Surface can't stretch it to the window. The
 * dots and the runner animate forever, so those tests set `mainClock.autoAdvance = false`, as
 * [HomeFeedUiTest] does, and move time on by hand.
 */
@OptIn(ExperimentalTestApi::class)
class LoadingStatesUiTest {

    @Test
    fun aPlatformWithNoLivePlayerSaysSo() = runComposeUiTest {
        setContent {
            FrigatePreview {
                Box {
                    LiveViewUnavailablePlaceholder()
                }
            }
        }

        onNodeWithText("Live view not yet available on this platform").assertIsDisplayed()
    }

    @Test
    fun aSkeletonCardHasTheShapeOfTheCardItStandsFor() = runComposeUiTest {
        setContent {
            FrigatePreview {
                Box {
                    SkeletonCameraCard(phase = { 0.3f }, modifier = Modifier.testTag("skeleton").width(320.dp))
                }
            }
        }

        onNodeWithTag("skeleton").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(180.dp)
    }

    @Test
    fun theRunnerGoesRoundTheCardOnceEveryTwoPointFourSeconds() = runComposeUiTest {
        lateinit var phase: State<Float>
        mainClock.autoAdvance = false
        setContent {
            phase = rememberLoadingPhase()
        }
        mainClock.advanceTimeBy(160)
        val start = phase.value

        mainClock.advanceTimeBy(600)
        val quarterLap = (phase.value - start).mod(1f)
        mainClock.advanceTimeBy(1_800)
        val fullLap = (phase.value - start).mod(1f)

        // Time only moves a whole frame at a time, so both are allowed a frame or so either way.
        assertEquals(0.25f, quarterLap, 0.02f)
        assertTrue(fullLap < 0.02f || fullLap > 0.98f, "a 2.4s lap should come back round to where it began, but moved $fullLap")
    }

    @Test
    fun aSkeletonThatAppearsLaterRunsInStepWithTheOneAlreadyThere() = runComposeUiTest {
        lateinit var first: State<Float>
        lateinit var second: State<Float>
        var showSecond by mutableStateOf(false)
        mainClock.autoAdvance = false
        setContent {
            first = rememberLoadingPhase()
            if (showSecond) second = rememberLoadingPhase()
        }
        mainClock.advanceTimeBy(1_000)

        runOnIdle { showSecond = true }
        mainClock.advanceTimeBy(500)

        // Both read the same frame time, not time since they appeared — so the sign-in skeleton
        // handing over to Home's shows one runner carrying on, not a second one starting at zero.
        assertEquals(first.value, second.value, 0.001f)
    }

    @Test
    fun aPulsingDotIsTheSizeItWasAskedFor() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Box {
                    PulsingDot(color = Color.Red, modifier = Modifier.testTag("dot"), size = 12.dp)
                }
            }
        }
        mainClock.advanceTimeBy(500)

        onNodeWithTag("dot").assertWidthIsEqualTo(12.dp).assertHeightIsEqualTo(12.dp)
    }

    @Test
    fun aStillDotLetsTheScreenSettle() = runComposeUiTest {
        // The clock is left to run: a dot that isn't pulsing must not keep a frame loop going,
        // or every screen with a disabled camera on it could never be idle.
        setContent {
            FrigatePreview {
                Box {
                    PulsingDot(color = Color.Red, modifier = Modifier.testTag("dot"), pulsing = false)
                }
            }
        }

        onNodeWithTag("dot").assertIsDisplayed().assertWidthIsEqualTo(8.dp)
    }

    @Test
    fun theBufferingDotsAreThreeDotsInARow() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Box {
                    BufferingDots(color = Color.Red, modifier = Modifier.testTag("dots"), dotSize = 6.dp)
                }
            }
        }
        mainClock.advanceTimeBy(500)

        // Three 6dp dots with half a dot between each: 3 x 6 + 2 x 3.
        onNodeWithTag("dots").assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(6.dp)
    }
}
