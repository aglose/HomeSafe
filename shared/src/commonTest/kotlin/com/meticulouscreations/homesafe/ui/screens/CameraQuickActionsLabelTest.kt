package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The captions under the camera screen's quick actions — each one names the state the button is in. */
class CameraQuickActionsLabelTest {

    @Test
    fun qualityUsesTheShortNamesAVideoMenuWould() {
        assertEquals("Auto", qualityLabel(StreamQuality.AUTO))
        assertEquals("HD", qualityLabel(StreamQuality.HIGH))
        assertEquals("SD", qualityLabel(StreamQuality.LOW))
    }

    @Test
    fun everyQualityChoiceHasItsOwnExplanation() {
        val descriptions = StreamQuality.entries.map(::qualityDescription)
        assertEquals(descriptions.size, descriptions.toSet().size)
        assertTrue(descriptions.none(String::isBlank))
    }

    @Test
    fun theLightStreamWarnsThatItIsSilent() {
        // Only the full stream carries audio, so picking SD is also picking no sound.
        assertTrue("no sound" in qualityDescription(StreamQuality.LOW))
    }

    @Test
    fun soundSaysWhetherItIsMuted() {
        assertEquals("Muted", soundLabel(isMuted = true))
        assertEquals("Sound on", soundLabel(isMuted = false))
    }

    @Test
    fun theScissorsSayWhatTheyDo() {
        assertEquals("Clip", CLIP_LABEL)
    }
}
