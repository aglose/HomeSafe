package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_confirm_save_reason
import homesafe.shared.generated.resources.biometric_name_generic
import homesafe.shared.generated.resources.biometric_sign_in_reason
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The Mac's [BiometricCredentialStore]: the login in the Mac's login Keychain, kept and read by
 * the app's Swift helper ([MacOwnerCheck]) behind Touch ID, or the Mac's password.
 *
 * Weaker than the phones', and said so here: the login Keychain is encrypted at rest and unlocked
 * with the Mac's own login, and its item is readable without a prompt only by the helper that
 * wrote it (the Keychain's own access list), but the Touch ID check is the helper's, made before
 * it reads, not the Keychain's: an unsigned app can't use the data-protection Keychain's
 * `.userPresence` items, which need an entitlement. Elsewhere (no helper) it is unavailable, and a
 * sign-in is typed every time.
 */
private object MacBiometricCredentialStore : BiometricCredentialStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun isAvailable(): Boolean = MacOwnerCheck.isAvailable

    override fun displayName(): StringResource = MacOwnerCheck.methodName

    override fun hasSavedCredentials(): Boolean = MacOwnerCheck.quietly("exists")

    override suspend fun save(credentials: SavedCredentials): Result<Unit> {
        val secret = json.encodeToString(SavedCredentials.serializer(), credentials).encodeToByteArray()
        return MacOwnerCheck.ask("save", getString(Res.string.biometric_confirm_save_reason), input = secret).map { }
    }

    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
        MacOwnerCheck.ask("read", getString(Res.string.biometric_sign_in_reason)).mapCatching { json.decodeFromString(SavedCredentials.serializer(), it.decodeToString()) }

    override fun clear() {
        MacOwnerCheck.quietly("delete")
    }
}

/** Where there is no helper to ask: nothing is saved, so every sign-in is typed. */
private object NoBiometricCredentialStore : BiometricCredentialStore {
    override fun isAvailable() = false
    override fun displayName(): StringResource = Res.string.biometric_name_generic
    override fun hasSavedCredentials() = false
    override suspend fun save(credentials: SavedCredentials): Result<Unit> = Result.failure(UnsupportedOperationException("No saved logins on this device"))
    override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> = Result.failure(UnsupportedOperationException("No saved logins on this device"))
    override fun clear() = Unit
}

actual fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore =
    if (MacOwnerCheck.isAvailable) MacBiometricCredentialStore else NoBiometricCredentialStore
