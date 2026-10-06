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
import com.meticulouscreations.homesafe.network.LocalNetworkAccess
import com.meticulouscreations.homesafe.network.NetworkMonitor
import com.meticulouscreations.homesafe.network.SessionCheck
import com.meticulouscreations.homesafe.network.swapUrlScheme
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.components.LiveStartupMilestones
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.error_sign_in_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
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
 *
 * **Offline.** A saved login is a login the server accepted once already. When the app is opened
 * again and the server can't be reached at all — the box is still booting after a power cut, the
 * VPN hasn't come up yet — and this device has connected to that server before, the saved login
 * opens the app on what the device kept (cameras, moments) rather than on an error, and the
 * session is minted behind the screens by the same revalidation a long absence runs
 * ([refreshRoute]), which keeps trying until the server answers. Only a server that answers can
 * refuse: a password it rejects still ends the saved login on the spot ([signInWithBiometrics]),
 * and a first sign-in still needs the server. See [resumeOffline].
 *
 * **Permission.** Where the system keeps the local network from an app until the user allows it
 * (Android 17), a LAN address that is right there times out like one that isn't, and nothing says
 * why. So a sign-in that has a LAN address to try asks first ([LocalNetworkAccess.request]) and
 * only then probes; the probe is still what decides the route. A user who refused can change
 * their mind in system settings, which no network event reports, so the answer is read again
 * whenever the app comes back on screen and a new grant re-checks the route there and then.
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
    private val localNetworkAccess: LocalNetworkAccess,
    private val appScope: CoroutineScope,
    private val clock: Clock,
) : ConnectionRepository {

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    override val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    override val currentServerUrl: StateFlow<String?> =
        _activeConnection.map { it?.activeUrl }.stateIn(appScope, SharingStarted.Eagerly, null)

    /** [LAN_GRACE_MS]; tests shorten or lengthen it (real time, see [signInExpecting]). */
    internal var lanGraceMs = LAN_GRACE_MS

    private val _expectedConnection = MutableStateFlow<ActiveConnection?>(null)
    override val expectedConnection: StateFlow<ActiveConnection?> = _expectedConnection.asStateFlow()

    private val _serverUnreachable = MutableStateFlow(false)
    override val serverUnreachable: StateFlow<Boolean> = _serverUnreachable.asStateFlow()

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
    override val biometricDisplayName: StringResource = biometricCredentialStore.displayName()

    /** The signed-in session's credentials, so an expired session can be renewed without asking. */
    private var sessionCredentials: SavedCredentials? = null

    /** Serializes sign-ins so a user-driven connect and a network-driven route switch can't interleave. */
    private val signInMutex = Mutex()

    /** When the app last left the foreground, or null while it is on screen (or has never left). */
    private var backgroundedAtMillis: Long? = null

    /** Whether the local network was the app's to use when last looked at; see [localNetworkAccessGained]. */
    private var localNetworkAllowed = localNetworkAccess.isGranted()
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
    ): Result<SavedCredentials> {
        val normalizedLocalUrl = localUrl?.trim()?.takeIf { it.isNotEmpty() }
        // Only with a LAN address to probe: a sign-in that will never touch the local network
        // has no business asking for it. Outside the lock: the system's dialog can stay up as
        // long as the user likes, and a route check a network change set off meanwhile
        // shouldn't queue behind it.
        if (normalizedLocalUrl != null) localNetworkAllowed = localNetworkAccess.request()
        return signInMutex.withLock { signIn(serverUrl, normalizedLocalUrl, username, password) }
    }

    override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit): Result<SavedCredentials> {
        // A returning user's live video can start joining while the prompt is still up.
        val anticipation = appScope.launch { anticipateSavedLogin() }
        try {
            val unlocked = biometricCredentialStore.authenticateAndRetrieve()
            // The sign-in proper probes the routes itself from here on.
            anticipation.cancelAndJoin()
            return completeBiometricSignIn(unlocked, onCredentialsUnlocked)
        } finally {
            anticipation.cancel()
            // A prompt dismissed (or a sign-in that failed) leaves nothing to expect.
            if (_activeConnection.value == null) _expectedConnection.value = null
        }
    }

    /**
     * Publishes [expectedConnection] for the last server this device signed in to, from nothing
     * but whether its addresses answer — no credentials go anywhere — so the players for its
     * cached cameras can start while the biometric prompt is up (see `LiveStreamPrefetcher`). The
     * LAN wins whenever it answers; Tailscale is expected only once it has answered and the LAN
     * has had [LAN_GRACE_MS] more to do the same. A device with nothing cached expects nothing,
     * because it has nothing to prefetch.
     */
    private suspend fun anticipateSavedLogin() {
        val record = mostRecentConnection.first() ?: return
        if (cameraDao.observeByServer(record.serverUrl).first().isEmpty()) return
        coroutineScope {
            launch {
                if (localAnswers(LOCAL_SERVER_URL)) {
                    _expectedConnection.value = ActiveConnection(record.serverUrl, LOCAL_SERVER_URL, ConnectionRoute.LOCAL_NETWORK)
                }
            }
            launch {
                if (apiClient.isReachable(record.serverUrl, LOCAL_PROBE_TIMEOUT_MS)) {
                    delay(lanGraceMs)
                    if (_expectedConnection.value == null) {
                        _expectedConnection.value = ActiveConnection(record.serverUrl, LOCAL_SERVER_URL, ConnectionRoute.TAILSCALE)
                    }
                }
            }
        }
    }

    private suspend fun completeBiometricSignIn(unlocked: Result<SavedCredentials>, onCredentialsUnlocked: () -> Unit): Result<SavedCredentials> =
        unlocked.fold(
            onSuccess = { credentials ->
                onCredentialsUnlocked()
                // Deliberately not credentials.localUrl: the LAN address is compiled in, and a
                // credential saved before this route existed carries none at all.
                val attempt = connect(credentials.serverUrl, LOCAL_SERVER_URL, credentials.username, credentials.password)
                when (val error = attempt.exceptionOrNull()) {
                    null -> attempt

                    // The server answered and refused the saved password: it has changed
                    // server-side, and every future biometric attempt would fail the same
                    // way. Forgetting it here turns the dead end into the normal first-time
                    // flow — sign in with the password, get offered to save it. A transport
                    // failure or a server error says nothing about the password, so those
                    // keep the saved login.
                    is CredentialsRejectedException -> {
                        biometricCredentialStore.clear()
                        Result.failure(StaleBiometricCredentialsException())
                    }

                    // The server answered, just not well (a 500): it is there, so nothing is
                    // gained by pretending otherwise. The user sees the error and tries again.
                    is FrigateResponseException -> attempt

                    // Nothing answered. If this device has been here before, that is not a
                    // reason to keep the user out of what it kept.
                    else -> if (resumeOffline(credentials)) Result.success(credentials) else attempt
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
        val longAway = clock.now().toEpochMilliseconds() - since >= REVALIDATE_AFTER_BACKGROUND_MS
        // A trip to system settings to allow the local network takes a few seconds and changes
        // no network, so neither the absence nor the network monitor would notice it.
        val accessGained = localNetworkAccessGained()
        if (!longAway && !accessGained) return
        // The OS may have moved the phone between networks while the app was suspended (iOS
        // delivers no path events to a suspended app), and a long enough absence outlives the
        // session cookie. One pass fixes both before the first card asks for anything.
        if (revalidationJob?.isActive == true) return
        revalidationJob = appScope.launch { refreshRoute(verifySession = longAway) }
    }

    /** True once for each time the local network goes from withheld to allowed between two looks. */
    private fun localNetworkAccessGained(): Boolean {
        val allowed = localNetworkAccess.isGranted()
        val gained = allowed && !localNetworkAllowed
        localNetworkAllowed = allowed
        return gained
    }

    override suspend fun reconnect() {
        // One pass rather than [refreshRoute]'s loop: a pull wants an answer, and a revalidation
        // already retrying in the background carries on doing so either way.
        checkRoute(verifySession = true)
        _activeConnection.value?.let { refreshCameras(it) }
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
    ): Result<SavedCredentials> = try {
        signInExpecting(serverUrl, localUrl, username, password)
    } finally {
        _expectedConnection.value = null
    }

    /**
     * [signIn] itself, publishing [expectedConnection] along the way: the LAN as soon as it
     * answers (it is used unless it turns out not to be this server, which is rare), otherwise
     * Tailscale as soon as it has accepted the password — the LAN probe can take up to
     * [LOCAL_PROBE_TIMEOUT_MS] longer to give up.
     */
    private suspend fun signInExpecting(
        serverUrl: String,
        localUrl: String?,
        username: String,
        password: String,
    ): Result<SavedCredentials> = coroutineScope {
        // Not a child of this scope: away from home the probe can take its whole timeout to give
        // up, and the sign-in doesn't wait for that (below). The watcher that publishes a LAN
        // expectation is a child, cancelled once the sign-in stops listening to the probe.
        val lanAnswers = appScope.async { localAnswers(localUrl) }
        val lanWatch = launch {
            val answered = lanAnswers.await()
            if (answered) _expectedConnection.value = ActiveConnection(serverUrl, localUrl, ConnectionRoute.LOCAL_NETWORK)
            LiveStartupMilestones.mark("signin.lan $answered")
        }
        val remote = loginResolvingScheme(serverUrl, username, password)
        LiveStartupMilestones.mark("signin.login")
        val remoteUrl = remote.getOrNull()
        val remoteError = remote.exceptionOrNull()
        if (remoteUrl != null && _expectedConnection.value == null) {
            _expectedConnection.value = ActiveConnection(remoteUrl, localUrl, ConnectionRoute.TAILSCALE)
        }
        val connection = when {
            remoteUrl != null -> {
                // At home the LAN has long answered by the time Tailscale accepts a password; away,
                // its probe may still be waiting out the timeout. Give it [LAN_GRACE_MS], then go on
                // over Tailscale, and move to the LAN behind the sign-in if it answers after all.
                // On a real clock: the probe is real network I/O, and a test scheduler's virtual
                // time would let the grace run out before any reply could arrive.
                val lanUp = withContext(Dispatchers.Default) { withTimeoutOrNull(lanGraceMs) { lanAnswers.await() } }
                if (lanUp == null) {
                    lanWatch.cancel()
                    moveToLanIfItAnswers(lanAnswers, remoteUrl)
                }
                val verifiedLocal = if (lanUp == true) verifiedLocalUrl(remoteUrl, localUrl!!, username) else null
                ActiveConnection(
                    serverUrl = remoteUrl,
                    localUrl = verifiedLocal ?: localUrl,
                    route = if (verifiedLocal != null) ConnectionRoute.LOCAL_NETWORK else ConnectionRoute.TAILSCALE,
                )
            }

            remoteError is FrigateResponseException || localUrl == null || !lanAnswers.await() ->
                return@coroutineScope Result.failure(remoteError ?: LocalizedException(UiText.of(Res.string.error_sign_in_failed), technical = "Login failed"))

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
     * A sign-in that went ahead over Tailscale without waiting for the LAN probe: if the probe
     * answers after all, the route is re-chosen (as on a network change) once the sign-in has
     * landed — the session moves to the LAN, and everything derived from the address follows.
     */
    private fun moveToLanIfItAnswers(lanAnswers: Deferred<Boolean>, serverUrl: String) {
        appScope.launch {
            if (!lanAnswers.await()) return@launch
            withTimeoutOrNull(ROUTE_SWITCH_RETRY_MS) { _activeConnection.first { it?.serverUrl == serverUrl } } ?: return@launch
            if (revalidationJob?.isActive != true) revalidationJob = appScope.launch { refreshRoute(verifySession = false) }
        }
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
        _serverUnreachable.value = false
        _activeConnection.value = connection
        return Result.success(Unit)
    }

    /**
     * Opens the app on the saved login without the server: the last known connection goes live
     * with [credentials] behind it, and [refreshRoute] is started to mint a session the moment
     * the server answers (it retries on an interval until then). Only for a server this device
     * has cameras cached for — anything else has nothing to show and needs the server anyway.
     * The route starts as Tailscale, the address the password is allowed to go to; the
     * revalidation moves it to the LAN if that is what answers. Returns whether it did so.
     */
    private suspend fun resumeOffline(credentials: SavedCredentials): Boolean = signInMutex.withLock {
        // A sign-in that landed in the meantime is the real thing; don't replace it.
        if (_activeConnection.value != null) return true
        if (cameraDao.observeByServer(credentials.serverUrl).first().isEmpty()) return false
        sessionCredentials = credentials
        _serverUnreachable.value = true
        _activeConnection.value = ActiveConnection(
            serverUrl = credentials.serverUrl,
            localUrl = LOCAL_SERVER_URL,
            route = ConnectionRoute.TAILSCALE,
        )
        if (revalidationJob?.isActive != true) {
            revalidationJob = appScope.launch { refreshRoute(verifySession = true) }
        }
        true
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
        while (!checkRoute(verifySession)) delay(ROUTE_SWITCH_RETRY_MS)
    }

    /**
     * One pass of [refreshRoute]. True when there is nothing more to do — the session is where
     * it should be, or there is no session to move — and false when the address it should move
     * to couldn't be reached, so a caller that wants it settled has to try again.
     */
    private suspend fun checkRoute(verifySession: Boolean): Boolean {
        val current = _activeConnection.value ?: return true
        if (sessionCredentials == null) return true
        val preferred = if (localAnswers(current.localUrl)) ConnectionRoute.LOCAL_NETWORK else ConnectionRoute.TAILSCALE
        if (preferred == current.route && !verifySession) return true

        return signInMutex.withLock {
            // Re-check under the lock: a user-driven connect may have landed in the meantime.
            val latest = _activeConnection.value ?: return true
            val credentials = sessionCredentials ?: return true
            if (latest.route == preferred && !verifySession) return true
            // The one place the server is actually asked: what it found is what the screens are told.
            moveSession(latest, preferred, credentials).also { reached -> _serverUnreachable.value = !reached }
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

        /**
         * How long a sign-in that Tailscale has accepted still waits for the LAN probe. At home
         * the probe answers in tens of milliseconds, well before the login; away from home it can
         * take all of [LOCAL_PROBE_TIMEOUT_MS] to give up, and that is not worth holding the app for.
         */
        const val LAN_GRACE_MS = 250L

        /** A session check goes to an address already known to answer; still bounded so a stalled route can't hang a switch. */
        const val SESSION_CHECK_TIMEOUT_MS = 5_000L
        const val NETWORK_SETTLE_MS = 750L
        const val ROUTE_SWITCH_RETRY_MS = 10_000L

        /** Shorter than this in the background and nothing worth re-checking can have happened. */
        const val REVALIDATE_AFTER_BACKGROUND_MS = 15_000L
    }
}
