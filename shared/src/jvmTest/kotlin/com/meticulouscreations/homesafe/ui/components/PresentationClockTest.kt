package com.meticulouscreations.homesafe.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Wall-clock nanoseconds, written as milliseconds since an arbitrary origin. */
private fun atMs(millis: Long): Long = 1_000_000_000_000L + millis * 1_000_000

/** A frame's presentation timestamp, written as milliseconds into the stream. */
private fun frameAtMs(millis: Long): Long = millis * 1_000

class PresentationClockTest {

    @Test
    fun `the first frame is shown straight away, whatever it is stamped`() {
        val clock = PresentationClock()

        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(90_000), atMs(0)))
    }

    @Test
    fun `a frame that is not due yet waits out the difference`() {
        val clock = PresentationClock()
        clock.decide(frameAtMs(0), atMs(0))

        val decision = clock.decide(frameAtMs(40), atMs(0))

        assertEquals(40L, assertIs<PresentationClock.Decision.Wait>(decision).nanos / 1_000_000)
    }

    @Test
    fun `frames play out on the stream's timing, not on the rate they were decoded at`() {
        val clock = PresentationClock()
        clock.decide(frameAtMs(0), atMs(0))

        // Decoding ran ahead: three frames' worth arrived within a millisecond of each other.
        assertIs<PresentationClock.Decision.Wait>(clock.decide(frameAtMs(50), atMs(1)))
        assertIs<PresentationClock.Decision.Wait>(clock.decide(frameAtMs(100), atMs(1)))
        // Each becomes due at the moment the stream says, measured from the first frame.
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(50), atMs(50)))
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(100), atMs(100)))
    }

    @Test
    fun `a frame the clock has already left far behind re-anchors instead of being rushed`() {
        val clock = PresentationClock()
        clock.decide(frameAtMs(0), atMs(0))

        // The machine stalled for four seconds; this frame was due three seconds ago.
        assertEquals(PresentationClock.Decision.ReAnchor, clock.decide(frameAtMs(1_000), atMs(4_000)))
        // Asking again shows it now, and times what follows from here.
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(1_000), atMs(4_000)))
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(1_040), atMs(4_040)))
    }

    @Test
    fun `a seek forwards re-anchors rather than waiting out the gap`() {
        val clock = PresentationClock()
        clock.decide(frameAtMs(0), atMs(0))

        // The next frame is stamped a minute later: the source was seeked, not stalled.
        assertEquals(PresentationClock.Decision.ReAnchor, clock.decide(frameAtMs(60_000), atMs(40)))
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(60_000), atMs(40)))
    }

    @Test
    fun `a gap smaller than the drift limit is honoured, not re-anchored`() {
        val clock = PresentationClock(resyncDriftMs = 1_000)
        clock.decide(frameAtMs(0), atMs(0))

        val decision = clock.decide(frameAtMs(900), atMs(0))

        assertEquals(900L, assertIs<PresentationClock.Decision.Wait>(decision).nanos / 1_000_000)
    }

    @Test
    fun `resetting makes the next frame the new now`() {
        val clock = PresentationClock()
        clock.decide(frameAtMs(0), atMs(0))
        clock.reset()

        // Paused for a minute; playback resumes from the frame in hand rather than racing.
        assertEquals(PresentationClock.Decision.Present, clock.decide(frameAtMs(500), atMs(60_000)))
        assertIs<PresentationClock.Decision.Wait>(clock.decide(frameAtMs(540), atMs(60_000)))
    }
}
