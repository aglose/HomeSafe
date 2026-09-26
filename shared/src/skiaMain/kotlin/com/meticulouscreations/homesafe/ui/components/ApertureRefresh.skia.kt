package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The pull-to-refresh band on the Skia-drawn targets (desktop, iOS, web): the same AGSL source,
 * compiled once per process as a Skia runtime effect; null if that fails, and the band draws its
 * plain ring instead. Compiled into each of those targets by srcDir, as the timeline's lens is.
 */
internal actual fun apertureScanShaderOrNull(): ApertureScanShader? =
    apertureScanEffect.getOrNull()?.let(::SkiaApertureScanShader)

private val apertureScanEffect: Result<RuntimeEffect> by lazy { runCatching { RuntimeEffect.makeForShader(APERTURE_SCAN_SHADER) } }

private class SkiaApertureScanShader(effect: RuntimeEffect) : ApertureScanShader {
    private val builder = RuntimeShaderBuilder(effect)

    override fun setUniform(name: String, value: Float) = builder.uniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = builder.uniform(name, x, y)

    override fun setUniform(name: String, color: Color) = builder.uniform(name, color.red, color.green, color.blue)

    // The shader copies the uniforms as they are now, so it is made afresh for each frame's draw.
    override fun brush(): Brush = ShaderBrush(builder.makeShader())
}
