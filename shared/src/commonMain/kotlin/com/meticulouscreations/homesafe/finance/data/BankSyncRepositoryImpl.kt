package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankLinkProgress
import com.meticulouscreations.homesafe.finance.domain.BankLinkStart
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BankSyncRepository
import com.meticulouscreations.homesafe.network.TailnetProbe
import com.meticulouscreations.homesafe.network.isTailnetUrl
import com.meticulouscreations.homesafe.network.isTransportFailure
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_generic
import homesafe.shared.generated.resources.fin_bank_error_tailscale_off
import homesafe.shared.generated.resources.fin_data_error_not_connected

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BankSyncRepositoryImpl(
    private val relay: BankSyncRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val tailnetProbe: TailnetProbe,
) : BankSyncRepository {

    override suspend fun status(): Result<BankSync> = onServer { relay.status(it) }

    override suspend fun startLink(kind: BankLinkKind): Result<BankLinkStart> = onServer { relay.startLink(it, kind.wire) }

    // The relay ignores the kind when it's told which institution to sign in to again.
    override suspend fun startRelink(institutionId: String): Result<BankLinkStart> = onServer { relay.startLink(it, BankLinkKind.BANK.wire, institutionId) }

    override suspend fun linkProgress(token: String): Result<BankLinkProgress> = onServer { relay.linkProgress(it, token) }

    override suspend fun syncNow(): Result<BankSync> = onServer { relay.syncNow(it) }

    override suspend fun unlink(institutionId: String): Result<BankSync> = onServer { relay.unlink(it, institutionId) }

    private suspend fun <T> onServer(call: suspend (String) -> Result<T>): Result<T> {
        val serverUrl = connectionRepository.currentServerUrl.value
            ?: return Result.failure(BankSyncException(BankProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        val result = call(serverUrl)
        val e = result.exceptionOrNull()
        if (e == null || !e.isTransportFailure()) return result
        return Result.failure(unanswered(serverUrl, e))
    }

    /**
     * Nothing answered [serverUrl]. The platform's words for that ("Unable to resolve host …:
     * No address associated with hostname") aren't the app's and don't say what to do. Most often the
     * server is a tailnet name and this device is off the tailnet: Tailscale is off, or another VPN
     * took its place while a bank was signed in to.
     */
    private fun unanswered(serverUrl: String, e: Throwable): BankSyncException =
        if (isTailnetUrl(serverUrl) && tailnetProbe.isOnTailnet() == false) {
            BankSyncException(BankProblem.TAILSCALE_OFF, UiText.of(Res.string.fin_bank_error_tailscale_off), technical = e.message)
        } else {
            BankSyncException(BankProblem.UNREACHABLE, UiText.of(Res.string.fin_bank_error_generic), technical = e.message)
        }
}
