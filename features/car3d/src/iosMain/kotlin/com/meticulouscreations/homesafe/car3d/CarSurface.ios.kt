@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.meticulouscreations.homesafe.car3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import homesafe.shared.generated.resources.Res
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectZero
import platform.CoreGraphics.CGSize
import platform.Metal.MTLClearColorMake
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatDepth32Float
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.UIKit.UIScreen
import platform.UIKit.UIViewContentMode
import platform.darwin.NSObject
import kotlin.math.max

internal actual val carTier = MOBILE_TIER

internal actual val carDrawsLive = true

internal actual suspend fun readCarFile(name: String): ByteArray = Res.readBytes("files/car/$name")

@Composable
internal actual fun CarSurface(model: CarModel, orbit: CarOrbitState, onFirstFrame: () -> Unit, modifier: Modifier) {
    val firstFrame by rememberUpdatedState(onFirstFrame)
    val renderer = remember { CarMetalRenderer { firstFrame() } }
    // Not interactive: the touches go to Compose, whose gestures turn the car.
    UIKitView(factory = renderer::view, modifier = modifier, properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false))
    LaunchedEffect(model) { renderer.show(model, loadCarAssets(model)) }
    LaunchedEffect(renderer) { snapshotFlow { orbit.camera }.collect(renderer::look) }
    DisposableEffect(renderer) { onDispose(renderer::close) }
}

private val COLOR_FORMAT = MTLPixelFormatBGRA8Unorm
private val DEPTH_FORMAT = MTLPixelFormatDepth32Float
private const val SAMPLES = 4

/** MetalKit's view draws on the main thread when it's asked to, which is only when something moved. */
private class CarMetalRenderer(private val firstFrame: () -> Unit) :
    NSObject(),
    MTKViewDelegateProtocol {
    private val device: MTLDeviceProtocol = checkNotNull(MTLCreateSystemDefaultDevice()) { "no Metal device" }
    private val queue = checkNotNull(device.newCommandQueue()) { "no Metal queue" }
    private val gl = MetalGl(device, COLOR_FORMAT, DEPTH_FORMAT, SAMPLES)
    private var metalView: MTKView? = null
    private var scene: CarScene? = null
    private var pending: Pair<CarModel, CarAssets>? = null
    private var camera = CarCamera(CarOrbitState.START_AZIMUTH, CarOrbitState.START_ELEVATION)
    private var drewOnce = false

    fun view(): MTKView {
        val made = MTKView(frame = CGRectZero.readValue(), device = device)
        made.delegate = this
        made.colorPixelFormat = COLOR_FORMAT
        made.depthStencilPixelFormat = DEPTH_FORMAT
        made.sampleCount = SAMPLES.toULong()
        // Compose cuts a hole for a UIKit view, so what shows round the car is this view's, not the page's.
        made.clearColor = MTLClearColorMake(CAR_BACKDROP_RED.toDouble(), CAR_BACKDROP_GREEN.toDouble(), CAR_BACKDROP_BLUE.toDouble(), 1.0)
        made.opaque = true
        // Draws when asked and when it changes size, not sixty times a second.
        made.paused = true
        made.enableSetNeedsDisplay = true
        made.contentMode = UIViewContentMode.UIViewContentModeRedraw
        // A big phone has more pixels than the eye gets from the car: held down to the tier's size.
        val screen = UIScreen.mainScreen
        val longestPoints = screen.bounds.useContents { max(size.width, size.height) }
        made.contentScaleFactor = minOf(screen.nativeScale, carTier.maxLongEdgePx / longestPoints)
        metalView = made
        return made
    }

    fun show(model: CarModel, assets: CarAssets) {
        pending = model to assets
        metalView?.setNeedsDisplay()
    }

    fun look(newCamera: CarCamera) {
        camera = newCamera
        metalView?.setNeedsDisplay()
    }

    override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) {
        view.setNeedsDisplay()
    }

    override fun drawInMTKView(view: MTKView) {
        val (width, height) = view.drawableSize.useContents { width.toInt() to height.toInt() }
        // Asked before Compose has laid the view out: it draws again when it gets its size.
        if (width == 0 || height == 0) return
        pending?.let { (model, assets) ->
            scene?.release()
            scene = CarScene(gl, model.look, assets, carTier)
            pending = null
        }
        val toDraw = scene ?: return
        val pass = view.currentRenderPassDescriptor ?: return
        val drawable = view.currentDrawable ?: return
        val commands = queue.commandBuffer() ?: return
        val encoder = commands.renderCommandEncoderWithDescriptor(pass) ?: return
        gl.encoder = encoder
        toDraw.draw(width, height, camera, showroomFloor = true)
        gl.encoder = null
        encoder.endEncoding()
        commands.presentDrawable(drawable)
        commands.commit()
        if (!drewOnce) {
            drewOnce = true
            firstFrame()
        }
    }

    fun close() {
        scene?.release()
        scene = null
        metalView?.delegate = null
    }
}
