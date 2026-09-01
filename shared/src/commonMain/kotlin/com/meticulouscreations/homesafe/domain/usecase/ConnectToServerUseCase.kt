package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class ConnectToServerUseCase(private val connectionRepository: ConnectionRepository) {
    suspend operator fun invoke(serverUrl: String, username: String, password: String): Result<SavedCredentials> =
        connectionRepository.connect(serverUrl, username, password)
}
