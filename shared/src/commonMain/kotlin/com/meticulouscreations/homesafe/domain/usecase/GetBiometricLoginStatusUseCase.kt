package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.BiometricLoginStatus
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

/** A point-in-time read; call again after saving or forgetting credentials. */
@Inject
class GetBiometricLoginStatusUseCase(private val connectionRepository: ConnectionRepository) {
    operator fun invoke(): BiometricLoginStatus = BiometricLoginStatus(
        isAvailable = connectionRepository.biometricLoginAvailable,
        displayName = connectionRepository.biometricDisplayName,
        hasSavedCredentials = connectionRepository.hasSavedBiometricCredentials(),
    )
}
