package com.meticulouscreations.homesafe.fitness.ui.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The same AGSL source as a Skia runtime effect on desktop, iOS and web, compiled once per source
 * per process; null if that fails.
 */
internal actual fun fitnessShaderOrNull(source: String): FitnessShader? =
    effects.getOrPut(source) { runCatching { RuntimeEffect.makeForShader(source) } }.getOrNull()?.let(::SkiaFitnessShader)

private val effects = HashMap<String, Result<RuntimeEffect>>()

private class SkiaFitnessShader(effect: RuntimeEffect) : FitnessShader {
    private val builder = RuntimeShaderBuilder(effect)

    override fun setUniform(name: String, value: Float) = builder.uniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = builder.uniform(name, x, y)

    override fun setUniform(name: String, color: Color) = builder.uniform(name, color.red, color.green, color.blue)

    // The shader copies the uniforms as they are now, so it's made afresh for each frame's draw.
    override fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())
}
