package com.meticulouscreations.homesafe.ui.assistant

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The assistant's glow on the Skia-drawn targets (iOS): the same AGSL source, compiled once per
 * process as a Skia runtime effect; null if that fails, and the plain rim is drawn instead.
 * Compiled into each of those targets by srcDir, as the timeline's lens is.
 */
internal actual fun assistantGlowShaderOrNull(): AssistantGlowShader? =
    assistantGlowEffect.getOrNull()?.let(::SkiaAssistantGlowShader)

private val assistantGlowEffect: Result<RuntimeEffect> by lazy { runCatching { RuntimeEffect.makeForShader(ASSISTANT_GLOW_SHADER) } }

private class SkiaAssistantGlowShader(effect: RuntimeEffect) : AssistantGlowShader {
    private val builder = RuntimeShaderBuilder(effect)

    override fun setUniform(name: String, value: Float) = builder.uniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float) = builder.uniform(name, x, y, z, w)

    override fun setUniform(name: String, color: Color) = builder.uniform(name, color.red, color.green, color.blue)

    // The shader copies the uniforms as they are now, so it is made afresh for each frame's draw.
    override fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())
}
