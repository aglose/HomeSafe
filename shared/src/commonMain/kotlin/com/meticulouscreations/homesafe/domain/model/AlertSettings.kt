package com.meticulouscreations.homesafe.domain.model

/** The user's alert preferences on this device, persisted across sessions. */
data class AlertSettings(
    /** Master switch: post a system notification when Frigate reports a new detection. */
    val pushNotificationsEnabled: Boolean,
    val notifyPeople: Boolean,
    val notifyVehicles: Boolean,
    val notifyAnimals: Boolean,
) {
    /** Whether a detection of [category] should notify, per these preferences. Uncategorised labels never do. */
    fun notifies(category: MomentCategory): Boolean = when (category) {
        MomentCategory.PEOPLE -> notifyPeople
        MomentCategory.VEHICLES -> notifyVehicles
        MomentCategory.ANIMALS -> notifyAnimals
        MomentCategory.ALL -> false
    }

    companion object {
        val DEFAULT = AlertSettings(
            pushNotificationsEnabled = false,
            notifyPeople = true,
            notifyVehicles = true,
            notifyAnimals = false,
        )
    }
}
