package com.meticulouscreations.homesafe.domain.model

/**
 * One-tap starting points for the per-place alert rules, so nobody has to work through a grid of
 * every zone on every camera to get something sensible. A preset is just a way of filling that
 * grid: applying one writes a rule for every place it's given, and the grid stays there for
 * fine-tuning afterwards. Zones differ per install, so the only thing a preset reads from a zone
 * is its name, and only [PEOPLE_AND_DRIVEWAY_CARS] reads even that.
 */
enum class AlertPreset {
    /** People anywhere; no vehicles or animals. The quiet choice for a house on a busy street. */
    PEOPLE_ONLY,

    /**
     * People anywhere, and vehicles only where a car means someone arriving — zones named like a
     * driveway, garage, carport, parking spot or gate (see [isArrivalZone]). Passing traffic on a
     * street zone, or a car "anywhere else" in frame, stays quiet.
     */
    PEOPLE_AND_DRIVEWAY_CARS,

    /** People and vehicles everywhere: the app's out-of-the-box rules ([AlertSettings.DEFAULT_CATEGORIES]). */
    PEOPLE_AND_VEHICLES,

    /** Every category everywhere, animals included. */
    EVERYTHING,
    ;

    /** What this preset has [place] notify for. */
    fun categoriesFor(place: AlertZone): Set<MomentCategory> = when (this) {
        PEOPLE_ONLY -> setOf(MomentCategory.PEOPLE)
        PEOPLE_AND_DRIVEWAY_CARS -> if (place.zone?.let(::isArrivalZone) == true) AlertSettings.DEFAULT_CATEGORIES else setOf(MomentCategory.PEOPLE)
        PEOPLE_AND_VEHICLES -> AlertSettings.DEFAULT_CATEGORIES
        EVERYTHING -> setOf(MomentCategory.PEOPLE, MomentCategory.VEHICLES, MomentCategory.ANIMALS)
    }

    /**
     * Whether this preset means anything among [places]. The driveway one needs a driveway to
     * point at; without one it's the same as [PEOPLE_ONLY], so it isn't offered.
     */
    fun appliesTo(places: List<AlertZone>): Boolean =
        this != PEOPLE_AND_DRIVEWAY_CARS || places.any { place -> place.zone?.let(::isArrivalZone) == true }

    companion object {
        /** The presets worth offering for [places], in the order they're shown. */
        fun offeredFor(places: List<AlertZone>): List<AlertPreset> = entries.filter { it.appliesTo(places) }
    }
}

/** Every place in [places] set to what [preset] wants there. Places not listed keep their rules. */
fun AlertSettings.withPreset(preset: AlertPreset, places: List<AlertZone>): AlertSettings =
    copy(zoneRules = zoneRules + places.associateWith { preset.categoriesFor(it) })

/**
 * The preset these rules amount to across [places], or null when they've been tuned into
 * something no preset describes (the screen's "Custom"). Only offered presets are considered,
 * first match wins, and no places at all match nothing.
 */
fun AlertSettings.matchingPreset(places: List<AlertZone>): AlertPreset? {
    if (places.isEmpty()) return null
    return AlertPreset.offeredFor(places).firstOrNull { preset -> places.all { categoriesFor(it) == preset.categoriesFor(it) } }
}

/**
 * Whether a zone's name says a vehicle there is someone arriving rather than someone passing:
 * "driveway", "Garage", "front_drive", "parking_pad". Frigate zone names are free-form keys, so
 * this is a word match on the name, not anything the server knows.
 */
fun isArrivalZone(zone: String): Boolean {
    val name = zone.lowercase()
    return ARRIVAL_ZONE_WORDS.any { it in name }
}

private val ARRIVAL_ZONE_WORDS = listOf("drive", "garage", "carport", "parking", "gate")
