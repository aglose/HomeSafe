package com.meticulouscreations.homesafe.ui.components

import com.meticulouscreations.homesafe.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /**
     * Gets [count] silent WebRTC offers ready — peer created, ICE gathered — before any stream is
     * asked for, so the joins [prefetch] starts can go straight to signaling. Nothing is sent
     * anywhere; unused offers are thrown away after [LivePlaybackPolicy.PREPARED_OFFER_TTL_MS].
     */
    fun prepare(count: Int)

    /** Starts [streams], and lets go of any earlier prefetch whose key or source isn't among them. */
    fun prefetch(streams: List<LivePrefetch>)

    /** Lets go of every prefetched player now: whatever they were started for isn't going to happen. */
    fun cancel()
}

/** A platform without a pooled live player has nothing to start early. */
internal object NoLivePlayerPrefetch : LivePlayerPrefetch {
    override fun prepare(count: Int) = Unit
    override fun prefetch(streams: List<LivePrefetch>) = Unit
    override fun cancel() = Unit
}

expect fun createLivePlayerPrefetch(context: PlatformContext): LivePlayerPrefetch

/**
 * The bookkeeping behind a pool's [LivePlayerPrefetch.prefetch], the same on every platform: each
 * prefetched key holds one pool reference and one "watching" binder on its holder until
 * [LivePlaybackPolicy.PREFETCH_HOLD_MS] passes or it is let go, whichever comes first. [start]
 * acquires a holder, counts it watched and loads the source; [stop] undoes exactly that. Main
 * thread only, like the pools.
 */
internal class LivePrefetchLeases<H : Any>(
    private val scope: CoroutineScope,
    private val start: (LivePrefetch) -> H,
    private val stop: (holder: H) -> Unit,
) {
    private class Lease<H>(val holder: H, val url: String, var expiry: Job? = null)

    private val leases = HashMap<String, Lease<H>>()

    fun prefetch(streams: List<LivePrefetch>) {
        val wanted = streams.associateBy { it.playerKey }
        leases.entries.filter { (key, lease) -> wanted[key]?.source?.url != lease.url }.map { it.key }.forEach(::end)
        for (stream in streams) {
            if (stream.playerKey in leases) continue
            val lease = Lease(start(stream), stream.source.url)
            leases[stream.playerKey] = lease
            lease.expiry = scope.launch {
                delay(LivePlaybackPolicy.PREFETCH_HOLD_MS)
                end(stream.playerKey)
            }
        }
    }

    fun cancel() {
        leases.keys.toList().forEach(::end)
    }

    private fun end(key: String) {
        val lease = leases.remove(key) ?: return
        lease.expiry?.cancel()
        stop(lease.holder)
    }
}
