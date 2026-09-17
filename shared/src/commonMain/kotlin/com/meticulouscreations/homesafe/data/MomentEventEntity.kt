package com.meticulouscreations.homesafe.data

import androidx.room3.Entity
import androidx.room3.Index
import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MomentEvent

/**
 * One detection, kept on the device so the Moments feed has something to show the instant it
 * opens — before the first poll answers, and at all if the server can't be reached.
 *
 * Rows are filed under the server's identity
 * ([com.meticulouscreations.homesafe.domain.model.ActiveConnection.serverUrl]), not the address
 * in use, so a LAN ↔ Tailscale route flip reads the same cache rather than an empty one.
 *
 * What's stored is a detection already *placed* — run through
 * [com.meticulouscreations.homesafe.domain.model.inZones], so it carries the zones it was
 * actually in and the ones the zones rejected were never written — but not yet folded: a parked
 * car's sightings are separate rows, and [MomentEvent.sightings] is always 1 on the way out.
 * Folding is what the feed makes of the rows it has, and it has to be redone as pages join.
 */
@Entity(
    primaryKeys = ["serverUrl", "id"],
    // The feed's only question is "this server's newest, before X", so that is the index.
    indices = [Index(value = ["serverUrl", "startEpochSeconds"])],
)
data class MomentEventEntity(
    val serverUrl: String,
    val id: String,
    val cameraName: String,
    val label: String,
    val subLabel: String?,
    val startEpochSeconds: Double,
    val endEpochSeconds: Double?,
    val topScore: Double?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
    /** [MomentEvent.zones], newline-separated — a Frigate zone name can't contain one. */
    val zones: String,
    /** [MomentEvent.pathPoints] as "x,y" pairs separated by ";". */
    val pathPoints: String,
    val boxX: Double?,
    val boxY: Double?,
    val boxW: Double?,
    val boxH: Double?,
    val subLabelScore: Double?,
)

internal fun MomentEvent.toEntity(serverUrl: String): MomentEventEntity = MomentEventEntity(
    serverUrl = serverUrl,
    id = id,
    cameraName = cameraName,
    label = label,
    subLabel = subLabel,
    startEpochSeconds = startEpochSeconds,
    endEpochSeconds = endEpochSeconds,
    topScore = topScore,
    hasClip = hasClip,
    hasSnapshot = hasSnapshot,
    zones = zones.joinToString(ZONE_SEPARATOR),
    pathPoints = pathPoints.joinToString(POINT_SEPARATOR) { "${it.x},${it.y}" },
    boxX = box?.x,
    boxY = box?.y,
    boxW = box?.w,
    boxH = box?.h,
    subLabelScore = subLabelScore,
)

internal fun MomentEventEntity.toDomain(): MomentEvent = MomentEvent(
    id = id,
    cameraName = cameraName,
    label = label,
    subLabel = subLabel,
    startEpochSeconds = startEpochSeconds,
    endEpochSeconds = endEpochSeconds,
    topScore = topScore,
    hasClip = hasClip,
    hasSnapshot = hasSnapshot,
    zones = zones.split(ZONE_SEPARATOR).filter { it.isNotEmpty() },
    pathPoints = pathPoints.split(POINT_SEPARATOR).mapNotNull { it.toMaskPointOrNull() },
    box = DetectionBox.fromFractions(listOfNotNull(boxX, boxY, boxW, boxH)),
    subLabelScore = subLabelScore,
)

private const val ZONE_SEPARATOR = "\n"
private const val POINT_SEPARATOR = ";"

/** A row written by an older build, or a half-written one, is skipped rather than read as (0, 0). */
private fun String.toMaskPointOrNull(): MaskPoint? {
    val x = substringBefore(',').toDoubleOrNull() ?: return null
    val y = substringAfter(',', missingDelimiterValue = "").toDoubleOrNull() ?: return null
    return MaskPoint(x, y)
}
