package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.data.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Manages the connection to a Frigate server: login, session state, history, and biometric credentials. */
interface ConnectionRepository {
    /** The currently connected server's URL, or null if not connected. */
    val currentServerUrl: StateFlow<String?>

    /** The most recently successful connection, used to prefill the connect screen. */
    val mostRecentConnection: Flow<ConnectionRecord?>

    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val biometricLoginAvailable: Boolean

    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val biometricDisplayName: String

    /** True if credentials were previously saved for biometric login and are (still) present. */
    fun hasSavedBiometricCredentials(): Boolean

    /** Logs into [serverUrl], fetches and caches its cameras, and records the connection in history. */
    suspend fun connect(serverUrl: String, username: String, password: String): Result<SavedCredentials>

    /** Runs the biometric prompt, then reuses [connect] with the retrieved credentials on success. */
    suspend fun signInWithBiometrics(): Result<SavedCredentials>

    /** Prompts for biometric auth, then encrypts and persists [credentials] for future biometric login. */
    suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit>

    /** Forgets any saved biometric credentials. Does not require a biometric prompt. */
    fun forgetBiometricCredentials()
}
