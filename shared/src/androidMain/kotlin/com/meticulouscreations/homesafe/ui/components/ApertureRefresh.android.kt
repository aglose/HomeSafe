package com.meticulouscreations.homesafe.ui.components

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The pull-to-refresh band as an AGSL [RuntimeShader] (API 33, this app's minSdk). Null where it
 * can't be compiled — Android Studio's preview renderer among them — and the band draws its
 * plain ring instead.
 */
internal actual fun apertureScanShaderOrNull(): ApertureScanShader? =
    runCatching { AgslApertureScanShader(RuntimeShader(APERTURE_SCAN_SHADER)) }.getOrNull()

private class AgslApertureScanShader(private val shader: RuntimeShader) : ApertureScanShader {
    // One brush serves every frame: a draw records the shader as its uniforms stand, and setting
    // a uniform makes the next draw build a new native instance (see HomeSkeleton's snake).
    private val brush = ShaderBrush(shader)

    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    override fun setUniform(name: String, color: Color) = shader.setFloatUniform(name, color.red, color.green, color.blue)

    override fun brush(): Brush = brush
}
