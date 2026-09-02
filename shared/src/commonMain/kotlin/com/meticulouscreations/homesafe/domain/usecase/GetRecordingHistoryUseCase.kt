package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import dev.zacsweers.metro.Inject

@Inject
class GetRecordingHistoryUseCase(private val recordingsRepository: RecordingsRepository) {
    suspend operator fun invoke(
        serverUrl: String,
        cameraName: String,
        afterEpochSeconds: Double,
        beforeEpochSeconds: Double,
    ): Result<RecordingHistory> =
        recordingsRepository.getRecordingHistory(serverUrl, cameraName, afterEpochSeconds, beforeEpochSeconds)
}
