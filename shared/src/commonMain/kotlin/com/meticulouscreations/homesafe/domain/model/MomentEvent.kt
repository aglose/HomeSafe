package com.meticulouscreations.homesafe.domain.model

enum class MomentCategory {
    ALL,
    PEOPLE,
    VEHICLES,
    ANIMALS,
}

/** A detected event (person, vehicle, animal, etc.) surfaced in the Moments feed. */
data class MomentEvent(
    val id: String,
    val title: String,
    val cameraName: String,
    val timestamp: String,
    val durationLabel: String?,
    val dateGroup: String,
    val dateSubLabel: String,
    val category: MomentCategory,
    val badgeLabel: String,
)
