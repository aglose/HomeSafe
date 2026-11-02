package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.data.DeviceIdentityStore
import com.meticulouscreations.homesafe.data.InMemorySettingsDao
import com.meticulouscreations.homesafe.finance.FakeServerConnection
import com.meticulouscreations.homesafe.fitness.FakeHealthRelay
import com.meticulouscreations.homesafe.fitness.FakeHealthSource
import com.meticulouscreations.homesafe.fitness.domain.DistanceSpan
import com.meticulouscreations.homesafe.fitness.domain.HealthAvailability
import com.meticulouscreations.homesafe.fitness.domain.HealthKind
import com.meticulouscreations.homesafe.fitness.domain.HealthReadings
import com.meticulouscreations.homesafe.fitness.domain.HealthSample
import com.meticulouscreations.homesafe.fitness.domain.Pause
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.RoutePoint
import com.meticulouscreations.homesafe.fitness.domain.Run
import com.meticulouscreations.homesafe.fitness.domain.RunRecord
import com.meticulouscreations.homesafe.fitness.domain.RunTrack
import com.meticulouscreations.homesafe.fitness.domain.SleepSession
import com.meticulouscreations.homesafe.fitness.domain.SleepStage
import com.meticulouscreations.homesafe.fitness.domain.SleepStageKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A phone's copy going to the server, and a device with no store reading it back. All made up. */
class HealthUploaderTest {
    private val relay = FakeHealthRelay()
    private val connection = FakeServerConnection(URL)
    private val store = FakeHealthSource(HealthAvailability.AVAILABLE).apply { allowed = HealthKind.entries.toSet() }
    private val uploader = HealthUploader(relay, connection, DeviceIdentityStore(InMemorySettingsDao()), store)
    private val fromThePhone = RelayHealthSource(relay, connection)

    private val everything = HealthKind.entries.toSet()
    private val run = RunRecord("r1", NOW - 3_000, NOW - 1_200, utcOffsetSeconds = -28_800, treadmill = true, meters = 5_000.0, activeSeconds = 1_700, calories = 400.0, averageBpm = 150, peakBpm = 170, steps = 5_000, climbMeters = 12.5)
    private val track = RunTrack(
        heart = listOf(HealthSample(NOW - 3_000, 140.0)),
        distance = listOf(DistanceSpan(NOW - 3_000, NOW - 2_900, 250.0)),
        route = listOf(RoutePoint(1.5, 2.5), RoutePoint(1.6, 2.6)),
        pauses = listOf(Pause(NOW - 2_000, NOW - 1_900)),
    )
    private val readings = HealthReadings(
        readAtEpochSeconds = NOW,
        granted = everything,
        steps = mapOf(20_000L to 4_200L),
        sleep = listOf(SleepSession(NOW - 30_000, NOW - 2_000, listOf(SleepStage(NOW - 30_000, NOW - 20_000, SleepStageKind.DEEP)), -28_800, -25_200)),
        resting = listOf(HealthSample(NOW - 2_000, 55.0)),
        variability = listOf(HealthSample(NOW - 2_000, 48.5)),
        runs = listOf(run),
    )

    @Test
    fun everythingReadComesBackAsItWas() = runTest {
        store.tracks = mapOf("r1" to track)
        uploader.send(readings, emptyList())

        assertEquals(everything, fromThePhone.granted())
        assertEquals(NOW, fromThePhone.readAtEpochSeconds())
        assertEquals(readings.steps, fromThePhone.stepsByDay(19_990, 20_010))
        assertEquals(readings.sleep, fromThePhone.sleep(0, NOW))
        assertEquals(readings.resting, fromThePhone.restingHeartRates(0, NOW))
        assertEquals(readings.variability, fromThePhone.heartRateVariability(0, NOW))
        assertEquals(listOf(run), fromThePhone.runs(0, NOW))
        assertEquals(track, fromThePhone.track(run))
    }

    @Test
    fun aTrackIsSentOnceAndAgainOnlyWhenWhatItDependsOnChanges() = runTest {
        uploader.send(readings, emptyList())
        assertEquals(listOf("r1"), relay.sentTracks)
        uploader.send(readings.copy(steps = mapOf(20_000L to 5_000L)), emptyList())
        assertEquals(listOf("r1"), relay.sentTracks, "steps have nothing to do with it")
        uploader.send(readings.copy(runs = listOf(run.copy(averageBpm = 151))), emptyList())
        assertEquals(listOf("r1", "r1"), relay.sentTracks, "the band has added to the run")
    }

    @Test
    fun aHeartSwitchedOffIsSentAgainWithoutIt() = runTest {
        store.tracks = mapOf("r1" to track)
        uploader.send(readings, emptyList())
        assertEquals(1, relay.tracks.getValue("r1").second.heart.size)
        store.allowed = everything - HealthKind.HEART_RATE
        uploader.send(readings.copy(granted = store.allowed), emptyList())
        assertEquals(0, relay.tracks.getValue("r1").second.heart.size)
    }

    @Test
    fun aRunGoneFromTheStoreGoesFromTheServer() = runTest {
        uploader.send(readings, emptyList())
        uploader.send(readings.copy(runs = emptyList()), emptyList())
        assertEquals(emptyList(), relay.listed)
        assertTrue(relay.tracks.isEmpty())
    }

    @Test
    fun exerciseSwitchedOffTakesEveryTrackWithIt() = runTest {
        uploader.send(readings, emptyList())
        uploader.send(readings.copy(granted = everything - HealthKind.EXERCISE, runs = emptyList()), emptyList())
        assertTrue(relay.tracks.isEmpty())
    }

    @Test
    fun aFewTracksGoAtATimeNewestFirst() = runTest {
        val many = (0 until 13).map { run.copy(id = "r$it", startEpochSeconds = NOW - 100_000 + it * 1_000, endEpochSeconds = NOW - 99_000 + it * 1_000) }
        uploader.send(readings.copy(runs = many), emptyList())
        assertEquals((12 downTo 3).map { "r$it" }, relay.sentTracks)
        relay.sentTracks.clear()
        uploader.send(readings.copy(runs = many), emptyList())
        assertEquals(listOf("r2", "r1", "r0"), relay.sentTracks, "the same reading again still goes, for the ones left")
    }

    @Test
    fun theSameReadingSoonAfterIsntSentAgain() = runTest {
        uploader.send(readings, emptyList())
        uploader.send(readings.copy(readAtEpochSeconds = NOW + 60), emptyList())
        assertEquals(1, relay.sends)
        assertEquals(NOW, relay.snapshot?.at)
    }

    @Test
    fun theSameReadingALaterGoesSoTheOtherDevicesKnowItIsStillSo() = runTest {
        uploader.send(readings, emptyList())
        uploader.send(readings.copy(readAtEpochSeconds = NOW + 16 * 60), emptyList())
        assertEquals(2, relay.sends)
        assertEquals(NOW + 16 * 60, relay.snapshot?.at)
    }

    @Test
    fun aRunRecordedInTheAppHasItsHeartSentByItsMinutes() = runTest {
        store.heart = listOf(HealthSample(NOW - 500, 150.0))
        uploader.send(readings.copy(runs = emptyList()), listOf(recorded(NOW - 600, NOW - 100)))
        assertEquals(store.heart, fromThePhone.heartRate(NOW - 600, NOW - 100))
    }

    @Test
    fun aRecordedRunsHeartIsReadAgainWhileTheBandMayStillBeSyncing() = runTest {
        val ran = recorded(NOW - 600, NOW - 100)
        uploader.send(readings.copy(runs = emptyList()), listOf(ran))
        assertEquals(emptyList(), fromThePhone.heartRate(NOW - 600, NOW - 100))
        store.heart = listOf(HealthSample(NOW - 500, 150.0))
        uploader.send(readings.copy(runs = emptyList(), readAtEpochSeconds = NOW + 2 * 3_600), listOf(ran))
        assertEquals(store.heart, fromThePhone.heartRate(NOW - 600, NOW - 100))
    }

    @Test
    fun aRecordedRunsHeartIsntAskedForOnceItHasSettled() = runTest {
        val ran = recorded(NOW - 3 * DAY, NOW - 3 * DAY + 600)
        uploader.send(readings.copy(runs = emptyList()), listOf(ran))
        relay.sentTracks.clear()
        uploader.send(readings.copy(runs = emptyList(), readAtEpochSeconds = NOW + 3_600), listOf(ran))
        assertEquals(emptyList(), relay.sentTracks)
    }

    @Test
    fun noHeartSharedMeansNoHeartSentForARecordedRun() = runTest {
        uploader.send(readings.copy(granted = everything - HealthKind.HEART_RATE), listOf(recorded(NOW - 600, NOW - 100)))
        assertEquals(listOf("r1"), relay.listed)
    }

    @Test
    fun clearingTakesTheServersCopyAway() = runTest {
        uploader.send(readings, emptyList())
        uploader.clear()
        assertNull(relay.snapshot)
        assertEquals(emptySet(), fromThePhone.granted())
    }

    @Test
    fun anotherInstallClearingDoesntTouchThisOnesCopy() = runTest {
        uploader.send(readings, emptyList())
        HealthUploader(relay, connection, DeviceIdentityStore(InMemorySettingsDao()), store).clear()
        assertEquals(everything, fromThePhone.granted())
    }

    @Test
    fun signedOutNothingGoes() = runTest {
        val away = HealthUploader(relay, FakeServerConnection(null), DeviceIdentityStore(InMemorySettingsDao()), store)
        away.send(readings, emptyList())
        away.clear()
        assertEquals(0, relay.sends)
    }

    @Test
    fun theServerKnownToBeUnreachableIsntTried() = runTest {
        val away = HealthUploader(relay, FakeServerConnection(URL, unreachable = true), DeviceIdentityStore(InMemorySettingsDao()), store)
        away.send(readings, emptyList())
        assertEquals(0, relay.sends)
    }

    @Test
    fun aFailureIsThrownAndTheNextReadingGoes() = runTest {
        relay.failing = true
        assertFailsWith<IllegalStateException> { uploader.send(readings, emptyList()) }
        relay.failing = false
        uploader.send(readings, emptyList())
        assertEquals(1, relay.sends)
    }

    @Test
    fun aRouteHandedOverIsKeptWithItsRun() = runTest {
        uploader.send(readings, emptyList())
        uploader.sendTrack(run, everything, track)
        assertEquals(track.route, fromThePhone.track(run).route)
        uploader.send(readings.copy(steps = emptyMap()), emptyList())
        assertEquals(track.route, fromThePhone.track(run).route, "the next reading doesn't send over it")
    }

    @Test
    fun aRouteOnlyThePhoneCanAskForIsntOfferedElsewhere() = runTest {
        uploader.send(readings.copy(runs = listOf(run.copy(route = RouteAccess.ASK))), emptyList())
        fromThePhone.granted()
        assertEquals(RouteAccess.NONE, fromThePhone.runs(0, NOW).single().route)
    }

    @Test
    fun readsAreCutToWhatWasAskedFor() = runTest {
        uploader.send(readings, emptyList())
        fromThePhone.granted()
        assertEquals(emptyMap(), fromThePhone.stepsByDay(1, 2))
        assertEquals(emptyList(), fromThePhone.sleep(0, 10))
        assertEquals(emptyList(), fromThePhone.restingHeartRates(0, 10))
        assertEquals(emptyList(), fromThePhone.heartRateVariability(0, 10))
        assertEquals(emptyList(), fromThePhone.runs(0, 10))
    }

    @Test
    fun signingOutLetsGoOfWhatWasRead() = runTest {
        uploader.send(readings, emptyList())
        val signedIn = FakeServerConnection(URL)
        val source = RelayHealthSource(relay, signedIn)
        source.granted()
        assertEquals(NOW, source.readAtEpochSeconds())
        signedIn.activeConnection.value = null
        assertEquals(emptySet(), source.granted())
        assertNull(source.readAtEpochSeconds())
        assertEquals(emptyMap(), source.stepsByDay(19_990, 20_010))
    }

    @Test
    fun aServerThatCantBeReachedIsThrownSoWhatIsShownStands() = runTest {
        uploader.send(readings, emptyList())
        relay.failing = true
        assertFailsWith<IllegalStateException> { fromThePhone.granted() }
    }

    private fun recorded(start: Long, end: Long) = Run(
        id = "recorded-$start",
        startEpochSeconds = start,
        endEpochSeconds = end,
        meters = 1_000.0,
        seconds = end - start,
        recorded = true,
    )

    private companion object {
        const val URL = "http://frigate.test"
        const val NOW = 1_790_000_000L
        const val DAY = 86_400L
    }
}
