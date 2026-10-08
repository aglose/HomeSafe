package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.fitness.FakeHeartRateLink
import com.meticulouscreations.homesafe.fitness.TEST_BAND
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The monitor against a radio that does what the test says: which advertisement it takes for the
 * chosen sensor, what it makes of the bytes, and what it does when the link drops, goes quiet or
 * never comes up. Time is the test's own, so half a minute of backing off takes none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateMonitorImplTest {

    private val other = HeartSensor("AA:BB:CC:00:00:02", "Someone's Strap")

    private class Harness(scope: TestScope, val link: FakeHeartRateLink = FakeHeartRateLink()) {
        /** Wall-clock time that moves with the test's. */
        private val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(scope.currentTime)
        }
        val monitor = HeartRateMonitorImpl(link, scope.backgroundScope, clock)
        val state get() = monitor.state.value
    }

    private fun runMonitorTest(link: FakeHeartRateLink = FakeHeartRateLink(), body: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this, link)
        runCurrent()
        body(h)
    }

    /** Follows the band as far as a reading of [bpm]. */
    private fun TestScope.connect(h: Harness, bpm: Int = 72) {
        h.monitor.follow(TEST_BAND)
        runCurrent()
        h.link.advertise(TEST_BAND)
        runCurrent()
        h.link.ready()
        h.link.measure(bpm)
        runCurrent()
    }

    // ---- Where it starts ----------------------------------------------------------------------

    @Test
    fun withNoBluetoothToUseItIsUnsupportedAndStaysSo() = runMonitorTest(FakeHeartRateLink(isSupported = false)) { h ->
        assertEquals(HeartSensorState.Unsupported, h.state)
        h.monitor.search()
        h.monitor.follow(TEST_BAND, ask = true)
        runCurrent()
        assertEquals(HeartSensorState.Unsupported, h.state)
        assertEquals(0, h.link.asked)
        assertEquals(0, h.link.scans)
        h.monitor.stop()
        runCurrent()
        assertEquals(HeartSensorState.Unsupported, h.state)
    }

    @Test
    fun untilAskedItIsOffAndTouchesNothing() = runMonitorTest { h ->
        assertEquals(HeartSensorState.Off, h.state)
        assertEquals(0, h.link.scans)
    }

    // ---- Looking for sensors to choose from ---------------------------------------------------

    @Test
    fun searchingListsEachSensorOnceInTheOrderTheyWereHeard() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        assertEquals(HeartSensorState.Searching(), h.state)
        h.link.advertise(other, rssi = -80)
        h.link.advertise(TEST_BAND, rssi = -50)
        h.link.advertise(other, rssi = -70)
        runCurrent()
        assertEquals(HeartSensorState.Searching(listOf(SensorSighting(other, -70), SensorSighting(TEST_BAND, -50))), h.state)
    }

    @Test
    fun aSensorThatNamedItselfOnceKeepsItsName() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        h.link.advertise(TEST_BAND)
        h.link.advertise(TEST_BAND.copy(name = ""), rssi = -61)
        runCurrent()
        assertEquals(listOf(SensorSighting(TEST_BAND, -61)), assertIs<HeartSensorState.Searching>(h.state).found)
    }

    @Test
    fun searchingAsksForBluetoothAndGoesOnWhenItIsGiven() = runMonitorTest { h ->
        h.link.permitted = false
        h.link.grantsWhenAsked = true
        h.monitor.search()
        runCurrent()
        assertEquals(1, h.link.asked)
        assertEquals(HeartSensorState.Searching(), h.state)
    }

    @Test
    fun searchingRefusedBluetoothSaysSoAndDoesNotScan() = runMonitorTest { h ->
        h.link.permitted = false
        h.monitor.search()
        runCurrent()
        assertEquals(1, h.link.asked)
        assertEquals(HeartSensorState.PermissionNeeded, h.state)
        assertEquals(0, h.link.scans)
    }

    @Test
    fun aSearchWhoseScanWillNotStartIsOffAgainNotLeftLooking() = runMonitorTest { h ->
        h.link.scanFails = true
        h.monitor.search()
        runCurrent()
        // Nothing is scanning and nothing was found: saying "looking" would be untrue.
        assertEquals(HeartSensorState.Off, h.state)
        assertEquals(0, h.link.scanning)
    }

    // ---- Following the chosen sensor ----------------------------------------------------------

    @Test
    fun followingScansConnectsAndThenReads() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)

        h.link.advertise(TEST_BAND)
        runCurrent()
        assertEquals(HeartSensorState.Connecting(TEST_BAND), h.state)
        assertEquals(listOf(TEST_BAND.address), h.link.connections)
        // The scan is over once the sensor is found.
        assertEquals(0, h.link.scanning)

        h.link.ready()
        runCurrent()
        assertEquals(HeartSensorState.Connected(TEST_BAND, null, currentTime), h.state)

        advanceTimeBy(1_000)
        h.link.measure(72)
        runCurrent()
        assertEquals(HeartSensorState.Connected(TEST_BAND, 72, currentTime), h.state)
    }

    @Test
    fun otherPeoplesSensorsAreNotTheOneBeingFollowed() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        h.link.advertise(other)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
        assertEquals(emptyList(), h.link.connections)
    }

    @Test
    fun followingDoesNotAskForBluetoothUnlessTheUserTapped() = runMonitorTest { h ->
        h.link.permitted = false
        h.link.grantsWhenAsked = true
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(0, h.link.asked)
        assertEquals(HeartSensorState.PermissionNeeded, h.state)

        h.monitor.follow(TEST_BAND, ask = true)
        runCurrent()
        assertEquals(1, h.link.asked)
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
    }

    @Test
    fun withBluetoothOffItWaitsAndStartsWhenItComesOn() = runMonitorTest { h ->
        h.link.bluetoothOn = false
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(HeartSensorState.BluetoothOff, h.state)
        assertEquals(0, h.link.scans)

        h.link.bluetoothOn = true
        advanceTimeBy(HeartRateMonitorImpl.RADIO_POLL_MILLIS)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
    }

    @Test
    fun aSensorOffTheWristIsConnectedWithNothingToRead() = runMonitorTest { h ->
        connect(h, bpm = 72)
        h.link.measure(72, contact = false)
        runCurrent()
        assertEquals(null, assertIs<HeartSensorState.Connected>(h.state).bpm)
        h.link.measure(0)
        runCurrent()
        assertEquals(null, assertIs<HeartSensorState.Connected>(h.state).bpm)
        h.link.measure(75, contact = true)
        runCurrent()
        assertEquals(75, assertIs<HeartSensorState.Connected>(h.state).bpm)
    }

    @Test
    fun everyNotificationIsAStateOfItsOwnEvenAtTheSameRate() = runMonitorTest { h ->
        connect(h, bpm = 72)
        val first = h.state
        advanceTimeBy(1_000)
        h.link.measure(72)
        runCurrent()
        assertEquals(72, assertIs<HeartSensorState.Connected>(h.state).bpm)
        assertTrue(h.state != first)
    }

    // ---- When the link goes -------------------------------------------------------------------

    @Test
    fun aDroppedLinkIsLostAndThenLookedForAgain() = runMonitorTest { h ->
        connect(h)
        h.link.drop()
        runCurrent()
        assertEquals(HeartSensorState.Lost(TEST_BAND), h.state)
        assertEquals(1, h.link.closed)
        assertEquals(1, h.link.scans)

        advanceTimeBy(HeartRateMonitorImpl.retryDelay(0))
        runCurrent()
        assertEquals(2, h.link.scans)
        assertEquals(HeartSensorState.Lost(TEST_BAND), h.state)

        h.link.advertise(TEST_BAND)
        runCurrent()
        h.link.ready()
        h.link.measure(90)
        runCurrent()
        assertEquals(90, assertIs<HeartSensorState.Connected>(h.state).bpm)
    }

    @Test
    fun aLinkThatFailsIsTreatedLikeOneThatDropped() = runMonitorTest { h ->
        connect(h)
        h.link.drop(IllegalStateException("GATT status 8"))
        runCurrent()
        assertEquals(HeartSensorState.Lost(TEST_BAND), h.state)
    }

    @Test
    fun aLinkGoneQuietIsLetGoOfWithoutWaitingToBeTold() = runMonitorTest { h ->
        connect(h)
        advanceTimeBy(HeartRateMonitorImpl.SILENCE_MILLIS - 1)
        runCurrent()
        assertIs<HeartSensorState.Connected>(h.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(HeartSensorState.Lost(TEST_BAND), h.state)
        assertEquals(1, h.link.closed)
    }

    @Test
    fun readingsKeepAQuietLinkFromBeingLetGo() = runMonitorTest { h ->
        connect(h)
        repeat(30) {
            advanceTimeBy(1_000)
            h.link.measure(100)
            runCurrent()
        }
        assertEquals(100, assertIs<HeartSensorState.Connected>(h.state).bpm)
        assertEquals(0, h.link.closed)
    }

    @Test
    fun aLinkThatNeverComesUpIsGivenUpOnAndTriedAgain() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        h.link.advertise(TEST_BAND)
        runCurrent()
        assertEquals(HeartSensorState.Connecting(TEST_BAND), h.state)

        advanceTimeBy(HeartRateMonitorImpl.CONNECT_MILLIS)
        runCurrent()
        assertEquals(HeartSensorState.Lost(TEST_BAND), h.state)
        assertEquals(1, h.link.closed)

        advanceTimeBy(HeartRateMonitorImpl.retryDelay(1))
        runCurrent()
        assertEquals(2, h.link.scans)
    }

    @Test
    fun aScanThatWillNotStartIsTriedAgainMoreAndMoreSlowly() = runMonitorTest { h ->
        h.link.scanFails = true
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(1, h.link.scans)
        // Never heard, so not lost: still being looked for.
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, h.link.scans)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, h.link.scans)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, h.link.scans)
    }

    @Test
    fun aSensorThatIsNotThereIsScannedForInBurstsWithLongerAndLongerRests() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(1, h.link.scanning)

        // The first scan gives up; the radio rests two seconds, and the state still says it is being looked for.
        advanceTimeBy(HeartRateMonitorImpl.SCAN_MILLIS)
        runCurrent()
        assertEquals(0, h.link.scanning)
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, h.link.scanning)
        assertEquals(2, h.link.scans)

        // The second gives up; this time the rest is four.
        advanceTimeBy(HeartRateMonitorImpl.SCAN_MILLIS)
        runCurrent()
        assertEquals(0, h.link.scanning)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, h.link.scanning)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, h.link.scanning)
        assertEquals(3, h.link.scans)
    }

    @Test
    fun overTenMinutesAnAbsentSensorCostsFewScansAndMostlyRest() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        // Fifteen seconds on, thirty off once the rests have grown: about one scan every three quarters of a minute.
        assertTrue(h.link.scans in 12..16, "scans: ${h.link.scans}")
    }

    @Test
    fun aSensorHeardInALaterBurstIsConnectedToAllTheSame() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        advanceTimeBy(HeartRateMonitorImpl.SCAN_MILLIS + 2_000)
        runCurrent()
        h.link.advertise(TEST_BAND)
        runCurrent()
        h.link.ready()
        h.link.measure(64)
        runCurrent()
        assertEquals(64, assertIs<HeartSensorState.Connected>(h.state).bpm)
    }

    @Test
    fun aSearchThatFindsNothingStopsAfterAMinuteAndIsOffAgain() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        advanceTimeBy(HeartRateMonitorImpl.SEARCH_MILLIS - 1)
        runCurrent()
        assertEquals(1, h.link.scanning)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(0, h.link.scanning)
        assertEquals(HeartSensorState.Off, h.state)
    }

    @Test
    fun aSearchThatFoundSomethingStopsScanningButKeepsItToChooseFrom() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        h.link.advertise(TEST_BAND)
        advanceTimeBy(HeartRateMonitorImpl.SEARCH_MILLIS)
        runCurrent()
        assertEquals(0, h.link.scanning)
        assertEquals(listOf(SensorSighting(TEST_BAND, -55)), assertIs<HeartSensorState.Searching>(h.state).found)
    }

    @Test
    fun theWaitBetweenAttemptsDoublesAndStopsAtHalfAMinute() {
        assertEquals(listOf(2_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), listOf(0, 1, 2, 3, 4, 5, 12).map(HeartRateMonitorImpl::retryDelay))
    }

    @Test
    fun aLinkThatComesUpAndFallsOverBeforeAnyReadingIsBackedAwayFromToo() = runMonitorTest { h ->
        h.monitor.follow(TEST_BAND)
        runCurrent()
        // Twice it agrees to notify and drops at once: the second wait is twice the first.
        h.link.advertise(TEST_BAND)
        runCurrent()
        h.link.ready()
        runCurrent()
        h.link.drop()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, h.link.scans)
        h.link.advertise(TEST_BAND)
        runCurrent()
        h.link.ready()
        runCurrent()
        h.link.drop()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, h.link.scans)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, h.link.scans)
    }

    // ---- A band that changes its address ------------------------------------------------------

    @Test
    fun atANewAddressTheSameNameIsTakenOnlyOnceTheOldAddressHasGoneUnheard() = runMonitorTest { h ->
        val moved = TEST_BAND.copy(address = "AA:BB:CC:00:00:99")
        h.monitor.follow(TEST_BAND)
        runCurrent()
        h.link.advertise(moved)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)

        advanceTimeBy(HeartRateMonitorImpl.ADDRESS_GRACE_MILLIS)
        h.link.advertise(moved)
        runCurrent()
        assertEquals(HeartSensorState.Connecting(moved), h.state)
        assertEquals(listOf(moved.address), h.link.connections)

        h.link.ready()
        h.link.measure(88)
        runCurrent()
        assertEquals(moved, assertIs<HeartSensorState.Connected>(h.state).sensor)
    }

    @Test
    fun aSensorWithNoNameIsOnlyEverFoundByItsAddress() = runMonitorTest { h ->
        val nameless = HeartSensor("AA:BB:CC:00:00:03", "")
        h.monitor.follow(nameless)
        runCurrent()
        advanceTimeBy(HeartRateMonitorImpl.ADDRESS_GRACE_MILLIS)
        h.link.advertise(HeartSensor("AA:BB:CC:00:00:04", ""))
        runCurrent()
        assertEquals(HeartSensorState.Scanning(nameless), h.state)
    }

    // ---- Stopping, and changing what is asked -------------------------------------------------

    @Test
    fun stoppingLetsGoOfTheLinkAndIsOff() = runMonitorTest { h ->
        connect(h)
        h.monitor.stop()
        runCurrent()
        assertEquals(HeartSensorState.Off, h.state)
        assertEquals(1, h.link.closed)
        // And nothing is tried again afterwards.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.link.scans)
        assertEquals(HeartSensorState.Off, h.state)
    }

    @Test
    fun stoppingASearchStopsTheScan() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        assertEquals(1, h.link.scanning)
        h.monitor.stop()
        runCurrent()
        assertEquals(0, h.link.scanning)
        assertEquals(HeartSensorState.Off, h.state)
    }

    @Test
    fun choosingFromASearchEndsItAndFollowsWhatWasChosen() = runMonitorTest { h ->
        h.monitor.search()
        runCurrent()
        h.link.advertise(TEST_BAND)
        runCurrent()
        h.monitor.follow(TEST_BAND, ask = true)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
        assertEquals(1, h.link.scanning)
        assertEquals(2, h.link.scans)
    }

    @Test
    fun whateverWasAskedLastIsWhatHappens() = runMonitorTest { h ->
        h.monitor.follow(other)
        h.monitor.stop()
        h.monitor.search()
        h.monitor.follow(TEST_BAND)
        runCurrent()
        assertEquals(HeartSensorState.Scanning(TEST_BAND), h.state)
        assertEquals(1, h.link.scanning)

        h.monitor.follow(TEST_BAND)
        h.monitor.stop()
        runCurrent()
        assertEquals(HeartSensorState.Off, h.state)
        assertEquals(0, h.link.scanning)
    }
}
