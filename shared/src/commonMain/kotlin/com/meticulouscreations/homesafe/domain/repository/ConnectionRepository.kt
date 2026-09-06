package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Manages the connection to a Frigate server: login, session state, route selection, history, and biometric credentials. */
interface ConnectionRepository {
    /** The server the app is signed in to and which of its addresses is in use, or null if not connected. */
    val activeConnection: StateFlow<ActiveConnection?>

    /**
     * The base URL in use right now — [ActiveConnection.activeUrl] — or null if not connected.
     * Changes when the route changes (e.g. the phone leaves the server's Wi-Fi), so anything
     * derived from it (stream and snapshot URLs) follows automatically.
     */
    val currentServerUrl: StateFlow<String?>

    /** The most recently successful connection, used to prefill the connect screen. */
    val mostRecentConnection: Flow<ConnectionRecord?>

    /** True if biometric hardware is present, enrolled, and usable on this platform/device. */
    val biometricLoginAvailable: Boolean

    /** Short user-facing name for the biometric method, e.g. "Face ID" or "fingerprint". */
    val biometricDisplayName: String

    /** True if credentials were previously saved for biometric login and are (still) present. */
    fun hasSavedBiometricCredentials(): Boolean

    /**
     * Signs in to the server, fetches and caches its cameras, and records the connection in history.
     *
     * [serverUrl] is the Tailscale/remote address and always works; [localUrl] is the server's
     * private LAN address. When [localUrl] is given and answers, it's used for everything
     * (API, snapshots, video) — the direct path when the device is on the server's Wi-Fi.
     * Otherwise [serverUrl] is used. The choice is re-evaluated whenever the network changes.
     */
    suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String): Result<SavedCredentials>

    /**
     * Runs the biometric prompt, then reuses [connect] with the retrieved credentials on success.
     * [onCredentialsUnlocked] fires between the two — the prompt has been passed and the server
     * round-trip is about to start — so a caller can tell "waiting on the user" from "connecting".
     */
    suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit = {}): Result<SavedCredentials>

    /** Prompts for biometric auth, then encrypts and persists [credentials] for future biometric login. */
    suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit>

    /** Forgets any saved biometric credentials. Does not require a biometric prompt. */
    fun forgetBiometricCredentials()
}
