package com.meticulouscreations.homesafe.network

import kotlin.test.Test
import kotlin.test.assertEquals

class FrigateSessionTest {

    @Test
    fun formatsEpochSecondsWithoutScientificNotation() {
        assertEquals("1700000000.000", formatEpochSeconds(1_700_000_000.0))
        assertEquals("1700000000.123", formatEpochSeconds(1_700_000_000.1239))
        assertEquals("0.005", formatEpochSeconds(0.005))
    }

    @Test
    fun buildsRecordingStreamUrlOnFrigatePort() {
        assertEquals(
            "http://frigate:8971/vod/front_door/start/1700000000.000/end/1700003600.500/index.m3u8",
            frigateRecordingStreamUrl("http://frigate:8971/", "front_door", 1_700_000_000.0, 1_700_003_600.5),
        )
    }

    @Test
    fun liveStreamUrlUsesGo2rtcPort() {
        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam", frigateLiveStreamUrl("https://frigate:8971", "cam"))
    }

    @Test
    fun liveStreamUrlAsksForAudioOnlyWhenGivenCodecs() {
        assertEquals(
            "http://frigate:1984/api/stream.m3u8?src=cam&video&audio=aac,opus",
            frigateLiveStreamUrl("https://frigate:8971", "cam", audioCodecs = listOf("aac", "opus")),
        )
        assertEquals("http://frigate:1984/api/stream.m3u8?src=cam", frigateLiveStreamUrl("https://frigate:8971", "cam", audioCodecs = emptyList()))
    }
}
