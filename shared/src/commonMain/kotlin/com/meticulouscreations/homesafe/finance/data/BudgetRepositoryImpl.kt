package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.Budget
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_not_connected

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class BudgetRepositoryImpl(
    private val relay: BudgetRelayApi,
    private val connectionRepository: ConnectionRepository,
) : BudgetRepository {

    override suspend fun budget(month: String?): Result<Budget> = onServer { relay.budget(it, month) }

    override suspend fun save(patch: BudgetConfigPatch): Result<Budget> = onServer { relay.save(it, patch) }

    override suspend fun tag(purchaseId: String, bucket: BucketId, remember: Boolean): Result<Budget> = onServer { relay.tag(it, purchaseId, bucket, remember) }

    override suspend fun forgetRule(merchantKey: String): Result<Budget> = onServer { relay.forgetRule(it, merchantKey) }

    override suspend fun syncNow(): Result<Budget> = onServer { relay.syncNow(it) }

    private suspend fun <T> onServer(call: suspend (String) -> Result<T>): Result<T> {
        val serverUrl = connectionRepository.currentServerUrl.value
            ?: return Result.failure(BankSyncException(BankProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        return call(serverUrl)
    }
}
