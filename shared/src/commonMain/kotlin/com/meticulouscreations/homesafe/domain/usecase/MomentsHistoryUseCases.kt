package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [MomentsRepository.observePaging]: where the feed's window sits and whether more lies below it. */
@Inject
class ObserveMomentsPagingUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(): Flow<MomentsPaging> = momentsRepository.observePaging()
}

/** See [MomentsRepository.loadOlder]: the next page of detections below what the feed has. */
@Inject
class LoadOlderMomentsUseCase(private val momentsRepository: MomentsRepository) {
    suspend operator fun invoke() = momentsRepository.loadOlder()
}

/** See [MomentsRepository.showBefore]: opens the feed at an earlier instant, or back at now with null. */
@Inject
class ShowMomentsBeforeUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(epochSeconds: Double?) = momentsRepository.showBefore(epochSeconds)
}
