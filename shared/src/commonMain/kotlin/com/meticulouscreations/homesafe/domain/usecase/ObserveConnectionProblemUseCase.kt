package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.TailnetProbe
import com.meticulouscreations.homesafe.network.isTailnetUrl
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * What to tell someone whose app is signed in but has no server: null while the server answers,
 * otherwise why it doesn't, as far as the device can tell. On 2026-10-04 the app opened on its
 * kept cameras with nothing playing and nothing said, twice, because Tailscale was off on the
 * phone; the server was fine both times. So an unreachable server is checked against the
 * device's own tailnet address first ([TailnetProbe]).
 *
 * Tailscale can be switched on or off without the connection noticing, so while the server is
 * unreachable the device is asked again every [RECHECK_MS].
 */
@Inject
class ObserveConnectionProblemUseCase(
    private val connectionRepository: ConnectionRepository,
    private val tailnetProbe: TailnetProbe,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ConnectionProblem?> =
        combine(connectionRepository.serverUnreachable, connectionRepository.activeConnection) { unreachable, connection ->
            connection?.serverUrl?.takeIf { unreachable }
        }.transformLatest { serverUrl ->
            if (serverUrl == null) {
                emit(null)
                return@transformLatest
            }
            while (true) {
                emit(problemFor(serverUrl))
                delay(RECHECK_MS)
            }
        }.distinctUntilChanged()

    /** Why a sign-in to [serverUrl] that nothing answered failed, when Tailscale being off here explains it; null otherwise. */
    fun explainUnanswered(serverUrl: String): ConnectionProblem? =
        problemFor(serverUrl).takeIf { it == ConnectionProblem.TailscaleOff }

    private fun problemFor(serverUrl: String): ConnectionProblem =
        if (isTailnetUrl(serverUrl) && tailnetProbe.isOnTailnet() == false) ConnectionProblem.TailscaleOff else ConnectionProblem.ServerUnreachable

    private companion object {
        const val RECHECK_MS = 5_000L
    }
}
