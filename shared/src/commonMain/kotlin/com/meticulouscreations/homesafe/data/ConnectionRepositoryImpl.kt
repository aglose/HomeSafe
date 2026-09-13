package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.LOCAL_SERVER_URL
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.StaleBiometricCredentialsException
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.CredentialsRejectedException
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateCamera
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.NetworkMonitor
import com.meticulouscreations.homesafe.network.SessionCheck
import com.meticulouscreations.homesafe.network.swapUrlScheme
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Picks the server's private LAN address whenever it answers and its Tailscale address
 * otherwise, and keeps that choice current as the device moves between networks and as the
 * app comes back from the background.
 *
 * **Trust.** The Tailscale address is the server's identity: reaching it means going through
 * WireGuard to a node the tailnet vouches for, so that is the only address the password is
 * ever sent to. The LAN address is just an IP on whatever Wi-Fi the phone happens to be on —
 * a coffee shop on the same 192.168.68.0/22 would answer at it too — so it is trusted only
 * after it proves it is the same server: the session token the Tailscale login produced is
 * presented to it ([FrigateApiClient.copySession] then [FrigateApiClient.checkSession]), and
 * only a host holding the server's own signing secret can accept that token. A host that can't
 * gets no password and no traffic. The one exception is a device with Tailscale switched off at
 * home: the Tailscale login fails at the transport level and the LAN address is the only way in,
 * so the password goes there as a last resort — see [signIn].
 *
 * **Speed.** The LAN probe and the Tailscale login run at the same time, so being away from home
 * costs nothing extra and being at home costs one probe round trip on the LAN. The camera list
 * is served from the local cache when there is one: the connection is live as soon as the
 * session is, and `/api/config` is fetched in the background to refresh it. Frigate's session
 * cookie is keyed by host in the client's jar, so switching address means copying it across, not
 * signing in again; a switch is silent and the user never sees a prompt. Cameras are cached under
 * the Tailscale URL — the server's identity — so the list doesn't blink when the route flips.
 */
@OptIn(ExperimentalTime::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ConnectionRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionHistoryDao: ConnectionHistoryDao,
    private val cameraDao: CameraDao,
    private val biometricCredentialStore: BiometricCredentialStore,
    networkMonitor: NetworkMonitor,
    private val appScope: CoroutineScope,
    private val clock: Clock,
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

    /** The signed-in session's credentials, so an expired session can be renewed without asking. */
    private var sessionCredentials: SavedCredentials? = null

    /** Serializes sign-ins so a user-driven connect and a network-driven route switch can't interleave. */
    private val signInMutex = Mutex()

    /** When the app last left the foreground, or null while it is on screen (or has never left). */
    private var backgroundedAtMillis: Long? = null
    private var revalidationJob: Job? = null

    init {
        appScope.launch {
            networkMonitor.changes.collectLatest {
                // Interfaces flap in bursts while a device joins a network; let them settle.
                // collectLatest also means a newer change cancels an in-flight follow-up.
                delay(NETWORK_SETTLE_MS)
                refreshRoute(verifySession = false)
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
        signIn(serverUrl, normalizedLocalUrl, username, password)
    }

    override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit): Result<SavedCredentials> =
        biometricCredentialStore.authenticateAndRetrieve().fold(
            onSuccess = { credentials ->
                onCredentialsUnlocked()
                // Deliberately not credentials.localUrl: the LAN address is compiled in, and a
                // credential saved before this route existed carries none at all.
                connect(credentials.serverUrl, LOCAL_SERVER_URL, credentials.username, credentials.password)
                    .recoverCatching { error ->
                        // The server answered and refused the saved password: it has changed
                        // server-side, and every future biometric attempt would fail the same
                        // way. Forgetting it here turns the dead end into the normal first-time
                        // flow — sign in with the password, get offered to save it. A transport
                        // failure or a server error says nothing about the password, so those
                        // keep the saved login.
                        if (error !is CredentialsRejectedException) throw error
                        biometricCredentialStore.clear()
                        throw StaleBiometricCredentialsException()
                    }
            },
            onFailure = { Result.failure(it) },
        )

    override suspend fun saveBiometricCredentials(credentials: SavedCredentials): Result<Unit> =
        biometricCredentialStore.save(credentials)

    override fun forgetBiometricCredentials() {
        biometricCredentialStore.clear()
    }

    override fun onAppVisibilityChanged(visible: Boolean) {
        if (!visible) {
            backgroundedAtMillis = clock.now().toEpochMilliseconds()
            return
        }
        val since = backgroundedAtMillis ?: return
        backgroundedAtMillis = null
        if (clock.now().toEpochMilliseconds() - since < REVALIDATE_AFTER_BACKGROUND_MS) return
        // The OS may have moved the phone between networks while the app was suspended (iOS
        // delivers no path events to a suspended app), and a long enough absence outlives the
        // session cookie. One pass fixes both before the first card asks for anything.
        if (revalidationJob?.isActive == true) return
        revalidationJob = appScope.launch { refreshRoute(verifySession = true) }
    }

    /**
     * Whether [localUrl] answers within [LOCAL_PROBE_TIMEOUT_MS]. Answering only proves *something*
     * is at that address; [verifiedLocalUrl] is what proves it is the server.
     */
    private suspend fun localAnswers(localUrl: String?): Boolean =
        localUrl != null && apiClient.isReachable(localUrl, LOCAL_PROBE_TIMEOUT_MS)

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

    /**
     * Hands the session held for [fromUrl] to the host at [localUrl] and asks it whose session
     * that is. Returns the LAN URL to use (the scheme may have been swapped, as for logins) when
     * the host accepts the token for [expectedUsername], or null when it doesn't: a different
     * server, a stale token, or no answer. A server running without auth issues no token; there
     * is then no secret to protect, and answering as a Frigate at all is enough.
     */
    private suspend fun verifiedLocalUrl(fromUrl: String, localUrl: String, expectedUsername: String): String? {
        val sessionCopied = apiClient.copySession(fromUrl, localUrl)
        for (candidate in listOfNotNull(localUrl, swapUrlScheme(localUrl))) {
            when (val check = apiClient.checkSession(candidate, LOCAL_PROBE_TIMEOUT_MS)) {
                is SessionCheck.Valid -> return candidate.takeIf { !sessionCopied || check.username == expectedUsername }
                SessionCheck.Rejected -> return null
                SessionCheck.Unreachable -> Unit // try the other scheme
            }
        }
        return null
    }

    /**
     * A fresh sign-in with a password. The Tailscale login and the LAN probe run together; then:
     *
     *  - Tailscale accepted the password and the LAN answered → the LAN host gets the new token
     *    and is used if it accepts it ([verifiedLocalUrl]); otherwise Tailscale is used.
     *  - Tailscale answered but refused the password → fail. The LAN host would refuse it too,
     *    and it must not be given the chance to *accept* it.
     *  - Tailscale never answered (the VPN is off) and the LAN did → the last resort: sign in
     *    over the LAN with the password. This is the one path on which the password crosses the
     *    local network, and it is taken only when nothing else can work.
     */
    private suspend fun signIn(
        serverUrl: String,
        localUrl: String?,
        username: String,
        password: String,
    ): Result<SavedCredentials> = coroutineScope {
        val lanAnswers = async { localAnswers(localUrl) }
        val remote = loginResolvingScheme(serverUrl, username, password)
        val remoteUrl = remote.getOrNull()
        val remoteError = remote.exceptionOrNull()
        val connection = when {
            remoteUrl != null -> {
                val verifiedLocal = if (lanAnswers.await()) verifiedLocalUrl(remoteUrl, localUrl!!, username) else null
                ActiveConnection(
                    serverUrl = remoteUrl,
                    localUrl = verifiedLocal ?: localUrl,
                    route = if (verifiedLocal != null) ConnectionRoute.LOCAL_NETWORK else ConnectionRoute.TAILSCALE,
                )
            }

            remoteError is FrigateResponseException || localUrl == null || !lanAnswers.await() ->
                return@coroutineScope Result.failure(remoteError ?: IllegalStateException("Login failed"))

            else -> {
                val workingLocal = loginResolvingScheme(localUrl, username, password)
                    .getOrElse { return@coroutineScope Result.failure(it) }
                ActiveConnection(serverUrl = serverUrl, localUrl = workingLocal, route = ConnectionRoute.LOCAL_NETWORK)
            }
        }
        val credentials = SavedCredentials(
            serverUrl = connection.serverUrl,
            username = username,
            password = password,
            localUrl = connection.localUrl,
        )
        activate(connection, credentials).map { credentials }
    }

    /**
     * Makes [connection] the live one. With cameras already cached for this server the app is
     * connected right now and the list is refreshed behind it; a first sign-in has nothing to
     * show, so it waits for the list (and a server that can't produce one fails the sign-in,
     * as it always has). Only a password sign-in — the user's own act — is recorded in history.
     */
    private suspend fun activate(connection: ActiveConnection, credentials: SavedCredentials): Result<Unit> {
        val cached = cameraDao.observeByServer(connection.serverUrl).first()
        if (cached.isEmpty()) {
            val cameras = apiClient.getCameras(connection.activeUrl).getOrElse { return Result.failure(it) }
            storeCameras(connection.serverUrl, cameras, cached)
        } else {
            appScope.launch { refreshCameras(connection) }
        }
        connectionHistoryDao.insert(
            ConnectionHistoryEntity(
                serverUrl = connection.serverUrl,
                localUrl = connection.localUrl,
                connectedAtEpochMillis = clock.now().toEpochMilliseconds(),
            ),
        )
        sessionCredentials = credentials
        _activeConnection.value = connection
        return Result.success(Unit)
    }

    private suspend fun refreshCameras(connection: ActiveConnection) {
        val cameras = apiClient.getCameras(connection.activeUrl).getOrNull() ?: return
        // A newer sign-in (another server) may have landed meanwhile; its list is not ours to touch.
        if (_activeConnection.value?.serverUrl != connection.serverUrl) return
        storeCameras(connection.serverUrl, cameras, cameraDao.observeByServer(connection.serverUrl).first())
    }

    /**
     * Writes [cameras] over [cached] without an empty moment in between: the grid is up while
     * this runs, and a delete-then-insert would flash "No cameras". Nothing is written when the
     * list hasn't changed, so the grid isn't recomposed for a refresh that found nothing new.
     */
    private suspend fun storeCameras(serverUrl: String, cameras: List<FrigateCamera>, cached: List<CameraEntity>) {
        val entities = cameras.map {
            CameraEntity(
                serverUrl = serverUrl,
                name = it.name,
                enabled = it.enabled,
                liveStreamName = it.liveStreamName,
                gridStreamName = it.gridStreamName,
            )
        }
        if (entities.sortedBy { it.name } == cached.sortedBy { it.name }) return
        cameraDao.insertAll(entities)
        cameraDao.deleteOthers(serverUrl, entities.map { it.name })
    }

    /**
     * After the network changed, or the app came back from a long spell in the background: if
     * the address we should be using differs from the one in use, move the session over to it.
     * With [verifySession] the current session is checked even when the route stands, and
     * renewed with the remembered password if the server has forgotten it. A failed attempt
     * (e.g. Tailscale hasn't finished coming up after leaving home) is retried on an interval
     * until it succeeds or the next network change supersedes it.
     */
    private suspend fun refreshRoute(verifySession: Boolean) {
        while (true) {
            val current = _activeConnection.value ?: return
            if (sessionCredentials == null) return
            val preferred = if (localAnswers(current.localUrl)) ConnectionRoute.LOCAL_NETWORK else ConnectionRoute.TAILSCALE
            if (preferred == current.route && !verifySession) return

            val settled = signInMutex.withLock {
                // Re-check under the lock: a user-driven connect may have landed in the meantime.
                val latest = _activeConnection.value ?: return
                val credentials = sessionCredentials ?: return
                if (latest.route == preferred && !verifySession) return
                moveSession(latest, preferred, credentials)
            }
            if (settled) return
            delay(ROUTE_SWITCH_RETRY_MS)
        }
    }

    /**
     * Carries the live session over to [preferred] (or re-checks it in place) without a prompt.
     * The token in the jar for the address in use is the freshest one — Frigate renews it on
     * the responses it serves — so it is copied to the target address first, then the target is
     * asked to confirm it. A target that rejects it means the session has expired: a new one is
     * minted where the password may go, the Tailscale address, and copied over; only when that
     * address is unreachable and the target is the LAN does the password go to the LAN itself.
     * Returns false when the target couldn't be reached at all, so the caller can retry later.
     */
    private suspend fun moveSession(latest: ActiveConnection, preferred: ConnectionRoute, credentials: SavedCredentials): Boolean {
        val target = latest.copy(route = preferred)
        val targetUrl = target.activeUrl
        if (targetUrl != latest.activeUrl) apiClient.copySession(latest.activeUrl, targetUrl)
        when (apiClient.checkSession(targetUrl, SESSION_CHECK_TIMEOUT_MS)) {
            is SessionCheck.Valid -> {
                _activeConnection.value = target
                return true
            }

            SessionCheck.Unreachable -> return false

            SessionCheck.Rejected -> Unit
        }
        val renewed = loginResolvingScheme(target.serverUrl, credentials.username, credentials.password)
        val renewedServerUrl = renewed.getOrNull()
        when {
            renewedServerUrl != null -> {
                val renewedTarget = target.copy(serverUrl = renewedServerUrl)
                if (renewedTarget.route == ConnectionRoute.LOCAL_NETWORK) {
                    val verified = verifiedLocalUrl(renewedServerUrl, renewedTarget.localUrl!!, credentials.username)
                    _activeConnection.value = if (verified != null) {
                        renewedTarget.copy(localUrl = verified)
                    } else {
                        // Answers, but not with our server's session: don't use it.
                        renewedTarget.copy(route = ConnectionRoute.TAILSCALE)
                    }
                } else {
                    _activeConnection.value = renewedTarget
                }
                return true
            }

            // The server refused the remembered password: nothing here can fix that. Keep the
            // connection as it is; the next user-driven sign-in replaces it.
            renewed.exceptionOrNull() is FrigateResponseException -> return true

            // Tailscale unreachable. The LAN is the only way, and the last resort (see signIn).
            preferred == ConnectionRoute.LOCAL_NETWORK -> {
                val workingLocal = loginResolvingScheme(target.localUrl!!, credentials.username, credentials.password).getOrNull()
                    ?: return false
                _activeConnection.value = target.copy(localUrl = workingLocal)
                return true
            }

            else -> return false
        }
    }

    private companion object {
        /** A LAN address either answers in well under a second or isn't there; don't make a remote sign-in wait longer. */
        const val LOCAL_PROBE_TIMEOUT_MS = 1_500L

        /** A session check goes to an address already known to answer; still bounded so a stalled route can't hang a switch. */
        const val SESSION_CHECK_TIMEOUT_MS = 5_000L
        const val NETWORK_SETTLE_MS = 750L
        const val ROUTE_SWITCH_RETRY_MS = 10_000L

        /** Shorter than this in the background and nothing worth re-checking can have happened. */
        const val REVALIDATE_AFTER_BACKGROUND_MS = 15_000L
    }
}
