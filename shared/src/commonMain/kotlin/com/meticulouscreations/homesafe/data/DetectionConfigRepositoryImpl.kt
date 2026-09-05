package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.DetectionConfigRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.parseFrigatePolygon
import com.meticulouscreations.homesafe.network.parseFrigatePolygons
import com.meticulouscreations.homesafe.network.toFrigateCoordinates
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class DetectionConfigRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
) : DetectionConfigRepository {

    override suspend fun getDetectionConfig(cameraName: String): Result<CameraDetectionConfig> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return apiClient.getDetectionConfig(serverUrl, cameraName).map { config ->
            CameraDetectionConfig(
                cameraName = config.cameraName,
                detectWidth = config.detectWidth,
                detectHeight = config.detectHeight,
                objectMasks = parseFrigatePolygons(config.objectMasks),
                motionMasks = parseFrigatePolygons(config.motionMasks),
                zones = config.zones.mapNotNull { zone ->
                    parseFrigatePolygon(zone.coordinates)?.let { DetectionZone(zone.name, zone.friendlyName, it, zone.objects) }
                },
                trackedObjects = config.trackedObjects,
            )
        }
    }

    override suspend fun saveMasks(cameraName: String, layer: MaskLayer, masks: List<MaskPolygon>): Result<Unit> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        val section = when (layer) {
            MaskLayer.OBJECT_MASK -> FrigateApiClient.CameraSection.OBJECTS
            MaskLayer.MOTION_MASK -> FrigateApiClient.CameraSection.MOTION
            MaskLayer.ZONES -> return Result.failure(IllegalArgumentException("Zones are saved with saveZones"))
        }
        return apiClient.setCameraMasks(
            serverUrl = serverUrl,
            cameraName = cameraName,
            section = section,
            polygons = masks.filter { it.isValid }.map { it.toFrigateCoordinates() },
        )
    }

    override suspend fun saveZones(cameraName: String, zones: List<DetectionZone>, previous: List<DetectionZone>): Result<Unit> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        val params = zoneConfigParams(cameraName, zones, previous)
        if (params.isEmpty()) return Result.success(Unit)
        return apiClient.setCameraConfig(serverUrl, cameraName, FrigateApiClient.CameraSection.ZONES, params)
    }

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(FrigateResponseException("Not connected to a server"))
}

/**
 * The `config/set` query parameters that turn [previous] into [zones] on the server, in the
 * shape Frigate's own zone editor sends: `zones.<name>.coordinates`, one `objects` parameter
 * per label (a bare one to clear the list), `friendly_name`, and a bare `zones.<name>` to
 * delete a zone. Unchanged zones are left out entirely.
 */
internal fun zoneConfigParams(cameraName: String, zones: List<DetectionZone>, previous: List<DetectionZone>): List<Pair<String, String>> {
    val prefix = "cameras.$cameraName.zones"
    val before = previous.associateBy { it.name }
    val after = zones.filter { it.polygon.isValid && DetectionZone.isValidName(it.name) }.associateBy { it.name }
    val params = mutableListOf<Pair<String, String>>()

    before.keys.filterNot { it in after }.forEach { params += "$prefix.$it" to "" }

    after.values.forEach { zone ->
        val old = before[zone.name]
        val coordinates = zone.polygon.toFrigateCoordinates()
        if (old == null || old.polygon.toFrigateCoordinates() != coordinates) params += "$prefix.${zone.name}.coordinates" to coordinates
        if (old == null || old.objects != zone.objects) {
            when {
                zone.objects.isNotEmpty() -> zone.objects.forEach { params += "$prefix.${zone.name}.objects" to it }
                old != null && old.objects.isNotEmpty() -> params += "$prefix.${zone.name}.objects" to ""
            }
        }
        val friendly = zone.friendlyName?.trim()?.takeIf { it.isNotEmpty() }
        val oldFriendly = old?.friendlyName?.trim()?.takeIf { it.isNotEmpty() }
        if ((old == null || oldFriendly != friendly)) {
            when {
                friendly != null -> params += "$prefix.${zone.name}.friendly_name" to friendly
                oldFriendly != null -> params += "$prefix.${zone.name}.friendly_name" to ""
            }
        }
    }
    return params
}
