package com.meticulouscreations.homesafe.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun snapshotMomentsAreQuantisedSoAScrubAndItsPosterShareOneFrame() {
        assertEquals(1788407038.0, snapshotEpochSeconds(1788407038.0))
        assertEquals(1788407038.0, snapshotEpochSeconds(1788407039.9))
        assertEquals(1788407040.0, snapshotEpochSeconds(1788407040.0))
    }
}
