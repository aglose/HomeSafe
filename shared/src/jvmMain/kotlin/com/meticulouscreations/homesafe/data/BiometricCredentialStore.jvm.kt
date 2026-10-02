package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.biometric_unavailable_desktop
import org.jetbrains.compose.resources.StringResource

/**
 * JVM desktop has no OS-level biometric API comparable to Android's BiometricPrompt or iOS's
 * LocalAuthentication, so — matching the precedent set by [createConnectionHistoryDao]'s web
 * fallback and [com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer]'s desktop
 * placeholder — biometric login is simply unavailable here rather than faked with an
 * insecure local substitute.
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

    private fun unavailable() = LocalizedException(UiText.of(Res.string.biometric_unavailable_desktop), technical = "Biometric login isn't available on desktop")
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    UnavailableBiometricCredentialStore()
