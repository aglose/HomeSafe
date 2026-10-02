package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.biometric_unavailable_platform
import org.jetbrains.compose.resources.StringResource

/**
 * Browsers (JS/Wasm) have no equivalent of Android's BiometricPrompt or iOS's
 * LocalAuthentication reachable from Kotlin/JS or Kotlin/Wasm here, so — matching the
 * precedent set by [createConnectionHistoryDao]'s in-memory web fallback and
 * [com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer]'s web placeholder —
 * biometric login is simply unavailable rather than faked with an insecure substitute.
 */
private class UnavailableBiometricCredentialStore : BiometricCredentialStore {
    override fun isAvailable(): Boolean = false

    override fun displayName(): StringResource = Res.string.biometric_name_generic

    override fun hasSavedCredentials(): Boolean = false

    override suspend fun save(credentials: SavedCredentials): Result<Unit> =
        Result.failure(unavailable())

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
        Result.failure(unavailable())

    override fun clear() = Unit

    private fun unavailable() = LocalizedException(UiText.of(Res.string.biometric_unavailable_platform), technical = "Biometric login isn't available on this platform")
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    UnavailableBiometricCredentialStore()
