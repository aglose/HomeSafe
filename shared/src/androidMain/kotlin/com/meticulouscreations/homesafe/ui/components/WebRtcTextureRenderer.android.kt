package com.meticulouscreations.homesafe.ui.components

import android.content.Context
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
 * [onFrameRendered] fires on the main thread each time the surface has taken a new frame —
 * `onSurfaceTextureUpdated`, i.e. after the render thread has actually swapped it in, not when a
 * frame was merely handed to the renderer. Callers that only care about the first one keep
 * their own flag.
 */
internal class WebRtcTextureRenderer(
    context: Context,
    sharedContext: EglBase.Context?,
) : TextureView(context),
    VideoSink {

    private val renderer = EglRenderer("HomeSafeLive")
    private var released = false

    var onFrameRendered: (() -> Unit)? = null

    init {
        isOpaque = false
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
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

    override fun onFrame(frame: VideoFrame) {
        renderer.onFrame(frame)
    }

    /** Paints the view transparent, so whatever is stacked beneath it shows through until the next frame. */
    fun clear() {
        if (!released) renderer.clearImage()
    }

    fun release() {
        if (released) return
        released = true
        onFrameRendered = null
        renderer.release()
    }
}
