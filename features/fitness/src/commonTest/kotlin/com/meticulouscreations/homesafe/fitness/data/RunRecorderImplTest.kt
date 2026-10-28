package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.InMemoryFitnessRunDao
import com.meticulouscreations.homesafe.fitness.domain.GpsFix
import com.meticulouscreations.homesafe.fitness.domain.GpsSignal
import com.meticulouscreations.homesafe.fitness.domain.RecorderProblem
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RunEvent
import com.meticulouscreations.homesafe.fitness.domain.RunLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The recorder against a GPS that gives what the test says, keeping its runs in memory. Time is
 * the test's own: the wall clock starts at [START] and moves with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunRecorderImplTest {

    private class FakeLocation(var allowed: Boolean = true, var allowsWhenAsked: Boolean = true) : RunLocation {
        override val isSupported = true
        val positions = Channel<GpsFix>(Channel.UNLIMITED)
        var following = false
        var asked = 0
        val notified = mutableListOf<Recording>()

        override fun hasPermission() = allowed

        override suspend fun requestPermission(): Boolean {
            asked++
            allowed = allowsWhenAsked
            return allowed
        }

        override fun fixes(): Flow<GpsFix> = positions.receiveAsFlow().onStart { following = true }.onCompletion { following = false }

        override fun notify(recording: Recording) {
            notified += recording
        }
    }

    private class Harness(val scope: TestScope, val location: FakeLocation, val dao: InMemoryFitnessRunDao = InMemoryFitnessRunDao()) {
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(START + scope.currentTime)
        }
        val runs = RecordedRunsImpl(dao)
        val recorder = RunRecorderImpl(location, runs, scope.backgroundScope, clock)

        /** A position [meters] north of the start, taken now. */
        fun at(meters: Double) {
            location.positions.trySend(GpsFix(clock.now().toEpochMilliseconds(), 45.0 + meters / 111_195.0, -93.0, 5.0))
        }
    }

    private fun runRecorderTest(location: FakeLocation = FakeLocation(), dao: InMemoryFitnessRunDao = InMemoryFitnessRunDao(), body: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this, location, dao)
        runCurrent()
        body(h)
    }

    /** [seconds] of running at 3 m/s from [fromMeters], a position a second. */
    private fun TestScope.run(h: Harness, seconds: Int, fromMeters: Double = 0.0) {
        repeat(seconds) { i ->
            h.at(fromMeters + i * 3.0)
            runCurrent()
            advanceTimeBy(1_000)
        }
        h.at(fromMeters + seconds * 3.0)
        runCurrent()
    }

    @Test
    fun aRunIsRecordedAndKept() = runRecorderTest { h ->
        h.recorder.start()
        runCurrent()
        val started = assertNotNull(h.recorder.recording.value)
        assertEquals(GpsSignal.SEARCHING, started.signal)
        assertTrue(h.location.following)
        run(h, 100)
        val going = assertNotNull(h.recorder.recording.value)
        assertEquals(300.0, going.meters, 1.0)
        assertEquals(GpsSignal.GOOD, going.signal)
        assertTrue(h.location.notified.isNotEmpty())

        h.recorder.finish()
        runCurrent()
        assertNull(h.recorder.recording.value)
        assertFalse(h.location.following)
        val kept = h.runs.runs.first().single()
        assertEquals(300.0, kept.meters, 1.0)
        assertEquals(100_000, kept.movingMillis)
        // Every position was written down, to work the run out again from.
        assertEquals(101, h.runs.events(kept.id).size)
    }

    @Test
    fun aPauseStopsTheClock() = runRecorderTest { h ->
        h.recorder.start()
        runCurrent()
        run(h, 60)
        h.recorder.pause()
        runCurrent()
        assertTrue(h.recorder.recording.value?.paused == true)
        advanceTimeBy(120_000)
        runCurrent()
        h.recorder.resume()
        runCurrent()
        run(h, 60, fromMeters = 400.0)
        h.recorder.finish()
        runCurrent()
        val kept = h.runs.runs.first().single()
        assertEquals(120_000, kept.movingMillis, "moving time")
        assertEquals(360.0, kept.meters, 2.0)
    }

    @Test
    fun aRunThrownAwayLeavesNothing() = runRecorderTest { h ->
        h.recorder.start()
        runCurrent()
        run(h, 30)
        h.recorder.discard()
        runCurrent()
        assertNull(h.recorder.recording.value)
        assertEquals(emptyList(), h.runs.runs.first())
        assertEquals(emptyList(), h.runs.unfinished())
    }

    @Test
    fun withTheLocationRefusedNothingIsRecorded() = runRecorderTest(FakeLocation(allowed = false, allowsWhenAsked = false)) { h ->
        h.recorder.start()
        runCurrent()
        assertEquals(1, h.location.asked)
        assertNull(h.recorder.recording.value)
        assertEquals(RecorderProblem.LOCATION_REFUSED, h.recorder.problem.value)
        assertFalse(h.location.following)
        // Allowed the next time, it records and the problem is gone.
        h.location.allowsWhenAsked = true
        h.recorder.start()
        runCurrent()
        assertNotNull(h.recorder.recording.value)
        assertNull(h.recorder.problem.value)
    }

    @Test
    fun aRunCutShortByTheAppStoppingIsFinishedWhereItStopped() = runTest {
        val dao = InMemoryFitnessRunDao()
        val earlier = RecordedRunsImpl(dao)
        // Five minutes at 3 m/s, written down, then nothing: the app was stopped.
        earlier.begin("app-1", START - 3_600_000, -18_000)
        earlier.append("app-1", (0..300).map { RunEvent.of(GpsFix(START - 3_600_000 + it * 1_000L, 45.0 + it * 3.0 / 111_195.0, -93.0, 5.0)) })
        // And one that never got anywhere.
        earlier.begin("app-2", START - 1_800_000, -18_000)
        earlier.append("app-2", listOf(RunEvent.of(GpsFix(START - 1_800_000, 45.0, -93.0, 5.0))))

        val h = Harness(this, FakeLocation(), dao)
        runCurrent()
        val kept = h.runs.runs.first().single()
        assertEquals("app-1", kept.id)
        assertEquals(900.0, kept.meters, 2.0)
        assertEquals(START - 3_600_000 + 300_000, kept.endEpochMillis)
        assertEquals(-18_000, kept.utcOffsetSeconds)
        assertEquals(emptyList(), h.runs.unfinished())
    }

    private companion object {
        /** 14 November 2023, 22:13 UTC. */
        const val START = 1_700_000_000_000L
    }
}
