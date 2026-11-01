package com.meticulouscreations.homesafe.ui.components

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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
        val hd = server.clip("hd", width = HD_WIDTH, height = 72, joinDelayMs = 1_500)
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
        val other = server.clip("other", width = OTHER_WIDTH, height = 54, joinDelayMs = 500)
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
    fun `a slow camera doesn't hold up another camera's start`() {
        val slow = server.clip("slow", width = HD_WIDTH, height = 72, joinDelayMs = 60_000)
        val quick = server.clip("quick", width = SD_WIDTH, height = 36)
        val slowEngine = onUi { DesktopVideoEngine("slow") }
        try {
            onUi { slowEngine.load(VideoSource.Live(slow.url)) }
            awaitTrue("the slow camera's join to begin") { slow.requests.get() == 1 }
            onUi { engine.load(VideoSource.Live(quick.url)) }
            awaitTrue("the quick camera's picture") { engine.picture?.width == SD_WIDTH }
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

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + AWAIT_NANOS
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
    }

    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        start()
    }

    fun clip(name: String, width: Int, height: Int, joinDelayMs: Long = 0): Clip {
        val bytes = encodeClip(width, height)
        val clip = Clip("http://127.0.0.1:${http.address.port}/$name.ts")
        http.createContext("/$name.ts") { exchange ->
            clip.requests.incrementAndGet()
            clip.connections.incrementAndGet()
            try {
                Thread.sleep(joinDelayMs)
                exchange.responseHeaders.add("Content-Type", "video/mp2t")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { body -> sendAtLivePace(bytes, body) }
            } catch (_: IOException) {
                // The client went away.
            } catch (_: InterruptedException) {
                // The test is over.
            } finally {
                clip.connections.decrementAndGet()
                exchange.close()
            }
        }
        return clip
    }

    fun stop() = http.stop(0)

    private fun sendAtLivePace(bytes: ByteArray, body: java.io.OutputStream) {
        val perStep = (bytes.size * WRITE_STEP_MS / (CLIP_SECONDS * 1000)).coerceAtLeast(1)
        val opening = minOf(bytes.size, perStep * OPENING_STEPS)
        body.write(bytes, 0, opening)
        body.flush()
        var sent = opening
        while (sent < bytes.size) {
            Thread.sleep(WRITE_STEP_MS.toLong())
            val step = minOf(perStep, bytes.size - sent)
            body.write(bytes, sent, step)
            body.flush()
            sent += step
        }
    }

    private fun encodeClip(width: Int, height: Int): ByteArray {
        val file = File.createTempFile("homesafe-clip", ".ts")
        try {
            FFmpegFrameRecorder(file, width, height).run {
                format = "mpegts"
                videoCodec = avcodec.AV_CODEC_ID_MPEG4
                pixelFormat = avutil.AV_PIX_FMT_YUV420P
                frameRate = CLIP_FPS.toDouble()
                gopSize = CLIP_FPS / 2
                start()
                val frame = Frame(width, height, Frame.DEPTH_UBYTE, 3)
                val pixels = frame.image[0] as ByteBuffer
                repeat(CLIP_SECONDS * CLIP_FPS) { index ->
                    pixels.clear()
                    repeat(pixels.capacity()) { pixels.put((index + it).toByte()) }
                    pixels.clear()
                    record(frame)
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
    }
}
