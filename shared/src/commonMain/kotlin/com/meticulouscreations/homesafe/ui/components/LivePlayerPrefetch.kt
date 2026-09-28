package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext

/** One camera's live source to start before any screen shows it, under the player key the screen will bind with. */
data class LivePrefetch(val playerKey: String, val source: VideoSource.Live)

/**
 * Starts pooled live players ahead of the screens that will show them, so a camera card that
 * binds a moment later finds its player already joining — or already decoding. A prefetched
 * player plays for [LivePlaybackPolicy.PREFETCH_HOLD_MS] as if something were watching it, then
 * falls back to the pool's usual idle rules; a screen that binds it meanwhile keeps it going.
 * See `LiveStreamPrefetcher` for when this is called. Callable from any thread.
 */
interface LivePlayerPrefetch {
    /** Starts [streams], and lets go of any earlier prefetch whose key or source isn't among them. */
    fun prefetch(streams: List<LivePrefetch>)

    /** Lets go of every prefetched player now: whatever they were started for isn't going to happen. */
    fun cancel()
}

/** A platform without a pooled live player has nothing to start early. */
internal object NoLivePlayerPrefetch : LivePlayerPrefetch {
    override fun prefetch(streams: List<LivePrefetch>) = Unit
    override fun cancel() = Unit
}

expect fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch
