package com.meticulouscreations.homesafe.ui.components

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as AndroidRenderEffect

/**
 * The lens as an AGSL [RuntimeShader] (API 33, this app's minSdk). Null where it can't be
 * compiled — Android Studio's preview renderer among them — and the timeline is drawn plain.
 */
internal actual fun liquidLensShaderOrNull(): LiquidLensShader? =
    runCatching { AgslLiquidLensShader(RuntimeShader(LIQUID_LENS_SHADER)) }.getOrNull()

private class AgslLiquidLensShader(private val shader: RuntimeShader) : LiquidLensShader {
    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    // The effect copies the uniforms as they are now, so one shader serves every frame. That copy
    // is also why this makes a new effect each frame rather than caching one: a cached effect
    // keeps the uniforms it was made with, so the lens would freeze (and a layer handed the same
    // effect again doesn't redraw). The documented way to animate a RuntimeShader RenderEffect.
    override fun renderEffect(): RenderEffect =
        AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}
