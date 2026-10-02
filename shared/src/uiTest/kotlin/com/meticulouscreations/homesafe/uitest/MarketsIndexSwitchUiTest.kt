package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.ui.FinanceFixtures
import com.meticulouscreations.homesafe.finance.ui.FinancePalette
import com.meticulouscreations.homesafe.finance.ui.FinanceTypography
import com.meticulouscreations.homesafe.finance.ui.LocalFinancePalette
import com.meticulouscreations.homesafe.finance.ui.LocalFinanceTypography
import com.meticulouscreations.homesafe.finance.ui.MarketsScreen
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Tapping between the index cards under the Markets chart swaps the hero's name and the day's
 * verdict ("Dow is down 0.24% today — a quiet day. …"), whose length differs by index; where one
 * index's fit on a line and the next's took two, everything under the chart jumped at each tap.
 * The cards (and the page below them) must stay exactly where they are, whichever index is up,
 * on a narrow phone, a wide screen, and with larger text.
 */
@OptIn(ExperimentalTestApi::class)
class MarketsIndexSwitchUiTest {

    /** Created but not started: the 1D chart's once-a-minute refresh waits on STARTED, so it never spins the test's clock. */
    private val notStarted = object : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
    }

    /**
     * The fixtures with the session open around the real clock, which the screen reads: the hero's
     * pill then says "Market open · closes …", its longest, leaving the name the least room.
     */
    private val openState = run {
        val now = Clock.System.now().epochSeconds
        FinanceFixtures.state.copy(
            quotes = FinanceFixtures.state.quotes.mapValues { (_, q) -> q.copy(sessionStartEpochSeconds = now - 3 * 3600, sessionEndEpochSeconds = now + 3 * 3600) },
        )
    }

    /** Where each index card's top sits once it's tapped. */
    private fun cardTops(width: Dp, fontScale: Float = 1f): List<Float> {
        val tops = mutableListOf<Float>()
        // The hero's aurora is a shader drawn in software here: seconds per simulated second.
        runComposeUiTest(testTimeout = 3.minutes) {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalLifecycleOwner provides notStarted,
                        LocalFinancePalette provides FinancePalette(),
                        LocalFinanceTypography provides FinanceTypography(albertSansFontFamily()),
                    ) {
                        // The root fills the test window whatever it's given; a phone's width inside it.
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width)) {
                                MarketsScreen(openState, rememberLazyListState(), PaddingValues(), { _, _ -> }, {}, {})
                            }
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(500)
            // The cards' row: the page's one sideways list.
            val cards = onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            MarketCatalog.indices.forEachIndexed { i, index ->
                val tag = "finance_index_${index.symbol}"
                // One jump to the card (scrolling to a node steps the paused clock without end).
                cards.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(i) }
                mainClock.advanceTimeByFrame()
                onNodeWithTag(tag).performClick()
                // Past the change line's crossfade.
                mainClock.advanceTimeBy(400)
                tops += onNodeWithTag(tag).getBoundsInRoot().top.value
            }
        }
        return tops
    }

    private fun assertSteady(tops: List<Float>) =
        assertEquals(List(tops.size) { tops.first() }, tops, "the index cards' top with ${MarketCatalog.indices.joinToString { it.shortName }} up in turn")

    @Test
    fun switchingIndicesNeverMovesThePageOnANarrowPhone() = assertSteady(cardTops(360.dp))

    // Wide enough that some days' verdicts fit on one line and others take two: where it jumped.
    @Test
    fun switchingIndicesNeverMovesThePageOnAWideScreen() = assertSteady(cardTops(480.dp))

    @Test
    fun switchingIndicesNeverMovesThePageWithLargerText() = assertSteady(cardTops(412.dp, fontScale = 1.3f))
}
