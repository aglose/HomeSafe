package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.network.FrigateServerConfig
import com.meticulouscreations.homesafe.network.parseFrigatePolygon

/**
 * Every camera's zones as the domain sees them, keyed by camera name, for `MomentEvent.inZones`.
 * A zone whose polygon doesn't parse is skipped — it can't be tested against anyway.
 */
internal fun FrigateServerConfig.zonesByCamera(): Map<String, List<DetectionZone>> =
    cameras.associate { camera ->
        camera.name to camera.zones.mapNotNull { zone ->
            parseFrigatePolygon(zone.coordinates)?.let { DetectionZone(zone.name, zone.friendlyName, it, zone.objects) }
        }
    }
