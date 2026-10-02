package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [MomentsRepository.observeError]: why the last fetch of the feed failed, or null. */
@Inject
class ObserveMomentsErrorUseCase(private val momentsRepository: MomentsRepository) {
    operator fun invoke(): Flow<UiText?> = momentsRepository.observeError()
}
