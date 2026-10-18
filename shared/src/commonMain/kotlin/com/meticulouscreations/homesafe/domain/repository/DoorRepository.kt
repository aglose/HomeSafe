package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import kotlinx.coroutines.flow.StateFlow

/** The door sensors, as the relay keeps them (see docs/doors.md). */
interface DoorRepository {
    /** The latest snapshot; [DoorsSnapshot.EMPTY] until the relay has answered. Re-read every so often while something collects it. */
    val doors: StateFlow<DoorsSnapshot>

    /** Re-reads the doors now rather than waiting for the next poll. */
    suspend fun refresh(): Result<Unit>

    /** Sets when [doorId]'s openings push, for the whole household; [doors] reflects the relay's answer. */
    suspend fun setNotify(doorId: String, notify: DoorNotify): Result<Unit>
}
