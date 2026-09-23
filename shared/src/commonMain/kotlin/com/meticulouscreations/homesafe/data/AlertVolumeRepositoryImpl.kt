package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.AlertVolume
import com.meticulouscreations.homesafe.domain.repository.AlertVolumeRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateResponseException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock

/**
 * Two requests: the last week's detections, capped at [SAMPLE_LIMIT] so a busy street can't turn
 * one look at the Settings tab into megabytes, and the config for the zones to place them in.
 * A zones read that fails leaves every detection where Frigate tagged it, which is close enough
 * for an estimate.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AlertVolumeRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    private val clock: Clock,
) : AlertVolumeRepository {

    override suspend fun estimate(): Result<AlertVolume> {
        val url = connectionRepository.currentServerUrl.value
            ?: return Result.failure(FrigateResponseException("Not connected to a server"))
        val now = clock.now().toEpochMilliseconds() / 1000.0
        val windowStart = now - WINDOW_SECONDS
        val events = apiClient.getEvents(url, limit = SAMPLE_LIMIT, afterEpochSeconds = windowStart).getOrElse { return Result.failure(it) }
        val zones = apiClient.getServerConfig(url).map { it.zonesByCamera() }.getOrDefault(emptyMap())
        return Result.success(AlertVolume.estimate(events.map { it.toDomain() }, zones, windowStart, now, SAMPLE_LIMIT))
    }

    private companion object {
        const val WINDOW_SECONDS = 7 * 86_400.0
        const val SAMPLE_LIMIT = 500
    }
}
