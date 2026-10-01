package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/**
 * What a pushed notification's buttons do without opening the app: for now, "Not a person" and
 * its Undo (see `phantom people` in relay/relay.py). Like [PushedAlertMedia], a button can be
 * pressed with no Activity, no sign-in and no Frigate session, so these go through the relay on
 * this install's device secret, to the live server if there is one and otherwise the last one
 * signed in to, remote address first.
 */
@Inject
class PushedAlertActions(
    private val connectionRepository: ConnectionRepository,
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
) {
    /** Marks [eventId] not a person: its spot stops alerting, and it becomes an example for the person classifier. */
    suspend fun markNotAPerson(eventId: String): Result<Unit> = onEachServer { url, deviceId, secret ->
        relayApi.markNotAPerson(url, eventId, deviceId, secret).map { }
    }

    /** Takes a [markNotAPerson] back. */
    suspend fun undoNotAPerson(eventId: String): Result<Unit> = onEachServer { url, deviceId, secret ->
        relayApi.undoNotAPerson(url, eventId, deviceId, secret)
    }

    /** [call] on each address the relay may answer on, until one does; the last failure otherwise. */
    private suspend fun onEachServer(call: suspend (url: String, deviceId: String, secret: String?) -> Result<Unit>): Result<Unit> {
        val deviceId = identity.deviceId()
        val secret = identity.secret()
        var last: Result<Unit> = Result.failure(IllegalStateException("No server to ask"))
        for (url in candidateUrls()) {
            last = call(url, deviceId, secret)
            if (last.isSuccess) break
        }
        return last
    }

    private suspend fun candidateUrls(): List<String> {
        connectionRepository.currentServerUrl.value?.let { return listOf(it) }
        val recent = connectionRepository.mostRecentConnection.first() ?: return emptyList()
        return listOfNotNull(recent.serverUrl, recent.localUrl).distinct()
    }
}
