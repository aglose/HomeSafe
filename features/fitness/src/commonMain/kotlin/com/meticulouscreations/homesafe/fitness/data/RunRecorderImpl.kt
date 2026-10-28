package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.fitness.domain.RecordedRuns
import com.meticulouscreations.homesafe.fitness.domain.RecorderProblem
import com.meticulouscreations.homesafe.fitness.domain.Recording
import com.meticulouscreations.homesafe.fitness.domain.RunEvent
import com.meticulouscreations.homesafe.fitness.domain.RunEventKind
import com.meticulouscreations.homesafe.fitness.domain.RunLocation
import com.meticulouscreations.homesafe.fitness.domain.RunRecorder
import com.meticulouscreations.homesafe.fitness.domain.RunTracker
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * [RunRecorder] over the phone's GPS ([RunLocation]), keeping each run in [RecordedRuns] as it
 * goes: what happened is written every [FLUSH_MILLIS] or so, so a run the app was stopped in the
 * middle of loses a few seconds at most. Such a run is finished, where its positions stopped,
 * when the app next starts; one that never got anywhere is thrown away.
 *
 * Everything that changes the run happens under one lock, in the order it was asked for.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RunRecorderImpl(
    private val location: RunLocation,
    private val runs: RecordedRuns,
    private val appScope: CoroutineScope,
    private val clock: Clock,
) : RunRecorder {
    override val isSupported: Boolean = location.isSupported

    private val _recording = MutableStateFlow<Recording?>(null)
    override val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private val _problem = MutableStateFlow<RecorderProblem?>(null)
    override val problem: StateFlow<RecorderProblem?> = _problem.asStateFlow()

    private val lock = Mutex()
    private var session: Session? = null
    private var starting: Job? = null

    init {
        if (isSupported) appScope.launch { lock.withLock { salvage() } }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    override fun start() {
        if (!isSupported || session != null || starting?.isActive == true) return
        starting = appScope.launch {
            if (!location.hasPermission() && !location.requestPermission()) {
                _problem.value = RecorderProblem.LOCATION_REFUSED
                return@launch
            }
            _problem.value = null
            lock.withLock {
                if (session != null) return@withLock
                val at = now()
                val id = "app-$at"
                val offset = localUtcOffsetSeconds(at / 1_000.0)
                runs.begin(id, at, offset)
                val started = Session(id, RunTracker(at), offset, lastFlushAt = at)
                session = started
                publish(started, at)
                started.job = appScope.launch { follow(started) }
            }
        }
    }

    override fun pause() = mark(RunEventKind.PAUSE)

    override fun resume() = mark(RunEventKind.RESUME)

    private fun mark(kind: RunEventKind) {
        appScope.launch {
            lock.withLock {
                val current = session ?: return@withLock
                val at = now()
                record(current, RunEvent(kind, at), at)
                flush(current, at)
                publish(current, at)
            }
        }
    }

    override fun finish() = end(keep = true)

    override fun discard() = end(keep = false)

    private fun end(keep: Boolean) {
        appScope.launch {
            lock.withLock {
                val current = session ?: return@withLock
                session = null
                current.job?.cancel()
                val at = now()
                if (keep) {
                    flush(current, at)
                    runs.finish(current.tracker.finished(current.id, at, current.utcOffsetSeconds))
                } else {
                    runs.delete(current.id)
                }
                _recording.value = null
            }
        }
    }

    /** Takes the GPS's positions into [current] for as long as it is the run, and keeps its time and signal fresh on screen. */
    private suspend fun follow(current: Session) = coroutineScope {
        launch {
            while (true) {
                delay(TICK_MILLIS)
                lock.withLock {
                    if (session !== current) return@withLock
                    val at = now()
                    if (at - current.lastFlushAt >= FLUSH_MILLIS) flush(current, at)
                    publish(current, at)
                }
            }
        }
        location.fixes().collect { fix ->
            lock.withLock {
                if (session !== current) return@withLock
                val at = now()
                record(current, RunEvent.of(fix), at)
                publish(current, at)
            }
        }
    }

    private suspend fun record(current: Session, event: RunEvent, at: Long) {
        current.tracker.apply(event)
        current.pending += event
        if (current.pending.size >= FLUSH_EVENTS || at - current.lastFlushAt >= FLUSH_MILLIS) flush(current, at)
    }

    private suspend fun flush(current: Session, at: Long) {
        current.lastFlushAt = at
        if (current.pending.isEmpty()) return
        val events = current.pending.toList()
        current.pending.clear()
        runs.append(current.id, events)
    }

    private fun publish(current: Session, at: Long) {
        val shown = current.tracker.recording(current.id, at)
        _recording.value = shown
        location.notify(shown)
    }

    /** Finishes the runs the app was stopped in the middle of, where their positions stopped, or throws away those that never got anywhere. */
    private suspend fun salvage() {
        for ((id, startedAt) in runs.unfinished()) {
            val events = runs.events(id)
            val tracker = RunTracker.replay(startedAt, events)
            if (tracker.meters < LEAST_KEPT_METERS) {
                runs.delete(id)
                continue
            }
            val endedAt = events.maxOf { it.epochMillis }
            runs.finish(tracker.finished(id, endedAt, runs.offsetOf(id) ?: localUtcOffsetSeconds(startedAt / 1_000.0)))
        }
    }

    private class Session(
        val id: String,
        val tracker: RunTracker,
        val utcOffsetSeconds: Int,
        var lastFlushAt: Long,
        val pending: MutableList<RunEvent> = ArrayList(),
        var job: Job? = null,
    )

    private companion object {
        /** How often the screen's time and the GPS's signal are brought up to date when no position has come. */
        const val TICK_MILLIS = 1_000L

        /** What has happened is written at least this often… */
        const val FLUSH_MILLIS = 10_000L

        /** …or once this much has. */
        const val FLUSH_EVENTS = 30

        /** A run cut short by the app stopping is kept only if it got this far. */
        const val LEAST_KEPT_METERS = 50.0
    }
}
