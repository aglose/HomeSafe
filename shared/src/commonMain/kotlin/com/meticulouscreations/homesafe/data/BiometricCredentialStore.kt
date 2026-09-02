package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.serialization.Serializable

/** Login credentials for a Frigate server, saved locally behind a biometric check. */
@Serializable
data class SavedCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    /** The server's private LAN URL, if the user entered one. Defaults so credentials saved before it existed still decode. */
    val localUrl: String? = null,
)

/**
 * Stores login credentials behind a platform biometric check (Face ID / Touch ID on iOS,
 * BiometricPrompt/fingerprint on Android), so a returning user can skip retyping them.
 *
 * The security model on every platform that implements this for real ties biometric
 * verification directly to the decrypt/read operation itself, rather than using a
 * biometric prompt as a separate "yes/no" gate that then reads an otherwise-unprotected
 * credential:
 *  - **Android**: [save] and [authenticateAndRetrieve] encrypt/decrypt with an AES key
 *    generated in the Android Keystore with `setUserAuthenticationRequired(true)`. That
 *    key's [javax.crypto.Cipher] literally cannot be initialized without a fresh
 *    `BiometricPrompt` auth completing first (`CryptoObject`), so there is no code path
 *    that reads the plaintext credentials without the OS having just verified biometrics.
 *  - **iOS**: credentials are stored in the Keychain under a `SecAccessControl` created
 *    with the `.biometryCurrentSet` flag. The Keychain item's *data* cannot be read by
 *    `SecItemCopyMatching` without the Security framework itself driving a Face ID/Touch ID
 *    check (via `kSecUseAuthenticationContext`) — again, the OS enforces the tie between
 *    biometrics and the read, not application code.
 *
 * On platforms with no OS biometric API (JVM desktop, JS, Wasm), [isAvailable] returns
 * `false` and the other members are no-ops / failures — see the platform `actual`s of
 * [createBiometricCredentialStore] for the precedent (matches [createConnectionHistoryDao]'s
 * web fallback and [com.meticulouscreations.homesafe.ui.components.CameraStreamPlayer]'s
 * "not available on this platform" placeholders).
 */
interface BiometricCredentialStore {
    /** True if biometric hardware is present, enrolled, and usable for this on this platform/device. */
    fun isAvailable(): Boolean

    /** A short, user-facing name for the biometric method on this device, e.g. "Face ID" or "fingerprint". */
    fun displayName(): String

    /** True if credentials were previously saved with [save] and are (still) present on this device. */
    fun hasSavedCredentials(): Boolean

    /** Prompts for biometric auth, then encrypts and persists [credentials] for later biometric login. */
    suspend fun save(credentials: SavedCredentials): Result<Unit>

    /** Prompts for biometric auth, then decrypts and returns the previously saved credentials. */
    suspend fun authenticateAndRetrieve(): Result<SavedCredentials>

    /** Forgets any saved credentials. Does not require a biometric prompt. */
    fun clear()
}

/** Builds the platform's [BiometricCredentialStore]. See the class doc for the security model per platform. */
expect fun createBiometricCredentialStore(context: PlatformContext): BiometricCredentialStore
