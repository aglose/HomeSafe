package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * One camera's video on the desktop: FFmpeg (through JavaCV's [FFmpegFrameGrabber]) reading
 * go2rtc's continuous live stream ([go2rtcContinuousUrl]) or a Frigate recording, decoding on a
 * thread of its own, and handing each picture to Compose as a Skia [Image] ([picture]). The
 * desktop's counterpart of Android's pooled ExoPlayer and iOS's AVPlayer: there is no WebRTC
 * engine here.
 *
 * Frames are played at the pace their timestamps say, against the wall clock ([FramePacer]).
 *
 * Moving between a camera's live streams is make-before-break, as on the phones: the stream on
 * screen keeps decoding and drawing, and there is no poster, until the one replacing it has a
 * picture of its own; then the old one lets go of its decoder and its connection ([outgoing]).
 *
 * A live source that fails or ends reconnects by itself, backing off as the phones' players do
 * ([LivePlaybackPolicy.retryDelayMs]); a recording that fails or ends says so ([ended], [failed])
 * and stops.
 *
 * Everything Compose reads is snapshot state written on the UI thread ([ui]); the decode thread
 * only ever touches the grabber, the audio line and [pending].
 */
internal class DesktopVideoEngine(val key: String?) {
    /** The newest picture, for every bound surface to draw; null until the first. */
    var picture: Image? by mutableStateOf(null)
        private set

    /**
     * Moves on each cold start (a new source, a reconnect after a failure): a surface shows the
     * poster until it has drawn a picture of the generation it is in, as iOS's holder does.
     */
    var coldStartGeneration by mutableIntStateOf(0)
        private set

    /** The generation [picture] belongs to. */
    var pictureGeneration by mutableIntStateOf(-1)
        private set

    /** True while the stream has run dry after showing video: connected, but waiting for data. */
    var stalled by mutableStateOf(false)
        private set

    /** When the decode thread last had a picture, for [checkStalled]. */
    @Volatile private var lastPictureAtNanos = 0L

    /** Whether what's playing has a sound track FFmpeg decodes, which is anything go2rtc sends. */
    var hasAudio by mutableStateOf(false)
        private set

    /** How far into the recording the picture on screen is. */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** Bumped when the current recording plays to its end, or gives up; the binder reports each once. */
    var ended by mutableIntStateOf(0)
        private set
    var failed by mutableIntStateOf(0)
        private set

    /** What the decoder says about the stream, for the stats overlay. */
    var stats: LiveStreamStats? by mutableStateOf(null)
        private set

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    @Volatile private var source: VideoSource? = null

    @Volatile private var playWhenReady = true

    @Volatile private var muted = true

    @Volatile private var seekToMs: Long? = null

    private var thread: Thread? = null

    @Volatile private var running = false

    /**
     * Which decode thread is the engine's: each new one takes the next number, and one that finds
     * it has moved on stops. A thread can be stuck in a network read for [READ_TIMEOUT_US], or asleep
     * in a reconnect's back-off, when a new source arrives; rather than wait for it, a new thread
     * takes over at once and the old one bows out when it next looks.
     */
    @Volatile private var session = 0

    /** The [session] still drawing until the current one has its first picture, or [NO_SESSION]; see [load]. */
    private val outgoing = AtomicInteger(NO_SESSION)

    /** The [session] that made [picture]. UI thread only. */
    private var pictureSession = NO_SESSION

    /** A picture a decode thread made that the UI thread hasn't published yet; replaced, not queued, when it falls behind. */
    private val pending = AtomicReference<PendingPicture?>(null)

    /** How many surfaces are bound and on screen ([start]/[stop]), and whether a prefetch is holding the engine meanwhile. */
    var binders = 0
        private set
    var prefetchHold: Job? = null
    var idleStop: Job? = null

    fun load(next: VideoSource) {
        val previous = source
        if (previous == next) return
        source = next
        seekToMs = null
        // A live stream that keeps its URL is the same stream (see VideoSource.Live): nothing to redo.
        if (previous is VideoSource.Live && next is VideoSource.Live && previous.url == next.url) return
        val moving = picture != null && pictureGeneration == coldStartGeneration
        if (previous is VideoSource.Live && next is VideoSource.Live && running && moving) {
            // Whatever is on screen plays on until something takes over; a join that never drew is simply replaced.
            if (pictureSession == session) outgoing.compareAndSet(NO_SESSION, session)
        } else {
            outgoing.set(NO_SESSION)
            coldStartGeneration++
        }
        stalled = false
        positionMs = (next as? VideoSource.Recording)?.startPositionMs ?: 0L
        if (playWhenReady || running) restart()
    }

    fun setPlayWhenReady(play: Boolean) {
        playWhenReady = play
        if (play) ensureRunning()
    }

    fun setMuted(mute: Boolean) {
        muted = mute
    }

    fun seek(positionMs: Long) {
        seekToMs = positionMs
        this.positionMs = positionMs
    }

    /** Drops the connection and joins again now: the Home page's pull to refresh. */
    fun reconnect() {
        outgoing.set(NO_SESSION)
        coldStartGeneration++
        restart()
    }

    /** Marks the engine [stalled] once a playing stream has gone [STALL_NANOS] without a picture; the binders' poll asks. */
    fun checkStalled() {
        val starved = running && playWhenReady && picture != null && pictureGeneration == coldStartGeneration &&
            System.nanoTime() - lastPictureAtNanos > STALL_NANOS
        if (starved != stalled) stalled = starved
    }

    fun onBinderStarted() {
        binders++
        idleStop?.cancel()
        if (playWhenReady) ensureRunning()
    }

    fun onBinderStopped() {
        binders = (binders - 1).coerceAtLeast(0)
    }

    /** Stops decoding, keeping the last picture to show the moment something binds again. */
    fun halt() {
        running = false
        session++
        outgoing.set(NO_SESSION)
        thread?.interrupt()
        thread = null
        stalled = false
    }

    /** Lets go of everything, the last picture included. */
    fun release() {
        halt()
        pending.getAndSet(null)?.image?.close()
        picture?.close()
        picture = null
    }

    private fun ensureRunning() {
        if (running && thread?.isAlive == true) return
        restart()
    }

    /** A new decode thread for what is to play now, in place of any that was running ([session]). */
    private fun restart() {
        if (source == null) return
        val replaced = session
        val mine = ++session
        running = true
        if (outgoing.get() != replaced) thread?.interrupt()
        thread = Thread({ decodeLoop(mine) }, "HomeSafe video ${key ?: "private"}").apply {
            isDaemon = true
            start()
        }
    }

    private fun isCurrent(mine: Int) = running && session == mine

    private fun mayDraw(mine: Int) = isCurrent(mine) || (running && outgoing.get() == mine)

    /** One stream at a time is heard: the one on screen, so the sound moves over with the picture. */
    private fun isHeard(mine: Int): Boolean {
        val drawing = outgoing.get()
        return if (drawing == NO_SESSION) isCurrent(mine) else drawing == mine && running
    }

    private fun decodeLoop(mine: Int) {
        try {
            playUntilReplaced(mine)
        } catch (_: InterruptedException) {
            // Stopped or replaced: nothing to report.
        } finally {
            outgoing.compareAndSet(mine, NO_SESSION)
        }
    }

    private fun playUntilReplaced(mine: Int) {
        var failures = 0
        while (isCurrent(mine)) {
            val playing = source ?: break
            if (isHeldLive(playing)) {
                Thread.sleep(PAUSED_POLL_MS)
                continue
            }
            val attempt = PlayAttempt()
            val outcome = runCatching { play(playing, mine, attempt) }
            if (!isCurrent(mine)) break
            if (attempt.paused || isHeldLive(playing)) continue
            if (playing is VideoSource.Recording) {
                ui.launch {
                    if (session != mine) return@launch
                    if (outcome.isSuccess) ended++ else failed++
                }
                running = false
                break
            }
            // Live: whatever happened, join again, backing off while it keeps failing.
            failures = if (attempt.drewPicture) 1 else failures + 1
            ui.launch {
                if (session != mine) return@launch
                // While the stream being replaced still plays, its picture needs no poster over it.
                if (outgoing.get() == NO_SESSION) coldStartGeneration++
                stalled = false
            }
            // In short steps, so a thread that has been replaced doesn't sleep out the whole back-off.
            val wakeAt = System.nanoTime() + LivePlaybackPolicy.retryDelayMs(failures) * 1_000_000
            while (isCurrent(mine) && !isHeldLive(playing) && System.nanoTime() < wakeAt) Thread.sleep(BACKOFF_STEP_MS)
        }
    }

    /** A paused live stream holds nothing open: the server wouldn't wait for it, so it joins again on resuming. */
    private fun isHeldLive(playing: VideoSource) = playing is VideoSource.Live && !playWhenReady

    /** Plays [playing] until it ends (normal return), fails (throws), or is replaced, stopped or, if live, paused. */
    private fun play(playing: VideoSource, mine: Int, attempt: PlayAttempt) {
        quietLogs
        val continuousUrl = (playing as? VideoSource.Live)?.let { go2rtcContinuousUrl(it.url) }
        val grabber = FFmpegFrameGrabber(continuousUrl ?: playing.url).apply {
            pixelFormat = avutil.AV_PIX_FMT_BGRA
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            sampleRate = AUDIO_SAMPLE_RATE
            audioChannels = AUDIO_CHANNELS
            // Give up on a read that has had nothing for this long, so a dead stream reconnects.
            setOption("rw_timeout", READ_TIMEOUT_US.toString())
            // Our own retries rejoin a live stream that drops; FFmpeg's would resume at a byte it can't.
            if (continuousUrl == null) setOption("reconnect", "1")
            if (playing.headers.isNotEmpty()) {
                setOption("headers", playing.headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" })
            }
            if (playing is VideoSource.Live) {
                // Start from the newest segment rather than three back, which is HLS's default.
                if (continuousUrl == null) setOption("live_start_index", "-1")
                setOption("fflags", "nobuffer")
                setOption("flags", "low_delay")
            }
            setVideoOption("threads", if (playing is VideoSource.Live) LIVE_DECODE_THREADS else "auto")
        }
        var line: SourceDataLine? = null
        try {
            // The unsafe start and release: the plain ones hold one lock across every grabber in the app, so a camera
            // slow to answer would hold up every other camera's start and the end of the stream it replaces. FFmpeg
            // needs no such lock now. An fMP4's opening says what is in it, so the stream needn't be sampled first.
            grabber.startUnsafe(continuousUrl == null)
            if (playing is VideoSource.Recording && playing.startPositionMs > 0) grabber.setTimestamp(playing.startPositionMs * 1000, true)
            val audio = grabber.hasAudio()
            val transport = if (continuousUrl != null) LiveTransport.FMP4 else LiveTransport.HLS
            val pacer = FramePacer(live = playing is VideoSource.Live)
            val frameRate = FrameRateMeter()
            var pixels = ByteArray(0)
            var reported: LiveStreamStats? = null
            var lastPositionReport = 0L
            while (mayDraw(mine)) {
                seekToMs?.let { target ->
                    seekToMs = null
                    grabber.setTimestamp(target * 1000, true)
                    pacer.restart()
                    line?.flush()
                }
                if (!playWhenReady) {
                    if (playing is VideoSource.Live) {
                        attempt.paused = true
                        return
                    }
                    pacer.restart()
                    line?.stop()
                    Thread.sleep(PAUSED_POLL_MS)
                    continue
                }
                val wantAudio = audio && !muted && isHeard(mine)
                val frame: Frame = grabber.grabFrame(wantAudio, true, true, false, false) ?: return
                if (frame.samples != null) {
                    if (wantAudio) {
                        // The read may have outlasted this stream's turn to be heard.
                        if (isHeard(mine)) {
                            line = line ?: openAudioLine()
                            line?.let { writeAudio(it, frame) }
                        }
                    }
                    continue
                }
                if (frame.image == null) continue
                if (!wantAudio) {
                    line?.let {
                        it.stop()
                        it.flush()
                    }
                }
                val hold = pacer.holdNanos(frame.timestamp, System.nanoTime())
                if (hold > 0) Thread.sleep(hold / 1_000_000, (hold % 1_000_000).toInt())
                if (!mayDraw(mine)) break
                pixels = publish(frame, mine, audio, pixels)
                attempt.drewPicture = true
                val now = System.nanoTime()
                // What the frames say rather than what the opening said: a Nest stream's starts out as a tiny placeholder, and an fMP4's gives no rate.
                val measured = frameRate.onFrame(now)
                if (measured != null || frame.imageWidth != reported?.width || frame.imageHeight != reported.height) {
                    val current = LiveStreamStats(
                        transport = transport,
                        width = frame.imageWidth,
                        height = frame.imageHeight,
                        framesPerSecond = measured ?: reported?.framesPerSecond,
                        bitrateKbps = grabber.videoBitrate.takeIf { it > 0 }?.let { it / 1000 },
                        codec = grabber.videoCodecName,
                    )
                    reported = current
                    ui.launch { if (session == mine) stats = current }
                }
                if (playing is VideoSource.Recording && now - lastPositionReport > POSITION_REPORT_NANOS) {
                    lastPositionReport = now
                    val at = frame.timestamp / 1000
                    ui.launch { if (session == mine) positionMs = at }
                }
            }
        } finally {
            // What it has buffered goes too, so that a stream handing over isn't heard over the next.
            line?.flush()
            line?.close()
            runCatching { grabber.releaseUnsafe() }
        }
    }

    /**
     * Has the UI thread show [frame], copied into a picture of its own by way of [scratch], which
     * is handed back to use for the next frame: at 4K a fresh array for each would be nearly a
     * gigabyte a second for the garbage collector.
     */
    private fun publish(frame: Frame, mine: Int, audio: Boolean, scratch: ByteArray): ByteArray {
        val width = frame.imageWidth
        val height = frame.imageHeight
        val buffer = (frame.image[0] as? ByteBuffer) ?: return scratch
        val rowBytes = frame.imageStride.takeIf { it >= width * 4 } ?: (width * 4)
        val size = rowBytes * height
        val bytes = if (scratch.size == size) scratch else ByteArray(size)
        buffer.duplicate().apply { rewind() }.get(bytes, 0, minOf(size, buffer.limit()))
        val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, rowBytes)
        lastPictureAtNanos = System.nanoTime()
        if (!offerPicture(PendingPicture(image, mine, audio))) return bytes
        ui.launch {
            val next = pending.getAndSet(null) ?: return@launch
            // By the session alone, not whether anything still runs: a recording's last picture can arrive after it has ended.
            val fromCurrent = session == next.session
            if (!fromCurrent && outgoing.get() != next.session) {
                next.image.close()
                return@launch
            }
            if (fromCurrent) {
                // The first picture of the stream replacing another: that one can stop now, and the sound button is this one's.
                outgoing.set(NO_SESSION)
                hasAudio = next.hasAudio
            }
            // Drawn pictures hold their own reference to the Skia image, so the one replaced can go now.
            val previous = picture
            picture = next.image
            pictureSession = next.session
            previous?.close()
            if (fromCurrent && pictureGeneration != coldStartGeneration) pictureGeneration = coldStartGeneration
            if (stalled) stalled = false
        }
        return bytes
    }

    /**
     * Leaves [offered] for the UI thread in place of what is waiting there, unless that is the first
     * picture of the stream taking over and this one is from the stream it replaces: the handover
     * isn't put off, or lost if the new stream then stalls. False if [offered] was dropped for that.
     */
    private fun offerPicture(offered: PendingPicture): Boolean {
        while (true) {
            val waiting = pending.get()
            if (waiting != null && session == waiting.session && session != offered.session) {
                offered.image.close()
                return false
            }
            if (pending.compareAndSet(waiting, offered)) {
                waiting?.image?.close()
                return true
            }
        }
    }

    private fun openAudioLine(): SourceDataLine? = runCatching {
        val format = AudioFormat(AUDIO_SAMPLE_RATE.toFloat(), 16, AUDIO_CHANNELS, true, false)
        AudioSystem.getSourceDataLine(format).apply {
            open(format, AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2 / 4)
            start()
        }
    }.getOrNull()

    private fun writeAudio(line: SourceDataLine, frame: Frame) {
        val samples = frame.samples[0] as? ShortBuffer ?: return
        val shorts = samples.duplicate().apply { rewind() }
        val bytes = ByteArray(shorts.remaining() * 2)
        var i = 0
        while (shorts.hasRemaining()) {
            val sample = shorts.get().toInt()
            bytes[i++] = sample.toByte()
            bytes[i++] = (sample shr 8).toByte()
        }
        if (!line.isRunning) line.start()
        line.write(bytes, 0, bytes.size)
    }

    private class PendingPicture(val image: Image, val session: Int, val hasAudio: Boolean)

    private class PlayAttempt {
        var drewPicture = false

        /** It ended because live playback was paused, whatever the play state has become since. */
        var paused = false
    }

    private companion object {
        const val NO_SESSION = -1

        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_CHANNELS = 2

        /** Ten seconds without a byte and the stream is taken for dead. */
        const val READ_TIMEOUT_US = 10_000_000L

        /**
         * Enough for 4K at the frame rate with a little to spare, for a frame's wait behind it. FFmpeg's automatic
         * count on a 12-core Mac holds a dozen frames back, half a second at 25 fps and a second at the 12.5 a camera
         * drops to in low light, and made the gaps between pictures five times worse at no saving in CPU.
         */
        const val LIVE_DECODE_THREADS = "2"

        const val PAUSED_POLL_MS = 50L

        /** How often a reconnect's back-off looks to see whether it has been replaced. */
        const val BACKOFF_STEP_MS = 100L

        /** A live picture that hasn't moved for this long is buffering, not live. */
        const val STALL_NANOS = 1_500_000_000L

        /** A recording's position, four times a second, as the phones' players report it. */
        const val POSITION_REPORT_NANOS = 250_000_000L

        /**
         * FFmpeg talks a great deal on stderr about every HLS segment; only its errors are worth a
         * line. Done on the decode thread, on first use: if FFmpeg's libraries won't load, that is
         * one camera's failure to play (and it retries), not the UI thread's to crash on.
         */
        val quietLogs by lazy { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }
    }
}

/** Frames per second, worked out every couple of seconds; null between. */
internal class FrameRateMeter {
    private var windowStartNanos = 0L
    private var frames = 0

    fun onFrame(nowNanos: Long): Int? {
        if (windowStartNanos == 0L) windowStartNanos = nowNanos
        frames++
        val elapsed = nowNanos - windowStartNanos
        if (elapsed < WINDOW_NANOS) return null
        val perSecond = ((frames - 1) * NANOS_PER_SECOND / elapsed).toInt()
        windowStartNanos = nowNanos
        frames = 1
        return perSecond
    }

    private companion object {
        const val WINDOW_NANOS = 2_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * The desktop's live players, one per player key, shared by every surface that binds the key (the
 * Home card and the camera's own page), as [LivePlayerPool] is on iOS. A keyed engine outlives
 * its last surface for [LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS], so going back to Home finds
 * the cameras still playing, and is let go [LivePlaybackPolicy.POOL_RELEASE_MS] after that; an
 * engine with no key is the caller's alone and goes with it. UI thread only.
 */
internal object DesktopVideoPool {
    private val engines = HashMap<String, DesktopVideoEngine>()
    private val leases = HashMap<DesktopVideoEngine, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    fun acquire(key: String?): DesktopVideoEngine {
        val engine = if (key == null) DesktopVideoEngine(null) else engines.getOrPut(key) { DesktopVideoEngine(key) }
        leases[engine] = (leases[engine] ?: 0) + 1
        engine.idleStop?.cancel()
        return engine
    }

    fun release(engine: DesktopVideoEngine) {
        val left = (leases[engine] ?: 1) - 1
        if (left > 0) {
            leases[engine] = left
            return
        }
        leases.remove(engine)
        if (engine.key == null) {
            engine.release()
            return
        }
        scheduleIdle(engine)
    }

    /** A surface went off screen (the window minimised, the page covered); with none left, the engine is let go in time. */
    fun stopped(engine: DesktopVideoEngine) {
        if (engine.binders == 0 && engine.key != null) scheduleIdle(engine)
        if (engine.binders == 0 && engine.key == null) engine.halt()
    }

    private fun scheduleIdle(engine: DesktopVideoEngine) {
        engine.idleStop?.cancel()
        engine.idleStop = scope.launch {
            delay(LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS)
            if (engine.binders > 0 || engine.prefetchHold?.isActive == true) return@launch
            engine.halt()
            delay(LivePlaybackPolicy.POOL_RELEASE_MS - LivePlaybackPolicy.IDLE_STOP_FOREGROUND_MS)
            if ((leases[engine] ?: 0) > 0 || engine.binders > 0) return@launch
            engines.remove(engine.key)
            engine.release()
        }
    }

    /** Starts [streams] ahead of their cards (see [LivePlayerPrefetch]); one no longer asked for falls back to the idle rules. */
    fun prefetch(streams: List<LivePrefetch>) {
        val wanted = streams.associateBy { it.playerKey }
        engines.values.filter { it.prefetchHold?.isActive == true && it.key !in wanted }.forEach { engine ->
            engine.prefetchHold?.cancel()
            if (engine.binders == 0) scheduleIdle(engine)
        }
        for (stream in streams) {
            val engine = engines.getOrPut(stream.playerKey) { DesktopVideoEngine(stream.playerKey) }
            engine.idleStop?.cancel()
            engine.load(stream.source)
            engine.setPlayWhenReady(true)
            engine.prefetchHold?.cancel()
            engine.prefetchHold = scope.launch {
                delay(LivePlaybackPolicy.PREFETCH_HOLD_MS)
                if (engine.binders == 0 && (leases[engine] ?: 0) == 0) scheduleIdle(engine)
            }
        }
    }

    fun cancelPrefetch() {
        engines.values.filter { it.prefetchHold?.isActive == true }.forEach { engine ->
            engine.prefetchHold?.cancel()
            if (engine.binders == 0) scheduleIdle(engine)
        }
    }
}
