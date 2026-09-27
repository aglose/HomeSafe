package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.clipFileName
import com.meticulouscreations.homesafe.domain.platform.ClipDownloadProgress
import com.meticulouscreations.homesafe.domain.platform.ClipDownloader
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a clip save started by [SaveRecordingClipUseCase] has got to. */
sealed interface RecordingClipSave {
    data class Running(val progress: ClipDownloadProgress) : RecordingClipSave
    data object Saved : RecordingClipSave
    data class Failed(val message: String) : RecordingClipSave
}

/**
 * Saves [ClipRange]s of a camera's continuous recording to the device, through the same
 * [ClipDownloader] a detection's clip goes through (Downloads on Android, the share sheet on iOS).
 *
 * A long clip takes a while — Frigate cuts it before sending a byte, then it has to come down —
 * so the save runs in [appScope], not the caller's: the viewer can close the editor and the clip
 * still lands (Android's download notification, or iOS's share sheet over whatever is on screen).
 */
@Inject
class SaveRecordingClipUseCase(
    private val recordingsRepository: RecordingsRepository,
    private val clipDownloader: ClipDownloader,
    private val appScope: CoroutineScope,
) {
    /** Starts saving [range] of [cameraName] and returns immediately; the flow follows it until it's [RecordingClipSave.Saved] or [RecordingClipSave.Failed]. */
    operator fun invoke(serverUrl: String, cameraName: String, range: ClipRange): StateFlow<RecordingClipSave> {
        val state = MutableStateFlow<RecordingClipSave>(RecordingClipSave.Running(ClipDownloadProgress.Preparing))
        appScope.launch {
            val result = try {
                val download = recordingsRepository.getRecordingClipDownload(serverUrl, cameraName, range.startEpochSeconds, range.endEpochSeconds)
                clipDownloader.download(download.url, download.headers, clipFileName(cameraName, range)) { progress ->
                    state.value = RecordingClipSave.Running(progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            state.value = result.fold(
                onSuccess = { RecordingClipSave.Saved },
                onFailure = { error -> RecordingClipSave.Failed(error.message ?: "Couldn't save the clip") },
            )
        }
        return state.asStateFlow()
    }
}
