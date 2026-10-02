package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PhantomRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PhantomRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
) : PhantomRepository {

    /**
     * Kept per server — [ActiveConnection.serverUrl], which names the server whichever of its
     * routes is in use — so a LAN/Tailscale switch keeps the spots and another server's never hide
     * this one's people. Requests go to the active route.
     */
    private val byServer = MutableStateFlow<Map<String, List<PhantomSpot>>>(emptyMap())

    /**
     * One relay call at a time: a refresh answered after an Undo would otherwise put back the
     * spot the Undo just took away, and hide the card again.
     */
    private val mutex = Mutex()

    override val spots: Flow<List<PhantomSpot>> =
        combine(connectionRepository.activeConnection, byServer) { connection, known -> connection?.let { known[it.serverUrl] }.orEmpty() }

    override suspend fun refresh(): Result<Unit> {
        return mutex.withLock {
            val connection = connectionOrFailure().getOrElse { return Result.failure(it) }
            relayApi.getPhantomSpots(connection.activeUrl).map { spots -> byServer.update { it + (connection.serverUrl to spots) } }
        }
    }

    override suspend fun markNotAPerson(eventId: String): Result<PhantomSpot> {
        return mutex.withLock {
            val connection = connectionOrFailure().getOrElse { return Result.failure(it) }
            relayApi.markNotAPerson(connection.activeUrl, eventId).onSuccess { spot ->
                byServer.update { known -> known + (connection.serverUrl to known[connection.serverUrl].orEmpty().filterNot { it.eventId == eventId } + spot) }
            }
        }
    }

    override suspend fun undoNotAPerson(eventId: String): Result<Unit> {
        return mutex.withLock {
            val connection = connectionOrFailure().getOrElse { return Result.failure(it) }
            relayApi.undoNotAPerson(connection.activeUrl, eventId).onSuccess {
                byServer.update { known -> known + (connection.serverUrl to known[connection.serverUrl].orEmpty().filterNot { it.eventId == eventId }) }
            }
        }
    }

    private fun connectionOrFailure(): Result<ActiveConnection> =
        connectionRepository.activeConnection.value
            ?.let { Result.success(it) }
            ?: Result.failure(notConnected())
}
