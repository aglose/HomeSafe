package com.meticulouscreations.homesafe.ui.assistant

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The assistant's glow as an AGSL [RuntimeShader] (API 33, this app's minSdk). Null where it can't
 * be compiled — Android Studio's preview renderer among them — and the plain rim is drawn instead.
 */
internal actual fun assistantGlowShaderOrNull(): AssistantGlowShader? =
    runCatching { AgslAssistantGlowShader(RuntimeShader(ASSISTANT_GLOW_SHADER)) }.getOrNull()

private class AgslAssistantGlowShader(private val shader: RuntimeShader) : AssistantGlowShader {
    // One brush serves every frame: a draw records the shader as its uniforms stand (see ApertureScan's).
    private val brush = ShaderBrush(shader)

    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float, z: Float, w: Float) = shader.setFloatUniform(name, x, y, z, w)

    override fun setUniform(name: String, color: Color) = shader.setFloatUniform(name, color.red, color.green, color.blue)

    override fun brush(): Brush = brush
}
