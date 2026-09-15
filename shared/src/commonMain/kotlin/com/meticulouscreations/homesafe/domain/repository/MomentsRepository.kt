package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import kotlinx.coroutines.flow.Flow

/** Exposes Frigate's detections (people, vehicles, animals, ...) for the Moments feed. */
interface MomentsRepository {
    /**
     * Everything loaded so far in the current window (see [observePaging]), newest first: the
     * window's first page, which is re-fetched while observed, followed by every older page
     * [loadOlder] has appended. Empty until connected; starts over when the server or the
     * window changes.
     */
    fun observeMoments(): Flow<List<MomentEvent>>

    /** Whether the last fetch failed — surfaced so the feed can say so instead of looking empty. */
    fun observeError(): Flow<String?>

    /** Where the window sits and whether there is more of it below what's loaded. */
    fun observePaging(): Flow<MomentsPaging>

    /**
     * Appends the next page down: the detections that started before the oldest one loaded.
     * Returns once the page is in [observeMoments] (or the fetch has failed and
     * [observeError] says so). A no-op while a page is already in flight, or once
     * [MomentsPaging.hasOlder] is false.
     */
    suspend fun loadOlder()

    /**
     * Moves the window's top edge: the feed becomes the detections that started before
     * [epochSeconds], newest first, and forgets what it had. Null opens it back up at now.
     */
    fun showBefore(epochSeconds: Double?)

    suspend fun refresh()

    /** A playable stream for a detection's clip, authenticated for the current session. */
    suspend fun getClipStream(eventId: String): RecordingStream

    /** A downloadable MP4 URL for a detection's clip (see [com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl]), authenticated for the current session. */
    suspend fun getClipDownloadUrl(eventId: String): RecordingStream
}
