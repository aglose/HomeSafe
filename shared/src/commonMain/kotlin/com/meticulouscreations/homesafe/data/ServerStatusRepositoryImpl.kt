package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.CameraZone
import com.meticulouscreations.homesafe.domain.model.DetectorInfo
import com.meticulouscreations.homesafe.domain.model.GpuLoad
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateServerConfig
import com.meticulouscreations.homesafe.network.FrigateServerStats
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Polls `/api/stats` while something is looking at the overview (the same pattern as
 * [MomentsRepositoryImpl]: the loop lives in the observed flow, not in `init`, so a background
 * tab never hits the server). `/api/config` is bigger and changes rarely, so it's read on the
 * first poll, after every toggle, and otherwise once a minute; the account's role once per server.
 */
@Inject
@SingleIn(AppScope::class)
class ServerStatusRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    appScope: CoroutineScope,
) : ServerStatusRepository {

    private val _overview = MutableStateFlow<ServerOverview?>(null)
    private val _error = MutableStateFlow<String?>(null)

    /** The last config read, kept so a stats-only poll can rebuild the overview without re-reading it. */
    private var lastConfig: FrigateServerConfig? = null
    private var lastIsAdmin: Boolean? = null
    private var lastUrl: String? = null

    /** Serializes fetches so a toggle's re-read and the poller can't interleave and publish out of order. */
    private val fetchMutex = Mutex()

    private val poller: Flow<Unit> = channelFlow {
        connectionRepository.currentServerUrl.collectLatest { url ->
            if (url == null) { _overview.value = null; return@collectLatest }
            var polls = 0
            while (true) {
                fetch(url, includeConfig = polls % CONFIG_EVERY_N_POLLS == 0)
                polls++
                send(Unit)
                delay(POLL_INTERVAL_MS)
            }
        }
    }.shareIn(appScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

    override fun observeOverview(): Flow<ServerOverview?> =
        combine(_overview, poller.onStart { emit(Unit) }) { overview, _ -> overview }

    override fun observeError(): Flow<String?> = _error.asStateFlow()

    override suspend fun refresh() {
        connectionRepository.currentServerUrl.value?.let { fetch(it, includeConfig = true) }
    }

    override suspend fun setCameraDetection(cameraName: String, enabled: Boolean): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(IllegalStateException("Not connected"))
        val motionEnabled = _overview.value?.cameras?.firstOrNull { it.name == cameraName }?.motionEnabled ?: true
        return apiClient.setCameraDetection(url, cameraName, enabled, motionEnabled)
            .onSuccess { fetch(url, includeConfig = true) }
    }

    override suspend fun setCameraMotion(cameraName: String, enabled: Boolean): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(IllegalStateException("Not connected"))
        return apiClient.setCameraMotion(url, cameraName, enabled)
            .onSuccess { fetch(url, includeConfig = true) }
    }

    private suspend fun fetch(url: String, includeConfig: Boolean) = fetchMutex.withLock {
        if (url != lastUrl) { lastConfig = null; lastIsAdmin = null; lastUrl = url }
        val stats = apiClient.getStats(url).getOrElse { failure ->
            _error.value = failure.message ?: "Couldn't reach the server"
            return@withLock
        }
        val config = if (includeConfig || lastConfig == null) {
            apiClient.getServerConfig(url).getOrElse { failure ->
                _error.value = failure.message ?: "Couldn't load the server's config"
                return@withLock
            }.also { lastConfig = it }
        } else {
            checkNotNull(lastConfig)
        }
        // A viewer account can still read everything; only the switches depend on this, so a
        // failed profile read degrades to "can't edit" rather than failing the whole overview.
        val isAdmin = lastIsAdmin ?: apiClient.isAdmin(url).getOrDefault(false).also { lastIsAdmin = it }
        _overview.value = buildOverview(stats, config, isAdmin)
        _error.value = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val CONFIG_EVERY_N_POLLS = 6
    }
}

internal fun buildOverview(stats: FrigateServerStats, config: FrigateServerConfig, isAdmin: Boolean): ServerOverview {
    val recordings = stats.storage.entries.firstOrNull { it.key.endsWith("/recordings") } ?: stats.storage.entries.firstOrNull()
    val detectorEntry = config.detectors.entries.firstOrNull()
    return ServerOverview(
        version = stats.version,
        latestVersion = stats.latestVersion,
        uptimeSeconds = stats.uptimeSeconds,
        cpuPercent = stats.cpuPercent,
        memoryPercent = stats.memoryPercent,
        recordingsStorage = recordings?.let { (path, mount) -> StorageUsage(path, mount.usedMb, mount.totalMb) },
        detector = detectorEntry?.let { (name, type) ->
            DetectorInfo(
                name = name,
                type = type,
                modelType = config.model?.modelType,
                modelFileName = config.model?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                inputWidth = config.model?.width,
                inputHeight = config.model?.height,
                inferenceMs = stats.detectors.firstOrNull { it.name == name }?.inferenceMs?.takeIf { it > 0 },
            )
        },
        gpus = stats.gpus.map { GpuLoad(it.name, it.gpuPercent, it.memoryPercent, it.decoderPercent) },
        retention = RetentionPolicy(
            continuousDays = config.retention.continuousDays,
            motionDays = config.retention.motionDays,
            alertDays = config.retention.alertDays,
            detectionDays = config.retention.detectionDays,
        ),
        faceRecognitionEnabled = config.faceRecognitionEnabled,
        licensePlateRecognitionEnabled = config.licensePlateRecognitionEnabled,
        semanticSearchEnabled = config.semanticSearchEnabled,
        cameras = config.cameras.map { camera ->
            val live = stats.cameras[camera.name]
            CameraPipeline(
                name = camera.name,
                enabled = camera.enabled,
                // The effective config, not stats' `detection_enabled` — see FrigateCameraPipeline.
                detectionEnabled = camera.detectEnabled,
                motionEnabled = camera.motionEnabled,
                cameraFps = live?.cameraFps,
                detectionFps = live?.detectionFps,
                skippedFps = live?.skippedFps,
                zones = camera.zones.map { CameraZone(it.name, it.friendlyName) },
            )
        },
        canEditConfig = isAdmin,
    )
}
