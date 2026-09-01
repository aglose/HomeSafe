package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveMostRecentConnectionUseCase(private val connectionRepository: ConnectionRepository) {
    operator fun invoke(): Flow<ConnectionRecord?> = connectionRepository.mostRecentConnection
}
