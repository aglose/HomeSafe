package com.meticulouscreations.homesafe.car3d

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import homesafe.shared.generated.resources.Res
import kotlin.math.max
import kotlin.math.roundToInt

internal actual val carTier = MOBILE_TIER

internal actual val carDrawsLive = true

internal actual suspend fun readCarFile(name: String): ByteArray = Res.readBytes("files/car/$name")

@Composable
internal actual fun CarSurface(model: CarModel, orbit: CarOrbitState, onFirstFrame: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val view = remember { CarTextureView(context) { firstFrame() } }
    AndroidView(factory = { view }, modifier = modifier)
    LaunchedEffect(model) { view.show(model, loadCarAssets(model)) }
    LaunchedEffect(view) { snapshotFlow { orbit.camera }.collect(view::look) }
    DisposableEffect(view) { onDispose(view::close) }
}

/**
 * A view with its own GL thread. Compose can draw over and clip it, which a SurfaceView can't do.
 * It draws only when asked, so a car sitting still costs nothing.
 */
private class CarTextureView(context: Context, private val firstFrame: () -> Unit) :
    TextureView(context),
    TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("car-gl").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var egl: CarEgl? = null
    private var scene: CarScene? = null
    private var pending: Pair<CarModel, CarAssets>? = null
    private var shown: Pair<CarModel, CarAssets>? = null
    private var camera = CarCamera(CarOrbitState.START_AZIMUTH, CarOrbitState.START_ELEVATION)
    private var width = 0
    private var height = 0
    private var drewOnce = false
    private var queued = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun show(model: CarModel, assets: CarAssets) {
        handler.post {
            pending = model to assets
            requestDraw()
        }
    }

    fun look(newCamera: CarCamera) {
        handler.post {
            camera = newCamera
            requestDraw()
        }
    }

    private fun requestDraw() {
        if (queued) return
        queued = true
        handler.post {
            queued = false
            draw()
        }
    }

    private fun draw() {
        val current = egl ?: return
        if (width == 0 || height == 0) return
        current.makeCurrent()
        pending?.let { (model, assets) ->
            scene?.release()
            scene = CarScene(AndroidGl(), model.look, assets, carTier)
            shown = model to assets
            pending = null
        }
        val toDraw = scene ?: return
        toDraw.draw(width, height, camera, showroomFloor = true)
        current.swap()
        if (!drewOnce) {
            drewOnce = true
            main.post(firstFrame)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, surfaceWidth: Int, surfaceHeight: Int) {
        resize(surface, surfaceWidth, surfaceHeight)
        handler.post {
            egl = CarEgl(surface, carTier.samples)
            requestDraw()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, surfaceWidth: Int, surfaceHeight: Int) {
        resize(surface, surfaceWidth, surfaceHeight)
        handler.post(::requestDraw)
    }

    /** A big phone in a full-screen view has more pixels than the eye gets from the car: held down to the tier's size. */
    private fun resize(surface: SurfaceTexture, surfaceWidth: Int, surfaceHeight: Int) {
        val scale = minOf(1f, carTier.maxLongEdgePx.toFloat() / max(surfaceWidth, surfaceHeight))
        val w = (surfaceWidth * scale).roundToInt()
        val h = (surfaceHeight * scale).roundToInt()
        surface.setDefaultBufferSize(w, h)
        handler.post {
            width = w
            height = h
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Waits for the GL thread: after this returns the surface is gone.
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            releaseGl()
            done.countDown()
        }
        done.await()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun releaseGl() {
        egl?.makeCurrent()
        scene?.release()
        scene = null
        // The GPU's copy went with the context: the next surface makes it again.
        if (pending == null) pending = shown
        egl?.close()
        egl = null
        drewOnce = false
    }

    fun close() {
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }
}

/** An OpenGL ES 3 context on a [SurfaceTexture], multisampled where the GPU offers it. */
private class CarEgl(texture: SurfaceTexture, samples: Int) {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no EGL display" }
        val config = choose(samples) ?: choose(0) ?: error("no EGL config for GLES 3")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
    }

    private fun choose(samples: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
            EGL14.EGL_SAMPLES, samples,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (found && count[0] > 0) configs[0] else null
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun swap() {
        EGL14.eglSwapBuffers(display, surface)
    }

    fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }
}
