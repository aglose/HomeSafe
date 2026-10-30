package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.meticulouscreations.homesafe.tracing.traceSection
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * How often a renderer copies what it draws for [WebRtcTextureRenderer.recentFrame]: the kept
 * picture is at most this much older than the last one shown. Each copy is one small draw and
 * read-back on libwebrtc's render thread, never the main thread.
 */
private const val FRAME_COPY_INTERVAL_NS = 1_000_000_000L

/**
 * A `TextureView` that draws a WebRTC video track through libwebrtc's [EglRenderer].
 *
 * Why not libwebrtc's own `SurfaceViewRenderer`: a `SurfaceView` punches a hole in the window
 * and won't composite under the poster and over the card background (the same reason the HLS
 * player uses a bare `TextureView`), and both stock renderers fit the frame by its aspect
 * ratio. This one never sets a layout aspect ratio, so the frame is stretched to the box —
 * the rule every live surface in the app follows (see `CameraStreamPlayer`).
 *
 * The EGL surface follows the `SurfaceTexture`: created when the view gets one, released —
 * synchronously, since the texture is gone the moment the callback returns — when it's
 * destroyed. [release] tears the renderer down; the view is dead after that.
 *
 * The surface has an alpha channel ([EglBase.CONFIG_RGBA]), and that is what makes [clear]
 * work. libwebrtc's default config asks for red, green and blue only, and many GPUs then hand
 * back an RGBX surface: clearing that to "transparent" paints it opaque black. Since this view
 * sits over the HLS surface, a holder falling back to HLS — typically a full-quality upgrade
 * whose WebRTC join failed — played its video, and its sound, under a black rectangle for as long
 * as it stayed on HLS. Video frames are unaffected: both of libwebrtc's shaders write alpha 1.
 *
 * [onFrameRendered] fires on the main thread each time the surface has taken a new frame —
 * `onSurfaceTextureUpdated`, i.e. after the render thread has actually swapped it in, not when a
 * frame was merely handed to the renderer. Callers that only care about the first one keep
 * their own flag.
 *
 * [peerFrameGeneration] and [onDetaching] exist so the picture outlives the view: the holder
 * copies what this surface is showing ([snapshot]) for the next surface bound to the same
 * camera — see `LivePlayerHolder.bridgeFrame`.
 *
 * Copying a `TextureView` ([snapshot]) makes the main thread wait for the render thread to read
 * its layer back, and as Home's cards go (a drawer app opening over them) that render thread is
 * busy with the new screen: 4 ms a camera on average, 15 at worst, in the frame that should be
 * moving (docs/perf-tracing.md). So the renderer also keeps a copy of its own as it draws
 * ([recentFrame]), about once every [FRAME_COPY_INTERVAL_NS], read back on libwebrtc's render
 * thread at the view's width, and the holder keeps that one instead when it has it.
 */
internal class WebRtcTextureRenderer(
    context: Context,
    sharedContext: EglBase.Context?,
) : TextureView(context),
    VideoSink {

    private val renderer = EglRenderer("HomeSafeLive")
    private var released = false

    var onFrameRendered: (() -> Unit)? = null

    /**
     * The holder's cold-start generation this surface is showing a peer frame for, or -1 while it
     * shows nothing of the peer's (new, or [clear]ed). Set by the binder, which is what can tell a
     * peer frame from the swap a clear makes.
     */
    var peerFrameGeneration = -1

    /** Runs as the view leaves the window, while its layer can still be read. */
    var onDetaching: (() -> Unit)? = null

    /** The width of the surface frames are drawn to, or 0 while there is none (and the renderer drops them). */
    @Volatile private var surfaceWidth = 0

    /** The last copy [askForCopy] got back, made on libwebrtc's render thread. */
    @Volatile private var copy: FrameCopy? = null

    private class FrameCopy(val bitmap: Bitmap, val epoch: Int)

    /** Bumped by [clear], so a copy of a picture since wiped never comes back as this surface's. */
    @Volatile private var copyEpoch = 0

    /** A copy asked for and not yet made: libwebrtc makes it from the next frame it draws. */
    @Volatile private var copyPending = false

    /** Decode thread only. */
    private var lastCopyAskedAtNs = 0L

    init {
        isOpaque = false
        renderer.init(sharedContext, EglBase.CONFIG_RGBA, GlRectDrawer())
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                renderer.createEglSurface(surface)
                surfaceWidth = width
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceWidth = width
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceWidth = 0
                traceSection("live.renderer.releaseEglSurface") {
                    val done = CountDownLatch(1)
                    renderer.releaseEglSurface { done.countDown() }
                    ThreadUtils.awaitUninterruptibly(done)
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                onFrameRendered?.invoke()
            }
        }
    }

    override fun onDetachedFromWindow() {
        // Before super: TextureView drops its layer right after this, and [snapshot] reads it.
        onDetaching?.invoke()
        super.onDetachedFromWindow()
    }

    /** What the surface is showing right now, or null if it has no layer to read. Waits on the render thread. */
    fun snapshot(): Bitmap? = if (!released && isAvailable) runCatching { bitmap }.getOrNull() else null

    /** A copy of what the surface drew at most about [FRAME_COPY_INTERVAL_NS] before its last frame, without waiting; null before the first. */
    fun recentFrame(): Bitmap? = copy?.takeIf { it.epoch == copyEpoch }?.bitmap

    override fun onFrame(frame: VideoFrame) {
        // Asked before the frame is handed over, so the copy is of this one: both go to the render thread in order.
        askForCopy(frame)
        renderer.onFrame(frame)
    }

    /** On libwebrtc's decode thread. */
    private fun askForCopy(frame: VideoFrame) {
        val width = surfaceWidth
        val now = System.nanoTime()
        // With no surface the frame is dropped undrawn, and a listener would wait for one that is drawn.
        if (copyPending || width <= 0 || (lastCopyAskedAtNs != 0L && now - lastCopyAskedAtNs < FRAME_COPY_INTERVAL_NS)) return
        copyPending = true
        lastCopyAskedAtNs = now
        val epoch = copyEpoch
        // At the view's width, as a copy of the view would be: a full-quality stream is several times that.
        val scale = (width.toFloat() / frame.rotatedWidth).coerceAtMost(1f)
        renderer.addFrameListener({ bitmap ->
            copy = FrameCopy(bitmap, epoch)
            copyPending = false
        }, scale)
    }

    /** Paints the view transparent, so whatever is stacked beneath it shows through until the next frame. */
    fun clear() {
        peerFrameGeneration = -1
        copyEpoch++
        if (!released) renderer.clearImage()
    }

    fun release() {
        if (released) return
        released = true
        onFrameRendered = null
        onDetaching = null
        renderer.release()
    }
}
