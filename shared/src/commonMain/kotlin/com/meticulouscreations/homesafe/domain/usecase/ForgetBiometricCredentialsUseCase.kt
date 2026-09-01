package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

@Inject
class ForgetBiometricCredentialsUseCase(private val connectionRepository: ConnectionRepository) {
    operator fun invoke() = connectionRepository.forgetBiometricCredentials()
}
