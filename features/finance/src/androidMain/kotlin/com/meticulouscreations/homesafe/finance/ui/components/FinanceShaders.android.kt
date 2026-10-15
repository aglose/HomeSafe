package com.meticulouscreations.homesafe.finance.ui.components

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush

/** AGSL (API 33, this app's minSdk). Null where it can't compile, and the caller draws its fallback. */
internal actual fun financeShaderOrNull(source: String): FinanceShader? =
    runCatching { AgslFinanceShader(RuntimeShader(source)) }.getOrNull()

private class AgslFinanceShader(private val shader: RuntimeShader) : FinanceShader {
    // One brush for every frame: a draw records the shader as its uniforms stand.
    private val brush = ShaderBrush(shader)

    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    override fun setUniform(name: String, color: Color) = shader.setFloatUniform(name, color.red, color.green, color.blue)

    override fun brush(): Brush = brush
}
