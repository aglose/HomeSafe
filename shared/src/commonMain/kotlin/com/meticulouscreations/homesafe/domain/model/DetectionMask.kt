package com.meticulouscreations.homesafe.domain.model

/**
 * A point in a camera's detect frame, as fractions of its width and height (0..1). Frigate
 * stores every mask and zone this way, so a polygon drawn on a phone-sized preview means the
 * same thing at the camera's real detect resolution.
 */
data class MaskPoint(val x: Double, val y: Double) {
    fun clamped(): MaskPoint = MaskPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
}

/** A closed polygon of [MaskPoint]s. Frigate needs at least three corners to make a mask of it. */
data class MaskPolygon(val points: List<MaskPoint>) {
    val isValid: Boolean get() = points.size >= MIN_POINTS

    /** The average of the corners — where a zone's name label goes. */
    val centroid: MaskPoint
        get() = if (points.isEmpty()) MaskPoint(0.5, 0.5) else MaskPoint(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)

    /** Ray-casting point-in-polygon, for hit-testing taps in the editor. */
    fun contains(point: MaskPoint): Boolean {
        if (points.size < MIN_POINTS) return false
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            val crosses = (a.y > point.y) != (b.y > point.y)
            if (crosses) {
                val xAtY = (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                if (point.x < xAtY) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        const val MIN_POINTS = 3
    }
}

/**
 * The three kinds of polygon Frigate supports on a camera. Two exclude, one labels — and the
 * object mask is the only one that actually stops detections. See each entry's [description].
 */
enum class MaskLayer(val label: String, val description: String) {
    /**
     * Applied *after* the detector runs: any detection whose bottom-centre point lands inside
     * the polygon is discarded. This is the "never report objects here" tool.
     */
    OBJECT_MASK(
        label = "Objects",
        description = "Detections whose feet land in a masked area are ignored. Use this to hide a road, a neighbour's yard, or a TV.",
    ),

    /**
     * Applied *before* the detector runs: pixels inside the polygon never count as motion, so
     * motion there never triggers detection. Doesn't stop an object being reported if motion
     * elsewhere triggers a region covering the area — it's a false-positive and CPU saver.
     */
    MOTION_MASK(
        label = "Motion",
        description = "Motion in a masked area is ignored, so trees, flags and timestamps don't wake the detector. Doesn't hide objects on its own.",
    ),

    /**
     * Named areas. Frigate tags every tracked object with the zones it's in, which is what lets
     * an alert or a description say "in the driveway". Zones hide nothing.
     */
    ZONES(
        label = "Zones",
        description = "Name an area — driveway, lawn, sidewalk. Every detection is tagged with the zones it's in, so alerts can say where things happened. Zones hide nothing.",
    ),
}

/**
 * One Frigate zone. [name] is the config key (letters, digits, `_`, `-`) and is what events
 * carry; [friendlyName] is what people see. [objects] limits which labels count in the zone —
 * empty means every tracked object.
 */
data class DetectionZone(
    val name: String,
    val friendlyName: String?,
    val polygon: MaskPolygon,
    val objects: List<String> = emptyList(),
) {
    val displayName: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: name

    companion object {
        private val VALID_NAME = Regex("^[a-zA-Z0-9_-]+$")

        fun isValidName(name: String): Boolean = VALID_NAME.matches(name)

        /** "Front lawn!" -> "front_lawn": what Frigate accepts as a zone key, derived from what the user typed. */
        fun slug(friendlyName: String): String =
            friendlyName.trim().lowercase()
                .replace("'", "")
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .trim('_')
                .ifEmpty { "zone" }
    }
}

/** What Frigate currently has configured for one camera's masks and zones. */
data class CameraDetectionConfig(
    val cameraName: String,
    /** The camera's detect-stream resolution; the editor's canvas keeps this aspect ratio. */
    val detectWidth: Int,
    val detectHeight: Int,
    val objectMasks: List<MaskPolygon>,
    val motionMasks: List<MaskPolygon>,
    val zones: List<DetectionZone> = emptyList(),
    /** Labels this camera tracks (`objects.track`), offered as zone object filters. */
    val trackedObjects: List<String> = emptyList(),
)
