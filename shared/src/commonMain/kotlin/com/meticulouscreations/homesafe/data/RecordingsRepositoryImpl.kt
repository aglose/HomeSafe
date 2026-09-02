package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingSegment
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.repository.RecordingsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.frigateRecordingStreamUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * No Room cache on purpose: recording history changes every few seconds while a camera records,
 * and the timeline re-fetches its window on a short interval anyway.
 */
@Inject
@SingleIn(AppScope::class)
class RecordingsRepositoryImpl(private val apiClient: FrigateApiClient) : RecordingsRepository {

    override suspend fun getRecordingHistory(
        serverUrl: String,
        cameraName: String,
        afterEpochSeconds: Double,
        beforeEpochSeconds: Double,
    ): Result<RecordingHistory> =
        apiClient.getRecordings(serverUrl, cameraName, afterEpochSeconds, beforeEpochSeconds).map { recordings ->
            RecordingHistory(
                recordings.map { recording ->
                    RecordingSegment(
                        startEpochSeconds = recording.startTime,
                        endEpochSeconds = recording.endTime,
                        durationSeconds = recording.duration ?: (recording.endTime - recording.startTime),
                        motion = recording.motion ?: 0,
                        objects = recording.objects ?: 0,
                    )
                },
            )
        }

    override suspend fun getRecordingStream(
        serverUrl: String,
        cameraName: String,
        playlist: RecordingPlaylist,
    ): RecordingStream = RecordingStream(
        url = frigateRecordingStreamUrl(serverUrl, cameraName, playlist.startEpochSeconds, playlist.endEpochSeconds),
        headers = apiClient.sessionCookieHeader(serverUrl)?.let { mapOf("Cookie" to it) }.orEmpty(),
    )
}
