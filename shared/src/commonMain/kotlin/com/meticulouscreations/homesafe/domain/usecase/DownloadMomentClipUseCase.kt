package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.data.ClipDownloader
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import dev.zacsweers.metro.Inject

@Inject
class DownloadMomentClipUseCase(
    private val momentsRepository: MomentsRepository,
    private val clipDownloader: ClipDownloader,
) {
    suspend operator fun invoke(eventId: String, fileName: String): Result<Unit> {
        val stream = momentsRepository.getClipDownloadUrl(eventId)
        return clipDownloader.download(stream.url, stream.headers, fileName)
    }
}
