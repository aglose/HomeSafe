package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.ClipLimits
import kotlin.test.Test
import kotlin.test.assertEquals

/** The clip editor's length chips read the way a camera app's duration picker would. */
class ClipEditorLabelsTest {

    @Test
    fun secondsUnderAMinuteAndWholeMinutesAbove() {
        assertEquals("10s", lengthLabel(10.0))
        assertEquals("30s", lengthLabel(30.0))
        assertEquals("1m", lengthLabel(60.0))
        assertEquals("2m", lengthLabel(120.0))
        assertEquals("90s", lengthLabel(90.0))
    }

    @Test
    fun everyPresetHasItsOwnChip() {
        val labels = ClipLimits.PRESET_SECONDS.map(::lengthLabel)
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun everyPresetIsAClipThatCanBeSaved() {
        ClipLimits.PRESET_SECONDS.forEach { assertEquals(it, it.coerceIn(ClipLimits.MIN_SECONDS, ClipLimits.MAX_SECONDS)) }
    }
}
