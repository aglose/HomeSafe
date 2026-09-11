package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.LOCAL_SERVER_URL
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.NetworkMonitor
import com.meticulouscreations.homesafe.network.swapUrlScheme
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Picks the server's private LAN address whenever it answers and its Tailscale address
 * otherwise, and keeps that choice current as the device moves between networks.
 *
 * Frigate's session cookie is keyed by host, so switching address means signing in again on
 * the other one. The credentials of the current session are kept in memory for exactly that;
 * the switch is silent and the user never sees a prompt. Cameras are cached under the
 * Tailscale URL — the server's identity — so the camera list doesn't blink when the route flips.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ConnectionRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val cameraDao: CameraDao,
    private val biometricCredentialStore: BiometricCredentialStore,
    networkMonitor: NetworkMonitor,
    appScope: CoroutineScope,
) : ConnectionRepository {

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    override val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    override val currentServerUrl: StateFlow<String?> =
        _activeConnection.map { it?.activeUrl }.stateIn(appScope, SharingStarted.Eagerly, null)

    override val mostRecentConnection: Flow<ConnectionRecord?> =
        connectionHistoryDao.mostRecentAsFlow().map { entity ->
            entity?.let {
                ConnectionRecord(
                    serverUrl = it.serverUrl,
                    localUrl = it.localUrl,
                    connectedAtEpochMillis = it.connectedAtEpochMillis,
                )
            }
        }

    override val biometricLoginAvailable: Boolean = biometricCredentialStore.isAvailable()
    override val biometricDisplayName: String = biometricCredentialStore.displayName()

    /** The signed-in session's credentials, so a route change can sign in on the other address without asking. */
    private var sessionCredentials: SavedCredentials? = null

    /** Serializes sign-ins so a user-driven connect and a network-driven route switch can't interleave. */
    private val signInMutex = Mutex()

    init {
        appScope.launch {
            networkMonitor.changes.collectLatest {
                // Interfaces flap in bursts while a device joins a network; let them settle.
                // collectLatest also means a newer change cancels an in-flight follow-up.
                delay(NETWORK_SETTLE_MS)
                followNetworkChange()
            }
        }
    }

    override fun hasSavedBiometricCredentials(): Boolean = biometricCredentialStore.hasSavedCredentials()

    override suspend fun connect(
        serverUrl: String,
        localUrl: String?,
        username: String,
        password: String,
    ): Result<SavedCredentials> = signInMutex.withLock {
        val normalizedLocalUrl = localUrl?.trim()?.takeIf { it.isNotEmpty() }
        val overTailscale = ActiveConnection(
            serverUrl = serverUrl,
            localUrl = normalizedLocalUrl,
            route = ConnectionRoute.TAILSCALE,
        )
        signIn(
            connection = overTailscale.routedTo(reachableLocalUrl(normalizedLocalUrl)),
            username = username,
            password = password,
            recordInHistory = true,
        )
    }

    override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit): Result<SavedCredentials> =
        biometricCredentialStore.authenticateAndRetrieve().fold(
            onSuccess = { credentials ->
                onCredentialsUnlocked()
                // Deliberately not credentials.localUrl: the LAN address is compiled in, and a
                // credential saved before this route existed carries none at all.
                connect(credentials.serverUrl, LOCAL_SERVER_URL, credentials.username, credentials.password)
            },
            onFailure = { Result.failure(it) },
        )

    override suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> =
        biometricCredentialStore.save(credentials)

    override fun forgetBiometricCredentials() {
        biometricCredentialStore.clear()
    }

    /**
     * The LAN URL to sign in on, or null to take the Tailscale route: [localUrl] if it answers
     * within [LOCAL_PROBE_TIMEOUT_MS], else the same address over the other scheme if that does.
     * [LOCAL_SERVER_URL] is compiled in with one scheme, but whether Frigate's port speaks TLS is
     * a server setting that can change under it; probing the compiled-in scheme alone would then
     * fail silently and leave the app on Tailscale while sitting next to the server. Two probes
     * at most, so a remote sign-in waits no longer than twice the probe timeout.
     */
    private suspend fun reachableLocalUrl(localUrl: String?): String? {
        if (localUrl == null) return null
        if (apiClient.isReachable(localUrl, LOCAL_PROBE_TIMEOUT_MS)) return localUrl
        val swapped = swapUrlScheme(localUrl) ?: return null
        return swapped.takeIf { apiClient.isReachable(it, LOCAL_PROBE_TIMEOUT_MS) }
    }

    /** True when this connection already uses what [reachableLocalUrl] calls for: Tailscale, or the LAN over that exact URL. */
    private fun ActiveConnection.alreadyUses(reachableLocalUrl: String?): Boolean =
        when (reachableLocalUrl) {
            null -> route == ConnectionRoute.TAILSCALE
            else -> route == ConnectionRoute.LOCAL_NETWORK && localUrl == reachableLocalUrl
        }

    /** The connection [reachableLocalUrl] calls for, keeping the LAN address on file when it isn't answering. */
    private fun ActiveConnection.routedTo(reachableLocalUrl: String?): ActiveConnection =
        when (reachableLocalUrl) {
            null -> copy(route = ConnectionRoute.TAILSCALE)
            else -> copy(route = ConnectionRoute.LOCAL_NETWORK, localUrl = reachableLocalUrl)
        }

    /**
     * Logs in to [url], retrying once over the opposite scheme when the first attempt never got
     * an HTTP response at all. Turning TLS on or off at the server strands every saved `https://`
     * URL against a now-plaintext port (and the reverse), which surfaces as an opaque handshake
     * error rather than anything the user can act on. Adopting whichever scheme actually answers
     * lets a saved login heal itself. Returns the URL that worked.
     */
    private suspend fun loginResolvingScheme(url: String, username: String, password: String): Result<String> {
        val attempt = apiClient.login(url, username, password)
        if (attempt.isSuccess) return Result.success(url)
        val error = attempt.exceptionOrNull() ?: return Result.success(url)
        // The server answered and turned us away (wrong password): the scheme is fine as it is.
        if (error is FrigateResponseException) return Result.failure(error)
        val swapped = swapUrlScheme(url) ?: return Result.failure(error)
        return apiClient.login(swapped, username, password).map { swapped }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun signIn(
        connection: ActiveConnection,
        username: String,
        password: String,
        recordInHistory: Boolean,
    ): Result<SavedCredentials> {
        val workingUrl = loginResolvingScheme(connection.activeUrl, username, password)
            .getOrElse { return Result.failure(it) }
        // Keep a corrected scheme, so the repair lands in history and saved credentials too.
        val resolved = when {
            workingUrl == connection.activeUrl -> connection
            connection.route == ConnectionRoute.LOCAL_NETWORK -> connection.copy(localUrl = workingUrl)
            else -> connection.copy(serverUrl = workingUrl)
        }
        val activeUrl = resolved.activeUrl
        val credentials = SavedCredentials(
            serverUrl = resolved.serverUrl,
            username = username,
            password = password,
            localUrl = resolved.localUrl,
        )
        return runCatching { apiClient.getCameras(activeUrl).getOrThrow() }
            .onSuccess { cameras ->
                cameraDao.deleteByServer(resolved.serverUrl)
                cameraDao.insertAll(
                    cameras.map {
                        CameraEntity(
                            serverUrl = resolved.serverUrl,
                            name = it.name,
                            enabled = it.enabled,
                            liveStreamName = it.liveStreamName,
                            gridStreamName = it.gridStreamName,
                        )
                    },
                )
                if (recordInHistory) {
                    connectionHistoryDao.insert(
                        ConnectionHistoryEntity(
                            serverUrl = resolved.serverUrl,
                            localUrl = resolved.localUrl,
                            connectedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
                sessionCredentials = credentials
                _activeConnection.value = resolved
            }
            .map { credentials }
    }

    /**
     * After the network changed: if the address we should be using differs from the one in
     * use — the other route, or the LAN address now answering over the other scheme — sign in
     * again over the preferred one. A failed attempt (e.g. Tailscale hasn't
     * finished coming up after leaving home) is retried on an interval until it succeeds or
     * the next network change supersedes it.
     */
    private suspend fun followNetworkChange() {
        while (true) {
            val credentials = sessionCredentials ?: return
            val current = _activeConnection.value ?: return
            val localUrl = current.localUrl ?: return
            val reachableLocalUrl = reachableLocalUrl(localUrl)
            if (current.alreadyUses(reachableLocalUrl)) return

            val switched = signInMutex.withLock {
                // Re-check under the lock: a user-driven connect may have landed in the meantime.
                val latest = _activeConnection.value ?: return
                if (latest.alreadyUses(reachableLocalUrl)) return
                signIn(
                    connection = latest.routedTo(reachableLocalUrl),
                    username = credentials.username,
                    password = credentials.password,
                    recordInHistory = false,
                )
            }
            if (switched.isSuccess) return
            delay(ROUTE_SWITCH_RETRY_MS)
        }
    }

    private companion object {
        /** A LAN address either answers in well under a second or isn't there; don't make a remote sign-in wait longer. */
        const val LOCAL_PROBE_TIMEOUT_MS = 1_500L
        const val NETWORK_SETTLE_MS = 750L
        const val ROUTE_SWITCH_RETRY_MS = 10_000L
    }
}
