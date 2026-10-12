package com.meticulouscreations.homesafe.fitness.ui.shader

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/** AGSL (API 33, this app's minSdk). Null where it can't compile, and the caller draws its fallback. */
internal actual fun fitnessShaderOrNull(source: String): FitnessShader? =
    runCatching { AgslFitnessShader(RuntimeShader(source)) }.getOrNull()

private class AgslFitnessShader(private val shader: RuntimeShader) : FitnessShader {
    // One brush for every frame: a draw records the shader as its uniforms stand.
    private val brush = ShaderBrush(shader)

    override fun setUniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    override fun setUniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    override fun setUniform(name: String, color: Color) = shader.setFloatUniform(name, color.red, color.green, color.blue)

    override fun setUniform(name: String, values: FloatArray) = shader.setFloatUniform(name, values)

    // The picture each name was last given, so that the same one isn't wrapped again every frame.
    private val pictures = HashMap<String, ImageBitmap>()

    override fun setImage(name: String, image: ImageBitmap) {
        if (pictures[name] === image) return
        pictures[name] = image
        val picture = BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        picture.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader(name, picture)
    }

    override fun brush(): Brush = brush
}
