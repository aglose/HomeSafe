package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect

/**
 * A compiled runtime shader the weather app draws with: an AGSL `RuntimeShader` on Android, a
 * Skia `RuntimeEffect` everywhere else, from the one source (AGSL is Android's name for Skia's
 * shading language). Uniforms are set by name before each frame's draw.
 *
 * It is drawn one of two ways. As a [brush] it generates a picture from nothing: the sky, the
 * rain. As a [renderEffect] it reworks the layer it is put on, which it reads through a child
 * shader named `content`: drops on glass bending the sky behind them, the radar's glow.
 */
internal interface WeatherShader {
    fun setUniform(name: String, value: Float)

    fun setUniform(name: String, x: Float, y: Float)

    fun setUniform(name: String, x: Float, y: Float, z: Float)

    fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float)

    /** A `float3` colour uniform: red, green and blue, 0–1, not premultiplied. */
    fun setUniform(name: String, color: Color) = setUniform(name, color.red, color.green, color.blue)

    /** The shader as its uniforms stand now, to fill a shape with. */
    fun brush(): Brush

    /** The shader as its uniforms stand now, as an effect over a layer; its source must declare `uniform shader content`. */
    fun renderEffect(): RenderEffect
}

/** [source] compiled, or null where it can't be (Android Studio's preview renderer, say): callers draw a plain fallback. */
internal expect fun weatherShaderOrNull(source: String): WeatherShader?

/**
 * Whether an offscreen layer here is a picture kept until what is in it changes (Android's are:
 * a texture, redrawn only when its content is) or only a way of drawing, done over on every frame
 * (Skia's). Where they are kept, something costly that changes less often than the screen around
 * it is worth a layer of its own.
 */
internal expect val layersKeepTheirPixels: Boolean
