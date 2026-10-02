package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.ui.LocalChartStyle
import com.meticulouscreations.homesafe.finance.ui.components.Bar
import com.meticulouscreations.homesafe.finance.ui.components.BarChart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wallet's bar chart buzzes with the chart settings' haptics when a tap picks a new bar, and
 * not at all when they're off or the bar is already picked.
 */
@OptIn(ExperimentalTestApi::class)
class FinanceChartHapticsUiTest {

    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private fun tapsWith(feel: ChartHaptics): List<HapticFeedbackType> {
        val haptics = RecordingHaptics()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalChartStyle provides ChartStyle(haptics = feel)) {
                    var selected by remember { mutableStateOf<Int?>(null) }
                    BarChart(
                        bars = listOf(Bar("Jan", 3.0, Color.Green), Bar("Feb", 5.0, Color.Green)),
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.width(300.dp).height(160.dp),
                    )
                }
            }
            onNodeWithText("Feb").performClick()
            waitForIdle()
            onNodeWithText("Feb").performClick()
            onNodeWithText("Jan").performClick()
            waitForIdle()
        }
        return haptics.performed
    }

    @Test
    fun aNewBarTicksAsStronglyAsTheSettingSays() {
        assertEquals(listOf(HapticFeedbackType.KeyboardTap, HapticFeedbackType.KeyboardTap), tapsWith(ChartHaptics.STRONG))
        assertEquals(listOf(HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentTick), tapsWith(ChartHaptics.CRISP))
    }

    @Test
    fun offIsSilent() {
        assertEquals(emptyList(), tapsWith(ChartHaptics.OFF))
    }
}
