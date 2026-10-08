package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/**
 * Seconds for a shader's `time`, ticking every frame while [running] and starting over every
 * [wrapSeconds] so a float always has room for it. Read it only while drawing: a read during
 * composition would recompose every frame.
 */
@Composable
internal fun rememberWeatherShaderClock(running: Boolean = true, wrapSeconds: Float = 600f): FloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, wrapSeconds) {
        if (!running) return@LaunchedEffect
        // An infinite animation's frames, so anything waiting for animations to settle doesn't wait on this.
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                time.floatValue = (time.floatValue + ((now - last) / 1e9f).coerceIn(0f, 0.1f)) % wrapSeconds
                last = now
            }
        }
    }
    return time
}
