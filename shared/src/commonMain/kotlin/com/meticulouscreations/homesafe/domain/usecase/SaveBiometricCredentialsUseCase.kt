package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class SaveBiometricCredentialsUseCase(private val connectionRepository: ConnectionRepository) {
    suspend operator fun invoke(credentials: SavedCredentials): Result<Unit> =
        connectionRepository.saveBiometricCredentials(credentials)
}
