package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.network.frigateEventThumbnailUrl
import com.meticulouscreations.homesafe.network.frigateLiveStreamUrl
import com.meticulouscreations.homesafe.network.frigateRecordingSnapshotUrl
import com.meticulouscreations.homesafe.network.frigateSnapshotUrl
import com.meticulouscreations.homesafe.network.frigateWebRtcSignalingUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Frigate's URL layout, behind the domain's [MediaUrlRepository]. Pure functions of the server address; nothing is fetched here. */
@Inject
@ContributesBinding(AppScope::class)
class FrigateMediaUrlRepository : MediaUrlRepository {

    override fun liveStreamUrl(serverUrl: String, streamName: String, audioCodecs: List<String>): String =
        frigateLiveStreamUrl(serverUrl, streamName, audioCodecs)

    override fun liveWebRtcSignalingUrl(serverUrl: String, streamName: String): String =
        frigateWebRtcSignalingUrl(serverUrl, streamName)

    override fun cameraSnapshotUrl(serverUrl: String, cameraName: String, height: Int?, cacheBuster: Long?): String {
        val url = frigateSnapshotUrl(serverUrl, cameraName, height)
        return if (cacheBuster == null) url else url + (if ('?' in url) "&" else "?") + "t=$cacheBuster"
    }

    override fun eventThumbnailUrl(serverUrl: String, eventId: String): String = frigateEventThumbnailUrl(serverUrl, eventId)

    override fun recordingSnapshotUrl(serverUrl: String, cameraName: String, epochSeconds: Double, height: Int?): String =
        frigateRecordingSnapshotUrl(serverUrl, cameraName, epochSeconds, height)
}
