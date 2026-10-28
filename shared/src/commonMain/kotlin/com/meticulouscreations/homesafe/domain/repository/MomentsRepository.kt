package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.text.UiText
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
    fun observeError(): Flow<UiText?>

    /** Where the window sits and whether there is more of it below what's loaded. */
    fun observePaging(): Flow<MomentsPaging>

    /**
     * Reads further down: the detections that started before the oldest one loaded, a page at a
     * time until [observeMoments] has a moment more to show, since a page may hold nothing the
     * feed shows at all. It stops a day below where it started if that day held nothing, leaving
     * [MomentsPaging.hasOlder] up for the next ask. Returns once what it read is in
     * [observeMoments] (or a fetch has failed and [observeError] says so). A no-op while a read
     * down is already in flight, or once [MomentsPaging.hasOlder] is false.
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
     * [cameraName]'s newest [limit] moments, and every other moment that started within the last
     * [lookbackSeconds], placed and folded the way [observeMoments] does it, newest first, but
     * fetched for that camera alone and always from now: nothing the Moments feed is narrowed or
     * scrolled back to changes it. Pages back past detections the zones and folding throw away,
     * within a bound, rather than coming up empty under a parked car's re-detections. Opens on
     * what the device kept, like [observeMoments], then polls while collected. Emits nothing
     * until it has something to say — the cache or an answer — so a first emission, even an
     * empty one, means the camera really has nothing to show.
     */
    fun observeRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double = 0.0): Flow<List<MomentEvent>>

    /**
     * [observeRecentMoments] read once, asking the server now rather than opening on the cache:
     * [RecentMoments.fresh] when the server answered, what the device kept when it didn't, and null
     * when nobody is signed in.
     */
    suspend fun readRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double = 0.0): RecentMoments?

    /**
     * [cameraName]'s moments that started inside [afterEpochSeconds]..[beforeEpochSeconds],
     * placed and folded the way [observeMoments] does it, oldest first. Asks the server for
     * exactly that interval (paged within it, up to a bound) rather than reading down from now,
     * so a window hours or days in the past on a busy camera is reached in one question. Polls
     * while collected, so one still in progress grows its end; emits nothing until the server
     * first answers.
     */
    fun observeMomentsBetween(cameraName: String, afterEpochSeconds: Double, beforeEpochSeconds: Double): Flow<List<MomentEvent>>

    /**
     * Shows the detection [eventId] as the car [subLabel] from now on, in [observeMoments] and
     * [observeRecentMoments]: a person has just named it (see `TagMomentCarUseCase`), and a detection older than the pages the polls
     * re-read would otherwise go on reading "Car" until the app next starts.
     */
    fun nameCar(eventId: String, subLabel: String)

    /**
     * Re-reads the feed's newest page (and the zones) now, rather than at the next poll: the
     * Moments tab's pull to refresh. Returns once that answer has landed or failed; a failure is
     * reported through [observeError] like any poll's, and the feed keeps what it has.
     */
    suspend fun refresh()

    /** A playable stream for a detection's clip, authenticated for the current session. */
    suspend fun getClipStream(eventId: String): RecordingStream

    /** A downloadable MP4 URL for a detection's clip (see [com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl]), authenticated for the current session. */
    suspend fun getClipDownloadUrl(eventId: String): RecordingStream
}

/** One read of a camera's recent moments, and whether the server gave them ([fresh]) or the device's copy did. */
data class RecentMoments(val moments: List<MomentEvent>, val fresh: Boolean)
