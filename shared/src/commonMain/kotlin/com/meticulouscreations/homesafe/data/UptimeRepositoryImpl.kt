package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.UptimeRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UptimeRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
) : UptimeRepository {

    override suspend fun getUptime(range: UptimeRange): Result<ServerUptime> =
        connectionRepository.currentServerUrl.value
            ?.let { relayApi.getUptime(it, range.hours) }
            ?: Result.failure(notConnected())
}
