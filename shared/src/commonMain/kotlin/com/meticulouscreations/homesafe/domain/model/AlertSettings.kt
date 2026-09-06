package com.meticulouscreations.homesafe.domain.model

/**
 * Somewhere a detection can happen: a named zone on a camera, or — [zone] null — anywhere on
 * that camera outside its zones (or all of it, for a camera with none drawn).
 */
data class AlertZone(val camera: String, val zone: String?)

/** The user's alert preferences on this device, persisted across sessions. */
data class AlertSettings(
    /** Master switch: post a system notification when Frigate reports a new detection. */
    val pushNotificationsEnabled: Boolean,
    /** Which categories notify in each place. Places with no entry use [DEFAULT_CATEGORIES]. */
    val zoneRules: Map<AlertZone, Set<MomentCategory>> = emptyMap(),
    /**
     * Familiar vs. stranger: when on, a person Frigate put a name to (a recognised face arrives
     * as the event's sub-label) never notifies, so only people it couldn't place do. Off, every
     * person the zone rules want notifies, named or not.
     */
    val quietFamiliarPeople: Boolean = false,
) {
    fun categoriesFor(place: AlertZone): Set<MomentCategory> = zoneRules[place] ?: DEFAULT_CATEGORIES

    /**
     * Whether a [category] detection on [camera] that passed through [zones] should notify: yes
     * if any of those places wants the category, and a detection that touched no zone counts as
     * the camera's "anywhere else". Uncategorised labels never notify, and neither does a
     * [recognized] person while [quietFamiliarPeople] is on.
     */
    fun notifies(camera: String, zones: List<String>, category: MomentCategory, recognized: Boolean = false): Boolean {
        if (category == MomentCategory.ALL) return false
        if (quietFamiliarPeople && category == MomentCategory.PEOPLE && recognized) return false
        val places = zones.filter { it.isNotBlank() }.map { AlertZone(camera, it) }.ifEmpty { listOf(AlertZone(camera, null)) }
        return places.any { category in categoriesFor(it) }
    }

    fun withCategory(place: AlertZone, category: MomentCategory, enabled: Boolean): AlertSettings {
        val categories = categoriesFor(place)
        return copy(zoneRules = zoneRules + (place to if (enabled) categories + category else categories - category))
    }

    /**
     * Whether anything at all still notifies on [camera]: some place — one of its [zones], or
     * "anywhere else" — wants at least one category. The state a camera-level alerts switch shows.
     */
    fun alertsEnabledOn(camera: String, zones: List<String>): Boolean =
        placesOn(camera, zones).any { categoriesFor(it).isNotEmpty() }

    /**
     * The camera-level switch. Off silences every place on [camera]. On sets the silent places
     * back to [DEFAULT_CATEGORIES] and leaves any place that already has a choice of its own
     * alone — so per-place tweaks made on the Settings tab while the camera was off are kept,
     * though ones made before it was switched off are not: off is a plain overwrite, not a pause.
     * The defaults are written as an explicit rule rather than by dropping the entry: the store
     * upserts rules and never deletes them, so a dropped entry would silently stay silent.
     */
    fun withAlertsOn(camera: String, zones: List<String>, enabled: Boolean): AlertSettings {
        val places = placesOn(camera, zones)
        val rules = if (enabled) {
            places.filter { categoriesFor(it).isEmpty() }.associateWith { DEFAULT_CATEGORIES }
        } else {
            places.associateWith { emptySet() }
        }
        return copy(zoneRules = zoneRules + rules)
    }

    private fun placesOn(camera: String, zones: List<String>): List<AlertZone> =
        zones.filter { it.isNotBlank() }.map { AlertZone(camera, it) } + AlertZone(camera, null)

    companion object {
        val DEFAULT_CATEGORIES: Set<MomentCategory> = setOf(MomentCategory.PEOPLE, MomentCategory.VEHICLES)
        val DEFAULT = AlertSettings(pushNotificationsEnabled = false)
    }
}
