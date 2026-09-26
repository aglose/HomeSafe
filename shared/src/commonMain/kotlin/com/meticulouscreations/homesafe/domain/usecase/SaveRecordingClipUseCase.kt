package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.clipFileName
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import dev.zacsweers.metro.Inject

/**
 * Saves [range] of [cameraName]'s continuous recording to the device, through the same
 * [ClipDownloader] a detection's clip goes through (Downloads on Android, the share sheet on iOS).
 */
@Inject
class SaveRecordingClipUseCase(
    private val recordingsRepository: RecordingsRepository,
    private val clipDownloader: ClipDownloader,
) {
    suspend operator fun invoke(serverUrl: String, cameraName: String, range: ClipRange): Result<Unit> {
        val download = runCatching {
            recordingsRepository.getRecordingClipDownload(serverUrl, cameraName, range.startEpochSeconds, range.endEpochSeconds)
        }.getOrElse { return Result.failure(it) }
        return clipDownloader.download(download.url, download.headers, clipFileName(cameraName, range))
    }
}
