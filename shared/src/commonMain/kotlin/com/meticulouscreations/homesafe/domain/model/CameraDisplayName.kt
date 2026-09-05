package com.meticulouscreations.homesafe.domain.model

/**
 * How a Frigate camera id reads in the UI. Frigate's camera "names" are config keys
 * ("amcrest_1", "hikvision_2") — fine for URLs and stream lookups, meaningless on a card. The
 * cameras on the home server get the place they watch; anything else gets its id humanized
 * ("side_gate_cam" -> "Side Gate Cam") so a newly added camera still reads sensibly.
 */
fun cameraDisplayName(cameraName: String): String =
    KNOWN_CAMERA_DISPLAY_NAMES[cameraName.lowercase()] ?: humanizeCameraName(cameraName)

private val KNOWN_CAMERA_DISPLAY_NAMES = mapOf(
    "amcrest_1" to "Front Door",
    "hikvision_1" to "Front Yard",
    "hikvision_2" to "Backyard",
)

private fun humanizeCameraName(cameraName: String): String =
    cameraName
        .split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { cameraName }
