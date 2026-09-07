package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceSource
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mirrors the relay's presence table. The poll loop runs only while [presence] has a collector
 * (the Settings tab, the Home banner, the in-app alert poller) and restarts on every server
 * change, so a signed-out or idle app never touches the relay.
 *
 * Writes name this install by its device id and carry its relay secret, so they work from a
 * background wake with no signed-in session — the geofence's whole reason to exist. In that
 * case there is no active connection either, and the last server the user signed in to is
 * tried instead: its Tailscale address first (we're presumably not home), then its LAN one.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PresenceRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
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

    override suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource, dwellSeconds: Int): Result<Unit> {
        val urls = candidateUrls()
        if (urls.isEmpty()) return Result.failure(IllegalStateException("Not connected"))
        val deviceId = identity.deviceId()
        val secret = identity.secret()
        var last: Result<HouseholdPresence> = Result.failure(IllegalStateException("Not connected"))
        for (url in urls) {
            last = relayApi.setPresence(url, deviceId, secret, away, source.wire, dwellSeconds)
            if (last.isSuccess) break
        }
        return last.map { _presence.value = it }
    }

    override suspend fun setHome(home: HomeLocation?): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(IllegalStateException("Not connected"))
        return relayApi.setHome(url, home, identity.deviceId()).map { _presence.value = it }
    }

    private suspend fun fetch(url: String): Result<Unit> =
        relayApi.getPresence(url, identity.deviceId(), identity.secret()).map { _presence.value = it }

    /** The live server if there is one; otherwise the last one signed in to, remote address first. */
    private suspend fun candidateUrls(): List<String> {
        connectionRepository.currentServerUrl.value?.let { return listOf(it) }
        val recent = connectionRepository.mostRecentConnection.first() ?: return emptyList()
        return listOfNotNull(recent.serverUrl, recent.localUrl).distinct()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
