package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/**
 * The doors as they stand, for what asks with no screen up: Android's Quick Settings tile. Like
 * [PushedAlertActions], it may run with no Activity, no sign-in and no Frigate session, so it
 * reads the relay as this install, on its device secret, from the live server if there is one and
 * otherwise the last one signed in to, remote address first.
 */
@Inject
class BackgroundDoors(
    private val connectionRepository: ConnectionRepository,
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
) {
    /**
     * Every door sensor and how it stands; null when the relay couldn't be asked or didn't answer:
     * no server signed in to yet, no device secret (this install never registered with the relay,
     * so it can't read as itself), every address failed, or a relay from before the door sensors.
     */
    suspend fun doors(): List<Door>? {
        val secret = identity.secret() ?: return null
        val deviceId = identity.deviceId()
        for (url in candidateUrls()) {
            // No history: the tile only says how the doors stand now.
            val snapshot = relayApi.getDoors(url, deviceId, secret, days = 0).getOrNull() ?: continue
            return snapshot.doors.takeIf { snapshot.supported }
        }
        return null
    }

    private suspend fun candidateUrls(): List<String> {
        connectionRepository.currentServerUrl.value?.let { return listOf(it) }
        val recent = connectionRepository.mostRecentConnection.first() ?: return emptyList()
        return listOfNotNull(recent.serverUrl, recent.localUrl).distinct()
    }
}
