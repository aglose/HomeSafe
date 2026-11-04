package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.LocalServer
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class ConnectToServerUseCase(private val connectionRepository: ConnectionRepository) {
    /** See [ConnectionRepository.connect]. The LAN address is [LocalServer.url], not user input. */
    suspend operator fun invoke(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<SavedCredentials> = connectionRepository.connect(serverUrl, LocalServer.url, username, password)
}
