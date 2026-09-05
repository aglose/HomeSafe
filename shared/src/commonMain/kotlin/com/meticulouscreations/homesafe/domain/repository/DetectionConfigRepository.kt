package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPolygon

/**
 * Reads and writes a camera's detection masks and zones on the connected Frigate server.
 * Frigate's config.yml is the single source of truth — nothing is cached locally, and a save is
 * followed by a fresh read so the editor shows what the server actually kept.
 */
interface DetectionConfigRepository {
    suspend fun getDetectionConfig(cameraName: String): Result<CameraDetectionConfig>

    /** Replaces every polygon on [layer] for [cameraName]; an empty list clears the layer. Applies live. Not for [MaskLayer.ZONES]. */
    suspend fun saveMasks(cameraName: String, layer: MaskLayer, masks: List<MaskPolygon>): Result<Unit>

    /**
     * Makes the server's zones for [cameraName] equal [zones]: zones missing from the list are
     * deleted, the rest are written. [previous] is what the server had (from the last read), so
     * the write can be expressed as a diff — Frigate errors on deleting a key that isn't there.
     */
    suspend fun saveZones(cameraName: String, zones: List<DetectionZone>, previous: List<DetectionZone>): Result<Unit>
}
