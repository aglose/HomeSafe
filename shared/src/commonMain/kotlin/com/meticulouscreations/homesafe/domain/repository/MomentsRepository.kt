package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import kotlinx.coroutines.flow.Flow

/** Exposes Frigate's detections (people, vehicles, animals, ...) for the Moments feed. */
interface MomentsRepository {
    /** Newest first. Empty until connected; re-fetched when the server changes and while observed. */
    fun observeMoments(): Flow<List<MomentEvent>>

    /** Whether the last fetch failed — surfaced so the feed can say so instead of looking empty. */
    fun observeError(): Flow<String?>

    suspend fun refresh()

    /** A playable stream for a detection's clip, authenticated for the current session. */
    suspend fun getClipStream(eventId: String): RecordingStream

    /** A downloadable MP4 URL for a detection's clip (see [com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl]), authenticated for the current session. */
    suspend fun getClipDownloadUrl(eventId: String): RecordingStream
}
