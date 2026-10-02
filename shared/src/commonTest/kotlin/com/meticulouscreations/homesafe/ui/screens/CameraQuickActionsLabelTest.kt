package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.domain.model.StreamQuality
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_action_clip
import homesafe.shared.generated.resources.camera_quality_auto
import homesafe.shared.generated.resources.camera_quality_high
import homesafe.shared.generated.resources.camera_quality_low
import homesafe.shared.generated.resources.camera_quality_low_description
import homesafe.shared.generated.resources.camera_sound_muted
import homesafe.shared.generated.resources.camera_sound_on
import kotlin.test.Test
import kotlin.test.assertEquals

/** The captions under the camera screen's quick actions — each one names the state the button is in. */
class CameraQuickActionsLabelTest {

    @Test
    fun qualityUsesTheShortNamesAVideoMenuWould() {
        assertEquals(Res.string.camera_quality_auto, qualityLabel(StreamQuality.AUTO))
        assertEquals(Res.string.camera_quality_high, qualityLabel(StreamQuality.HIGH))
        assertEquals(Res.string.camera_quality_low, qualityLabel(StreamQuality.LOW))
    }

    @Test
    fun everyQualityChoiceHasItsOwnExplanation() {
        val descriptions = StreamQuality.entries.map(::qualityDescription)
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test
    fun theLightStreamWarnsThatItIsSilent() {
        // Only the full stream carries audio, so picking SD is also picking no sound ("Lighter on data, no sound").
        assertEquals(Res.string.camera_quality_low_description, qualityDescription(StreamQuality.LOW))
    }

    @Test
    fun soundSaysWhetherItIsMuted() {
        assertEquals(Res.string.camera_sound_muted, soundLabel(isMuted = true))
        assertEquals(Res.string.camera_sound_on, soundLabel(isMuted = false))
    }

    @Test
    fun theScissorsSayWhatTheyDo() {
        assertEquals(Res.string.camera_action_clip, CLIP_LABEL)
    }
}
