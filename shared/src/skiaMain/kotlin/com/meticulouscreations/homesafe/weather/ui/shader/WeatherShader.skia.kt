package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The same AGSL source as a Skia runtime effect on desktop, iOS and web. The compile is kept per
 * source for the life of the process (a sky's shader is hundreds of lines); each caller gets its
 * own builder, so two skies on screen don't share uniforms. Null if the compile fails.
 */
internal actual fun weatherShaderOrNull(source: String): WeatherShader? =
    effects.getOrPut(source) { runCatching { RuntimeEffect.makeForShader(source) } }.getOrNull()?.let(::SkiaWeatherShader)

private val effects = HashMap<String, Result<RuntimeEffect>>()

private class SkiaWeatherShader(effect: RuntimeEffect) : WeatherShader {
    private val builder = RuntimeShaderBuilder(effect)

    override fun setUniform(name: String, value: Float) = builder.uniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = builder.uniform(name, x, y)

    override fun setUniform(name: String, x: Float, y: Float, z: Float) = builder.uniform(name, x, y, z)

    override fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float) = builder.uniform(name, x, y, z, w)

    // The shader copies the uniforms as they are now, so it's made afresh for each frame's draw.
    override fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())

    override fun renderEffect(): RenderEffect = ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
}
