package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertPreferences
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The budget's alert switches through [BudgetRepository], for Settings → Notifications. A relay
 * with no budget (no Plaid keys, or older than budgets) and an account that may not see the
 * household's money both read as no switches at all: there is nothing there for Settings to offer.
 */
@Inject
@ContributesBinding(AppScope::class)
class BudgetAlertPreferencesImpl(private val budgets: BudgetRepository) : BudgetAlertPreferences {
    override suspend fun read(): Result<BudgetAlertSwitches?> {
        val result = budgets.budget()
        val problem = (result.exceptionOrNull() as? BankSyncException)?.problem
        if (problem in NOTHING_TO_OFFER) return Result.success(null)
        return result.map { budget -> budget.config.alerts.takeIf { budget.configured } }
    }

    override suspend fun save(alerts: BudgetAlertSwitches): Result<BudgetAlertSwitches> =
        budgets.save(BudgetConfigPatch(alerts = alerts)).map { it.config.alerts }

    private companion object {
        val NOTHING_TO_OFFER = setOf(BankProblem.NOT_ALLOWED, BankProblem.NOT_CONFIGURED, BankProblem.RELAY_OUTDATED)
    }
}
