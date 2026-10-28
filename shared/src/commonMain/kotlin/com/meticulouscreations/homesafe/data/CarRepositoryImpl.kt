package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.repository.CarRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
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
 * Mirrors the relay's `/parking`. Like [DoorRepositoryImpl], it reads only while [cars] has a
 * collector (Home's car pills, the cars' map), restarts on every server change, and forgets one
 * server's cars as soon as the app is signed out of it or moves to another.
 *
 * The relay asks Tesla about each car every half hour or so, and answers from what it already
 * has, so reading it more often than every [pollIntervalMs] would only read the same answer.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CarRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val connectionRepository: ConnectionRepository,
    appScope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val retryMs: Long = RETRY_MS,
) : CarRepository {

    private val _cars = MutableStateFlow<List<HouseholdCar>?>(null)
    override val cars: StateFlow<List<HouseholdCar>?> = _cars.asStateFlow()

    /** The server [cars] came from, null while there are none. */
    private var carsFrom: String? = null

    init {
        appScope.launch {
            connectionRepository.currentServerUrl.collect { url ->
                if (url == null || (carsFrom != null && carsFrom != url)) {
                    carsFrom = null
                    _cars.value = null
                }
            }
        }
        appScope.launch {
            combine(
                _cars.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
                connectionRepository.currentServerUrl,
            ) { observed, url -> url.takeIf { observed } }
                .distinctUntilChanged()
                .collectLatest { url ->
                    if (url == null) return@collectLatest
                    while (true) {
                        val answer = fetch(url)
                        delay(if (answer.isFailure) retryMs else pollIntervalMs)
                    }
                }
        }
    }

    override suspend fun refresh(): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        return fetch(url).map { }
    }

    private suspend fun fetch(url: String): Result<List<HouseholdCar>> = relayApi.getCars(url).onSuccess {
        // A refresh isn't cancelled by a server switch: an answer from the old server is dropped.
        if (connectionRepository.currentServerUrl.value == url) {
            carsFrom = url
            _cars.value = it
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 120_000L
        const val RETRY_MS = 30_000L
    }
}
