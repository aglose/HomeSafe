package com.meticulouscreations.homesafe.domain.model

/**
 * Decides whether a detection belongs in the feed, and where it was, from the zones drawn on
 * its camera. The rule the user asked for (2026-09-06): the zones say what matters where —
 * people and dogs on the lawn, anything in the driveway — and everything else is noise, unless
 * Frigate put a name to it (a known car, a known face), in which case it always shows.
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
 *   every zone for a recognized object, only the zones that wanted it otherwise; or null when
 *   nothing wanted it. A camera with no zones drawn has no opinion: its events pass unchanged.
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
        isRecognized -> visited.toList()
        wanted.isEmpty() -> return null
        else -> wanted
    }
    return if (placed == zones) this else copy(zones = placed)
}

/** [inZones] over a feed: every camera's zones keyed by camera name; cameras absent from the map keep their events as they are. */
fun List<MomentEvent>.inZones(zonesByCamera: Map<String, List<DetectionZone>>): List<MomentEvent> =
    mapNotNull { event -> event.inZones(zonesByCamera[event.cameraName].orEmpty()) }
