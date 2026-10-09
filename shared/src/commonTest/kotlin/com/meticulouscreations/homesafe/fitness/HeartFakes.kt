package com.meticulouscreations.homesafe.fitness

import com.meticulouscreations.homesafe.fitness.domain.HeartLinkEvent
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.HeartRateMonitor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A band that doesn't exist, at an address nothing has. */
internal val TEST_BAND = HeartSensor("AA:BB:CC:00:00:01", "Test Band")

/**
 * A radio for tests: the test says what is advertising and what comes over the link, and reads
 * back what was asked of it.
 */
internal class FakeHeartRateLink(override val isSupported: Boolean = true) : HeartRateLink {
    var permitted = true

    /** What the user answers when asked. */
    var grantsWhenAsked = false
    var asked = 0
    var bluetoothOn = true

    /** The scan refuses to start. */
    var scanFails = false
    var scans = 0

    /** Scans being collected right now. */
    var scanning = 0

    /** Every address a connection was asked for, in order. */
    val connections = mutableListOf<String>()

    /** Links let go of, by drop or by the monitor. */
    var closed = 0

    private val adverts = MutableSharedFlow<SensorSighting>(extraBufferCapacity = 64)
    private var link: Channel<HeartLinkEvent>? = null

    override fun hasPermission() = permitted

    override suspend fun requestPermission(): Boolean {
        asked++
        if (grantsWhenAsked) permitted = true
        return permitted
    }

    override fun isBluetoothOn() = bluetoothOn

    override fun scan(): Flow<SensorSighting> = flow {
        scans++
        check(!scanFails) { "scan failed" }
        scanning++
        try {
            emitAll(adverts)
        } finally {
            scanning--
        }
    }

    override fun connect(address: String): Flow<HeartLinkEvent> = flow {
        connections += address
        val events = Channel<HeartLinkEvent>(Channel.UNLIMITED)
        link = events
        try {
            for (event in events) emit(event)
        } finally {
            closed++
        }
    }

    /** An advertisement, heard by whoever is scanning at this moment. */
    fun advertise(sensor: HeartSensor, rssi: Int = -55) {
        adverts.tryEmit(SensorSighting(sensor, rssi))
    }

    fun ready() {
        link?.trySend(HeartLinkEvent.Ready)
    }

    /** A Heart Rate Measurement notification of [bpm], eight bits, with the sensor saying whether it is on skin. */
    fun measure(bpm: Int, contact: Boolean? = null) {
        val flags = when (contact) {
            null -> 0x00
            true -> 0x06
            false -> 0x04
        }
        link?.trySend(HeartLinkEvent.Measurement(byteArrayOf(flags.toByte(), bpm.toByte())))
    }

    /** The link goes, as it does when the band walks out of range. */
    fun drop(error: Throwable? = null) {
        link?.close(error)
    }
}

/** A [HeartRateMonitor] that does what it is told and remembers being told: the view model's side of the sensor, without one. */
internal class FakeHeartRateMonitor(supported: Boolean = true) : HeartRateMonitor {
    private val idle: HeartSensorState = if (supported) HeartSensorState.Off else HeartSensorState.Unsupported
    override val state = MutableStateFlow(idle)

    /** What was asked, in order: "search", "follow <address>" (with "ask" when the user was to be asked), "stop". */
    val calls = mutableListOf<String>()

    override fun search() {
        calls += "search"
        state.value = HeartSensorState.Searching()
    }

    override fun follow(sensor: HeartSensor, ask: Boolean) {
        calls += "follow ${sensor.address}" + if (ask) " ask" else ""
        state.value = HeartSensorState.Scanning(sensor)
    }

    override fun stop() {
        calls += "stop"
        state.value = idle
    }

    /** The sensor reads [bpm] at [atMillis] (null: linked, with nothing to say). */
    fun read(bpm: Int?, atMillis: Long, sensor: HeartSensor = TEST_BAND) {
        state.value = HeartSensorState.Connected(sensor, bpm, atMillis)
    }
}
