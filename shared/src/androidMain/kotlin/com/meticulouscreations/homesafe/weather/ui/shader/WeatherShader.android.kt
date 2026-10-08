package com.meticulouscreations.homesafe.weather.ui.shader

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as AndroidRenderEffect

/** AGSL (API 33, this app's minSdk). Null where it can't compile, and the caller draws its fallback. */
internal actual fun weatherShaderOrNull(source: String): WeatherShader? =
    runCatching { AgslWeatherShader(RuntimeShader(source)) }.getOrNull()

private class AgslWeatherShader(private val shader: RuntimeShader) : WeatherShader {
    // One brush for every frame: a draw records the shader as its uniforms stand.
    private val brush = ShaderBrush(shader)

    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    override fun setUniform(name: String, x: Float, y: Float, z: Float) = shader.setFloatUniform(name, x, y, z)

    override fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float) = shader.setFloatUniform(name, x, y, z, w)

    override fun brush(): Brush = brush

    // The effect copies the uniforms as they are now, so a new one is made for each frame: a
    // cached effect would keep the uniforms it was made with (see LiquidLens.android.kt).
    override fun renderEffect(): RenderEffect = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

internal actual val layersKeepTheirPixels: Boolean = true
