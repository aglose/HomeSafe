package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext

/**
 * Browsers (JS/Wasm) have no equivalent of Android's BiometricPrompt or iOS's
 * LocalAuthentication reachable from Kotlin/JS or Kotlin/Wasm here, so — matching the
 * precedent set by [createConnectionHistoryDao]'s in-memory web fallback and
 * [com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer]'s web placeholder —
 * biometric login is simply unavailable rather than faked with an insecure substitute.
 */
private class UnavailableBiometricCredentialStore : BiometricCredentialStore {
    override fun isAvailable(): Boolean = false

    override fun displayName(): String = "biometrics"

    override fun hasSavedCredentials(): Boolean = false

    override suspend fun save(credentials: SavedCredentials): Result<Unit> =
        Result.failure(UnsupportedOperationException("Biometric login isn't available on this platform"))

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
        Result.failure(UnsupportedOperationException("Biometric login isn't available on this platform"))

    override fun clear() = Unit
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    UnavailableBiometricCredentialStore()
