package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class SignInWithBiometricsUseCase(private val connectionRepository: ConnectionRepository) {
    suspend operator fun invoke(): Result<SavedCredentials> = connectionRepository.signInWithBiometrics()
}
