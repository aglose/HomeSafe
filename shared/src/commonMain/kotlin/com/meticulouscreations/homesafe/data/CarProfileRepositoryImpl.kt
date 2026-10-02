package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CarProfile
import com.meticulouscreations.homesafe.domain.model.CarProfiles
import com.meticulouscreations.homesafe.domain.repository.CarProfileRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CarProfileRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
) : CarProfileRepository {

    override suspend fun getProfiles(): Result<CarProfiles> =
        serverUrlOrFailure().fold({ relayApi.getCarProfiles(it) }, { Result.failure(it) })

    override suspend fun saveProfile(profile: CarProfile): Result<CarProfile> =
        serverUrlOrFailure().fold({ relayApi.saveCarProfile(it, profile) }, { Result.failure(it) })

    override suspend fun deleteProfile(name: String): Result<Unit> =
        serverUrlOrFailure().fold({ relayApi.deleteCarProfile(it, name) }, { Result.failure(it) })

    private fun serverUrlOrFailure(): Result<String> =
        connectionRepository.currentServerUrl.value
            ?.let { Result.success(it) }
            ?: Result.failure(notConnected())
}
