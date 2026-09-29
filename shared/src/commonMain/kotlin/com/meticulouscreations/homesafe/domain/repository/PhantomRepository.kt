package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import kotlinx.coroutines.flow.Flow

/**
 * The phantom people the push relay keeps (see [PhantomSpot]): where each camera's detector sees
 * someone who isn't there, and the detections marked "Not a person" that taught it.
 */
interface PhantomRepository {
    /** The current server's spots, as last heard from its relay plus any marked since; empty until then. */
    val spots: Flow<List<PhantomSpot>>

    /** Asks the relay for its spots again. */
    suspend fun refresh(): Result<Unit>

    /** Marks [eventId], a person detection, not a person; its spot joins [spots] at once. */
    suspend fun markNotAPerson(eventId: String): Result<PhantomSpot>

    /** Takes a [markNotAPerson] back. */
    suspend fun undoNotAPerson(eventId: String): Result<Unit>
}
