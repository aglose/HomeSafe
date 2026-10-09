package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.fitness.domain.HeartLinkEvent
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.HeartRateMeasurement
import com.meticulouscreations.homesafe.fitness.domain.HeartRateMonitor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/**
 * [HeartRateMonitor] over a [HeartRateLink]: everything about a heart-rate sensor that isn't the
 * radio itself. It decides which advertisement is the chosen sensor, reads the measurements,
 * notices a link that has gone quiet, and goes looking again when one drops.
 *
 * One thing at a time: [search], [follow] and [stop] each end whatever was running before they
 * begin, in the order they were called.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HeartRateMonitorImpl(
    private val link: HeartRateLink,
    private val appScope: CoroutineScope,
    private val clock: Clock,
) : HeartRateMonitor {
    private val idle: HeartSensorState = if (link.isSupported) HeartSensorState.Off else HeartSensorState.Unsupported
    private val _state = MutableStateFlow(idle)
    override val state: StateFlow<HeartSensorState> = _state.asStateFlow()

    // What to do next, null to do nothing. Taken one at a time by the loop below, so a task has wholly stopped
    // (and written its last state) before the next one starts, however quickly they were asked for.
    private val tasks = Channel<(suspend () -> Unit)?>(Channel.UNLIMITED)

    init {
        appScope.launch {
            var running: Job? = null
            for (task in tasks) {
                running?.cancelAndJoin()
                _state.value = idle
                running = task?.let { launch { it() } }
            }
        }
    }

    override fun search() {
        if (link.isSupported) tasks.trySend { discover() }
    }

    override fun follow(sensor: HeartSensor, ask: Boolean) {
        if (link.isSupported) tasks.trySend { hold(sensor, ask) }
    }

    override fun stop() {
        tasks.trySend(null)
    }

    /** True once the app may use Bluetooth and it is on; false (with the state saying why) when the user won't allow it. */
    private suspend fun ready(ask: Boolean): Boolean {
        if (!link.hasPermission() && !(ask && link.requestPermission())) {
            _state.value = HeartSensorState.PermissionNeeded
            return false
        }
        // Nothing announces the radio coming back on in terms every platform shares, so it is looked at again now and then.
        while (!link.isBluetoothOn()) {
            _state.value = HeartSensorState.BluetoothOff
            delay(RADIO_POLL_MILLIS)
        }
        return true
    }

    private suspend fun discover() {
        if (!ready(ask = true)) return
        val found = LinkedHashMap<String, SensorSighting>()
        _state.value = HeartSensorState.Searching()
        try {
            link.scan().collect { sighting ->
                // A sensor that named itself once keeps the name: not every advertisement carries it.
                val known = found[sighting.sensor.address]
                found[sighting.sensor.address] = if (sighting.sensor.name.isEmpty() && known != null) sighting.copy(sensor = known.sensor) else sighting
                _state.value = HeartSensorState.Searching(found.values.toList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The scan wouldn't start or was stopped under us; what was found so far is still there to choose from.
        }
    }

    private suspend fun hold(sensor: HeartSensor, ask: Boolean) {
        var known = sensor
        var failures = 0
        var everLinked = false
        while (true) {
            if (!ready(ask && !everLinked && failures == 0)) return
            _state.value = if (everLinked || failures > 0) HeartSensorState.Lost(known) else HeartSensorState.Scanning(known)
            val seen = try {
                find(known)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val reached = if (seen != null) session(seen) else Reach.NOTHING
            if (reached != Reach.NOTHING) {
                // It may have come back at a new address under its old name; that is where it is now.
                known = seen ?: known
                everLinked = true
            }
            // Only a link that carried a heart rate counts as having worked: one that comes up and falls straight over
            // again, time after time, has to be backed away from like one that never came up.
            if (reached == Reach.MEASURED) failures = 0 else failures++
            _state.value = HeartSensorState.Lost(known)
            delay(retryDelay(failures))
        }
    }

    /**
     * Scans until [sensor] is heard. By its address at once; and, since a wearable may change
     * the address it advertises from, by its name alone once the address has gone unheard for
     * [ADDRESS_GRACE_MILLIS]. (Two bands of one name in the room can't be told apart that way:
     * forgetting the sensor and choosing it again settles it.)
     */
    private suspend fun find(sensor: HeartSensor): HeartSensor {
        val since = now()
        return link.scan().first { sighting ->
            sighting.sensor.address == sensor.address ||
                (sensor.name.isNotEmpty() && sighting.sensor.name == sensor.name && now() - since >= ADDRESS_GRACE_MILLIS)
        }.sensor.let { if (it.name.isEmpty()) it.copy(name = sensor.name) else it }
    }

    /** One link to [sensor], from connecting until it drops or goes quiet, and how far it got. */
    private suspend fun session(sensor: HeartSensor): Reach = coroutineScope {
        _state.value = HeartSensorState.Connecting(sensor)
        var reached = Reach.NOTHING
        // A link that fails ends the flow like one that drops; either way the caller tries again.
        val events = link.connect(sensor.address).catch { }.produceIn(this)
        try {
            while (true) {
                // A sensor out of range often isn't reported as gone for half a minute. Silence says it sooner.
                val wait = if (reached == Reach.NOTHING) CONNECT_MILLIS else SILENCE_MILLIS
                val event = withTimeoutOrNull(wait) { events.receiveCatching() }?.getOrNull() ?: break
                _state.value = when (event) {
                    HeartLinkEvent.Ready -> {
                        if (reached == Reach.NOTHING) reached = Reach.LINKED
                        HeartSensorState.Connected(sensor, null, now())
                    }

                    is HeartLinkEvent.Measurement -> {
                        reached = Reach.MEASURED
                        HeartSensorState.Connected(sensor, HeartRateMeasurement.parse(event.bytes)?.reading, now())
                    }
                }
            }
        } finally {
            events.cancel()
        }
        reached
    }

    /** How far a link got before it ended. */
    private enum class Reach { NOTHING, LINKED, MEASURED }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    internal companion object {
        /** How long to wait for a link to come up before giving up on the attempt. */
        const val CONNECT_MILLIS = 20_000L

        /** A sensor notifies about once a second. One that has said nothing for this long has gone, whatever the radio thinks. */
        const val SILENCE_MILLIS = 12_000L

        const val ADDRESS_GRACE_MILLIS = 6_000L
        const val RADIO_POLL_MILLIS = 3_000L
        private const val RETRY_MILLIS = 2_000L
        private const val RETRY_MAX_MILLIS = 30_000L

        /**
         * How long to wait before looking again: two seconds after a link that had been up, and
         * doubling from there for each attempt that came to nothing, up to half a minute. The
         * floor matters as much as the ceiling: Android refuses an app that starts more than five
         * scans in thirty seconds.
         */
        fun retryDelay(failures: Int): Long = (RETRY_MILLIS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(RETRY_MAX_MILLIS)
    }
}
