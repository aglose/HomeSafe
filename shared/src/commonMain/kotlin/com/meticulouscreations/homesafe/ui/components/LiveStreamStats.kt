package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.roundToInt

/** How often a player that was asked for [LiveStreamStats] reports them. */
internal const val LIVE_STATS_INTERVAL_MS = 1_000L

/**
 * What a live stream is doing right now, for the camera screen's "stats for nerds" panel. Every
 * figure is null when the engine playing the stream can't say.
 *
 * [delayMs] is how old the frame on screen is by the time it is shown, counted from the moment
 * go2rtc had it: the server is the earliest point this app can see. What the camera spends
 * encoding the frame and getting it to the server comes before that and is not in the figure.
 * How it is arrived at depends on [transport]:
 *
 *  - **WebRTC**: [networkMs] (half the connection's round trip) plus what the receiver holds a
 *    frame for between its first packet arriving and it being decoded: [bufferMs] in the jitter
 *    buffer, [decodeMs] in the decoder.
 *  - **HLS**: how far the playhead is behind the newest video the server had when the player last
 *    asked it for some (see [LiveEdgeTracker]). [bufferMs] is the part of that already downloaded
 *    and waiting to play; [networkMs] and [decodeMs] are not known.
 */
@Immutable
data class LiveStreamStats(
    val transport: LiveTransport,
    val delayMs: Int? = null,
    val networkMs: Int? = null,
    val bufferMs: Int? = null,
    val decodeMs: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val framesPerSecond: Int? = null,
    val bitrateKbps: Int? = null,
    /** The video codec as the engine names it ("H264", "video/avc"); see [codecDisplayName]. */
    val codec: String? = null,
    val droppedFrames: Long? = null,
    /** WebRTC only: packets the network lost on the way. */
    val packetsLost: Long? = null,
)

/** A video codec under the name people know it by, whatever the engine called it. */
fun codecDisplayName(codec: String): String {
    val name = codec.substringAfter('/').substringBefore('.').lowercase()
    return when {
        name == "h264" || name == "avc" || name.startsWith("avc1") || name.startsWith("avc3") -> "H.264"
        name == "h265" || name == "hevc" || name.startsWith("hvc1") || name.startsWith("hev1") -> "H.265"
        else -> name.uppercase()
    }
}

/** One entry of a WebRTC `getStats` report, as either platform's engine hands it over. */
class RtcStat(val id: String, val type: String, val members: Map<String, Any?>) {
    fun number(name: String): Double? = (members[name] as? Number)?.toDouble()

    fun text(name: String): String? = members[name] as? String

    /** Booleans arrive as numbers from engines whose reports are dictionaries of `NSNumber`. */
    fun flag(name: String): Boolean = when (val value = members[name]) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        else -> false
    }
}

/**
 * The receiving side of a peer connection at one instant: mostly running totals since the
 * connection began, which is how WebRTC reports them. [WebRtcStatsMeter] turns two of these into
 * rates and per-frame averages.
 */
data class WebRtcReceiverStats(
    val timestampMs: Double,
    val roundTripTimeMs: Double? = null,
    val jitterBufferDelayMs: Double? = null,
    val jitterBufferEmittedCount: Double? = null,
    val totalProcessingDelayMs: Double? = null,
    val totalDecodeTimeMs: Double? = null,
    val framesDecoded: Double? = null,
    val bytesReceived: Double? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val framesPerSecond: Double? = null,
    val framesDropped: Long? = null,
    val packetsLost: Long? = null,
    val codec: String? = null,
)

/**
 * Picks the incoming video stream, and the candidate pair carrying it, out of a `getStats` report
 * taken at [timestampMs]. Null when the report has no incoming video yet.
 */
fun webRtcReceiverStats(stats: Collection<RtcStat>, timestampMs: Double): WebRtcReceiverStats? {
    val video = stats.firstOrNull { it.type == "inbound-rtp" && (it.text("kind") ?: it.text("mediaType")) == "video" } ?: return null
    val byId = stats.associateBy { it.id }
    // The video's own transport: a peer that isn't bundled has one for audio as well, on a path of its own.
    val transport = video.text("transportId")?.let { byId[it] } ?: stats.firstOrNull { it.type == "transport" }
    val pair = transport?.text("selectedCandidatePairId")?.let { byId[it] }
        ?: stats.firstOrNull { it.type == "candidate-pair" && it.flag("nominated") && it.text("state") == "succeeded" }
    return WebRtcReceiverStats(
        timestampMs = timestampMs,
        roundTripTimeMs = pair?.number("currentRoundTripTime")?.times(MS_PER_SECOND),
        jitterBufferDelayMs = video.number("jitterBufferDelay")?.times(MS_PER_SECOND),
        jitterBufferEmittedCount = video.number("jitterBufferEmittedCount"),
        totalProcessingDelayMs = video.number("totalProcessingDelay")?.times(MS_PER_SECOND),
        totalDecodeTimeMs = video.number("totalDecodeTime")?.times(MS_PER_SECOND),
        framesDecoded = video.number("framesDecoded"),
        bytesReceived = video.number("bytesReceived"),
        frameWidth = video.number("frameWidth")?.toInt(),
        frameHeight = video.number("frameHeight")?.toInt(),
        framesPerSecond = video.number("framesPerSecond"),
        framesDropped = video.number("framesDropped")?.toLong(),
        packetsLost = video.number("packetsLost")?.toLong(),
        codec = video.text("codecId")?.let { byId[it] }?.text("mimeType"),
    )
}

/**
 * Turns successive [WebRtcReceiverStats] into [LiveStreamStats]: the totals are differenced, so
 * each report describes the stretch since the one before it rather than the whole connection.
 *
 * The first report after [reset] (or after the totals go backwards, which is a different peer)
 * has nothing to be compared with and yields null. A stretch in which no frame was decoded keeps
 * the last per-frame timings rather than dropping them: a stalled picture is not a faster one.
 */
class WebRtcStatsMeter {
    private var previous: WebRtcReceiverStats? = null
    private var bufferMs: Double? = null
    private var decodeMs: Double? = null
    private var receiveMs: Double? = null

    fun reset() {
        previous = null
        bufferMs = null
        decodeMs = null
        receiveMs = null
    }

    fun update(now: WebRtcReceiverStats): LiveStreamStats? {
        val before = previous
        val restarted = before != null && (now.timestampMs <= before.timestampMs || (now.framesDecoded ?: 0.0) < (before.framesDecoded ?: 0.0))
        if (restarted) reset()
        previous = now
        if (before == null || restarted) return null

        val frames = difference(now.framesDecoded, before.framesDecoded)
        if (frames != null && frames > 0) {
            val emitted = difference(now.jitterBufferEmittedCount, before.jitterBufferEmittedCount)
            bufferMs = perFrame(difference(now.jitterBufferDelayMs, before.jitterBufferDelayMs), emitted) ?: bufferMs
            decodeMs = perFrame(difference(now.totalDecodeTimeMs, before.totalDecodeTimeMs), frames) ?: decodeMs
            // First packet in to decoded frame out, measured by the engine itself; the two parts
            // it is made of stand in for it on an engine that doesn't report the whole.
            receiveMs = perFrame(difference(now.totalProcessingDelayMs, before.totalProcessingDelayMs), frames)
                ?: bufferMs?.let { it + (decodeMs ?: 0.0) }
                ?: receiveMs
        }
        val networkMs = now.roundTripTimeMs?.div(2)
        val seconds = (now.timestampMs - before.timestampMs) / MS_PER_SECOND
        val bytes = difference(now.bytesReceived, before.bytesReceived)
        return LiveStreamStats(
            transport = LiveTransport.WEBRTC,
            delayMs = receiveMs?.let { (it + (networkMs ?: 0.0)).roundToInt() },
            networkMs = networkMs?.roundToInt(),
            bufferMs = bufferMs?.roundToInt(),
            decodeMs = decodeMs?.roundToInt(),
            width = now.frameWidth,
            height = now.frameHeight,
            framesPerSecond = (now.framesPerSecond ?: frames?.div(seconds))?.roundToInt(),
            bitrateKbps = bytes?.let { (it * BITS_PER_BYTE / seconds / BITS_PER_KILOBIT).roundToInt() },
            codec = now.codec,
            droppedFrames = now.framesDropped,
            packetsLost = now.packetsLost,
        )
    }

    private fun difference(now: Double?, before: Double?): Double? = if (now == null || before == null) null else now - before

    private fun perFrame(total: Double?, frames: Double?): Double? = if (total == null || frames == null || frames <= 0) null else total / frames
}

/**
 * Where the live edge of an HLS stream is, in the player's own media time, worked out from when
 * its video arrived.
 *
 * go2rtc's playlists carry no wall-clock tags, so nothing says outright how old a frame is. What
 * is known (measured against the server) is that a segment holds everything up to the instant
 * it was asked for: the newest video in a download was the server's newest when that download
 * began. So each arrival is an *anchor* — media time [observe]d at a wall-clock time — and the
 * live edge moves on from an anchor at one second per second.
 *
 * An arrival is always noticed a little after its request went out (the download, the parse, a
 * poll), which makes its anchor read early by that much and never late. The anchor that puts
 * the edge furthest ahead is therefore the truest, and the tracker goes by the best of the last
 * [windowMs]. What is left is the video the server had not yet cut into a segment at the
 * request, a frame or two, so the delay read off this is short by about a tenth of a second.
 *
 * Media time starts over whenever the player does (a new session, a seek that empties its
 * buffer), and an anchor from before would then be meaningless: callers [reset] at those
 * points, and an arrival more than [restartDropMs] behind the best one is taken for one too.
 *
 * Not thread-safe.
 */
class LiveEdgeTracker(private val windowMs: Long = 10_000L, private val restartDropMs: Long = 3_000L) {
    /** Media time minus the wall clock at an arrival; the larger, the nearer the request it was noticed. */
    private class Anchor(val offsetMs: Long, val atMs: Long)

    /** Best first, and newer towards the end: each is the best of everything observed after it. */
    private val anchors = ArrayDeque<Anchor>()

    /** The newest video the player has been seen to hold. */
    var newestMediaMs: Long? = null
        private set

    fun reset() {
        anchors.clear()
        newestMediaMs = null
    }

    /** Video up to [mediaTimeMs] was in the player's hands at [atMs]. */
    fun observe(mediaTimeMs: Long, atMs: Long) {
        val offset = mediaTimeMs - atMs
        val best = anchors.firstOrNull()
        if (best != null && best.offsetMs - offset > restartDropMs) reset()
        while (anchors.isNotEmpty() && anchors.last().offsetMs <= offset) anchors.removeLast()
        anchors.addLast(Anchor(offset, atMs))
        // The last anchor is never dropped for age: with nothing arriving, the edge it implies
        // is still the best there is, and the growing gap to it is the stall being measured.
        while (anchors.size > 1 && atMs - anchors.first().atMs > windowMs) anchors.removeFirst()
        holds(mediaTimeMs)
    }

    /** The player holds video up to [mediaTimeMs], whenever that arrived: only [bufferedMs] moves. */
    fun holds(mediaTimeMs: Long) {
        newestMediaMs = max(newestMediaMs ?: mediaTimeMs, mediaTimeMs)
    }

    /** How far a playhead at [positionMs] is behind the live edge at [nowMs]; null before any arrival. */
    fun behindMs(positionMs: Long, nowMs: Long): Long? {
        val best = anchors.firstOrNull() ?: return null
        return (best.offsetMs + nowMs - positionMs).coerceAtLeast(0)
    }

    /** How much video beyond [positionMs] the player already holds. */
    fun bufferedMs(positionMs: Long): Long? = newestMediaMs?.let { (it - positionMs).coerceAtLeast(0) }
}

/** A running total (bytes loaded, frames drawn) read at intervals, as a rate per second. */
class RateMeter {
    private var lastValue: Long? = null
    private var lastAtMs = 0L

    /** Forget the last reading, so the next one starts a fresh stretch instead of averaging over the gap. */
    fun reset() {
        lastValue = null
    }

    /** Null for the first reading, and whenever the total went backwards (a new session's count). */
    fun perSecond(value: Long?, atMs: Long): Double? {
        val before = lastValue
        val elapsedMs = atMs - lastAtMs
        lastValue = value
        lastAtMs = atMs
        if (value == null || before == null || value < before || elapsedMs <= 0) return null
        return (value - before) * MS_PER_SECOND / elapsedMs
    }
}

/** Bytes a second as the kilobits a second a bitrate is quoted in. */
fun Double.bytesPerSecondAsKbps(): Int = (this * BITS_PER_BYTE / BITS_PER_KILOBIT).roundToInt()

private const val MS_PER_SECOND = 1_000.0
private const val BITS_PER_BYTE = 8
private const val BITS_PER_KILOBIT = 1_000
