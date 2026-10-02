package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_dot_separator
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.moments_clip_count
import homesafe.shared.generated.resources.moments_label_car
import homesafe.shared.generated.resources.moments_label_person
import homesafe.shared.generated.resources.moments_sighting_count
import homesafe.shared.generated.resources.moments_title_came_and_went
import homesafe.shared.generated.resources.moments_title_detected
import homesafe.shared.generated.resources.moments_title_in_zone
import homesafe.shared.generated.resources.moments_title_on_zone

/**
 * The feed's words as the presenters build them, spelled out once for the tests that check a
 * card reads right: they compare [UiText]s, not English, so each expectation names its resource
 * and arguments the long way here rather than in every assertion.
 */
internal object MomentTexts {
    val person: UiText = UiText.of(Res.string.moments_label_person)
    val car: UiText = UiText.of(Res.string.moments_label_car)

    /** "Andrew's Tesla": a name, which is data. */
    fun named(name: String): UiText = name.asUiText()

    /** "Person detected" */
    fun detected(subject: UiText): UiText = UiText.of(Res.string.moments_title_detected, subject)

    /** "Car in the driveway"; [zone] as the title has it, lower case. */
    fun inThe(subject: UiText, zone: String): UiText = UiText.of(Res.string.moments_title_in_zone, subject, zone)

    /** "Person on the lawn" */
    fun onThe(subject: UiText, zone: String): UiText = UiText.of(Res.string.moments_title_on_zone, subject, zone)

    /** "Andrew's Tesla came and went 8×" */
    fun cameAndWent(name: String, times: Int): UiText = UiText.plural(Res.plurals.moments_title_came_and_went, times, name.asUiText(), times)

    /** "Front Yard · Lawn, Driveway", or just "Front Yard" with no [places]. */
    fun location(camera: String, vararg places: String): UiText {
        if (places.isEmpty()) return camera.asUiText()
        val zones = UiText.Joined(places.map { it.asUiText() }, UiText.of(Res.string.common_list_separator))
        return UiText.Joined(listOf(camera.asUiText(), zones), UiText.of(Res.string.common_dot_separator))
    }

    /** "Front Yard, Backyard" */
    fun cameras(vararg cameras: String): UiText = UiText.Joined(cameras.map { it.asUiText() }, UiText.of(Res.string.common_list_separator))

    /** "5 clips" */
    fun clips(count: Int): UiText = UiText.plural(Res.plurals.moments_clip_count, count)

    /** "8 sightings" */
    fun sightings(count: Int): UiText = UiText.plural(Res.plurals.moments_sighting_count, count)
}
