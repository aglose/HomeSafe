package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import com.meticulouscreations.homesafe.tracing.traceInstant
import com.meticulouscreations.homesafe.ui.components.awaitNextGridFrame

/**
 * Seconds for a shader's `time`, ticking while [running] and starting over every [wrapSeconds]
 * so a float always has room for it. Read it only while drawing: a read during composition would
 * recompose every frame.
 *
 * It moves on every frame the screen draws, or with [frameSeconds] no more often than that: a
 * shader that drifts or shimmers looks the same stepped thirty times a second as a hundred and
 * twenty, and whatever reads this is drawn again, over the whole of itself, each time it moves.
 */
@Composable
fun rememberWeatherShaderClock(running: Boolean = true, wrapSeconds: Float = 600f, frameSeconds: Float = 0f): FloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, wrapSeconds, frameSeconds) {
        if (!running) return@LaunchedEffect
        // On the frames every other clock with this step moves on in, and asleep between them (see awaitNextGridFrame):
        // an infinite animation's frames, so anything waiting for animations to settle doesn't wait on this.
        var last = awaitNextGridFrame(frameSeconds)
        while (true) {
            val now = awaitNextGridFrame(frameSeconds, last)
            time.floatValue = (time.floatValue + ((now - last) / 1e9f).coerceIn(0f, 0.1f)) % wrapSeconds
            traceInstant("weather.shaderClock")
            last = now
        }
    }
    return time
}
