package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials

/**
 * JVM desktop has no OS-level biometric API comparable to Android's BiometricPrompt or iOS's
 * LocalAuthentication, so — matching the precedent set by [createConnectionHistoryDao]'s web
 * fallback and [com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer]'s desktop
 * placeholder — biometric login is simply unavailable here rather than faked with an
 * insecure local substitute.
 */
private class UnavailableBiometricCredentialStore : BiometricCredentialStore {
    override fun isAvailable(): Boolean = false

    override fun displayName(): String = "biometrics"

    override fun hasSavedCredentials(): Boolean = false

    override suspend fun save(credentials: SavedCredentials): Result<Unit> =
        Result.failure(UnsupportedOperationException("Biometric login isn't available on desktop"))

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
        Result.failure(UnsupportedOperationException("Biometric login isn't available on desktop"))

    override fun clear() = Unit
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    UnavailableBiometricCredentialStore()
