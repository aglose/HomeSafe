package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class SignInWithBiometricsUseCase(private val connectionRepository: ConnectionRepository) {
    /** [onCredentialsUnlocked] fires once the biometric prompt has been passed, before the server is contacted. */
    suspend operator fun invoke(onCredentialsUnlocked: () -> Unit = {}): Result<SavedCredentials> =
        connectionRepository.signInWithBiometrics(onCredentialsUnlocked)
}
