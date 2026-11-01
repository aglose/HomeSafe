package com.meticulouscreations.homesafe.ui.components

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The engine with real FFmpeg, playing small clips from a local server that sends them at the pace
 * of live video and keeps count of who is connected, and moving between them as a quality change does.
 */
class DesktopVideoEngineTest {
    private val server = LiveClipServer()
    private val engine = onUi { DesktopVideoEngine("test") }

    @AfterTest
    fun tearDown() {
        onUi { engine.release() }
        awaitTrue("the decode threads to finish") { decodeThreads() == 0 }
        server.stop()
    }

    @Test
    fun `the stream on screen plays on with no poster until the next has a picture, then lets go`() {
        val sd = server.clip("sd", width = SD_WIDTH, height = 36)
        val hd = server.clip("hd", width = HD_WIDTH, height = 72, joinDelayMs = 3_000)
        onUi { engine.load(VideoSource.Live(sd.url)) }
        awaitTrue("the first stream's picture") { engine.picture?.width == SD_WIDTH }
        val generation = engine.coldStartGeneration

        onUi { engine.load(VideoSource.Live(hd.url)) }
        val atSwitch = engine.picture

        awaitTrue("the first stream to keep moving during the join") { engine.picture !== atSwitch }
        assertEquals(SD_WIDTH, engine.picture?.width, "the new stream is still being held back by the server")
        var posterSeen = false
        awaitTrue("the next stream's picture") {
            posterSeen = posterSeen || engine.pictureGeneration != engine.coldStartGeneration
            engine.picture?.width == HD_WIDTH
        }
        assertEquals(generation, engine.coldStartGeneration, "not a cold start, so no poster")
        assertFalse(posterSeen, "the poster showed during the switch")

        awaitTrue("the first stream's connection to close") { sd.connections.get() == 0 }
        awaitTrue("the first stream's decode thread to finish") { decodeThreads() == 1 }
        repeat(STALE_CHECKS) {
            Thread.sleep(STALE_CHECK_STEP_MS)
            assertEquals(HD_WIDTH, engine.picture?.width, "an old frame came back after the switch")
        }

        // And back again.
        onUi { engine.load(VideoSource.Live(sd.url)) }
        awaitTrue("the first stream's picture again") { engine.picture?.width == SD_WIDTH }
        assertEquals(generation, engine.coldStartGeneration)
        awaitTrue("the second stream's connection to close") { hd.connections.get() == 0 }
    }

    @Test
    fun `changing mind during a join drops the join and keeps the picture on screen until the last choice lands`() {
        val sd = server.clip("sd", width = SD_WIDTH, height = 36)
        val slow = server.clip("slow", width = HD_WIDTH, height = 72, joinDelayMs = 60_000)
        val other = server.clip("other", width = OTHER_WIDTH, height = 54, joinDelayMs = 3_000)
        onUi { engine.load(VideoSource.Live(sd.url)) }
        awaitTrue("the first stream's picture") { engine.picture?.width == SD_WIDTH }

        onUi { engine.load(VideoSource.Live(slow.url)) }
        onUi { engine.load(VideoSource.Live(other.url)) }

        val shown = engine.picture
        awaitTrue("the first stream to keep moving") { engine.picture !== shown }
        assertEquals(SD_WIDTH, engine.picture?.width)
        awaitTrue("the last choice's picture") { engine.picture?.width == OTHER_WIDTH }
        awaitTrue("the first stream's connection to close") { sd.connections.get() == 0 }
    }

    @Test
    fun `a join that never drew stays cancelled if the stream on screen ended before the next choice`() {
        val ending = server.clip("ending", width = SD_WIDTH, height = 36, seconds = 1)
        val cancelled = server.clip("cancelled", width = HD_WIDTH, height = 72, joinDelayMs = 3_000)
        val chosen = server.clip("chosen", width = OTHER_WIDTH, height = 54, joinDelayMs = 6_000)
        onUi { engine.load(VideoSource.Live(ending.url)) }
        awaitTrue("the first stream's picture") { engine.picture?.width == SD_WIDTH }
        onUi { engine.load(VideoSource.Live(cancelled.url)) }
        awaitTrue("the first stream to end") { ending.connections.get() == 0 && decodeThreads() == 1 }

        onUi { engine.load(VideoSource.Live(chosen.url)) }

        awaitTrue("the last choice's picture") {
            assertTrue(engine.picture?.width != HD_WIDTH, "the cancelled stream was drawn")
            engine.picture?.width == OTHER_WIDTH
        }
    }

    @Test
    fun `a slow camera doesn't hold up another camera's start`() {
        val slow = server.clip("slow", width = HD_WIDTH, height = 72, joinDelayMs = 60_000)
        val quick = server.clip("quick", width = SD_WIDTH, height = 36)
        val slowEngine = onUi { DesktopVideoEngine("slow") }
        try {
            onUi { slowEngine.load(VideoSource.Live(slow.url)) }
            awaitTrue("the slow camera's join to begin") { slow.requests.get() == 1 }
            onUi { engine.load(VideoSource.Live(quick.url)) }
            // Sooner than the slow camera's join gives up (the engine's read timeout), which would free anything it held.
            awaitTrue("the quick camera's picture", withinNanos = BEFORE_READ_TIMEOUT_NANOS) { engine.picture?.width == SD_WIDTH }
        } finally {
            onUi { slowEngine.release() }
        }
    }

    @Test
    fun `a recording is a cold start`() {
        val live = server.clip("live", width = SD_WIDTH, height = 36)
        val recording = server.clip("recording", width = HD_WIDTH, height = 72)
        onUi { engine.load(VideoSource.Live(live.url)) }
        awaitTrue("the live picture") { engine.picture?.width == SD_WIDTH }
        val generation = engine.coldStartGeneration

        onUi { engine.load(VideoSource.Recording(recording.url, headers = emptyMap(), startPositionMs = 0)) }

        assertEquals(generation + 1, engine.coldStartGeneration)
        awaitTrue("the live stream's connection to close") { live.connections.get() == 0 }
        awaitTrue("the recording's picture") { engine.picture?.width == HD_WIDTH }
    }

    @Test
    fun `a paused live stream lets go of its connection and joins again on resuming, keeping its picture`() {
        val clip = server.clip("live", width = SD_WIDTH, height = 36)
        onUi { engine.load(VideoSource.Live(clip.url)) }
        awaitTrue("the picture") { engine.picture?.width == SD_WIDTH }
        val generation = engine.coldStartGeneration

        onUi { engine.setPlayWhenReady(false) }
        awaitTrue("the connection to close") { clip.connections.get() == 0 }
        Thread.sleep(STALE_CHECK_STEP_MS)
        val held = engine.picture
        assertTrue(held != null, "the last picture stays up while paused")

        onUi { engine.setPlayWhenReady(true) }
        awaitTrue("the picture to move again") { engine.picture !== held }
        assertNotSame(held, engine.picture)
        assertEquals(generation, engine.coldStartGeneration)
        assertEquals(1, decodeThreads())
    }

    @Test
    fun `a pause during a long back-off is not waited out on resuming`() {
        val clip = server.clip("flaky", width = SD_WIDTH, height = 36)
        clip.refusing.set(true)
        onUi { engine.load(VideoSource.Live(clip.url)) }
        // A try after a wait of four seconds, so the next is eight seconds away.
        awaitTrue("the back-off to grow") { clip.longestRequestGapMs.get() >= LONG_BACKOFF_MS }

        onUi { engine.setPlayWhenReady(false) }
        clip.refusing.set(false)
        Thread.sleep(PAUSE_OBSERVED_MS)
        onUi { engine.setPlayWhenReady(true) }

        awaitTrue("the picture, well inside that back-off", withinNanos = SHORTER_THAN_BACKOFF_NANOS) { engine.picture?.width == SD_WIDTH }
    }

    @Test
    fun `a go2rtc playlist plays as its continuous stream, said to be silent`() {
        val silent = server.continuousClip("silent", width = SD_WIDTH, height = 36)
        onUi { engine.load(VideoSource.Live(silent.url)) }
        awaitTrue("the picture") { engine.picture?.width == SD_WIDTH }
        awaitTrue("the stats") { engine.stats != null }
        assertEquals(1, silent.requests.get())
        assertEquals(LiveTransport.FMP4, engine.stats?.transport)
        assertEquals(SD_WIDTH, engine.stats?.width)
        assertEquals(36, engine.stats?.height)
        assertFalse(engine.hasAudio)
    }

    @Test
    fun `a continuous stream with a sound track says so, unsampled`() {
        val talking = server.continuousClip("talking", width = SD_WIDTH, height = 36, withAudio = true)
        onUi { engine.load(VideoSource.Live(talking.url + "&video&audio=opus,aac")) }
        awaitTrue("the picture") { engine.picture?.width == SD_WIDTH }
        awaitTrue("the sound track to be noticed") { engine.hasAudio }
        assertEquals(1, talking.requests.get())
    }

    @Test
    fun `the sound button goes on being about the stream on screen until the next has a picture`() {
        val talking = server.continuousClip("talking", width = SD_WIDTH, height = 36, withAudio = true)
        // Opened, and for a while nothing to show for it: when the button used to change too soon.
        val silent = server.continuousClip("silent", width = HD_WIDTH, height = 72, firstFrameDelayMs = 2_000)
        onUi { engine.load(VideoSource.Live(talking.url + "&video&audio=opus,aac")) }
        awaitTrue("the first stream's sound track") { engine.hasAudio }

        onUi { engine.load(VideoSource.Live(silent.url)) }
        awaitTrue("the next stream's picture") {
            assertTrue(engine.hasAudio || engine.picture?.width == HD_WIDTH, "said to be silent while the stream with sound was on screen")
            engine.picture?.width == HD_WIDTH
        }
        awaitTrue("the sound button to follow the picture") { !engine.hasAudio }
    }

    @Test
    fun `a recording's last picture is shown even if the UI gets to it after the end`() {
        val recording = server.clip("short", width = HD_WIDTH, height = 72, seconds = 1)
        val uiFree = CountDownLatch(1)
        onUi { engine.load(VideoSource.Recording(recording.url, headers = emptyMap(), startPositionMs = 0)) }
        SwingUtilities.invokeLater { uiFree.await() }

        try {
            awaitTrue("the recording to play to its end") { decodeThreads() == 0 }
        } finally {
            uiFree.countDown()
        }

        awaitTrue("the end to be reported") { engine.ended == 1 }
        assertEquals(HD_WIDTH, engine.picture?.width)
        assertEquals(engine.coldStartGeneration, engine.pictureGeneration)
    }

    /** [DesktopVideoEngine] hands the same array over for every frame, which only works if a picture keeps its own copy. */
    @Test
    fun `a raster image keeps its own copy of the pixels it was made from`() {
        val pixels = ByteArray(4) { 0x11 }
        Image.makeRaster(ImageInfo(1, 1, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, 4).use { image ->
            pixels.fill(0x55)
            val bitmap = Bitmap.makeFromImage(image)
            assertEquals(0x11, bitmap.getColor(0, 0) and 0xFF)
        }
    }

    private fun decodeThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "HomeSafe video test" }

    private fun awaitTrue(what: String, withinNanos: Long = AWAIT_NANOS, check: () -> Boolean) {
        val deadline = System.nanoTime() + withinNanos
        while (!check()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for $what")
            Thread.sleep(AWAIT_STEP_MS)
        }
    }

    private fun <T> onUi(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private companion object {
        const val SD_WIDTH = 64
        const val OTHER_WIDTH = 96
        const val HD_WIDTH = 128
        const val STALE_CHECKS = 10
        const val STALE_CHECK_STEP_MS = 50L
        const val AWAIT_STEP_MS = 5L
        const val AWAIT_NANOS = 30_000_000_000L
        const val LONG_BACKOFF_MS = 3_500L
        const val PAUSE_OBSERVED_MS = 300L
        const val SHORTER_THAN_BACKOFF_NANOS = 5_000_000_000L
        const val BEFORE_READ_TIMEOUT_NANOS = 8_000_000_000L
    }
}

/**
 * Serves [clip]s over HTTP as a live server would: the opening at once, then the rest at the speed
 * it plays. A client that goes away is noticed on the next write, and uncounted.
 */
private class LiveClipServer {
    class Clip(val url: String) {
        val connections = AtomicInteger(0)
        val requests = AtomicInteger(0)

        /** Answers every request with a 503 while set. */
        val refusing = AtomicBoolean(false)

        /** The longest wait between two requests so far. */
        val longestRequestGapMs = AtomicLong(0)
        val lastRequestAtMs = AtomicLong(0)
    }

    private class ContinuousClip(val clip: Clip, val bytes: ByteArray, val firstFrameDelayMs: Long)

    private val continuousClips = ConcurrentHashMap<String, ContinuousClip>()

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        start()
    }.also { server ->
        server.createContext("/api/stream.mp4") { exchange ->
            val name = exchange.requestURI.query.split('&').first().substringAfter("src=")
            val served = continuousClips.getValue(name)
            serve(exchange, served.clip, served.bytes, "video/mp4", joinDelayMs = 0, CLIP_SECONDS, served.firstFrameDelayMs)
        }
    }

    fun clip(name: String, width: Int, height: Int, joinDelayMs: Long = 0, seconds: Int = CLIP_SECONDS): Clip {
        val bytes = encodeClip(width, height, format = "mpegts", withAudio = false, seconds = seconds)
        val clip = Clip("http://127.0.0.1:${http.address.port}/$name.ts")
        http.createContext("/$name.ts") { exchange -> serve(exchange, clip, bytes, "video/mp2t", joinDelayMs, seconds) }
        return clip
    }

    /** A clip at the address of a go2rtc playlist, served only as what that playlist's continuous stream would be: fragmented MP4. */
    fun continuousClip(name: String, width: Int, height: Int, withAudio: Boolean = false, firstFrameDelayMs: Long = 0): Clip {
        val clip = Clip("http://127.0.0.1:${http.address.port}/api/stream.m3u8?src=$name")
        continuousClips[name] = ContinuousClip(clip, encodeClip(width, height, format = "mp4", withAudio = withAudio, seconds = CLIP_SECONDS), firstFrameDelayMs)
        return clip
    }

    private fun serve(exchange: HttpExchange, clip: Clip, bytes: ByteArray, contentType: String, joinDelayMs: Long, seconds: Int, firstFrameDelayMs: Long = 0) {
        clip.requests.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = clip.lastRequestAtMs.getAndSet(now)
        if (last != 0L) clip.longestRequestGapMs.accumulateAndGet(now - last, ::maxOf)
        if (clip.refusing.get()) {
            exchange.sendResponseHeaders(HTTP_UNAVAILABLE, -1)
            exchange.close()
            return
        }
        clip.connections.incrementAndGet()
        try {
            Thread.sleep(joinDelayMs)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body -> sendAtLivePace(bytes, body, seconds, firstFrameDelayMs) }
        } catch (_: IOException) {
            // The client went away.
        } catch (_: InterruptedException) {
            // The test is over.
        } finally {
            clip.connections.decrementAndGet()
            exchange.close()
        }
    }

    fun stop() = http.stop(0)

    private fun sendAtLivePace(bytes: ByteArray, body: java.io.OutputStream, seconds: Int, firstFrameDelayMs: Long) {
        val perStep = (bytes.size * WRITE_STEP_MS / (seconds * 1000)).coerceAtLeast(1)
        var sent = 0
        if (firstFrameDelayMs > 0) {
            // Up to the first frame's data: FFmpeg opens an fMP4 once it reaches that, and has no picture yet.
            sent = String(bytes, Charsets.ISO_8859_1).indexOf("mdat") + BOX_NAME_BYTES
            body.write(bytes, 0, sent)
            body.flush()
            Thread.sleep(firstFrameDelayMs)
        }
        val opening = minOf(bytes.size - sent, perStep * OPENING_STEPS)
        body.write(bytes, sent, opening)
        body.flush()
        sent += opening
        while (sent < bytes.size) {
            Thread.sleep(WRITE_STEP_MS.toLong())
            val step = minOf(perStep, bytes.size - sent)
            body.write(bytes, sent, step)
            body.flush()
            sent += step
        }
    }

    private fun encodeClip(width: Int, height: Int, format: String, withAudio: Boolean, seconds: Int): ByteArray {
        val file = File.createTempFile("homesafe-clip", ".$format")
        try {
            FFmpegFrameRecorder(file, width, height, if (withAudio) 1 else 0).run {
                this.format = format
                // As go2rtc sends it: the opening at once, with no index to seek back for.
                if (format == "mp4") setOption("movflags", "empty_moov+default_base_moof+frag_keyframe")
                if (withAudio) {
                    audioCodec = avcodec.AV_CODEC_ID_AAC
                    sampleRate = AUDIO_RATE
                }
                videoCodec = avcodec.AV_CODEC_ID_MPEG4
                pixelFormat = avutil.AV_PIX_FMT_YUV420P
                frameRate = CLIP_FPS.toDouble()
                gopSize = CLIP_FPS / 2
                start()
                val frame = Frame(width, height, Frame.DEPTH_UBYTE, 3)
                val pixels = frame.image[0] as ByteBuffer
                repeat(seconds * CLIP_FPS) { index ->
                    pixels.clear()
                    repeat(pixels.capacity()) { pixels.put((index + it).toByte()) }
                    pixels.clear()
                    record(frame)
                    if (withAudio) recordSamples(AUDIO_RATE, 1, ShortBuffer.allocate(AUDIO_RATE / CLIP_FPS))
                }
                stop()
                release()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val CLIP_SECONDS = 60
        const val CLIP_FPS = 20
        const val WRITE_STEP_MS = 20
        const val OPENING_STEPS = 100
        const val AUDIO_RATE = 48_000
        const val HTTP_UNAVAILABLE = 503
        const val BOX_NAME_BYTES = 4
    }
}
