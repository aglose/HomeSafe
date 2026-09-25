package com.meticulouscreations.homesafe.domain.model

/**
 * Decides whether a detection belongs in the feed, and where it was, from the zones drawn on
 * its camera. The rule the user asked for (2026-09-06): the zones say what matters where —
 * people and dogs on the lawn, anything in the driveway — and everything else is noise, unless
 * Frigate put a name to a face, in which case it always shows.
 *
 * A car's name does not buy that exemption. Measured 2026-09-23 on the Front Yard, the car
 * classifier named 45-89% of passing street cars as one of the household's (mostly "Andrew's
 * Tesla", at 0.98): it judges a 55-124 px crop from the detect frame and has only "none" to stand
 * for every other car in the world. Those names folded strangers into the household's routine and
 * hid them under "Unfamiliar only". So a named vehicle only keeps the exemption while it is parked
 * ([isStill]) — Sarah leaves her Tesla at the curb, and that is still worth a line — and one that
 * drove down the street has to earn its place like an unnamed one, by entering a zone that wants
 * cars (the driveway). Passing traffic is what the classifier gets wrong, so that is what it loses.
 *
 * Frigate can't express this on its own. It tracks every label in `objects.track` across the
 * whole frame and creates an event for each; a zone's `objects` list only decides which labels
 * count as *inside* that zone. So a car driving down a birds-only street is still an event,
 * just one with no zones at all, indistinguishable from a car that never entered any zone.
 *
 * This redoes the zone test on the client with the object's recorded path, ignoring the object
 * filter, to learn where it actually went; then applies the filter. Frigate's own tags are kept
 * too, because the path is sampled sparsely and can miss a zone Frigate saw it in (the driveway
 * is a sliver a walker crosses between two samples).
 *
 * @return the event with [MomentEvent.zones] set to where it was, in the order it got there —
 *   every zone for a recognized object (a vehicle only while parked), only the zones that wanted
 *   it otherwise; or null when nothing wanted it. A camera with no zones drawn has no opinion: its events pass unchanged.
 */
fun MomentEvent.inZones(cameraZones: List<DetectionZone>): MomentEvent? {
    if (cameraZones.isEmpty()) return this
    val byName = cameraZones.associateBy { it.name }
    val visited = LinkedHashSet<String>(zones.filter { it.isNotBlank() })
    for (point in pathPoints) {
        for (zone in cameraZones) {
            if (zone.name !in visited && zone.polygon.contains(point)) visited += zone.name
        }
    }
    // A zone Frigate tagged that we can't see is trusted: Frigate only tags a zone that wanted the object.
    val wanted = visited.filter { name ->
        val zone = byName[name] ?: return@filter true
        zone.objects.isEmpty() || zone.objects.any { it.equals(label, ignoreCase = true) }
    }
    val placed = when {
        isRecognized && (category != MomentCategory.VEHICLES || isStill()) -> visited.toList()
        wanted.isEmpty() -> return null
        else -> wanted
    }
    return if (placed == zones) this else copy(zones = placed)
}

/** [inZones] over a feed: every camera's zones keyed by camera name; cameras absent from the map keep their events as they are. */
fun List<MomentEvent>.inZones(zonesByCamera: Map<String, List<DetectionZone>>): List<MomentEvent> =
    mapNotNull { event -> event.inZones(zonesByCamera[event.cameraName].orEmpty()) }
