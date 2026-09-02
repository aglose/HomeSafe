package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.NetworkMonitor
import dev.zacsweers.metro.AppScope
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
        val route = preferredRoute(normalizedLocalUrl)
        signIn(
            connection = ActiveConnection(serverUrl = serverUrl, localUrl = normalizedLocalUrl, route = route),
            username = username,
            password = password,
            recordInHistory = true,
        )
    }

    override suspend fun signInWithBiometrics(): Result<SavedCredentials> =
        biometricCredentialStore.authenticateAndRetrieve().fold(
            onSuccess = { credentials ->
                connect(credentials.serverUrl, credentials.localUrl, credentials.username, credentials.password)
            },
            onFailure = { Result.failure(it) },
        )

    override suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> =
        biometricCredentialStore.save(credentials)

    override fun forgetBiometricCredentials() {
        biometricCredentialStore.clear()
    }

    /** Local if the LAN address answers within [LOCAL_PROBE_TIMEOUT_MS]; Tailscale otherwise (or when there is no LAN address). */
    private suspend fun preferredRoute(localUrl: String?): ConnectionRoute =
        if (localUrl != null && apiClient.isReachable(localUrl, LOCAL_PROBE_TIMEOUT_MS)) {
            ConnectionRoute.LOCAL_NETWORK
        } else {
            ConnectionRoute.TAILSCALE
        }

    @OptIn(ExperimentalTime::class)
    private suspend fun signIn(
        connection: ActiveConnection,
        username: String,
        password: String,
        recordInHistory: Boolean,
    ): Result<SavedCredentials> {
        val activeUrl = connection.activeUrl
        val credentials = SavedCredentials(
            serverUrl = connection.serverUrl,
            username = username,
            password = password,
            localUrl = connection.localUrl,
        )
        return apiClient.login(activeUrl, username, password)
            .mapCatching { apiClient.getCameras(activeUrl).getOrThrow() }
            .onSuccess { cameras ->
                cameraDao.deleteByServer(connection.serverUrl)
                cameraDao.insertAll(
                    cameras.map {
                        CameraEntity(
                            serverUrl = connection.serverUrl,
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
                            serverUrl = connection.serverUrl,
                            localUrl = connection.localUrl,
                            connectedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
                sessionCredentials = credentials
                _activeConnection.value = connection
            }
            .map { credentials }
    }

    /**
     * After the network changed: if the address we should be using differs from the one in
     * use, sign in again over the preferred one. A failed attempt (e.g. Tailscale hasn't
     * finished coming up after leaving home) is retried on an interval until it succeeds or
     * the next network change supersedes it.
     */
    private suspend fun followNetworkChange() {
        while (true) {
            val credentials = sessionCredentials ?: return
            val current = _activeConnection.value ?: return
            val localUrl = current.localUrl ?: return
            val preferred = preferredRoute(localUrl)
            if (preferred == current.route) return

            val switched = signInMutex.withLock {
                // Re-check under the lock: a user-driven connect may have landed in the meantime.
                val latest = _activeConnection.value ?: return
                if (latest.route == preferred) return
                signIn(latest.copy(route = preferred), credentials.username, credentials.password, recordInHistory = false)
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
