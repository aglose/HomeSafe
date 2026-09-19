package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import kotlinx.coroutines.flow.Flow

/** Exposes Frigate's detections (people, vehicles, animals, ...) for the Moments feed. */
interface MomentsRepository {
    /**
     * Everything loaded so far in the current window (see [observePaging]), newest first: the
     * window's first page, which is re-fetched while observed, followed by every older page
     * [loadOlder] has appended.
     *
     * A window that has just opened — a launch, another camera, an earlier day — starts as what
     * the device kept from the last time it was shown, so the feed is rarely empty and never
     * empty merely because the server is slow or out of reach; the fetch that follows replaces
     * it. What the feed is filed under is the server's *identity*, not the address it happens to
     * answer at, so a local ↔ remote route flip is not a new feed and doesn't blank this one.
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

    /**
     * Narrows the window to one camera, asked of the server rather than filtered from what's
     * loaded, so a quiet camera's moments aren't pages deep beneath a busy one's. Like
     * [showBefore], the feed forgets what it had and starts over. Null is every camera.
     */
    fun showCamera(cameraName: String?)

    /**
     * [cameraName]'s newest [limit] moments, placed and folded the way [observeMoments] does it,
     * but fetched for that camera alone and always from now: nothing the Moments feed is
     * narrowed or scrolled back to changes it. Opens on what the device kept, like
     * [observeMoments], then polls while collected.
     */
    fun observeRecentMoments(cameraName: String, limit: Int): Flow<List<MomentEvent>>

    /**
     * The household's cars parked in view of any camera right now — only the ones the classifier
     * has named — folded and placed the way [observeMoments] folds a visit but kept even when the
     * app never saw them arrive — see [com.meticulouscreations.homesafe.domain.model.stationaryObjects].
     * Its own poll, from now, across every camera: the feed's window is wherever the Moments tab
     * last left it. Opens on the device's cache of detections like [observeMoments] does, so the
     * strip has something to show before the server answers and while it can't, then polls while
     * collected; each answer replaces the cached list wholesale.
     */
    fun observeStationaryObjects(): Flow<List<StationaryObject>>

    suspend fun refresh()

    /** A playable stream for a detection's clip, authenticated for the current session. */
    suspend fun getClipStream(eventId: String): RecordingStream

    /** A downloadable MP4 URL for a detection's clip (see [com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl]), authenticated for the current session. */
    suspend fun getClipDownloadUrl(eventId: String): RecordingStream
}
