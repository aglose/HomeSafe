package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.meticulouscreations.homesafe.finance.domain.ChartHaptics

/**
 * The buzz for a finger reaching a new point on a chart, as strong as [feel] says. A [landmark]
 * (the range's high or low, or crossing the baseline) lands harder, except at [ChartHaptics.LIGHT],
 * which stays a faint tick throughout.
 */
internal fun HapticFeedback.chartTick(feel: ChartHaptics, landmark: Boolean = false) {
    val type = when (feel) {
        ChartHaptics.OFF -> return
        ChartHaptics.LIGHT -> HapticFeedbackType.SegmentFrequentTick
        ChartHaptics.CRISP -> if (landmark) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick
        ChartHaptics.STRONG -> if (landmark) HapticFeedbackType.LongPress else HapticFeedbackType.KeyboardTap
    }
    performHapticFeedback(type)
}
