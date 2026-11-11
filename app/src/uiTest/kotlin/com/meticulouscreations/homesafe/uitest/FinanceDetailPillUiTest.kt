package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.ui.FinanceDetailPill
import com.meticulouscreations.homesafe.finance.ui.FinancePalette
import com.meticulouscreations.homesafe.finance.ui.LocalFinancePalette
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pill at the foot of a finance page: a tap on its back half goes up one level; held, the
 * trail of levels comes out above the thumb, which slides up it and lets go on the one it wants.
 */
@OptIn(ExperimentalTestApi::class)
class FinanceDetailPillUiTest {

    private val pops = mutableListOf<Int>()
    private var hops = 0

    // Three levels beneath the page that is up: the nearest first, the tab last.
    private fun ComposeUiTest.setUpPill() {
        setContent {
            FrigatePreview {
                CompositionLocalProvider(LocalFinancePalette provides FinancePalette()) {
                    Box(Modifier.fillMaxSize()) {
                        FinanceDetailPill(
                            trail = listOf("Bank sync", "Sheet sync", "Wallet"),
                            onPop = { pops += it },
                            onCameras = { hops++ },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    /** Holds the back half until the trail is out, slides to [to] (from the button's top left corner), and lets go. */
    private fun ComposeUiTest.holdAndSlide(to: TouchInjectionScope.() -> Offset?) {
        onNodeWithTag("finance_pill_back").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            to()?.let { moveTo(it) }
            up()
        }
        waitForIdle()
    }

    @Test
    fun aTapGoesBackOneLevel() = runComposeUiTest {
        setUpPill()
        onNodeWithTag("finance_pill_back").performClick()
        waitForIdle()
        assertEquals(listOf(1), pops)
    }

    @Test
    fun heldTheTrailComesOutAndLettingGoWhereItWasPressedGoesBackOne() = runComposeUiTest {
        setUpPill()
        onNodeWithTag("finance_pill_trail").assertDoesNotExist()
        onNodeWithTag("finance_pill_back").performTouchInput { down(center) }
        // Held: time passes with the finger down and nothing else sent.
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithTag("finance_pill_trail").assertIsDisplayed()
        assertEquals(emptyList(), pops)
        onNodeWithTag("finance_pill_back").performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf(1), pops)
    }

    @Test
    fun slidingUpTheTrailPicksTheLevelLetGoOn() = runComposeUiTest {
        setUpPill()
        // The middle of the trail's second row, then of its third: 20dp to the foot of the trail, 48dp a row.
        holdAndSlide { Offset(centerX, -(20.dp + 48.dp * 1.5f).toPx()) }
        holdAndSlide { Offset(centerX, -(20.dp + 48.dp * 2.5f).toPx()) }
        // Past the top it is still the last level.
        holdAndSlide { Offset(centerX, -(400.dp).toPx()) }
        assertEquals(listOf(2, 3, 3), pops)
    }

    @Test
    fun slidingOffToTheSideAndLettingGoStaysPut() = runComposeUiTest {
        setUpPill()
        // Past the trail's far edge and the leeway beside it.
        holdAndSlide { Offset(310.dp.toPx(), centerY) }
        assertEquals(emptyList(), pops)
    }

    @Test
    fun theOtherHalfGoesToTheCameras() = runComposeUiTest {
        setUpPill()
        onNodeWithTag("finance_pill_cameras").performClick()
        waitForIdle()
        assertEquals(1, hops)
        assertEquals(emptyList(), pops)
    }
}
