package com.meticulouscreations.homesafe.finance.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Which lines being crossed the phones are told about. */
@Immutable
@Serializable
data class BudgetAlertSwitches(val total: Boolean = false, val savings: Boolean = false, val buckets: Boolean = false, val lines: Boolean = false)

/**
 * The budget's alert switches, for Settings → Notifications; the finance app's budget settings
 * change the same ones. They are the household's, kept by the relay, so both calls go there.
 */
interface BudgetAlertPreferences {
    /** The switches as the relay has them; null when there is no budget for them to be about, or it isn't this account's to see. */
    suspend fun read(): Result<BudgetAlertSwitches?>

    /** Saves [alerts] and answers with the switches as the relay now has them. */
    suspend fun save(alerts: BudgetAlertSwitches): Result<BudgetAlertSwitches>
}
