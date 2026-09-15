package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.MomentEvent
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

/** See [MomentsRepository.showCamera]: narrows the feed to one camera on the server, or back to every camera with null. */
@Inject
class ShowMomentsFromCameraUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(cameraName: String?) = momentsRepository.showCamera(cameraName)
}

/** See [MomentsRepository.observeRecentMoments]: one camera's newest moments, independent of the Moments feed's window. */
@Inject
class ObserveRecentCameraMomentsUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(cameraName: String, limit: Int): Flow<List<MomentEvent>> = momentsRepository.observeRecentMoments(cameraName, limit)
}
