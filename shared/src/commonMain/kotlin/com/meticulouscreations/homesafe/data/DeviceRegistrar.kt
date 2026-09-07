package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.DeviceRegistration
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the relay told who this install is and where to push. Re-registers whenever a server
 * becomes active (every sign-in, every LAN ↔ Tailscale flip), whenever the "only strangers"
 * preference changes, and whenever the push token rotates. Each registration hands back this
 * install's relay secret, which [DeviceIdentityStore] keeps for the background paths.
 *
 * Shared by every platform: what differs per platform is only what [DeviceInfo] says and whether
 * [PushTokenProvider] has a token — an iPhone registers without one and still gets an identity.
 */
@Inject
@SingleIn(AppScope::class)
class DeviceRegistrar(
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
    private val tokenProvider: PushTokenProvider,
    private val deviceInfo: DeviceInfo,
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** A token the platform pushed at us (Android's `onNewToken`); null means ask [tokenProvider]. */
    private val rotatedToken = MutableStateFlow<String?>(null)

    /** Idempotent: follows the connection for the life of the app. */
    fun start() {
        if (job?.isActive == true) return
        job = appScope.launch {
            combine(
                connectionRepository.currentServerUrl.filterNotNull(),
                settingsRepository.observeSettings().map { it.quietFamiliarPeople }.distinctUntilChanged(),
                rotatedToken,
            ) { url, quiet, token -> Triple(url, quiet, token) }
                .collect { (url, quiet, token) -> register(url, quiet, token) }
        }
    }

    /**
     * The platform handed out a new push token — possibly with no app running and no Frigate
     * session. Registers right away against the last known server, authenticated by the relay
     * secret; if this install has none yet, the next sign-in registers it anyway.
     */
    suspend fun onPushTokenChanged(token: String): Result<Unit> {
        rotatedToken.value = token
        val url = connectionRepository.currentServerUrl.value
            ?: connectionRepository.mostRecentConnection.first()?.serverUrl
            ?: return Result.failure(IllegalStateException("No server to register with"))
        return register(url, settingsRepository.observeSettings().first().quietFamiliarPeople, token)
    }

    private suspend fun register(serverUrl: String, quietFamiliar: Boolean, rotated: String?): Result<Unit> = runCatching {
        val registration = DeviceRegistration(
            deviceId = identity.deviceId(),
            token = rotated ?: tokenProvider.token(),
            platform = deviceInfo.platform,
            name = deviceInfo.name,
            quietFamiliar = quietFamiliar,
            build = deviceInfo.build,
        )
        val credentials = relayApi.registerDevice(serverUrl, registration, secret = identity.secret()).getOrThrow()
        identity.saveSecret(credentials.secret)
    }
}
