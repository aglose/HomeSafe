package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Whatever the camera publishes, no frame is decoded wider than this. The app draws every stream
 * into a 16:9 box a fraction of a desktop window wide, and Frigate's main streams are 4K: scaling
 * during the colour conversion FFmpeg has to do anyway costs far less than copying and uploading
 * 33 MB per frame (a 4K BGRA frame) to hand Skia a picture nothing on screen can show at that size.
 */
private const val MAX_DECODED_WIDTH = 1920

/**
 * Video frames decoded but not yet due on screen. Small on purpose: it is what makes the decode
 * thread run at playback speed rather than as fast as the network allows — it blocks pushing the
 * frame after this many — so a live stream stays at the live edge instead of racing ahead into a
 * buffer and then playing it out late.
 */
private const val FRAME_QUEUE_CAPACITY = 3

/** Audio buffered inside the output line. Kept short so audio never drifts far from the picture. */
private const val AUDIO_LINE_BUFFER_MS = 120

/**
 * How long a read may block before FFmpeg gives up on it. Bounds two things: how long a dead
 * server holds the decode thread, and — since that thread can only notice [FfmpegPlaybackSession.close]
 * between reads, FFmpeg's demuxer offering no way to interrupt one — how long a closed session
 * takes to let go of its socket.
 */
private const val READ_TIMEOUT_US = 5_000_000L

/** No frame for this long while playing is reported as a stall (a "Buffering" live indicator). */
private const val STALL_AFTER_MS = 1_500L

/**
 * How far the presentation clock may be from the frame it is about to show before it re-anchors
 * instead of honouring the gap. A seek, a live discontinuity or a stall all move the stream's
 * timestamps somewhere the wall clock cannot follow, and the only sane response is to call the
 * frame in hand "now" and time the ones after it from there.
 */
private const val RESYNC_DRIFT_MS = 1_000L

/** How long FFmpeg may analyse a newly opened source, and how much of it it may read to do so. */
private const val STREAM_ANALYSIS_US = 1_000_000L
private const val STREAM_PROBE_BYTES = 1_000_000L

/**
 * How far from the moment it asked for a seek may land before it is redone by reopening the
 * source. Wide enough for the ordinary case — a backward seek lands on the keyframe before the
 * target and decodes forward — and narrow enough to catch a demuxer that reported success and
 * went somewhere else.
 */
private const val SEEK_TOLERANCE_MS = 2_000L

private const val NO_SEEK = Long.MIN_VALUE
private const val PRESENTATION_TICK_MS = 4L
private const val PAUSED_POLL_MS = 50L
private const val ENQUEUE_POLL_MS = 100L

/**
 * One playback of one [VideoSource]: an FFmpeg demuxer and decoder on a thread of its own, a short
 * queue of decoded frames, and a presentation loop that puts each on screen when it is due.
 *
 * There is no long-lived player object here as there is on Android and iOS — a session *is* the
 * open connection, and [LivePlayerHolder] creates and closes them. That split follows what FFmpeg
 * gives us: [FFmpegFrameGrabber] has no notion of pausing a network stream or of retrying, and
 * (unlike ExoPlayer or AVPlayer) its blocking `grab()` cannot be interrupted from another thread.
 * So everything touching the grabber happens on the one thread that owns it, cancellation is
 * cooperative and checked between reads, and the holder's policy — retry, idle-stop, cold versus
 * warm restart — is expressed by closing one session and opening the next.
 *
 * Threading: [listener] is only ever called from [scope], which the holder supplies (the event
 * dispatch thread), so its callbacks can touch Compose state directly. Everything else runs on
 * the decode thread.
 */
internal class FfmpegPlaybackSession(
    private val source: VideoSource,
    private val startPositionMs: Long,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {

    /** All called on the session's [scope]. */
    internal interface Listener {
        /** A frame is due on screen now. [positionMs] is how far into the source it sits. */
        fun onFrame(image: ImageBitmap, positionMs: Long)

        /** Whether this source turned out to carry an audio track this machine can play. */
        fun onAudioAvailability(hasAudio: Boolean)

        /** Playing, but nothing has arrived to show for [STALL_AFTER_MS]. */
        fun onStalled(stalled: Boolean)

        /** The source ran out — the end of a recording, or a live stream the server closed. */
        fun onEnded()

        /** The source could not be opened, or died part-way through. */
        fun onFailed()
    }

    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val frames = ArrayBlockingQueue<DecodedFrame>(FRAME_QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(true)
    private val pendingSeekUs = AtomicLong(NO_SEEK)

    private class DecodedFrame(val image: ImageBitmap, val positionMs: Long, val presentationUs: Long)

    fun start(playing: Boolean, muted: Boolean) {
        isPaused.set(!playing)
        isMuted.set(muted)
        sessionScope.launch { presentFrames() }
        Thread({ decode() }, "homesafe-video-decode").apply {
            isDaemon = true
            start()
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    /** Seek to [positionMs] into the source. Applied by the decode thread before its next read. */
    fun seekTo(positionMs: Long) {
        pendingSeekUs.set(positionMs * 1_000)
    }

    /**
     * Stop playing and let go of the connection. Returns at once: the decode thread finishes the
     * read it is in first (bounded by [READ_TIMEOUT_US]) and closes the grabber itself, because it
     * is the only thread allowed to touch it. Nothing reaches [listener] after this call — the
     * presentation loop is cancelled here, on the very dispatcher those callbacks run on.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionScope.cancel()
        frames.clear()
    }

    // --- Presentation, on the caller's dispatcher -----------------------------------------

    /**
     * Shows each decoded frame when [PresentationClock] says it is due, and reports a stall when
     * nothing has arrived to show for [STALL_AFTER_MS].
     */
    private suspend fun CoroutineScope.presentFrames() {
        val clock = PresentationClock()
        var lastFrameAtMs = System.currentTimeMillis()
        var stalled = false

        while (isActive) {
            if (isPaused.get()) {
                clock.reset()
                lastFrameAtMs = System.currentTimeMillis()
                delay(PAUSED_POLL_MS)
                continue
            }
            val head = frames.peek()
            if (head == null) {
                if (!stalled && System.currentTimeMillis() - lastFrameAtMs > STALL_AFTER_MS) {
                    stalled = true
                    listener.onStalled(true)
                }
                delay(PRESENTATION_TICK_MS)
                continue
            }
            when (val decision = clock.decide(head.presentationUs, System.nanoTime())) {
                PresentationClock.Decision.ReAnchor -> continue

                is PresentationClock.Decision.Wait -> {
                    delay((decision.nanos / 1_000_000).coerceAtLeast(PRESENTATION_TICK_MS))
                    continue
                }

                PresentationClock.Decision.Present -> {
                    frames.poll()
                    if (stalled) {
                        stalled = false
                        listener.onStalled(false)
                    }
                    lastFrameAtMs = System.currentTimeMillis()
                    listener.onFrame(head.image, head.positionMs)
                }
            }
        }
    }

    // --- Decoding, on a thread of its own -------------------------------------------------

    /** How a run of the decode loop finished, and what to do next. */
    private sealed interface Outcome {
        /** The seek could not be made in place; reopen the source positioned there instead. */
        data class ReopenAt(val positionMs: Long) : Outcome

        /** The source ran out. */
        data object Ended : Outcome

        /** The source could not be opened, or died part-way through. */
        data object Failed : Outcome

        /** [close] was called; say nothing to anyone. */
        data object Closed : Outcome
    }

    private fun decode() {
        var openAtMs = startPositionMs
        while (!closed.get()) {
            when (val outcome = playFrom(openAtMs)) {
                is Outcome.ReopenAt -> openAtMs = outcome.positionMs
                Outcome.Ended -> return report { listener.onEnded() }
                Outcome.Failed -> return report { listener.onFailed() }
                Outcome.Closed -> return
            }
        }
    }

    /** Opens the source at [startAtMs] and plays it until it ends, fails, or needs reopening. */
    private fun playFrom(startAtMs: Long): Outcome {
        val grabber = FFmpegFrameGrabber(source.url)
        configure(grabber)
        try {
            grabber.start()
            // Seeking before anything has been read is the one seek that always lands, whatever
            // the container: there is no decoder state to unwind and no read position to move.
            if (startAtMs > 0) grabber.setTimestamp(startAtMs * 1_000)
        } catch (error: Throwable) {
            releaseQuietly(grabber)
            return if (closed.get()) Outcome.Closed else Outcome.Failed
        }

        var audio: AudioOutput? = null
        try {
            // What the grabber can say about the source the moment it opens is a guess: FFmpeg
            // may not have finished identifying the streams (with a live stream and a capped
            // analysis window it often has not, and reports a 0x0 video and no audio at all).
            // Everything that depends on the real format is settled below instead, off the first
            // frame of each kind, which is the first point at which the answer is certainly right.
            report { listener.onAudioAvailability(grabber.audioChannels > 0) }

            var scratch: ByteArray? = null
            var audioOpened = false
            var sizeCapped = false
            var startTimeUs = 0L
            var audioPaused = false
            var awaitingSeekTo: Long? = null
            while (!closed.get()) {
                if (isPaused.get() != audioPaused) {
                    audioPaused = isPaused.get()
                    audio?.setPaused(audioPaused)
                }
                val seekUs = pendingSeekUs.getAndSet(NO_SEEK)
                if (seekUs != NO_SEEK) {
                    val targetMs = seekUs / 1_000
                    frames.clear()
                    audio?.flush()
                    // The frame-checked seek, not the plain one: the plain one leaves an HLS
                    // playlist at end-of-stream as often as it moves it, and javacv answers that
                    // by grabbing forward through up to a thousand frames, which eats the clip.
                    runCatching { grabber.setVideoTimestamp(seekUs) }
                        .onFailure { return Outcome.ReopenAt(targetMs) }
                    awaitingSeekTo = targetMs
                }
                val frame = grabber.grab()
                if (frame == null) {
                    // A seek that took the stream off its end rather than to the asked-for moment.
                    return awaitingSeekTo?.let { Outcome.ReopenAt(it) } ?: Outcome.Ended
                }
                when {
                    frame.image != null -> {
                        if (!sizeCapped) {
                            sizeCapped = true
                            startTimeUs = grabber.sourceStartTimeUs()
                            capDecodedSize(grabber, frame.imageWidth, frame.imageHeight)
                        }
                        val positionMs = (frame.timestamp - startTimeUs) / 1_000
                        val target = awaitingSeekTo
                        if (target != null) {
                            // Some demuxers report a seek as successful and land somewhere else
                            // entirely. Reopening at the target is slower but always lands.
                            if (kotlin.math.abs(positionMs - target) > SEEK_TOLERANCE_MS) {
                                return Outcome.ReopenAt(target)
                            }
                            awaitingSeekTo = null
                        }
                        val decoded = frame.toBgraFrame(scratch) ?: continue
                        scratch = decoded.pixels
                        if (!enqueue(DecodedFrame(decoded.image, positionMs, frame.timestamp), audio)) break
                    }

                    frame.samples != null -> {
                        if (!audioOpened) {
                            audioOpened = true
                            audio = AudioOutput.open(frame.sampleRate, frame.audioChannels)
                            val playable = audio != null
                            report { listener.onAudioAvailability(playable) }
                            audio?.setPaused(isPaused.get())
                        }
                        audio?.write(frame, isMuted.get())
                    }

                    // A data or subtitle frame; nothing here plays those.
                    else -> Unit
                }
            }
            return Outcome.Closed
        } catch (error: Throwable) {
            return if (closed.get()) Outcome.Closed else Outcome.Failed
        } finally {
            audio?.close()
            releaseQuietly(grabber)
        }
    }

    /**
     * Hands [frame] to the presentation loop, waiting for room. This is the back-pressure that
     * keeps decoding at playback speed; while playback is paused the queue simply stays full and
     * the decode thread waits here, reading nothing, until it is resumed or closed.
     */
    private fun enqueue(frame: DecodedFrame, audio: AudioOutput?): Boolean {
        while (!closed.get()) {
            if (frames.offer(frame, ENQUEUE_POLL_MS, TimeUnit.MILLISECONDS)) return true
            audio?.setPaused(isPaused.get())
        }
        return false
    }

    private fun configure(grabber: FFmpegFrameGrabber) {
        // FFmpeg's own logging is chatty enough to print a line per playlist poll and per segment
        // — several a second, per camera — straight to stderr. Only real errors are wanted.
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        grabber.pixelFormat = avutil.AV_PIX_FMT_BGRA
        grabber.sampleMode = FrameGrabber.SampleMode.SHORT
        grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
        if (source.headers.isNotEmpty()) {
            // FFmpeg's HTTP protocol takes extra request headers as one CRLF-terminated blob.
            // This is how a recording's Frigate session cookie reaches the playlist and every
            // segment under it.
            grabber.setOption("headers", source.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
        }
        grabber.setOption("timeout", READ_TIMEOUT_US.toString())
        grabber.setOption("rw_timeout", READ_TIMEOUT_US.toString())
        // Cap how long FFmpeg studies the stream before it will admit what is in it. Its default
        // is generous enough to spend 3.6 s on a 4K camera over Tailscale before the first frame
        // (measured); a second is plenty to find both the video and the audio track (also
        // measured, against the Amcrest's 2960x1668 stream with Opus) and halves the wait.
        grabber.setOption("analyzeduration", STREAM_ANALYSIS_US.toString())
        grabber.setOption("probesize", STREAM_PROBE_BYTES.toString())
        if (source is VideoSource.Live) {
            // Start at the newest segment in the playlist rather than FFmpeg's default of three
            // back: go2rtc serves 0.5 s segments, so the default alone would open the "live" view
            // a second and a half behind, on top of whatever the camera and the network add.
            grabber.setOption("live_start_index", "-1")
            grabber.setOption("fflags", "nobuffer")
            grabber.setOption("flags", "low_delay")
        }
    }

    /**
     * Caps how large FFmpeg scales its output, given the true size of a frame it has now decoded.
     * Takes effect from the next frame — the grabber rebuilds its conversion context whenever the
     * requested size changes — so exactly one full-size frame gets through, which is a picture
     * worth showing rather than a cost worth avoiding.
     */
    private fun capDecodedSize(grabber: FFmpegFrameGrabber, width: Int, height: Int) {
        if (width <= MAX_DECODED_WIDTH || height <= 0) return
        grabber.imageWidth = MAX_DECODED_WIDTH
        // Rounded to an even number of lines: an odd height leaves the chroma planes of a 4:2:0
        // frame half a line short, which some scalers render as a smeared bottom row.
        val scaled = (height.toLong() * MAX_DECODED_WIDTH / width).toInt()
        grabber.imageHeight = (scaled and 1.inv()).coerceAtLeast(2)
    }

    private fun report(block: () -> Unit) {
        sessionScope.launch { block() }
    }

    /**
     * Where the container's clock starts, so frame timestamps can be reported as offsets from the
     * start of the source — which is what the app talks in, and what `setTimestamp` takes.
     */
    private fun FFmpegFrameGrabber.sourceStartTimeUs(): Long =
        formatContext?.start_time()?.takeIf { it != avutil.AV_NOPTS_VALUE } ?: 0L

    private fun releaseQuietly(grabber: FFmpegFrameGrabber) {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }
}

/** A BGRA frame copied out of FFmpeg's reused buffer, alongside the array it was copied into. */
private class BgraFrame(val image: ImageBitmap, val pixels: ByteArray)

/**
 * Copies this frame's pixels into an [ImageBitmap], reusing [scratch] when it is the right size.
 * FFmpeg decodes into one buffer it overwrites on every frame, so the bytes have to be taken out
 * of it before the next `grab()`, but the array doing that can live for the whole session.
 *
 * The row stride comes from the frame rather than from the width: FFmpeg pads each row out to a
 * 64-byte boundary for SIMD, so a 1366-wide frame's rows are not 1366 * 4 bytes apart.
 */
private fun Frame.toBgraFrame(scratch: ByteArray?): BgraFrame? {
    val buffer = image?.firstOrNull() as? ByteBuffer ?: return null
    val width = imageWidth
    val height = imageHeight
    val rowBytes = imageStride
    if (width <= 0 || height <= 0 || rowBytes < width * 4) return null
    val size = rowBytes * height
    if (buffer.capacity() < size) return null

    val pixels = if (scratch != null && scratch.size == size) scratch else ByteArray(size)
    val view = buffer.duplicate()
    view.position(0)
    view.limit(size)
    view.get(pixels)

    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    return BgraFrame(Image.makeRaster(info, pixels, rowBytes).toComposeImageBitmap(), pixels)
}

/**
 * The machine's audio output for one session, or nothing at all when the source carries no audio
 * (go2rtc's video-only sub-streams, recordings Frigate saved silent) or the host has no usable
 * output device.
 *
 * Muting writes silence rather than closing the line or skipping the decode: the line's fixed
 * drain rate is part of what paces a stream that has audio, so silencing it any other way would
 * change playback timing every time the user pressed the button.
 */
private class AudioOutput private constructor(private val line: SourceDataLine) {

    private var silence = ByteArray(0)

    fun write(frame: Frame, muted: Boolean) {
        val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: return
        val byteCount = samples.remaining() * 2
        if (muted) {
            if (silence.size != byteCount) silence = ByteArray(byteCount)
            runCatching { line.write(silence, 0, byteCount) }
            return
        }
        val bytes = ByteArray(byteCount)
        val view = samples.duplicate()
        var index = 0
        while (view.hasRemaining()) {
            val sample = view.get().toInt()
            bytes[index++] = (sample and 0xFF).toByte()
            bytes[index++] = ((sample shr 8) and 0xFF).toByte()
        }
        runCatching { line.write(bytes, 0, byteCount) }
    }

    fun setPaused(paused: Boolean) {
        runCatching { if (paused) line.stop() else line.start() }
    }

    fun flush() {
        runCatching { line.flush() }
    }

    fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    companion object {
        /**
         * Opens an output line for audio of this shape, or returns null when the machine has no
         * line that will take it — which is the same answer as "this source has no audio" as far
         * as a mute button is concerned.
         */
        fun open(sampleRate: Int, channels: Int): AudioOutput? {
            if (channels <= 0 || sampleRate <= 0) return null
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val bufferBytes = sampleRate * channels * 2 * AUDIO_LINE_BUFFER_MS / 1000
            return runCatching {
                val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                line.open(format, bufferBytes)
                line.start()
                AudioOutput(line)
            }.getOrNull()
        }
    }
}

/**
 * Decides when a decoded frame should go on screen: the stream's own timestamps, played out
 * against the wall clock from an anchor taken at the first frame after every [reset].
 *
 * The anchor exists so that playback follows the stream's timing rather than the rate frames
 * happen to be decoded at, and it is thrown away whenever the two have parted company by more
 * than [resyncDriftMs] — a seek, a live discontinuity, or a machine that could not keep up all
 * move the timestamps somewhere the wall clock cannot follow. Re-anchoring makes the frame in
 * hand "now" and times the following ones from there, so playback recovers in a single frame
 * instead of either racing through a backlog or waiting out a gap that will never close.
 */
internal class PresentationClock(private val resyncDriftMs: Long = RESYNC_DRIFT_MS) {

    sealed interface Decision {
        /** Put this frame on screen now. */
        data object Present : Decision

        /** It is not due yet; come back in [nanos]. */
        data class Wait(val nanos: Long) : Decision

        /** Too far from the clock to honour. Ask again — the answer will be [Present]. */
        data object ReAnchor : Decision
    }

    private var anchored = false
    private var anchorWallNs = 0L
    private var anchorPresentationUs = 0L

    /** Forget the anchor, so the next frame decided on becomes the new "now". */
    fun reset() {
        anchored = false
    }

    /** What to do with a frame stamped [presentationUs], asked at wall-clock [nowNs]. */
    fun decide(presentationUs: Long, nowNs: Long): Decision {
        if (!anchored) {
            anchored = true
            anchorWallNs = nowNs
            anchorPresentationUs = presentationUs
        }
        val driftNs = resyncDriftMs * 1_000_000
        val waitNs = anchorWallNs + (presentationUs - anchorPresentationUs) * 1_000 - nowNs
        return when {
            waitNs > driftNs || -waitNs > driftNs -> {
                anchored = false
                Decision.ReAnchor
            }

            waitNs > 0 -> Decision.Wait(waitNs)

            else -> Decision.Present
        }
    }
}
