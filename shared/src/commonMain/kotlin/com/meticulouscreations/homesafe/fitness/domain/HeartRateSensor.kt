package com.meticulouscreations.homesafe.fitness.domain

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A Bluetooth heart-rate sensor: the [address] it was seen at and the [name] it gave (empty when it gave none). */
@Immutable
data class HeartSensor(val address: String, val name: String)

/** A sensor as a scan saw it, and how loud ([rssi], in dBm: nearer zero is nearer). */
@Immutable
data class SensorSighting(val sensor: HeartSensor, val rssi: Int)

/** The lifter's heart-rate settings: what the zones are worked out from, and the sensor to listen to. */
@Immutable
data class HeartSettings(val profile: HeartProfile = HeartProfile(), val sensor: HeartSensor? = null)

/** What the link to the sensor is doing. */
@Immutable
sealed interface HeartSensorState {
    /** This platform has no Bluetooth the app can use. */
    data object Unsupported : HeartSensorState

    /** Nothing is being listened for. */
    data object Off : HeartSensorState

    /** The user hasn't allowed Bluetooth, and wasn't asked just now or said no. */
    data object PermissionNeeded : HeartSensorState

    data object BluetoothOff : HeartSensorState

    /** Looking for sensors to choose one from: every one seen so far, in the order they turned up. */
    data class Searching(val found: List<SensorSighting> = emptyList()) : HeartSensorState

    /** Looking for the one sensor that was chosen, which hasn't been heard yet. */
    data class Scanning(val sensor: HeartSensor) : HeartSensorState

    data class Connecting(val sensor: HeartSensor) : HeartSensorState

    /**
     * Linked, and as of [atEpochMillis] reading [bpm]; null when the sensor is linked but has no
     * heart rate to give (not on a wrist, or nothing sent yet).
     */
    data class Connected(val sensor: HeartSensor, val bpm: Int? = null, val atEpochMillis: Long = 0) : HeartSensorState

    /** The sensor was heard, and the link to it dropped or never came up; it is being tried again. */
    data class Lost(val sensor: HeartSensor) : HeartSensorState
}

/** What comes over a link to a sensor. */
sealed interface HeartLinkEvent {
    /** The sensor has agreed to notify: measurements follow. */
    data object Ready : HeartLinkEvent

    /** One notification of the Heart Rate Measurement characteristic, as its bytes (see [HeartRateMeasurement.parse]). */
    class Measurement(val bytes: ByteArray) : HeartLinkEvent
}

/**
 * The platform's Bluetooth, as little of it as a heart-rate sensor needs: the standard Heart
 * Rate Service (0x180D) and its Heart Rate Measurement characteristic (0x2A37), which a chest
 * strap speaks and which a Fitbit Air speaks too once "Share heart rate" is on in Google Health.
 * It only carries bytes; deciding what to connect to, reading the measurement and trying again
 * when the link drops are `HeartRateMonitorImpl`'s, so they can be tested without a radio.
 */
interface HeartRateLink {
    /** False where there is no Bluetooth LE this app can use; nothing else is called then. */
    val isSupported: Boolean

    /** Whether the app may scan and connect right now, without asking anyone. */
    fun hasPermission(): Boolean

    /** Asks the user for Bluetooth when they haven't answered, and waits. True when the app may scan and connect afterwards. */
    suspend fun requestPermission(): Boolean

    fun isBluetoothOn(): Boolean

    /**
     * Every advertisement heard from a sensor offering the Heart Rate Service, for as long as it
     * is collected (so the same sensor again and again). Fails if the scan can't start.
     */
    fun scan(): Flow<SensorSighting>

    /**
     * Connects to the sensor at [address] and asks it to notify. [HeartLinkEvent.Ready] once it
     * has agreed, then each measurement. The flow ends, with or without an error, when the link
     * drops; cancelling its collection disconnects.
     */
    fun connect(address: String): Flow<HeartLinkEvent>
}

/** For the platforms with no Bluetooth this app can use. */
internal object UnsupportedHeartRateLink : HeartRateLink {
    override val isSupported = false

    override fun hasPermission() = false

    override suspend fun requestPermission() = false

    override fun isBluetoothOn() = false

    override fun scan(): Flow<SensorSighting> = throw UnsupportedOperationException("No Bluetooth on this platform")

    override fun connect(address: String): Flow<HeartLinkEvent> = throw UnsupportedOperationException("No Bluetooth on this platform")
}

/**
 * The live heart rate, from whichever sensor was chosen. It only runs while it is wanted
 * ([search] or [follow], until [stop]) and only for as long as the app's process does: there is
 * no background service behind it.
 */
interface HeartRateMonitor {
    val state: StateFlow<HeartSensorState>

    /**
     * Looks for sensors to choose from, asking for Bluetooth first if need be:
     * [HeartSensorState.Searching] until [follow] or [stop]. The looking itself ends after a
     * minute; what was found stays listed, and with nothing found it is [HeartSensorState.Off] again.
     */
    fun search()

    /**
     * Holds a link to [sensor]: finds it, connects, and finds it again whenever the link drops,
     * until [stop]. With [ask] the user is asked for Bluetooth if they haven't allowed it (they
     * tapped something); without, a missing permission is only reported.
     */
    fun follow(sensor: HeartSensor, ask: Boolean = false)

    fun stop()
}
