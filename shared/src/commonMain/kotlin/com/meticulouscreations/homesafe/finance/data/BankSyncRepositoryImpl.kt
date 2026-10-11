package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BankLinkKind
import com.meticulouscreations.homesafe.finance.domain.BankLinkProgress
import com.meticulouscreations.homesafe.finance.domain.BankLinkStart
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSync
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BankSyncRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
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

/**
 * The bank sync through the relay, with the institutions and balances it last reported kept on the
 * device ([FinanceSaved]): the page is read back from there when the server can't be asked, if
 * [FinanceAccess] allows the household's balances to be shown without it. Linking, syncing,
 * unlinking and changing what is written to the sheet always need the server.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BankSyncRepositoryImpl(
    private val relay: BankSyncRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val tailnetProbe: TailnetProbe,
    private val saved: FinanceSaved,
    private val access: FinanceAccess,
) : BankSyncRepository {

    override suspend fun status(): Result<BankSync> {
        // Nobody to vouch for whoever is holding the phone, and they haven't shown it's theirs.
        if (access.locked.value) return Result.failure(signedOut())
        val serverUrl = connectionRepository.currentServerUrl.value
        // Known to be out of reach: what the device kept, rather than waiting on a request nothing will answer.
        if (serverUrl == null || !access.online.value) kept()?.let { return Result.success(it) }
        if (serverUrl == null) return Result.failure(signedOut())
        val result = onServer { relay.status(it) }.keeping()
        val problem = (result.exceptionOrNull() as? BankSyncException)?.problem ?: return result
        if (problem != BankProblem.TAILSCALE_OFF && problem != BankProblem.UNREACHABLE) return result
        // Nothing answered. If the server is gone, that locks the app before the kept balances are reached for.
        access.serverUnanswered()
        if (access.locked.value) return Result.failure(signedOut())
        return kept()?.let { Result.success(it) } ?: result
    }

    override suspend fun startLink(kind: BankLinkKind): Result<BankLinkStart> = onServer { relay.startLink(it, kind.wire) }

    // The relay ignores the kind when it's told which institution to sign in to again.
    override suspend fun startRelink(institutionId: String): Result<BankLinkStart> = onServer { relay.startLink(it, BankLinkKind.BANK.wire, institutionId) }

    override suspend fun linkProgress(token: String): Result<BankLinkProgress> =
        onServer { relay.linkProgress(it, token) }.onSuccess { progress -> progress.bank?.let { Result.success(it).keeping() } }

    override suspend fun syncNow(): Result<BankSync> = onServer { relay.syncNow(it) }.keeping()

    override suspend fun unlink(institutionId: String): Result<BankSync> = onServer { relay.unlink(it, institutionId) }.keeping()

    override suspend fun undoWrite(writeId: Long): Result<BankSync> = onServer { relay.undoWrite(it, writeId) }.keeping()

    override suspend fun setLinePaused(lineId: String, paused: Boolean): Result<BankSync> = onServer { relay.setLinePaused(it, lineId, paused) }.keeping()

    /**
     * With no server signed in to, a request that never left: not a refusal, which would take the
     * balances the device kept off the screen.
     */
    private suspend fun <T> onServer(call: suspend (String) -> Result<T>): Result<T> {
        val serverUrl = connectionRepository.currentServerUrl.value
            ?: return Result.failure(BankSyncException(BankProblem.UNREACHABLE, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        // Known to be out of reach: said at once, as nothing answering would be, rather than after a timeout.
        if (!access.online.value) return Result.failure(unanswered(serverUrl, technical = "Known to be unreachable"))
        val result = call(serverUrl)
        val e = result.exceptionOrNull()
        if (e == null || !e.isTransportFailure()) return result
        return Result.failure(unanswered(serverUrl, e.message))
    }

    /** Keeps what the relay reported for later; drops everything kept when the answer is the server refusing this account. */
    private suspend fun Result<BankSync>.keeping(): Result<BankSync> {
        val owner = owner()
        if (owner != null) onSuccess { saved.writePrivate(owner, FinanceSaved.BANK, BankSync.serializer(), it) }
        val problem = (exceptionOrNull() as? BankSyncException)?.problem
        if (problem == BankProblem.SIGNED_OUT || problem == BankProblem.NOT_ALLOWED) saved.forgetPrivate()
        return this
    }

    /** The banks as the device kept them. Never as "reading them now": that was true when it was kept, and nothing is following it. */
    private suspend fun kept(): BankSync? = saved.readPrivate(owner(), FinanceSaved.BANK, BankSync.serializer())?.copy(syncing = false)

    /** The server signed in to, by the address that names it whichever route is in use; null with none. */
    private fun owner(): String? = connectionRepository.activeConnection.value?.serverUrl

    private fun signedOut() = BankSyncException(BankProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected")

    /**
     * Nothing answered [serverUrl]. The platform's words for that ("Unable to resolve host …:
     * No address associated with hostname") aren't the app's and don't say what to do. Most often the
     * server is a tailnet name and this device is off the tailnet: Tailscale is off, or another VPN
     * took its place while a bank was signed in to.
     */
    private fun unanswered(serverUrl: String, technical: String?): BankSyncException =
        if (isTailnetUrl(serverUrl) && tailnetProbe.isOnTailnet() == false) {
            BankSyncException(BankProblem.TAILSCALE_OFF, UiText.of(Res.string.fin_bank_error_tailscale_off), technical = technical)
        } else {
            BankSyncException(BankProblem.UNREACHABLE, UiText.of(Res.string.fin_bank_error_generic), technical = technical)
        }
}
