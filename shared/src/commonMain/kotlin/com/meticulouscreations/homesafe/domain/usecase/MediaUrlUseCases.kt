package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import dev.zacsweers.metro.Inject

/** See [MediaUrlRepository.liveStreamUrl]. */
@Inject
class GetLiveStreamUrlUseCase(private val mediaUrls: MediaUrlRepository) {
    operator fun invoke(serverUrl: String, streamName: String, audioCodecs: List<String> = emptyList()): String =
        mediaUrls.liveStreamUrl(serverUrl, streamName, audioCodecs)
}

/** See [MediaUrlRepository.liveWebRtcSignalingUrl]. */
@Inject
class GetLiveWebRtcSignalingUrlUseCase(private val mediaUrls: MediaUrlRepository) {
    operator fun invoke(serverUrl: String, streamName: String): String = mediaUrls.liveWebRtcSignalingUrl(serverUrl, streamName)
}

/** See [MediaUrlRepository.cameraSnapshotUrl]. */
@Inject
class GetCameraSnapshotUrlUseCase(private val mediaUrls: MediaUrlRepository) {
    operator fun invoke(serverUrl: String, cameraName: String, height: Int? = null, cacheBuster: Long? = null): String =
        mediaUrls.cameraSnapshotUrl(serverUrl, cameraName, height, cacheBuster)
}

/** See [MediaUrlRepository.eventThumbnailUrl]. */
@Inject
class GetEventThumbnailUrlUseCase(private val mediaUrls: MediaUrlRepository) {
    operator fun invoke(serverUrl: String, eventId: String): String = mediaUrls.eventThumbnailUrl(serverUrl, eventId)
}

/** See [MediaUrlRepository.recordingSnapshotUrl]. */
@Inject
class GetRecordingSnapshotUrlUseCase(private val mediaUrls: MediaUrlRepository) {
    operator fun invoke(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int? = null): String =
        mediaUrls.recordingSnapshotUrl(serverUrl, cameraName, epochSeconds, height)
}
