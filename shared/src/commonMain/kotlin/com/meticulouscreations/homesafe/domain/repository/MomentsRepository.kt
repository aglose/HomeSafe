package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import kotlinx.coroutines.flow.Flow

/** Exposes detected events (people, vehicles, animals, etc.) for the Moments feed. */
interface MomentsRepository {
    fun observeMoments(): Flow<List<MomentEvent>>
}
