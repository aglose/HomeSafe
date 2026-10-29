package com.meticulouscreations.homesafe.ui.components

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
