package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import kotlinx.coroutines.flow.StateFlow

/** The door sensors, as the relay keeps them (see docs/doors.md). */
interface DoorRepository {
    /** The latest snapshot; [DoorsSnapshot.EMPTY] until the relay has answered. Re-read every so often while something collects it. */
    val doors: StateFlow<DoorsSnapshot>

    /** Re-reads the doors now rather than waiting for the next poll. */
    suspend fun refresh(): Result<Unit>

    /** Sets when [doorId]'s openings push, for the whole household; [doors] reflects the relay's answer. */
    suspend fun setNotify(doorId: String, notify: DoorNotify): Result<Unit>

    /** The house's floor plan, null while there is none (or it hasn't been read yet). Read once per server, and again on [refresh]. */
    val floorPlan: StateFlow<FloorPlan?>

    /** Places [doorId] on the floor plan, or with null takes it off; shown at once, like [setNotify]. */
    suspend fun setSpot(doorId: String, spot: DoorSpot?): Result<Unit>
}
