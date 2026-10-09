package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.meticulouscreations.homesafe.weather.ui.LocalWeatherShaders
import kotlinx.coroutines.delay

/** How a shader is drawn where it is really used, which is how it has to be drawn here to stand in for that. */
private enum class WarmUpDraw {
    /** Filling a shape, straight onto the screen: the moon's tile, what falls. */
    BRUSH,

    /** Filling a shape inside an offscreen layer: the clouds. */
    BRUSH_IN_LAYER,

    /** Reworking a layer: the map turned to night, the drops on the glass. */
    EFFECT,

    /** Reworking a layer that is asked for offscreen as well: the radar. */
    EFFECT_OFFSCREEN,
}

/**
 * The shaders the weather app does not draw until something brings them on: the moon's and the
 * radar's when their cards scroll up, cloud and rain when the sky turns or the reader moves to
 * a place that has them.
 */
private val WARM_UPS = listOf(
    MOON_SHADER to WarmUpDraw.BRUSH,
    MAP_NIGHT_SHADER to WarmUpDraw.EFFECT,
    RADAR_SHADER to WarmUpDraw.EFFECT_OFFSCREEN,
    CLOUD_SHADER to WarmUpDraw.BRUSH_IN_LAYER,
    PRECIP_SHADER to WarmUpDraw.BRUSH,
    GLASS_SHADER to WarmUpDraw.EFFECT,
)

/** Once is enough for a process: what the GPU compiled it keeps. */
private var warmedUp = false

/** After the app has opened over the shell (its opening is an animation, and no time for this). */
private const val WARM_UP_AFTER_MS = 600L

/** Whole pixels, so the shape is drawn the plain way a card's is and not with softened edges. */
private const val WARM_UP_PIXELS = 8

/**
 * Draws each of the weather app's shaders once, a few pixels of it, soon after the app opens.
 *
 * The first time the GPU is asked to draw with a shader it has to compile it, which for one of
 * these is ten to twenty milliseconds of the render thread: a frame or two lost, wherever that
 * first time falls. Left alone it falls in the reader's first scroll, as the radar's card and
 * the moon's tile come up the screen. The compiled programs are kept on disk, but with the app's
 * code cache, which the system empties at every update: so it is the first look at the weather
 * after each update that stutters.
 *
 * Here it falls while the reader is still looking at the top of the page instead, one shader
 * every other frame. Put it under something opaque (the sky): it is drawn, which is all that is
 * wanted, and never seen.
 */
@Composable
internal fun WeatherShaderWarmUp(modifier: Modifier = Modifier) {
    if (!LocalWeatherShaders.current || warmedUp) return
    var stage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        delay(WARM_UP_AFTER_MS)
        for (i in WARM_UPS.indices) {
            stage = i
            // One frame to draw it, and one for the compiling to have been that frame's alone.
            withFrameNanos { }
            withFrameNanos { }
        }
        warmedUp = true
        stage = WARM_UPS.size
    }
    val (source, draw) = WARM_UPS.getOrNull(stage) ?: return
    val shader = remember(source) { weatherShaderOrNull(source) } ?: return
    val side = with(LocalDensity.current) { WARM_UP_PIXELS.toDp() }
    Box(modifier.size(side)) {
        when (draw) {
            WarmUpDraw.BRUSH -> Canvas(Modifier.matchParentSize()) { drawRect(shader.brush()) }

            WarmUpDraw.BRUSH_IN_LAYER ->
                Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { drawRect(shader.brush()) }

            WarmUpDraw.EFFECT ->
                Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = shader.renderEffect() }) { drawRect(Color.White) }

            WarmUpDraw.EFFECT_OFFSCREEN ->
                Canvas(
                    Modifier.matchParentSize().graphicsLayer {
                        renderEffect = shader.renderEffect()
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
                ) { drawRect(Color.White) }
        }
    }
}
