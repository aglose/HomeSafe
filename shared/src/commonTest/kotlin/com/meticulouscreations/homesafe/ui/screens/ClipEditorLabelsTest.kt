package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.ClipLimits
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.clip_length_minutes
import homesafe.shared.generated.resources.clip_length_seconds
import kotlin.test.Test
import kotlin.test.assertEquals

/** The clip editor's length chips read the way a camera app's duration picker would. */
class ClipEditorLabelsTest {

    @Test
    fun secondsUnderAMinuteAndWholeMinutesAbove() {
        assertEquals(UiText.of(Res.string.clip_length_seconds, 10L), lengthLabel(10.0))
        assertEquals(UiText.of(Res.string.clip_length_seconds, 30L), lengthLabel(30.0))
        assertEquals(UiText.of(Res.string.clip_length_minutes, 1L), lengthLabel(60.0))
        assertEquals(UiText.of(Res.string.clip_length_minutes, 2L), lengthLabel(120.0))
        assertEquals(UiText.of(Res.string.clip_length_seconds, 90L), lengthLabel(90.0))
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
