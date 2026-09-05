package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import kotlin.math.roundToInt

/**
 * Frigate's on-the-wire polygon format: one string of comma-separated relative coordinates,
 * `x1,y1,x2,y2,...`, each in 0..1 with at most three decimals (what Frigate's own editor
 * emits). Frigate rejects any value above 1 with "add mask expects relative coordinates only"
 * — and does so by *string* comparison against "1.0", so a full-scale coordinate must be
 * written exactly as `1.0`, never `1.000`.
 */
fun MaskPolygon.toFrigateCoordinates(): String =
    points.joinToString(",") { "${formatRelativeCoordinate(it.x)},${formatRelativeCoordinate(it.y)}" }

/**
 * Parses one Frigate polygon string. Returns null for anything that isn't a usable mask: fewer
 * than three points, an odd number of values, non-numeric text, or the pre-0.13 absolute-pixel
 * format (values above 1) which Frigate itself migrates on startup.
 */
fun parseFrigatePolygon(coordinates: String): MaskPolygon? {
    val values = coordinates.split(",").map { it.trim().toDoubleOrNull() ?: return null }
    if (values.isEmpty() || values.size % 2 != 0) return null
    if (values.any { it < 0.0 || it > 1.0 }) return null
    val points = values.chunked(2) { (x, y) -> MaskPoint(x, y) }
    return MaskPolygon(points).takeIf { it.isValid }
}

/** Every parseable polygon in a `mask` value, which Frigate serves as either one string or a list. */
fun parseFrigatePolygons(coordinates: List<String>): List<MaskPolygon> =
    coordinates.mapNotNull { parseFrigatePolygon(it) }

internal fun formatRelativeCoordinate(value: Double): String {
    val thousandths = (value.coerceIn(0.0, 1.0) * 1000).roundToInt()
    return when {
        thousandths >= 1000 -> "1.0"
        thousandths <= 0 -> "0.0"
        else -> "0." + thousandths.toString().padStart(3, '0').trimEnd('0')
    }
}
