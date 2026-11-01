package com.meticulouscreations.homesafe.weather.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import com.meticulouscreations.homesafe.weather.domain.WeatherSoonClaims
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first

/** Asks the relay, by this install's own secret: the check runs in the background, with no session. An install that has none yet has no relay to ask. */
@Inject
@ContributesBinding(AppScope::class)
class RelayWeatherSoonClaims(
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
    private val connectionRepository: ConnectionRepository,
) : WeatherSoonClaims {
    override suspend fun claim(key: String): WeatherSoonClaims.Claim? {
        val secret = identity.secret() ?: return null
        val url = connectionRepository.currentServerUrl.value
            ?: connectionRepository.mostRecentConnection.first()?.serverUrl
            ?: return null
        return relayApi.claimRainSoon(url, identity.deviceId(), secret, key).getOrNull()
            ?.let { WeatherSoonClaims.Claim(it.granted) }
    }
}
