package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.LOCAL_SERVER_URL
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class ConnectToServerUseCase(private val connectionRepository: ConnectionRepository) {
    /** See [ConnectionRepository.connect]. The LAN address is [LOCAL_SERVER_URL], not user input. */
    suspend operator fun invoke(
        serverUrl: String,
        username: String,
        password: String,
    ): Result<SavedCredentials> = connectionRepository.connect(serverUrl, LOCAL_SERVER_URL, username, password)
}
