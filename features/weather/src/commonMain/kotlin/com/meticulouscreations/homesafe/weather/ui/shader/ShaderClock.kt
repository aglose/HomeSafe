package com.meticulouscreations.homesafe.weather.ui.shader

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

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
        // An infinite animation's frames, so anything waiting for animations to settle doesn't wait on this.
        var last = withInfiniteAnimationFrameNanos { it }
        var owed = 0f
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                owed += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                // A little short of the step is the step: frames don't land to the microsecond.
                if (owed >= frameSeconds - FRAME_SLACK_SECONDS) {
                    time.floatValue = (time.floatValue + owed) % wrapSeconds
                    owed = 0f
                }
            }
        }
    }
    return time
}

private const val FRAME_SLACK_SECONDS = 0.002f
