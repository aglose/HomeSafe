package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import kotlinx.coroutines.flow.StateFlow

/** Household presence ("away mode") as kept by the push relay, shared between the two phones. */
interface PresenceRepository {
    /**
     * The latest snapshot; [HouseholdPresence.EMPTY] until the relay has answered. Re-read on
     * every server change and about once a minute while something is collecting it.
     */
    val presence: StateFlow<HouseholdPresence>

    /** Re-reads presence now rather than waiting for the next poll. */
    suspend fun refresh(): Result<Unit>

    /** Tells the relay this phone's owner has left (or is back); [presence] reflects the answer. */
    suspend fun setThisDeviceAway(away: Boolean): Result<Unit>
}
