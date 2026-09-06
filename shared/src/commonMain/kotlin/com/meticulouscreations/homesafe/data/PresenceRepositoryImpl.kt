package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mirrors the relay's presence table. The poll loop runs only while [presence] has a collector
 * (the Settings tab, the Home banner, the in-app alert poller) and restarts on every server
 * change, so a signed-out or idle app never touches the relay. Writes go through this phone's
 * push token — the relay knows devices by nothing else.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PresenceRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val tokenProvider: PushTokenProvider,
    private val connectionRepository: ConnectionRepository,
    appScope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : PresenceRepository {

    private val _presence = MutableStateFlow(HouseholdPresence.EMPTY)
    override val presence: StateFlow<HouseholdPresence> = _presence.asStateFlow()

    init {
        // Signing out forgets the household; the next server's relay may know different phones.
        appScope.launch {
            connectionRepository.currentServerUrl.collect { url -> if (url == null) _presence.value = HouseholdPresence.EMPTY }
        }
        appScope.launch {
            combine(
                _presence.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
                connectionRepository.currentServerUrl,
            ) { observed, url -> url.takeIf { observed } }
                .distinctUntilChanged()
                .collectLatest { url ->
                    if (url == null) return@collectLatest
                    while (true) {
                        fetch(url)
                        delay(pollIntervalMs)
                    }
                }
        }
    }

    override suspend fun refresh(): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(IllegalStateException("Not connected"))
        return fetch(url)
    }

    override suspend fun setThisDeviceAway(away: Boolean): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(IllegalStateException("Not connected"))
        if (!tokenProvider.isSupported) return Result.failure(IllegalStateException("Push isn't available on this platform"))
        val token = tokenProvider.token() ?: return Result.failure(IllegalStateException("This phone has no push token yet"))
        return relayApi.setPresence(url, token, away).map { _presence.value = it }
    }

    private suspend fun fetch(url: String): Result<Unit> =
        relayApi.getPresence(url, tokenProvider.token()).map { _presence.value = it }

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
