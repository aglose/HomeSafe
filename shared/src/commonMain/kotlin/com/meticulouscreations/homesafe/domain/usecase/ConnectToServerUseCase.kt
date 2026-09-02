package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class ConnectToServerUseCase(private val connectionRepository: ConnectionRepository) {
    /** See [ConnectionRepository.connect]; [localUrl] is the optional private LAN address. */
    suspend operator fun invoke(
        serverUrl: String,
        localUrl: String?,
        username: String,
        password: String,
    ): Result<SavedCredentials> = connectionRepository.connect(serverUrl, localUrl, username, password)
}
