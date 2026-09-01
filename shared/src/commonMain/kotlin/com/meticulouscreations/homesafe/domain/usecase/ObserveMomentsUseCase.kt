package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveMomentsUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(): Flow<List<MomentEvent>> = momentsRepository.observeMoments()
}
