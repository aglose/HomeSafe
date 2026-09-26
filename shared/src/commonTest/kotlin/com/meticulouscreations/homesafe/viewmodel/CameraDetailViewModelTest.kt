package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.ui.components.LiveStreamStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CameraDetailViewModelTest {

    @Test
    fun joinsOnGridStreamAndUpgradesWhenStreamsDiffer() {
        val plan = planLiveJoin(gridStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam_sub", liveStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam")

        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam_sub", plan.joinUrl)
        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam", plan.upgradeToUrl)
    }

    @Test
    fun isANoOpWhenGridAndLiveStreamsAreTheSame() {
        val plan = planLiveJoin(gridStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam", liveStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam")

        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam", plan.joinUrl)
        assertNull(plan.upgradeToUrl)
    }

    @Test
    fun highQualityPinsTheFullStreamWithoutAnUpgrade() {
        val plan = planLiveJoin(gridStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam_sub", liveStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam", quality = StreamQuality.HIGH)

        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam", plan.joinUrl)
        assertNull(plan.upgradeToUrl)
    }

    @Test
    fun lowQualityPinsTheGridStreamWithoutAnUpgrade() {
        val plan = planLiveJoin(gridStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam_sub", liveStreamUrl = "http://frigate:1984/api/stream.m3u8?src=cam", quality = StreamQuality.LOW)

        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam_sub", plan.joinUrl)
        assertNull(plan.upgradeToUrl)
    }

    @Test
    fun qualityCyclesAutoHighLowAndBack() {
        assertEquals(StreamQuality.HIGH, StreamQuality.AUTO.next)
        assertEquals(StreamQuality.LOW, StreamQuality.HIGH.next)
        assertEquals(StreamQuality.AUTO, StreamQuality.LOW.next)
    }

    @Test
    fun snapshotMomentsAreQuantisedSoAScrubAndItsPosterShareOneFrame() {
        assertEquals(1788407038.0, snapshotEpochSeconds(1788407038.0))
        assertEquals(1788407038.0, snapshotEpochSeconds(1788407039.9))
        assertEquals(1788407040.0, snapshotEpochSeconds(1788407040.0))
    }

    @Test
    fun upgradeWaitsOnlyTheMinimumWhenTheGridStreamIsAlreadyLive() = runTest {
        awaitQualityUpgrade(MutableStateFlow(LiveStreamStatus.Live), minDelayMs = 3_000, maxWaitMs = 12_000)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun upgradeWaitsForASlowGridJoinToGoLiveRatherThanCancellingIt() = runTest {
        val status = MutableStateFlow(LiveStreamStatus.Connecting)
        launch {
            delay(7_000)
            status.value = LiveStreamStatus.Live
        }
        awaitQualityUpgrade(status, minDelayMs = 3_000, maxWaitMs = 12_000)
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun upgradeWaitsOutAStall() = runTest {
        val status = MutableStateFlow(LiveStreamStatus.Buffering)
        launch {
            delay(5_000)
            status.value = LiveStreamStatus.Live
        }
        awaitQualityUpgrade(status, minDelayMs = 3_000, maxWaitMs = 12_000)
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun upgradeGoesAheadWhenTheGridStreamNeverGoesLive() = runTest {
        awaitQualityUpgrade(MutableStateFlow(LiveStreamStatus.Connecting), minDelayMs = 3_000, maxWaitMs = 12_000)
        assertEquals(12_000L, currentTime)
    }
}
