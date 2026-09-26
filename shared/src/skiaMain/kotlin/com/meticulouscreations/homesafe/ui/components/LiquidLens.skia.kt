package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The lens on the Skia-drawn targets (desktop, iOS, web): the same AGSL source, compiled as a
 * Skia runtime effect, which is the language AGSL is Android's name for. Compiled once per
 * process; null if that fails, and the timeline is drawn plain.
 *
 * Compiled into jvmMain, the iOS source sets and jsMain/wasmJsMain by srcDir (see
 * shared/build.gradle.kts), since there is no Skia source set in the default hierarchy to share.
 */
internal actual fun liquidLensShaderOrNull(): LiquidLensShader? =
    liquidLensEffect.getOrNull()?.let(::SkiaLiquidLensShader)

private val liquidLensEffect: Result<RuntimeEffect> by lazy { runCatching { RuntimeEffect.makeForShader(LIQUID_LENS_SHADER) } }

private class SkiaLiquidLensShader(effect: RuntimeEffect) : LiquidLensShader {
    private val builder = RuntimeShaderBuilder(effect)

    override fun setUniform(name: String, value: Float) = builder.uniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = builder.uniform(name, x, y)

    // The filter copies the uniforms as they are now, so one builder serves every frame.
    override fun renderEffect(): RenderEffect =
        ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
}
