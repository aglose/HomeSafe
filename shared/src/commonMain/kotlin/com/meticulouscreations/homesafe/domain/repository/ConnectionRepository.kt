package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * Where a sign-in in progress is expected to land, from the moment its route is known well
     * enough to bet on — the LAN answered, or the Tailscale login went through — until the sign-in
     * lands or fails; null the rest of the time. What [activeConnection] will most likely become
     * a few hundred milliseconds later, once the server has checked the password and the route
     * is verified.
     *
     * For work that needs only the address, not the session: go2rtc's live streams take no
     * credentials, so the grid's players can start joining while the password is still being
     * checked. A fake that never signs in needn't override it.
     */
    val expectedConnection: StateFlow<ActiveConnection?> get() = NoExpectedConnection

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

    /**
     * The app came on screen ([visible] true) or left it. After a long enough stretch away the
     * route is re-chosen and the session re-checked — the network may have changed while the
     * app was suspended, and the session cookie may have expired — so the first request the
     * returning screens make doesn't fail.
     */
    fun onAppVisibilityChanged(visible: Boolean)

    /**
     * The user asked for it — the Home page's pull to refresh: one pass, now, of what a return
     * from the background does (re-choose the route, re-check the session and renew it if the
     * server has forgotten it), then a fresh read of the server's camera list. Returns once
     * both have landed or failed; a failure leaves the connection and the list as they were.
     * A fake that never signs in needn't override it.
     */
    suspend fun reconnect() {}
}

private val NoExpectedConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
