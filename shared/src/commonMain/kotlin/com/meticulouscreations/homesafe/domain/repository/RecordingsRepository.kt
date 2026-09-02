package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.RecordingHistory
import com.meticulouscreations.homesafe.domain.model.RecordingPlaylist
import com.meticulouscreations.homesafe.domain.model.RecordingStream

/** Exposes a camera's recorded history (the DVR window behind its live stream). */
interface RecordingsRepository {

    /** Everything [cameraName] recorded that overlaps [afterEpochSeconds]..[beforeEpochSeconds]. */
    suspend fun getRecordingHistory(
        serverUrl: String,
        cameraName: String,
        afterEpochSeconds: Double,
        beforeEpochSeconds: Double,
    ): Result<RecordingHistory>

    /** A playable stream for [playlist], authenticated for the current session. */
    suspend fun getRecordingStream(
        serverUrl: String,
        cameraName: String,
        playlist: RecordingPlaylist,
    ): RecordingStream
}
