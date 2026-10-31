package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BankTransactionQuery
import com.meticulouscreations.homesafe.finance.domain.BankTransactions
import com.meticulouscreations.homesafe.finance.domain.BankTransactionsRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.network.isTransportFailure
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_not_connected

/**
 * The transactions through the relay, and only from it. Unlike a month of the budget, nothing is
 * kept on the device: this is more than the app shows anyone, read only when the assistant asks.
 */
@Inject
@ContributesBinding(AppScope::class)
class BankTransactionsRepositoryImpl(
    private val relay: BudgetRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val access: FinanceAccess,
) : BankTransactionsRepository {

    override suspend fun transactions(query: BankTransactionQuery): Result<BankTransactions> {
        val serverUrl = connectionRepository.currentServerUrl.value
        if (access.locked.value || serverUrl == null || !access.online.value) {
            return Result.failure(BankSyncException(BankProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        }
        val result = relay.transactions(serverUrl, query)
        if (result.exceptionOrNull()?.isTransportFailure() == true) access.serverUnanswered()
        return result
    }
}
