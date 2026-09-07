package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceSource
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

    /**
     * Tells the relay this phone's owner has left (or is back); [presence] reflects the answer.
     * With [dwellSeconds] > 0 an `away` is only *armed*: the relay marks the phone away once the
     * dwell has passed unless something says home first (a re-entry, the LAN, the switch).
     * Works without a signed-in session — this install's relay secret authenticates it — so a
     * geofence crossing can report from a cold background wake.
     */
    suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource = PresenceSource.MANUAL, dwellSeconds: Int = 0): Result<Unit>

    /** Sets (or, with null, clears) the household's home; every phone's geofence follows [presence]. */
    suspend fun setHome(home: HomeLocation?): Result<Unit>
}
