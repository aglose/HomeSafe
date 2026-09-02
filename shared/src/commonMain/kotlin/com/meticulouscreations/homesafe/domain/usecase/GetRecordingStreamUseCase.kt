package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import dev.zacsweers.metro.Inject

@Inject
class GetRecordingStreamUseCase(private val recordingsRepository: RecordingsRepository) {
    suspend operator fun invoke(serverUrl: String, cameraName: String, playlist: RecordingPlaylist): RecordingStream =
        recordingsRepository.getRecordingStream(serverUrl, cameraName, playlist)
}
