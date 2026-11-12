package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

/**
 * Which step of a grid [stepSeconds] long, laid over the display's frame clock, the frame at
 * [frameTimeNanos] falls in; for an animation clock that moves on less often than the display
 * draws (a drifting shader at thirty a second).
 *
 * A clock that moves on when this changes moves in the same frames as every other one with the
 * same step, wherever each started. One that counts its own time since it started lands on
 * whichever frames that falls on instead, and two of those out of step on one screen ask for
 * every frame between them: sixty redraws a second at rest where thirty were meant, and on a
 * 120 Hz display up to four times that (docs/perf-tracing.md). A step of 0 or less is every frame.
 */
fun frameGridSlot(frameTimeNanos: Long, stepSeconds: Float): Long =
    if (stepSeconds <= 0f) frameTimeNanos else frameTimeNanos / (stepSeconds * 1e9f).toLong().coerceAtLeast(1)

/**
 * Waits for the first frame of the next step of the grid [stepSeconds] long (see [frameGridSlot])
 * after the frame at [lastFrameNanos], and returns its time; the first frame there is, with
 * [lastFrameNanos] 0.
 *
 * Asleep until shortly before that step begins, rather than woken by every frame in between and
 * letting it pass: a loop over `withFrameNanos` that only acts every so often still has the
 * display's every frame (60 or 120 a second) asked for, and the phone's frame pipeline never rests.
 * This one asks for a frame or two a step. An infinite animation's frames, so a test, or anything
 * else waiting for animations to settle, doesn't wait on it.
 */
suspend fun awaitNextGridFrame(stepSeconds: Float, lastFrameNanos: Long = 0L): Long {
    val step = (stepSeconds * 1e9f).toLong().coerceAtLeast(1)
    if (lastFrameNanos == 0L) return withInfiniteAnimationFrameNanos { it }
    val lastSlot = lastFrameNanos / step
    // Woken a frame early: the frame clock's time is the display's, a little before this runs, so a full wait could
    // land a frame late, and out of step with another clock on the same grid.
    val sleep = ((lastSlot + 1) * step - lastFrameNanos) / 1_000_000 - WAKE_EARLY_MILLIS
    if (sleep > 0) delay(sleep)
    while (true) {
        val now = withInfiniteAnimationFrameNanos { it }
        if (now / step != lastSlot) return now
    }
}

/**
 * The display's frame time in nanoseconds, moved on [stepsPerSecond] times a second while [running],
 * on the frames of [frameGridSlot]'s grid; between those it asks for no frames at all (see
 * [awaitNextGridFrame]). For a slow, steady animation, a pulse or a breath, that looks the same at
 * thirty steps a second as at a hundred and twenty. Read it only while drawing: a read during
 * composition would compose again each step.
 *
 * Held at 0 in a preview, where there is one frame and a still picture is wanted; and off while
 * not [running].
 */
@Composable
fun rememberSteppedFrameNanos(running: Boolean = true, stepsPerSecond: Int = SLOW_ANIMATION_STEPS_PER_SECOND): LongState {
    val time = remember { mutableLongStateOf(0L) }
    val still = LocalInspectionMode.current
    LaunchedEffect(running && !still, stepsPerSecond) {
        if (!running || still) return@LaunchedEffect
        val step = 1f / stepsPerSecond
        var last = 0L
        while (true) {
            last = awaitNextGridFrame(step, last)
            time.longValue = last
        }
    }
    return time
}

/**
 * A breath at [frameNanos]: from 0 up to 1 and back down to 0 over [periodMillis], easing in and out
 * at each end. Taken from the frame clock itself, so every breath with one period on a screen is in
 * step with the others.
 */
fun breathAt(frameNanos: Long, periodMillis: Int): Float {
    val period = periodMillis * 1_000_000L
    val turn = (frameNanos % period).toFloat() / period
    return 0.5f - 0.5f * cos(turn * 2f * PI.toFloat())
}

/** How far through a cycle of [periodMillis] the frame at [frameNanos] is, 0 up to (but not) 1. */
fun cycleAt(frameNanos: Long, periodMillis: Int): Float {
    val period = periodMillis * 1_000_000L
    return (frameNanos % period).toFloat() / period
}

/** Thirty steps a second: a pulse, a breath or a sweep looks the same as at the display's own rate. */
const val SLOW_ANIMATION_STEPS_PER_SECOND = 30

private const val WAKE_EARLY_MILLIS = 8L
