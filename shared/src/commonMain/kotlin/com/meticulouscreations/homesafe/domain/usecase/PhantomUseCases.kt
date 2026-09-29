package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.PhantomSpot
import com.meticulouscreations.homesafe.domain.repository.PhantomRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** Where each camera sees people who aren't there (see [PhantomSpot]). */
@Inject
class ObservePhantomSpotsUseCase(private val repository: PhantomRepository) {
    operator fun invoke(): Flow<List<PhantomSpot>> = repository.spots
}

/** Asks the relay for its phantom spots again. */
@Inject
class RefreshPhantomSpotsUseCase(private val repository: PhantomRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.refresh()
}

/**
 * Marks a person detection "Not a person": the relay stops pushing the same thing at the same spot,
 * the feed hides it, and the detection becomes a `none` example for the person classifier.
 */
@Inject
class MarkNotAPersonUseCase(private val repository: PhantomRepository) {
    suspend operator fun invoke(eventId: String): Result<PhantomSpot> = repository.markNotAPerson(eventId)
}

/** Takes a "Not a person" back. */
@Inject
class UndoNotAPersonUseCase(private val repository: PhantomRepository) {
    suspend operator fun invoke(eventId: String): Result<Unit> = repository.undoNotAPerson(eventId)
}
