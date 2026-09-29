package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PhantomRepository
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PhantomRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
) : PhantomRepository {

    /** Kept per server address, so another server's spots never hide this one's people. */
    private val byServer = MutableStateFlow<Map<String, List<PhantomSpot>>>(emptyMap())

    override val spots: Flow<List<PhantomSpot>> =
        combine(connectionRepository.currentServerUrl, byServer) { url, known -> url?.let { known[it] }.orEmpty() }

    override suspend fun refresh(): Result<Unit> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return relayApi.getPhantomSpots(serverUrl).map { spots -> byServer.update { it + (serverUrl to spots) } }
    }

    override suspend fun markNotAPerson(eventId: String): Result<PhantomSpot> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return relayApi.markNotAPerson(serverUrl, eventId).onSuccess { spot ->
            byServer.update { known -> known + (serverUrl to known[serverUrl].orEmpty().filterNot { it.eventId == eventId } + spot) }
        }
    }

    override suspend fun undoNotAPerson(eventId: String): Result<Unit> {
        val serverUrl = serverUrlOrFailure().getOrElse { return Result.failure(it) }
        return relayApi.undoNotAPerson(serverUrl, eventId).onSuccess {
            byServer.update { known -> known + (serverUrl to known[serverUrl].orEmpty().filterNot { it.eventId == eventId }) }
        }
    }

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(FrigateResponseException("Not connected to a server"))
}
