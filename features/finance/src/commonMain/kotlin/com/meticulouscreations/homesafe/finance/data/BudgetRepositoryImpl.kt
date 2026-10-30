package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.AmazonItem
import com.meticulouscreations.homesafe.finance.domain.AmazonNeed
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.finance.domain.LinePick
import com.meticulouscreations.homesafe.network.isTransportFailure
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_not_connected

/**
 * The budget through the relay, with every month it answers kept on the device ([FinanceSaved]).
 * A month is read back from there when the server can't be asked, if [FinanceAccess] allows the
 * household's spending to be shown without it. Changes always need the server: without it they
 * fail and the month on screen stays as it was.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BudgetRepositoryImpl(
    private val relay: BudgetRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val saved: FinanceSaved,
    private val access: FinanceAccess,
) : BudgetRepository {

    override suspend fun budget(month: String?): Result<Budget> {
        // Nobody to vouch for whoever is holding the phone, and they haven't shown it's theirs.
        if (access.locked.value) return Result.failure(signedOut())
        val serverUrl = connectionRepository.currentServerUrl.value
        // Known to be out of reach: what the device kept, rather than waiting on a request nothing will answer.
        if (serverUrl == null || !access.online.value) kept(month)?.let { return Result.success(it) }
        if (serverUrl == null) return Result.failure(signedOut())
        val result = relay.budget(serverUrl, month).keeping()
        val e = result.exceptionOrNull() ?: return result
        if (!e.isTransportFailure()) return result
        // Nothing answered. If the server is gone, that locks the app before the kept month is reached for.
        access.serverUnanswered()
        if (access.locked.value) return Result.failure(signedOut())
        return kept(month)?.let { Result.success(it) } ?: result
    }

    override suspend fun save(patch: BudgetConfigPatch): Result<Budget> = change { relay.save(it, patch) }

    override suspend fun tag(purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget> = change { relay.tag(it, purchaseId, bucket, remember) }

    override suspend fun forgetRule(merchantKey: String): Result<Budget> = change { relay.forgetRule(it, merchantKey) }

    override suspend fun tagLine(purchaseId: String, line: LinePick, remember: Boolean): Result<Budget> = change { relay.tagLine(it, purchaseId, line, remember) }

    override suspend fun forgetLineRule(merchantKey: String): Result<Budget> = change { relay.forgetLineRule(it, merchantKey) }

    override suspend fun sayNeed(orderId: String, item: AmazonItem, need: AmazonNeed?): Result<Budget> = change { relay.sayNeed(it, orderId, item, need) }

    override suspend fun syncNow(): Result<Budget> = change { relay.syncNow(it) }

    /**
     * A change, which only the server can make. With none signed in to, it fails as a request that
     * never left: not as a refusal, which would take the month off the screen and stop the sheet's
     * figures being reported again once the server is back.
     */
    private suspend fun change(call: suspend (String) -> Result<Budget>): Result<Budget> {
        val serverUrl = connectionRepository.currentServerUrl.value
        // Known to be out of reach counts as none: the request would only sit out its timeout.
        if (serverUrl == null || !access.online.value) {
            return Result.failure(LocalizedException(UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        }
        return call(serverUrl).keeping()
    }

    /** Keeps an answered month for later; drops everything kept when the answer is the server refusing this account. */
    private suspend fun Result<Budget>.keeping(): Result<Budget> {
        val owner = owner()
        if (owner != null) {
            onSuccess { budget ->
                saved.writePrivate(owner, FinanceSaved.budget(budget.month), Budget.serializer(), budget)
                if (budget.isCurrentMonth) saved.writePrivate(owner, FinanceSaved.budget(null), Budget.serializer(), budget)
            }
        }
        val problem = (exceptionOrNull() as? BankSyncException)?.problem
        if (problem == BankProblem.SIGNED_OUT || problem == BankProblem.NOT_ALLOWED) saved.forgetPrivate()
        return this
    }

    /** A month as the device kept it. Never as "reading the cards now": that was true when it was kept, and nothing is following it. */
    private suspend fun kept(month: String?): Budget? = saved.readPrivate(owner(), FinanceSaved.budget(month), Budget.serializer())?.copy(syncing = false)

    /** The server signed in to, by the address that names it whichever route is in use; null with none. */
    private fun owner(): String? = connectionRepository.activeConnection.value?.serverUrl

    private fun signedOut() = BankSyncException(BankProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected")
}
