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
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * One camera's video on the desktop: FFmpeg (through JavaCV's [FFmpegFrameGrabber]) reading
 * go2rtc's live HLS or a Frigate recording, decoding on a thread of its own, and handing each
 * picture to Compose as a Skia [Image] ([picture]). The desktop's counterpart of Android's pooled
 * ExoPlayer and iOS's AVPlayer: there is no WebRTC engine here, so live is always HLS.
 *
 * Frames are played at the pace their timestamps say, against the wall clock: HLS hands over a
 * segment's worth of video at once, which decoded as fast as it arrives would race and then
 * freeze. Falling more than [MAX_LAG_US] behind (a slow network, a laptop waking up) starts the
 * clock again from the frame in hand rather than playing catch-up.
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

    /** Moves whenever the decode loop must drop what it has and open [source] afresh. */
    @Volatile private var loadGeneration = 0

    private var thread: Thread? = null

    @Volatile private var running = false

    /**
     * Which decode thread is the engine's: each new one takes the next number, and one that finds
     * it has moved on stops. A thread can be stuck in a network read for [READ_TIMEOUT_US], or asleep
     * in a reconnect's back-off, when a new source arrives; rather than wait for it, a new thread
     * takes over at once and the old one bows out when it next looks.
     */
    @Volatile private var session = 0

    /** A picture the decode thread made that the UI thread hasn't published yet; replaced, not queued, when it falls behind. */
    private val pending = AtomicReference<Image?>(null)

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
        loadGeneration++
        coldStartGeneration++
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
        loadGeneration++
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
        thread?.interrupt()
        thread = null
        stalled = false
    }

    /** Lets go of everything, the last picture included. */
    fun release() {
        halt()
        pending.getAndSet(null)?.close()
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
        val mine = ++session
        running = true
        thread?.interrupt()
        thread = Thread({ decodeLoop(mine) }, "HomeSafe video ${key ?: "private"}").apply {
            isDaemon = true
            start()
        }
    }

    private fun isCurrent(mine: Int) = running && session == mine

    private fun decodeLoop(mine: Int) {
        var failures = 0
        while (isCurrent(mine)) {
            val playing = source ?: break
            val generation = loadGeneration
            val outcome = runCatching { play(playing, generation, mine) }
            if (!isCurrent(mine)) break
            if (loadGeneration != generation) {
                failures = 0
                continue
            }
            if (playing is VideoSource.Recording) {
                ui.launch {
                    if (session != mine) return@launch
                    if (outcome.isSuccess) ended++ else failed++
                }
                running = false
                break
            }
            // Live: whatever happened, join again, backing off while it keeps failing.
            failures++
            ui.launch {
                if (session != mine) return@launch
                coldStartGeneration++
                stalled = false
            }
            // In short steps, so a thread that has been replaced doesn't sleep out the whole back-off.
            val wakeAt = System.nanoTime() + LivePlaybackPolicy.retryDelayMs(failures) * 1_000_000
            try {
                while (isCurrent(mine) && System.nanoTime() < wakeAt) Thread.sleep(BACKOFF_STEP_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Plays [playing] until it ends (normal return), fails (throws), or is replaced or stopped. */
    private fun play(playing: VideoSource, generation: Int, mine: Int) {
        quietLogs
        val grabber = FFmpegFrameGrabber(playing.url).apply {
            pixelFormat = avutil.AV_PIX_FMT_BGRA
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            sampleRate = AUDIO_SAMPLE_RATE
            audioChannels = AUDIO_CHANNELS
            // Give up on a read that has had nothing for this long, so a dead stream reconnects.
            setOption("rw_timeout", READ_TIMEOUT_US.toString())
            setOption("reconnect", "1")
            if (playing.headers.isNotEmpty()) {
                setOption("headers", playing.headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" })
            }
            if (playing is VideoSource.Live) {
                // Start from the newest segment rather than three back, which is HLS's default.
                setOption("live_start_index", "-1")
                setOption("fflags", "nobuffer")
                setOption("flags", "low_delay")
            }
            setVideoOption("threads", "auto")
        }
        var line: SourceDataLine? = null
        try {
            grabber.start()
            if (playing is VideoSource.Recording && playing.startPositionMs > 0) grabber.setTimestamp(playing.startPositionMs * 1000, true)
            val audio = grabber.audioChannels > 0
            ui.launch {
                hasAudio = audio
                stats = LiveStreamStats(
                    transport = LiveTransport.HLS,
                    width = grabber.imageWidth.takeIf { it > 0 },
                    height = grabber.imageHeight.takeIf { it > 0 },
                    framesPerSecond = grabber.videoFrameRate.takeIf { it > 0 }?.toInt(),
                    bitrateKbps = grabber.videoBitrate.takeIf { it > 0 }?.let { it / 1000 },
                    codec = grabber.videoCodecName,
                )
            }
            var clockOriginUs = Long.MIN_VALUE
            var clockOriginNanos = 0L
            var lastPositionReport = 0L
            while (isCurrent(mine) && loadGeneration == generation) {
                seekToMs?.let { target ->
                    seekToMs = null
                    grabber.setTimestamp(target * 1000, true)
                    clockOriginUs = Long.MIN_VALUE
                    line?.flush()
                }
                if (!playWhenReady) {
                    clockOriginUs = Long.MIN_VALUE
                    line?.stop()
                    Thread.sleep(PAUSED_POLL_MS)
                    continue
                }
                val wantAudio = audio && !muted
                val frame: Frame = grabber.grabFrame(wantAudio, true, true, false, false) ?: return
                if (frame.samples != null) {
                    if (wantAudio) {
                        line = line ?: openAudioLine()
                        line?.let { writeAudio(it, frame) }
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
                // Pace the picture against the wall clock, from the first frame (or the first after a pause, a seek or a lag).
                val now = System.nanoTime()
                if (clockOriginUs == Long.MIN_VALUE || frame.timestamp < clockOriginUs) {
                    clockOriginUs = frame.timestamp
                    clockOriginNanos = now
                } else {
                    val dueNanos = clockOriginNanos + (frame.timestamp - clockOriginUs) * 1000
                    val waitNanos = dueNanos - now
                    when {
                        waitNanos > 0 -> Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())

                        -waitNanos > MAX_LAG_US * 1000 -> {
                            clockOriginUs = frame.timestamp
                            clockOriginNanos = now
                        }
                    }
                }
                publish(frame, generation)
                if (playing is VideoSource.Recording && now - lastPositionReport > POSITION_REPORT_NANOS) {
                    lastPositionReport = now
                    val at = frame.timestamp / 1000
                    ui.launch { if (loadGeneration == generation) positionMs = at }
                }
            }
        } catch (_: InterruptedException) {
            // Stopped or replaced: nothing to report.
        } finally {
            line?.close()
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /** Copies [frame]'s pixels into a picture of its own and has the UI thread show it. */
    private fun publish(frame: Frame, generation: Int) {
        val width = frame.imageWidth
        val height = frame.imageHeight
        val buffer = (frame.image[0] as? ByteBuffer) ?: return
        val rowBytes = frame.imageStride.takeIf { it >= width * 4 } ?: (width * 4)
        val bytes = ByteArray(rowBytes * height)
        buffer.duplicate().apply { rewind() }.get(bytes, 0, minOf(bytes.size, buffer.limit()))
        val image = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, rowBytes)
        pending.getAndSet(image)?.close()
        lastPictureAtNanos = System.nanoTime()
        ui.launch {
            val next = pending.getAndSet(null) ?: return@launch
            if (loadGeneration != generation && source !is VideoSource.Live) {
                next.close()
                return@launch
            }
            // Drawn pictures hold their own reference to the Skia image, so the one replaced can go now.
            val previous = picture
            picture = next
            previous?.close()
            if (pictureGeneration != coldStartGeneration) pictureGeneration = coldStartGeneration
            if (stalled) stalled = false
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

    private companion object {
        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_CHANNELS = 2

        /** Ten seconds without a byte and the stream is taken for dead. */
        const val READ_TIMEOUT_US = 10_000_000L

        /** How far behind its own timestamps the picture may fall before the clock starts again from it. */
        const val MAX_LAG_US = 1_000_000L

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
