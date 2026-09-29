package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

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

    init {
        isOpaque = false
        renderer.init(sharedContext, EglBase.CONFIG_RGBA, GlRectDrawer())
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                renderer.createEglSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                val done = CountDownLatch(1)
                renderer.releaseEglSurface { done.countDown() }
                ThreadUtils.awaitUninterruptibly(done)
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

    /** What the surface is showing right now, or null if it has no layer to read. */
    fun snapshot(): Bitmap? = if (!released && isAvailable) runCatching { bitmap }.getOrNull() else null

    override fun onFrame(frame: VideoFrame) {
        renderer.onFrame(frame)
    }

    /** Paints the view transparent, so whatever is stacked beneath it shows through until the next frame. */
    fun clear() {
        peerFrameGeneration = -1
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
