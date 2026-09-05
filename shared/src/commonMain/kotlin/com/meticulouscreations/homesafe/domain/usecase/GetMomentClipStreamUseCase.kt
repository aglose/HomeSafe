package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject

@Inject
class GetMomentClipStreamUseCase(private val momentsRepository: MomentsRepository) {
    suspend operator fun invoke(eventId: String): RecordingStream = momentsRepository.getClipStream(eventId)
}
