package com.meticulouscreations.homesafe.car3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

internal actual val carTier = HIGH_TIER

internal actual val carDrawsLive: Boolean = "mac" in System.getProperty("os.name").lowercase()

internal actual suspend fun readCarFile(name: String): ByteArray =
    checkNotNull(CarTier::class.java.classLoader.getResourceAsStream("car/$name")) { "no car file $name" }.use { it.readBytes() }

/**
 * Compose's window can't be drawn on, and a heavyweight panel would sit over everything: so the car
 * is drawn offscreen by the GPU and shown as a picture, a frame for each thing that moves it.
 */
@Composable
internal actual fun CarSurface(model: CarModel, orbit: CarOrbitState, onFirstFrame: () -> Unit, modifier: Modifier) {
    val firstFrame by rememberUpdatedState(onFirstFrame)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(model) {
        val assets = loadCarAssets(model)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "car-gl").apply { isDaemon = true } }
        val gpu = executor.asCoroutineDispatcher()
        // Kept here as they are made, on the GPU thread, so a cancellation that discards the
        // result of the block below still leaves them for the cleanup to find.
        var made: MacOffscreen? = null
        var madeScene: CarScene? = null
        var pixels: ByteBuffer? = null
        var bytes = ByteArray(0)
        try {
            withContext(gpu) {
                made = MacOffscreen(carTier.samples, supersample = 2)
                madeScene = CarScene(DesktopGl(), model.look, assets, carTier)
            }
            val offscreen = checkNotNull(made)
            val scene = checkNotNull(madeScene)
            // Drawn on the GL thread, shown from this one. Conflated, not cancelled: while a drag
            // outruns the GPU it shows each frame that finishes, and skips to the newest camera.
            snapshotFlow { size to orbit.camera }.conflate().collect { (shown, camera) ->
                if (shown.width == 0 || shown.height == 0) return@collect
                frame = withContext(gpu) {
                    val scale = minOf(1f, carTier.maxLongEdgePx.toFloat() / max(shown.width, shown.height))
                    val width = (shown.width * scale).roundToInt()
                    val height = (shown.height * scale).roundToInt()
                    val needed = width * height * 4
                    val buffer = pixels?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also {
                        pixels = it
                        bytes = ByteArray(needed)
                    }
                    offscreen.resize(width, height)
                    offscreen.render(buffer) { w, h -> scene.draw(w, h, camera, showroomFloor = true) }
                    buffer.get(bytes).clear()
                    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
                    Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
                }
                firstFrame()
            }
        } finally {
            withContext(NonCancellable + gpu) {
                madeScene?.release()
                made?.close()
            }
            executor.shutdown()
        }
    }
    // Measured in a real window: the latest thing drawn can stay off the screen until later draws
    // push it out, so on opening the poster would stay up, and a car coming to rest would hold its
    // last frame back. So it keeps drawing for a moment once a frame has settled, as an animation
    // would; a drag replaces the frame before this gets anywhere, so it costs nothing while moving.
    var settled by remember { mutableIntStateOf(0) }
    LaunchedEffect(frame) {
        if (frame == null) return@LaunchedEffect
        delay(SETTLE_WAIT_MILLIS)
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < SETTLE_REDRAW_MILLIS) settled++
    }
    Box(modifier.onSizeChanged { size = it }) {
        val image = frame
        if (image != null) {
            Canvas(Modifier.matchParentSize()) {
                settled // read here, so only this draw runs again
                drawImage(image, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()))
            }
        }
    }
}

private const val SETTLE_WAIT_MILLIS = 100L
private const val SETTLE_REDRAW_MILLIS = 1500L
